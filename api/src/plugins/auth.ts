import crypto from "node:crypto";
import { pool } from "../db/pool";
import { fail, type Req } from "../http";

/** Hook: requires a valid mod bearer token; sets req.user. */
export async function requireModToken(req: Req) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) fail(401, "missing_token");
  const hash = crypto.createHash("sha256").update(token).digest();
  const r = await pool.query(
    `SELECT u.id, u.mc_uuid FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = $1 AND s.kind = 'mod' AND s.expires_at > now()`,
    [hash],
  );
  if (!r.rowCount) fail(401, "invalid_token");
  req.user = { id: r.rows[0].id, mcUuid: r.rows[0].mc_uuid };
}

/** Hook factory: fixed-window rate limit per token (or IP) per route. */
const hits = new Map<string, { n: number; reset: number }>();
export function rateLimit(max: number, windowMs: number) {
  return (req: Req) => {
    const key = (req.headers.authorization ?? req.ip) + " " + req.method + " " + req.path.replace(/\/[^/]+$/, "/:x");
    const now = Date.now();
    const h = hits.get(key);
    if (!h || h.reset < now) { hits.set(key, { n: 1, reset: now + windowMs }); return; }
    if (++h.n > max) fail(429, "rate_limited");
  };
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (v.reset < now) hits.delete(k); }, 60_000).unref();
