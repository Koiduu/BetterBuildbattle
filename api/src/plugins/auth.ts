import crypto from "node:crypto";
import { pool } from "../db/pool";
import { fail, type Req } from "../http";

const WEB_COOKIE = "bb_session";
const WEB_SESSION_SECONDS = 30 * 24 * 60 * 60;

function cookieFlags(maxAge: number) {
  const secure = (process.env.PUBLIC_URL ?? "http://localhost:4000").startsWith("https://") ? "; Secure" : "";
  return `HttpOnly; SameSite=Lax; Path=/; Max-Age=${maxAge}${secure}`;
}

function setCookie(req: Req, value: string) {
  const current = req.reply.headers["set-cookie"];
  req.reply.headers["set-cookie"] = current
    ? [...(Array.isArray(current) ? current : [current]), value]
    : value;
}

export async function startWebSession(req: Req, userId: number) {
  const token = crypto.randomBytes(32).toString("base64url");
  await pool.query(
    `INSERT INTO sessions (user_id, kind, token_hash, expires_at)
     VALUES ($1, 'web', $2, now() + interval '30 days')`,
    [userId, crypto.createHash("sha256").update(token).digest()],
  );
  setCookie(req, `${WEB_COOKIE}=${token}; ${cookieFlags(WEB_SESSION_SECONDS)}`);
}

export async function endWebSession(req: Req) {
  const token = req.cookies[WEB_COOKIE];
  if (token) {
    await pool.query(
      "DELETE FROM sessions WHERE token_hash = $1 AND kind = 'web'",
      [crypto.createHash("sha256").update(token).digest()],
    );
  }
  setCookie(req, `${WEB_COOKIE}=; ${cookieFlags(0)}`);
}

export async function readWebSession(req: Req): Promise<{ id: number; mcUuid: string | null } | null> {
  try {
    const token = req.cookies[WEB_COOKIE];
    if (!token) return null;
    const result = await pool.query(
      `SELECT u.id, u.mc_uuid FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = $1 AND s.kind = 'web' AND s.expires_at > now()`,
      [crypto.createHash("sha256").update(token).digest()],
    );
    if (!result.rowCount) return null;
    return { id: result.rows[0].id, mcUuid: result.rows[0].mc_uuid };
  } catch {
    return null;
  }
}

export async function requireWebSession(req: Req) {
  const user = await readWebSession(req);
  if (!user) return fail(401, "not_logged_in");
  req.user = user;
}

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
export function rateLimit(max: number, windowMs: number, route?: string) {
  return (req: Req) => {
    const key = (req.headers.authorization ?? req.ip) + " " + req.method + " " + (route ?? req.path.replace(/\/[^/]+$/, "/:x"));
    const now = Date.now();
    const h = hits.get(key);
    if (!h || h.reset < now) { hits.set(key, { n: 1, reset: now + windowMs }); return; }
    if (++h.n > max) fail(429, "rate_limited");
  };
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (v.reset < now) hits.delete(k); }, 60_000).unref();
