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

/**
 * A secret that exists only inside this process. The Discord bot runs in the same process and calls the
 * API over localhost; it proves that with this value, so its requests can be limited per Discord user
 * instead of all sharing one address.
 */
export const INTERNAL_KEY = crypto.randomBytes(24).toString("hex");

/** Who a rate limit counts against: the logged-in user if a login hook already ran, else the address. */
function limitKey(req: Req) {
  if (req.user?.id) return `user:${req.user.id}`;
  const internal = req.headers["x-bb-internal"];
  const caller = req.headers["x-bb-caller"];
  if (typeof internal === "string" && typeof caller === "string" && internal.length === INTERNAL_KEY.length
      && crypto.timingSafeEqual(Buffer.from(internal), Buffer.from(INTERNAL_KEY))) {
    return `internal:${caller.slice(0, 64)}`;
  }
  return `ip:${req.ip}`;
}

/**
 * Hook factory: fixed-window rate limit per user (or address) per route.
 * The key never comes from anything the caller can simply make up, such as an Authorization header.
 */
const hits = new Map<string, { n: number; reset: number }>();
export function rateLimit(max: number, windowMs: number, route?: string) {
  return (req: Req) => {
    const key = limitKey(req) + " " + req.method + " " + (route ?? req.path.replace(/\/[^/]+$/, "/:x"));
    const now = Date.now();
    const h = hits.get(key);
    if (!h || h.reset < now) { hits.set(key, { n: 1, reset: now + windowMs }); return; }
    if (++h.n > max) fail(429, "rate_limited");
  };
}
setInterval(() => { const now = Date.now(); for (const [k, v] of hits) if (v.reset < now) hits.delete(k); }, 60_000).unref();

/**
 * Counts things that went wrong for a subject (failed logins for an email, say) and says when to stop
 * listening for a while. Separate from rateLimit so that guessing one account's password from many
 * addresses is slowed just the same.
 */
const failures = new Map<string, { n: number; reset: number }>();
export function tooManyFailures(subject: string, max: number) {
  const f = failures.get(subject);
  return !!f && f.reset > Date.now() && f.n >= max;
}
export function noteFailure(subject: string, windowMs: number) {
  const now = Date.now();
  const f = failures.get(subject);
  if (!f || f.reset < now) failures.set(subject, { n: 1, reset: now + windowMs });
  else f.n++;
}
export const clearFailures = (subject: string) => { failures.delete(subject); };
setInterval(() => { const now = Date.now(); for (const [k, v] of failures) if (v.reset < now) failures.delete(k); }, 60_000).unref();
