// Read-only routes the website uses. Works for ANY tracked Build Battle player;
// Better Battle extras (level, games, achievements) appear when the player uses the mod.
import { Router, Raw, fail, type Req } from "../http";
import { profileCard } from "../services/card";
import { siteOrigin } from "../lib/site";
import { BOARDS, boardById, boardPage, positions } from "../services/boards";
import { FEATURES, can, planOf, type Plan } from "../lib/plans";
import { readWebSession } from "../plugins/auth";

/** The plan of whoever is looking (free if logged out). */
async function viewerPlan(req: Req): Promise<Plan> {
  const viewer = await readWebSession(req);
  if (!viewer) return "free";
  const r = await pool.query("SELECT plan, plan_until, plan_comped FROM users WHERE id = $1", [viewer.id]);
  return planOf(r.rows[0]);
}

/** "architect" | "master" for a supporter's badge, else null. */
const supporterOf = (row: any) => {
  const plan = planOf(row);
  return can(plan, "badge") ? plan : null;
};
import { z } from "zod";
import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { queuePlayers, refreshOne, globalRank, HypixelKeyError } from "../jobs/crawler";
import { titleProgress } from "../lib/titles";
import { levelForXp, totalXpForLevel } from "../services/level";
import { rateLimit } from "../plugins/auth";
import { MOJANG_API, dashUuid } from "../lib/upstream";
import { weeklyProgress, currentWeek } from "../services/weekly";
import { worldRecords, buildLeaderboard, playerBests } from "../services/speedBuilders";

// Rarity changes slowly; one count for everybody every 10 minutes is plenty.
let rarityCache: { at: number; body: unknown } | null = null;

const NAME = /^[A-Za-z0-9_]{1,16}$/;

/** Find a player by name; if unknown, look them up via Mojang + Hypixel right now. */
async function resolvePlayer(name: string) {
  const known = await pool.query(
    "SELECT * FROM tracked_players WHERE mc_name = $1 ORDER BY last_checked DESC NULLS LAST LIMIT 1",
    [name],
  );
  if (known.rowCount && known.rows[0].last_checked) return known.rows[0];

  let uuid: string | undefined = known.rows[0]?.mc_uuid;
  if (!uuid) {
    const res = await fetch(`${MOJANG_API}/users/profiles/minecraft/${encodeURIComponent(name)}`);
    if (res.status !== 200) return null;
    const { id } = (await res.json()) as { id: string };
    uuid = dashUuid(id);
    await queuePlayers([uuid], "search", true);
  }
  try {
    await refreshOne(uuid);
  } catch (e) {
    if (e instanceof HypixelKeyError) fail(503, "hypixel_unavailable", { reason: "api_key" });
    fail(503, "hypixel_unavailable");
  }
  const r = await pool.query("SELECT * FROM tracked_players WHERE mc_uuid = $1", [uuid]);
  const row = r.rows[0];
  if (row && !row.last_checked) fail(503, "hypixel_unavailable");
  return row ?? null;
}

export function publicRoutes(app: Router) {
  app.get("/stats", async () => {
    const [tracked, users] = await Promise.all([
      redis.zcard("lb:bb_score"),
      pool.query("SELECT count(*)::int AS n FROM users WHERE mc_uuid IS NOT NULL"),
    ]);
    return { rankedPlayers: tracked, modUsers: users.rows[0].n };
  });

  /** The list of leaderboards, for the board picker. */
  app.get("/boards", async () => BOARDS.map((b) => ({ id: b.id, name: b.name, short: b.short, group: b.group })));

  /** Any board other than score. (Score keeps its own handler below, with wins and level columns.) */
  const otherBoard = async (id: string, page: number) => {
    const board = boardById(id);
    if (!board) return fail(400, "unknown_board");
    const { total, rows } = await boardPage(id, page);
    return {
      page, total, board: { id: board.id, name: board.name, short: board.short },
      rows: rows.map((r) => ({
        rank: r.rank, uuid: r.uuid, name: r.info?.mc_name ?? null, value: r.value,
        score: r.info?.bb_score ?? null, title: titleProgress(r.info?.bb_score ?? 0).current,
        usesMod: !!r.info?.uses_mod, supporter: supporterOf(r.info),
      })),
    };
  };

  /**
   * Where a player stands on every Build Battle leaderboard. Everyone sees the score board;
   * the full list is a paid perk (FEATURES.leaderboardPositions), for the viewer's own plan.
   */
  app.get("/players/:name/leaderboards", [rateLimit(60, 60_000)], async (req) => {
    if (!NAME.test(req.params.name)) fail(400, "bad_name");
    const p = await pool.query(
      `SELECT mc_uuid, mc_name FROM tracked_players
       WHERE mc_name = $1 AND last_checked IS NOT NULL AND NOT no_bb_stats
       ORDER BY last_checked DESC LIMIT 1`, [req.params.name]);
    if (!p.rowCount) return fail(404, "not_found");
    const all = await positions(p.rows[0].mc_uuid);
    const unlocked = can(await viewerPlan(req), "leaderboardPositions");
    return {
      name: p.rows[0].mc_name,
      locked: !unlocked,
      requires: FEATURES.leaderboardPositions,
      boards: unlocked ? all : all.filter((b) => b.id === "score"),
      lockedBoards: unlocked ? [] : all.filter((b) => b.id !== "score").map((b) => ({ id: b.id, name: b.name, group: b.group })),
    };
  });

  app.get("/leaderboard", async (req) => {
    const { page, board } = z.object({
      page: z.coerce.number().int().min(1).max(2000).default(1),
      board: z.string().regex(/^[a-z]{1,16}$/).default("score"),
    }).parse(req.query);
    if (board !== "score") return otherBoard(board, page);
    const per = 50;
    const raw = await redis.zrevrange("lb:bb_score", (page - 1) * per, page * per - 1, "WITHSCORES");
    const uuids: string[] = [];
    const scores = new Map<string, number>();
    for (let i = 0; i < raw.length; i += 2) { uuids.push(raw[i]); scores.set(raw[i], Number(raw[i + 1])); }
    const info = uuids.length
      ? await pool.query(
          `SELECT t.mc_uuid, t.mc_name, t.bb_wins, t.title, (u.id IS NOT NULL) AS uses_mod, p.level,
                  u.plan, u.plan_until, u.plan_comped
           FROM tracked_players t
           LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
           LEFT JOIN user_progress p ON p.user_id = u.id
           WHERE t.mc_uuid = ANY($1::uuid[])`, [uuids])
      : { rows: [] as any[] };
    const byUuid = new Map(info.rows.map((r: any) => [r.mc_uuid, r]));
    return {
      page,
      total: await redis.zcard("lb:bb_score"),
      rows: uuids.map((u, i) => {
        const r: any = byUuid.get(u) ?? {};
        const score = scores.get(u)!;
        return {
          rank: (page - 1) * per + i + 1, uuid: u, name: r.mc_name ?? null, score,
          title: titleProgress(score).current, wins: r.bb_wins ?? null, usesMod: !!r.uses_mod, level: r.level ?? null,
          value: score, supporter: supporterOf(r),
        };
      }),
    };
  });

  app.get("/search", [rateLimit(60, 60_000)], async (req) => {
    const { q } = z.object({ q: z.string().min(1).max(16) }).parse(req.query);
    const r = await pool.query(
      `SELECT mc_name, bb_score FROM tracked_players
       WHERE mc_name ILIKE $1 AND bb_score IS NOT NULL ORDER BY bb_score DESC LIMIT 8`,
      [q.replace(/[%_\\]/g, "\\$&") + "%"],
    );
    return r.rows.map((x: any) => ({ name: x.mc_name, score: x.bb_score }));
  });

  app.get("/players/:name", [rateLimit(30, 60_000)], async (req) => {
    const { name } = req.params;
    if (!NAME.test(name)) fail(400, "bad_name");
    const p = await resolvePlayer(name);
    if (!p) return fail(404, "not_found");
    if (p.no_bb_stats) fail(404, "never_played_build_battle", { name: p.mc_name });

    const score = p.bb_score ?? 0;
    const { rank, of } = await globalRank(p.mc_uuid);
    const hyp = p.hypixel_bb ?? {};

    const u = await pool.query(
      `SELECT u.id, u.bio, u.created_at, u.claimed, p.user_id IS NOT NULL AS has_progress,
              p.xp, p.ap, p.level, p.best_win_streak
       FROM users u LEFT JOIN user_progress p ON p.user_id = u.id WHERE u.mc_uuid = $1`, [p.mc_uuid]);
    let betterBattle = null;
    let bio: string | null = null;
    let claimed = false;
    if (u.rowCount) {
      const row = u.rows[0];
      bio = row.bio;
      claimed = row.claimed;
      if (row.has_progress) {
        const xp = Number(row.xp ?? 0);
        const level = levelForXp(xp);
        const metrics = await pool.query(
          `SELECT count(*)::int AS games,
                  avg(crowd_score) AS crowd_score,
                  count(DISTINCT lower(theme))::int AS themes,
                  count(*) FILTER (WHERE grade = 'SS')::int AS ss,
                  count(*) FILTER (WHERE grade = 'S')::int AS s,
                  count(*) FILTER (WHERE grade = 'A')::int AS a,
                  coalesce(sum(duration_sec), 0)::int AS playtime
           FROM matches WHERE user_id = $1`, [row.id]);
        const m = metrics.rows[0];
        betterBattle = {
          memberSince: row.created_at,
          bio: row.bio,
          level,
          xp,
          xpIntoLevel: xp - totalXpForLevel(level),
          xpForNext: totalXpForLevel(level + 1) - totalXpForLevel(level),
          ap: row.ap ?? 0,
          bestWinStreak: row.best_win_streak ?? 0,
          games: m.games,
          crowdScore: m.crowd_score === null ? null : Number(m.crowd_score),
          themesBuilt: m.themes,
          grades: { SS: m.ss, S: m.s, A: m.a },
          playtimeSec: m.playtime,
        };
      }
    }

    return {
      name: p.mc_name,
      uuid: p.mc_uuid,
      score,
      title: titleProgress(score),
      rank, rankedPlayers: of,
      lastChecked: p.last_checked,
      bio,
      claimed,
      hypixel: {
        wins: hyp.wins ?? p.bb_wins ?? 0,
        gamesPlayed: hyp.games_played ?? p.bb_games ?? 0,
        correctGuesses: hyp.correct_guesses ?? null,
        totalVotes: hyp.total_votes ?? null,
        modeWins: {
          solo: hyp.wins_solo_normal ?? null,
          teams: hyp.wins_teams_normal ?? null,
          pro: hyp.wins_solo_pro ?? null,
          gtb: hyp.wins_guess_the_build ?? null,
          speedBuilders: hyp.wins_speed_builders ?? null,
        },
      },
      betterBattle,
    };
  });

  app.get("/players/:name/rank-history", async (req) => {
    const { name } = req.params;
    const { days } = z.object({ days: z.coerce.number().int().min(7).max(730).default(90) }).parse(req.query);
    const r = await pool.query(
      `SELECT h.taken_on::text AS taken_on, h.global_rank, h.bb_score FROM player_rank_history h
       JOIN tracked_players t ON t.mc_uuid = h.mc_uuid
       WHERE t.mc_name = $1 AND h.taken_on >= current_date - $2::int ORDER BY h.taken_on`,
      [name, days]);
    if (!r.rowCount) return [];
    return r.rows.map((x: any) => ({ date: x.taken_on, rank: x.global_rank, score: x.bb_score }));
  });

  // ---- Better Battle-only data (empty for players who don't use the mod) ----
  const userIdFor = async (name: string) => {
    const r = await pool.query(
      `SELECT u.id FROM users u JOIN tracked_players t ON t.mc_uuid = u.mc_uuid WHERE t.mc_name = $1 LIMIT 1`, [name]);
    return r.rows[0]?.id as number | undefined;
  };

  app.get("/players/:name/matches", async (req) => {
    const { name } = req.params;
    const q = z.object({
      mode: z.enum(["solo", "teams", "pro", "gtb", "speed_builders"]).optional(),
      before: z.string().datetime().optional(),
    }).parse(req.query);
    const id = await userIdFor(name);
    if (!id) return [];
    const r = await pool.query(
      `SELECT id, played_at, mode, theme, placement, players, score, grade, crowd_score, xp_earned
       FROM matches WHERE user_id = $1 AND ($2::bb_mode IS NULL OR mode = $2)
         AND ($3::timestamptz IS NULL OR played_at < $3)
       ORDER BY played_at DESC LIMIT 25`, [id, q.mode ?? null, q.before ?? null]);
    return r.rows;
  });

  app.get("/players/:name/heatmap", async (req) => {
    const { name } = req.params;
    const id = await userIdFor(name);
    if (!id) return [];
    const r = await pool.query(
      `SELECT day::date::text AS day, games, wins FROM daily_activity
       WHERE user_id = $1 AND day >= now() - interval '26 weeks' ORDER BY day`, [id]);
    return r.rows;
  });

  app.get("/players/:name/achievements", async (req) => {
    const { name } = req.params;
    const id = await userIdFor(name);
    if (!id) return { unlocked: [], total: 0 };
    const [unlocked, total] = await Promise.all([
      pool.query(
        `SELECT a.id, a.name, a.description, a.category, a.points, ua.unlocked_at, ua.source
         FROM user_achievements ua JOIN achievements a ON a.id = ua.achievement_id
         WHERE ua.user_id = $1 ORDER BY ua.unlocked_at DESC`, [id]),
      pool.query("SELECT count(*)::int AS n FROM achievements"),
    ]);
    return { unlocked: unlocked.rows, total: total.rows[0].n };
  });

  app.get("/players/:name/pins", async (req) => {
    const { name } = req.params;
    const id = await userIdFor(name);
    if (!id) return [];
    const r = await pool.query(
      `SELECT m.id, m.theme, m.mode, m.placement, m.players, m.score, m.grade, pm.position
       FROM pinned_matches pm JOIN matches m ON m.id = pm.match_id AND m.played_at = pm.played_at
       WHERE pm.user_id = $1 ORDER BY pm.position`, [id]);
    return r.rows;
  });

  app.get("/challenges/weekly", async () => {
    const r = await pool.query(
      `SELECT id, text, xp FROM weekly_challenges
       WHERE week_start = date_trunc('week', now())::date ORDER BY id`);
    return r.rows;
  });

  /** This week's challenges with the player's progress, and when the week ends. */
  app.get("/players/:name/weekly", async (req) => {
    const id = await userIdFor(req.params.name);
    if (!id) {
      const { endsAt } = await currentWeek(pool);
      const r = await pool.query(
        "SELECT id, text, xp FROM weekly_challenges WHERE week_start = date_trunc('week', now())::date ORDER BY id");
      return { weekEndsAt: endsAt, challenges: r.rows.map((c: any) => ({ ...c, progress: 0, target: null, done: false })) };
    }
    const w = await weeklyProgress(pool, id);
    return { weekEndsAt: w.weekEndsAt, challenges: w.weekly };
  });

  /** The profile card as a PNG: the link-preview image for /p/:name. */
  app.get("/players/:name/card.png", [rateLimit(30, 60_000)], async (req) => {
    if (!NAME.test(req.params.name)) fail(400, "bad_name");
    const png = await profileCard(req.params.name, siteOrigin());
    if (!png) return fail(404, "not_found");
    return new Raw(png, "image/png", { "cache-control": "public, max-age=600" });
  });

  /** How many Better Battle players have each achievement. */
  app.get("/achievements/rarity", async () => {
    if (rarityCache && Date.now() - rarityCache.at < 10 * 60_000) return rarityCache.body;
    const [players, counts] = await Promise.all([
      pool.query("SELECT count(*)::int AS n FROM user_progress"),
      pool.query("SELECT achievement_id AS id, count(*)::int AS n FROM user_achievements GROUP BY achievement_id"),
    ]);
    const body = {
      players: players.rows[0].n,
      counts: Object.fromEntries(counts.rows.map((r: any) => [r.id, r.n])),
    };
    rarityCache = { at: Date.now(), body };
    return body;
  });

  // ---- Speed Builders
  app.get("/spb/builds", async () => worldRecords());

  app.get("/spb/builds/:id", async (req) => {
    if (!/^[a-z0-9_]{1,48}$/.test(req.params.id)) fail(400, "bad_build");
    const board = await buildLeaderboard(req.params.id);
    return board ?? fail(404, "not_found");
  });

  app.get("/players/:name/spb-bests", async (req) => {
    const id = await userIdFor(req.params.name);
    return id ? playerBests(id) : [];
  });
}
