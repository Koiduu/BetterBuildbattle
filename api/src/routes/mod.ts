// Mod-facing routes: Mojang handshake + batched, idempotent sync.
import { Router, fail } from "../http";
import { z } from "zod";
import crypto from "node:crypto";
import { pool } from "../db/pool";
import { xpForMatch, levelForXp } from "../services/level";
import { evaluateAchievements, catalog } from "../services/achievementEngine";
import { rateLimit, requireModToken } from "../plugins/auth";
import { MOJANG_SESSION, dashUuid } from "../lib/upstream";
import { queuePlayers, globalRank, refreshOne } from "../jobs/crawler";
import { weeklyProgress } from "../services/weekly";
import { worldRecords } from "../services/speedBuilders";
import { updateProgressBoards } from "../services/boards";

const Mode = z.enum(["solo", "teams", "pro", "gtb", "speed_builders"]);

const MatchIn = z.object({
  nonce: z.string().uuid(),
  playedAt: z.string().datetime(),
  mode: Mode,
  theme: z.string().max(80).nullable(),
  placement: z.number().int().min(1).max(32).nullable(),
  players: z.number().int().min(1).max(32).nullable(),
  score: z.number().nullable(),
  grade: z.enum(["SS", "S", "A", "B", "C"]).nullable().default(null),
  durationSec: z.number().int().min(0).max(3600),
  blocksPlaced: z.number().int().min(0).max(20000),
  blocksBroken: z.number().int().min(0).max(20000).default(0),
  palette: z.record(z.number().int().min(0)).optional(),
  votes: z
    .array(z.object({
      round: z.number().int().min(0).max(100),
      vote: z.enum(["super_poop", "poop", "meh", "ok", "good", "epic", "legendary"]),
      builder: z.string().regex(/^[A-Za-z0-9_]{1,16}$/).nullable(),
      at: z.string().datetime(),
    }))
    .max(64)
    .default([]),
  // One-off challenge ids this game met (even ones already unlocked). Weekly challenges count from these.
  tags: z.array(z.string().regex(/^[a-z0-9_]{1,48}$/)).max(64).default([]),
  gtbCorrect: z.number().int().min(0).max(100).nullable().default(null),
  spbRounds: z
    .array(z.object({
      build: z.string().regex(/^[a-z0-9_]{1,48}$/),
      name: z.string().max(64).nullable().default(null),
      timeMs: z.number().int().positive().max(600_000).nullable().default(null),   // null: not a perfect build
      accuracy: z.number().min(0).max(1).nullable().default(null),
      precision: z.number().min(0).max(1).nullable().default(null),
      perfect: z.boolean().default(false),
      placed: z.number().int().min(0).max(5000).default(0),
      broken: z.number().int().min(0).max(5000).default(0),
    }))
    .max(20)
    .optional(),
});

const SyncBody = z.object({
  modVersion: z.string(),
  mcVersion: z.enum(["26.3", "1.8.9"]),
  matches: z.array(MatchIn).max(50),
  localAchievements: z.array(z.string()).max(500).default([]),
  // UUIDs of other players seen in this player's lobbies since last sync (tab list). Feeds the global ranking.
  seenPlayers: z.array(z.string().uuid()).max(2000).default([]),
});

export function modRoutes(app: Router) {
  // ---- 1. Handshake: issue a random serverId ----
  app.post("/auth/mod/challenge", [rateLimit(10, 60_000)], async () => {
    const serverId = crypto.randomBytes(16).toString("hex");
    await pool.query(
      "INSERT INTO mod_challenges (server_id, expires_at) VALUES ($1, now() + interval '2 minutes')",
      [serverId],
    );
    return { serverId };
  });

  // ---- 2. Verify with Mojang hasJoined, mint a mod token ----
  app.post("/auth/mod/verify", [rateLimit(10, 60_000)], async (req) => {
    const { name, serverId } = z.object({ name: z.string().max(16), serverId: z.string() }).parse(req.body);
    const ch = await pool.query(
      "DELETE FROM mod_challenges WHERE server_id = $1 AND expires_at > now() RETURNING 1",
      [serverId],
    );
    if (ch.rowCount === 0) fail(400, "challenge_expired");

    const res = await fetch(
      `${MOJANG_SESSION}/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`,
    );
    if (res.status !== 200) fail(401, "not_verified");
    const profile = (await res.json()) as { id: string; name: string };
    const uuid = dashUuid(profile.id);

    const user = await pool.query(
      `INSERT INTO users (mc_uuid, mc_name) VALUES ($1, $2)
       ON CONFLICT (mc_uuid) DO UPDATE SET mc_name = EXCLUDED.mc_name, last_seen_at = now()
       RETURNING id`,
      [uuid, profile.name],
    );
    await queuePlayers([uuid], "mod_user", true);   // mod users are always in the global ranking
    await refreshOne(uuid).catch(() => {});          // pull Hypixel totals now so retroactive achievements unlock on first sync
    const token = crypto.randomBytes(32).toString("base64url");
    await pool.query(
      `INSERT INTO sessions (user_id, kind, token_hash, expires_at)
       VALUES ($1, 'mod', $2, now() + interval '30 days')`,
      [user.rows[0].id, crypto.createHash("sha256").update(token).digest()],
    );
    return { token, expiresInDays: 30 };
  });

  // ---- Bootstrap: catalog, weekly challenges with progress, confirmed achievements, Speed Builders records ----
  app.get("/mod/bootstrap", [requireModToken], async (req) => {
    const [mine, weekly, records] = await Promise.all([
      pool.query("SELECT achievement_id FROM user_achievements WHERE user_id = $1", [req.user.id]),
      weeklyProgress(pool, req.user.id),
      worldRecords(),
    ]);
    return {
      catalogVersion: catalog.catalog_version,
      confirmedAchievements: mine.rows.map((r: any) => r.achievement_id),
      weeklyChallenges: weekly.weekly,
      weekEndsAt: weekly.weekEndsAt,
      // Times only: the mod shows them next to its timer. No placements, so nothing can guide a build.
      worldRecords: records.map((r) => ({ build: r.build, name: r.name, holder: r.holder, timeMs: r.timeMs })),
    };
  });

  // ---- 3. Sync: idempotent batch insert ----
  app.post("/mod/sync", [requireModToken, rateLimit(30, 60_000)],
    async (req) => {
      const userId: number = req.user.id;
      const body = SyncBody.parse(req.body);
      const client = await pool.connect();
      const accepted: string[] = [];
      let xpGained = 0;
      const rejected: { nonce: string; reason: string }[] = [];
      // Oldest first, so streaks and first-win-of-day are counted in play order.
      const ordered = [...body.matches].sort((a, b) => a.playedAt.localeCompare(b.playedAt));
      try {
        await client.query("BEGIN");
        const st = await client.query(
          `INSERT INTO user_progress (user_id) VALUES ($1) ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
           RETURNING win_streak, best_win_streak`, [userId]);
        let streak: number = st.rows[0].win_streak, best: number = st.rows[0].best_win_streak;
        for (const m of ordered) {
          const problem = await sanityCheck(client, m);
          if (problem) { rejected.push({ nonce: m.nonce, reason: problem }); continue; }

          const firstWinToday = m.placement === 1 && !(await client.query(
            `SELECT 1 FROM matches WHERE user_id = $1 AND placement = 1
               AND played_at >= date_trunc('day', $2::timestamptz) AND played_at < $2::timestamptz LIMIT 1`,
            [userId, m.playedAt])).rowCount;
          const xp = xpForMatch(m, firstWinToday);
          const ins = await client.query(
            `INSERT INTO matches (user_id, played_at, mode, theme, placement, players, score,
                                  duration_sec, blocks_placed, blocks_broken, max_score, palette,
                                  spb_rounds, client_nonce, mod_version, mc_version, grade, xp_earned, tags, gtb_correct)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20)
             ON CONFLICT (user_id, client_nonce, played_at) DO NOTHING
             RETURNING id`,
            [userId, m.playedAt, m.mode, m.theme, m.placement, m.players, m.score, m.durationSec,
             m.blocksPlaced, m.blocksBroken, maxScore(m), m.palette ?? null,
             m.spbRounds ? JSON.stringify(m.spbRounds) : null, m.nonce,
             body.modVersion, body.mcVersion, m.grade, xp, m.tags.length ? m.tags : null,
             m.mode === "gtb" ? m.gtbCorrect : null],
          );
          accepted.push(m.nonce); // duplicates count as accepted: the mod can mark them synced
          if (ins.rowCount === 0) continue;
          xpGained += xp;
          for (const v of m.votes) {
            await client.query(
              `INSERT INTO match_votes (user_id, match_nonce, played_at, round, vote, builder, voted_at)
               VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT DO NOTHING`,
              [userId, m.nonce, m.playedAt, v.round, v.vote, v.builder, v.at]);
          }
          await saveSpeedBuildersBests(client, userId, m);
          if (m.mode !== "gtb" || m.placement !== null) {
            streak = m.placement === 1 ? streak + 1 : 0;
            best = Math.max(best, streak);
          }

          await client.query(
            `INSERT INTO user_stats (user_id, mode, games_played, wins, total_score, playtime_sec)
             VALUES ($1, $2, 1, $3, $4, $5)
             ON CONFLICT (user_id, mode) DO UPDATE SET
               games_played = user_stats.games_played + 1,
               wins         = user_stats.wins + EXCLUDED.wins,
               total_score  = user_stats.total_score + EXCLUDED.total_score,
               playtime_sec = user_stats.playtime_sec + EXCLUDED.playtime_sec,
               updated_at   = now()`,
            [userId, m.mode, m.placement === 1 ? 1 : 0, Math.round(m.score ?? 0), m.durationSec],
          );
        }
        const { confirmed, revoked, apGained } = await evaluateAchievements(client, userId, body.localAchievements);
        const weekly = await weeklyProgress(client, userId, true);
        const prog = await client.query(
          `INSERT INTO user_progress (user_id, xp) VALUES ($1, $2)
           ON CONFLICT (user_id) DO UPDATE SET xp = user_progress.xp + EXCLUDED.xp, updated_at = now()
           RETURNING xp`,
          [userId, xpGained + apGained * 10 + weekly.xpGained],
        );
        const level = levelForXp(Number(prog.rows[0].xp));
        await client.query(
          "UPDATE user_progress SET level = $2, ap = ap + $3, win_streak = $4, best_win_streak = $5 WHERE user_id = $1",
          [userId, level, apGained, streak, best]);
        await client.query("COMMIT");

        // Lobby rosters → discovery queue for the global ranking.
        await queuePlayers(body.seenPlayers, "lobby");

        // Global rank = Hypixel Build Battle score among ALL tracked players (crawler keeps it fresh).
        const uuid = (await pool.query("SELECT mc_uuid FROM users WHERE id = $1", [userId])).rows[0].mc_uuid;
        const { rank, of } = await globalRank(uuid);
        const totals = await pool.query("SELECT xp, ap FROM user_progress WHERE user_id = $1", [userId]);
        await updateProgressBoards(uuid, Number(totals.rows[0]?.xp ?? 0), Number(totals.rows[0]?.ap ?? 0)).catch(() => {});
        return { accepted, rejected, confirmed, revoked, level, xp: Number(prog.rows[0].xp),
                 globalRank: rank, rankedPlayers: of,
                 weekly: weekly.weekly, weekEndsAt: weekly.weekEndsAt, weeklyCompleted: weekly.completedNow };
      } catch (e) {
        await client.query("ROLLBACK");
        throw e;
      } finally {
        client.release();
      }
    });
}

/** Every voter (everyone but you and your teammate) gives Legendary = 18. */
function maxScore(m: z.infer<typeof MatchIn>): number | null {
  if (!m.players || m.mode === "gtb" || m.mode === "speed_builders") return null;
  const voters = m.players - (m.mode === "teams" ? 2 : 1);
  return voters > 0 ? voters * 18 : null;
}

/** Learns new builds and keeps each player's fastest perfect time per build. */
async function saveSpeedBuildersBests(client: any, userId: number, m: z.infer<typeof MatchIn>) {
  if (m.mode !== "speed_builders") return;
  for (const r of m.spbRounds ?? []) {
    if (!r.perfect || r.timeMs === null) continue;
    await client.query(
      "INSERT INTO spb_builds (id, name) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING",
      [r.build, r.name ?? r.build.replace(/_/g, " ")]);
    await client.query(
      `INSERT INTO spb_personal_bests (user_id, build_id, time_ms, accuracy, set_at) VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (user_id, build_id) DO UPDATE SET time_ms = EXCLUDED.time_ms, accuracy = EXCLUDED.accuracy,
         set_at = EXCLUDED.set_at
       WHERE EXCLUDED.time_ms < spb_personal_bests.time_ms`,
      [userId, r.build, r.timeMs, r.precision, m.playedAt]);
  }
}

async function sanityCheck(client: any, m: z.infer<typeof MatchIn>): Promise<string | null> {
  if (new Date(m.playedAt).getTime() > Date.now() + 5 * 60_000) return "future_timestamp";
  for (const r of m.spbRounds ?? []) {
    if (r.timeMs === null) continue;
    const b = await client.query("SELECT min_time_ms FROM spb_builds WHERE id = $1", [r.build]);
    if (b.rowCount && r.timeMs < b.rows[0].min_time_ms) return "impossible_spb_time";
  }
  return null;
}
