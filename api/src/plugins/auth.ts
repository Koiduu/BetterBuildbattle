import crypto from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { pool } from "../db/pool";

declare module "fastify" {
  interface FastifyInstance {
    requireModToken: (req: FastifyRequest, reply: FastifyReply) => Promise<void>;
  }
  interface FastifyRequest {
    user: { id: number; mcUuid: string | null };
  }
}

export function registerAuth(app: FastifyInstance) {
  app.decorateRequest("user", null as any);
  app.decorate("requireModToken", async (req: FastifyRequest, reply: FastifyReply) => {
    const header = req.headers.authorization ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (!token) return reply.code(401).send({ error: "missing_token" });
    const hash = crypto.createHash("sha256").update(token).digest();
    const r = await pool.query(
      `SELECT u.id, u.mc_uuid FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = $1 AND s.kind = 'mod' AND s.expires_at > now()`,
      [hash],
    );
    if (!r.rowCount) return reply.code(401).send({ error: "invalid_token" });
    req.user = { id: r.rows[0].id, mcUuid: r.rows[0].mc_uuid };
  });
}

/** Very small fixed-window rate limiter (per token or IP), so we don't need another dependency. */
const hits = new Map<string, { n: number; reset: number }>();
export function rateLimit(max: number, windowMs: number) {
  return async (req: FastifyRequest, reply: FastifyReply) => {
    const key = (req.headers.authorization ?? req.ip) + req.routeOptions.url;
    const now = Date.now();
    const h = hits.get(key);
    if (!h || h.reset < now) { hits.set(key, { n: 1, reset: now + windowMs }); return; }
    if (++h.n > max) return reply.code(429).send({ error: "rate_limited" });
  };
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (v.reset < now) hits.delete(k); }, 60_000).unref();
