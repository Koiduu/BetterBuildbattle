// Teammate pings in Build Battle Teams. The rules are in services/pings.ts; this file wires them to Redis.
// Only players signed in with the mod (a Mojang-verified mod token) can poll or send, and a ping only reaches
// a teammate who is polling too, so both sides are Better Battle players.
import { Router, fail } from "../http";
import { z } from "zod";
import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { rateLimit, requireModToken } from "../plugins/auth";
import { PING_KINDS, leave, poll, send, type Player, type PingStore } from "../services/pings";

const Name = z.string().regex(/^[A-Za-z0-9_]{1,16}$/);
const Coord = z.number().int().min(-30_000_000).max(30_000_000);

export const redisPingStore: PingStore = {
  async get(key) { return redis.get(key); },
  async set(key, value, ttlMs) { await redis.set(key, value, "PX", ttlMs); },
  async claim(key, ttlMs) { return (await redis.set(key, "1", "PX", ttlMs, "NX")) === "OK"; },
  async push(key, value, ttlMs, max) {
    await redis.multi().rpush(key, value).ltrim(key, -max, -1).pexpire(key, ttlMs).exec();
  },
  async drain(key) {
    const res = await redis.multi().lrange(key, 0, -1).del(key).exec();
    return (res?.[0]?.[1] as string[] | undefined) ?? [];
  },
  async del(...keys) { if (keys.length) await redis.del(...keys); },
};

async function me(id: number): Promise<Player> {
  const r = await pool.query("SELECT mc_name FROM users WHERE id = $1 AND mc_uuid IS NOT NULL", [id]);
  if (!r.rowCount || !r.rows[0].mc_name) return fail(404, "you_not_found");
  return { id, name: String(r.rows[0].mc_name) };
}

/** The teammate's Better Battle account, or null if they've never signed in with the mod. */
async function mate(name: string): Promise<Player | null> {
  const r = await pool.query(
    `SELECT u.id, u.mc_name FROM users u
     WHERE u.mc_name = $1 AND u.mc_uuid IS NOT NULL
       AND EXISTS (SELECT 1 FROM sessions s WHERE s.user_id = u.id AND s.kind = 'mod' AND s.expires_at > now())
     ORDER BY u.last_seen_at DESC NULLS LAST LIMIT 1`, [name]);
  return r.rowCount ? { id: Number(r.rows[0].id), name: String(r.rows[0].mc_name) } : null;
}

export function pingRoutes(app: Router) {
  // Polled every couple of seconds while in a Teams game: says who the teammate is, returns waiting pings.
  app.post("/mod/pings/poll", [requireModToken, rateLimit(90, 60_000, "/mod/pings/poll")], async (req) => {
    const { teammate } = z.object({ teammate: Name }).parse(req.body);
    const [self, other] = await Promise.all([me(req.user.id), mate(teammate)]);
    return poll(redisPingStore, self, teammate, other);
  });

  app.post("/mod/pings/send", [requireModToken, rateLimit(40, 60_000, "/mod/pings/send")], async (req) => {
    const body = z.object({
      teammate: Name,
      kind: z.enum(PING_KINDS),
      block: z.object({ x: Coord, y: z.number().int().min(-2048).max(2048), z: Coord }).nullable().default(null),
    }).parse(req.body);
    if (body.kind === "block" && !body.block) fail(400, "block_missing");
    const [self, other] = await Promise.all([me(req.user.id), mate(body.teammate)]);
    const r = await send(redisPingStore, self, body.teammate, other, body.kind, body.block);
    if (!r.ok) fail(r.status, r.error);
    return { ok: true };
  });

  app.post("/mod/pings/leave", [requireModToken, rateLimit(30, 60_000, "/mod/pings/leave")], async (req) => {
    await leave(redisPingStore, { id: req.user.id, name: "" });
    return { ok: true };
  });
}
