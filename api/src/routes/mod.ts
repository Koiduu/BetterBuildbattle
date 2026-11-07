// Mod-facing routes: Mojang handshake + batched, idempotent sync.
import { FastifyInstance } from "fastify";
import { z } from "zod";
import crypto from "node:crypto";
import { pool } from "../db/pool";
import { recomputeRating } from "../services/rating";
import { evaluateAchievements } from "../services/achievementEngine";
import { redis } from "../db/redis";

const Mode = z.enum(["solo", "teams", "pro", "gtb", "speed_builders"]);

const MatchIn = z.object({
  nonce: z.string().uuid(),
  playedAt: z.string().datetime(),
  mode: Mode,
  theme: z.string().max(80).nullable(),
  placement: z.number().int().min(1).max(32).nullable(),
  players: z.number().int().min(1).max(32).nullable(),
  score: z.number().nullable(),
  durationSec: z.number().int().min(0).max(3600),
  blocksPlaced: z.number().int().min(0).max(20000),
  blocksBroken: z.number().int().min(0).max(20000).default(0),
  maxScore: z.number().nullable().default(null),
  palette: z.record(z.number().int().min(0)).optional(),
  spbRounds: z
    .array(z.object({ build: z.string(), timeMs: z.number().int().positive(), accuracy: z.number().min(0).max(1) }))
    .optional(),
});

const SyncBody = z.object({
  modVersion: z.string(),
  mcVersion: z.enum(["26.3", "1.8.9"]),
  matches: z.array(MatchIn).max(50),
  localAchievements: z.array(z.string()).max(500).default([]),
});

export default async function modRoutes(app: FastifyInstance) {
  // ---- 1. Handshake: issue a random serverId ----
  app.post("/auth/mod/challenge", async () => {
    const serverId = crypto.randomBytes(16).toString("hex");
    await pool.query(
      "INSERT INTO mod_challenges (server_id, expires_at) VALUES ($1, now() + interval '2 minutes')",
      [serverId],
    );
    return { serverId };
  });

  // ---- 2. Verify with Mojang hasJoined, mint a mod token ----
  app.post("/auth/mod/verify", async (req, reply) => {
    const { name, serverId } = z.object({ name: z.string().max(16), serverId: z.string() }).parse(req.body);
    const ch = await pool.query(
      "DELETE FROM mod_challenges WHERE server_id = $1 AND expires_at > now() RETURNING 1",
      [serverId],
    );
    if (ch.rowCount === 0) return reply.code(400).send({ error: "challenge_expired" });

    const res = await fetch(
      `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`,
    );
    if (res.status !== 200) return reply.code(401).send({ error: "not_verified" });
    const profile = (await res.json()) as { id: string; name: string };
    const uuid = profile.id.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");

    const user = await pool.query(
      `INSERT INTO users (mc_uuid, mc_name) VALUES ($1, $2)
       ON CONFLICT (mc_uuid) DO UPDATE SET mc_name = EXCLUDED.mc_name, last_seen_at = now()
       RETURNING id`,
      [uuid, profile.name],
    );
    const token = crypto.randomBytes(32).toString("base64url");
    await pool.query(
      `INSERT INTO sessions (user_id, kind, token_hash, expires_at)
       VALUES ($1, 'mod', $2, now() + interval '30 days')`,
      [user.rows[0].id, crypto.createHash("sha256").update(token).digest()],
    );
    return { token, expiresInDays: 30 };
  });

  // ---- 3. Sync: idempotent batch insert ----
  app.post("/mod/sync", { preHandler: app.requireModToken, config: { rateLimit: { max: 30, timeWindow: "1 minute" } } },
    async (req) => {
      const userId: number = req.user.id;
      const body = SyncBody.parse(req.body);
      const client = await pool.connect();
      const accepted: string[] = [];
      const rejected: { nonce: string; reason: string }[] = [];
      try {
        await client.query("BEGIN");
        for (const m of body.matches) {
          const problem = await sanityCheck(client, m);
          if (problem) { rejected.push({ nonce: m.nonce, reason: problem }); continue; }

          const ins = await client.query(
            `INSERT INTO matches (user_id, played_at, mode, theme, placement, players, score,
                                  duration_sec, blocks_placed, palette, spb_rounds, client_nonce,
                                  mod_version, mc_version)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
             ON CONFLICT (user_id, client_nonce, played_at) DO NOTHING
             RETURNING id`,
            [userId, m.playedAt, m.mode, m.theme, m.placement, m.players, m.score, m.durationSec,
             m.blocksPlaced, m.palette ?? null, JSON.stringify(m.spbRounds ?? null), m.nonce,
             body.modVersion, body.mcVersion],
          );
          accepted.push(m.nonce); // duplicates count as accepted: the mod can mark them synced
          if (ins.rowCount === 0) continue;

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
        const rating = await recomputeRating(client, userId);
        const confirmed = await evaluateAchievements(client, userId, body.localAchievements);
        await client.query("COMMIT");

        await redis.zadd("lb:rating:overall", rating, String(userId));
        const rank = await redis.zrevrank("lb:rating:overall", String(userId));
        return { accepted, rejected, confirmed, rating, globalRank: rank === null ? null : rank + 1 };
      } catch (e) {
        await client.query("ROLLBACK");
        throw e;
      } finally {
        client.release();
      }
    });
}

async function sanityCheck(client: any, m: z.infer<typeof MatchIn>): Promise<string | null> {
  if (new Date(m.playedAt).getTime() > Date.now() + 5 * 60_000) return "future_timestamp";
  for (const r of m.spbRounds ?? []) {
    const b = await client.query("SELECT min_time_ms FROM spb_builds WHERE id = $1", [r.build]);
    if (b.rowCount && r.timeMs < b.rows[0].min_time_ms) return "impossible_spb_time";
  }
  return null;
}
