// Minimal HTTP framework on node:http: routing, JSON bodies, hooks, static files.
// Replaces Fastify so the API needs fewer dependencies.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { ZodError } from "zod";

export interface Req {
  method: string;
  path: string;
  params: Record<string, string>;
  query: Record<string, string>;
  headers: http.IncomingHttpHeaders;
  cookies: Record<string, string>;
  reply: { headers: Record<string, string | string[]> };
  body: unknown;
  /** The request body exactly as sent. Webhook signatures are checked against these bytes. */
  rawBody?: Buffer;
  ip: string;
  user: { id: number; mcUuid: string | null };
}

export class HttpError extends Error {
  constructor(public status: number, public body: Record<string, unknown>) { super(String(body.error ?? status)); }
}
/** Return from a handler to send bytes (an image, say) instead of JSON. */
export class Raw {
  constructor(public body: Buffer, public type: string, public headers: Record<string, string> = {}) {}
}
export class Redirect {
  constructor(public location: string) {}
}
/** Throw from a handler or hook to send an error response. */
export const fail = (status: number, error: string, extra: Record<string, unknown> = {}): never => {
  throw new HttpError(status, { error, ...extra });
};

/**
 * Who is really asking. Proxy headers (Cloudflare's cf-connecting-ip, x-forwarded-for) are believed only
 * when the connection itself comes from this machine or a private network, i.e. from the tunnel or a
 * reverse proxy. Anyone connecting directly gets their socket address, whatever headers they send.
 */
export function clientIp(req: http.IncomingMessage) {
  const peer = (req.socket.remoteAddress ?? "").replace(/^::ffff:/, "");
  const local = peer === "127.0.0.1" || peer === "::1" || /^10\./.test(peer) || /^192\.168\./.test(peer)
    || /^172\.(1[6-9]|2\d|3[01])\./.test(peer) || /^f[cd]/i.test(peer);
  if (!local) return peer;
  const cf = req.headers["cf-connecting-ip"];
  if (typeof cf === "string" && cf) return cf.trim();
  const xff = req.headers["x-forwarded-for"];
  if (typeof xff === "string" && xff) return xff.split(",")[0].trim();
  return peer;
}

/** The site's own origin(s). A browser request that changes something must come from one of these. */
function allowedOrigins() {
  const out = new Set<string>();
  try { out.add(new URL(process.env.PUBLIC_URL ?? "http://localhost:4000").origin); } catch { /* bad PUBLIC_URL: nothing allowed */ }
  return out;
}

/**
 * True if a request's Origin is this site: the configured PUBLIC_URL, or the very host the browser
 * connected to. (A page on another site can't make a visitor's browser lie about either.)
 */
function sameSite(origin: string, host: string | undefined) {
  if (allowedOrigins().has(origin)) return true;
  try {
    return !!host && new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** Everyone gets this many API requests a minute per address before being asked to slow down. */
const API_PER_MINUTE = Number(process.env.API_RATE ?? 600);
const apiHits = new Map<string, { n: number; reset: number }>();
function overApiLimit(ip: string) {
  const now = Date.now();
  const h = apiHits.get(ip);
  if (!h || h.reset < now) { apiHits.set(ip, { n: 1, reset: now + 60_000 }); return false; }
  return ++h.n > API_PER_MINUTE;
}
setInterval(() => { const now = Date.now(); for (const [k, v] of apiHits) if (v.reset < now) apiHits.delete(k); }, 60_000).unref();

const SECURE = () => (process.env.PUBLIC_URL ?? "").startsWith("https://");

/** Sent with every response. */
function baseHeaders(): Record<string, string> {
  return {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "strict-origin-when-cross-origin",
    "cross-origin-opener-policy": "same-origin",
    "permissions-policy": "camera=(), microphone=(), geolocation=(), payment=()",
    ...(SECURE() ? { "strict-transport-security": "max-age=31536000; includeSubDomains" } : {}),
  };
}

/** For pages: only this site's own scripts run, nothing can frame it, forms only post back here. */
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://mc-heads.net https://cdn.discordapp.com",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

export type Hook = (req: Req) => Promise<void> | void;
export type Handler = (req: Req) => Promise<unknown> | unknown;

interface Route { method: string; re: RegExp; keys: string[]; hooks: Hook[]; handler: Handler }

export class Router {
  private routes: Route[] = [];
  constructor(private prefix = "") {}

  private add(method: string, pattern: string, a: Hook[] | Handler, b?: Handler) {
    const hooks = Array.isArray(a) ? a : [];
    const handler = (b ?? a) as Handler;
    const keys: string[] = [];
    const re = new RegExp("^" + (this.prefix + pattern).replace(/:([A-Za-z]+)/g, (_, k) => { keys.push(k); return "([^/]+)"; }) + "/?$");
    this.routes.push({ method, re, keys, hooks, handler });
  }
  get(p: string, a: Hook[] | Handler, b?: Handler) { this.add("GET", p, a, b); }
  post(p: string, a: Hook[] | Handler, b?: Handler) { this.add("POST", p, a, b); }
  put(p: string, a: Hook[] | Handler, b?: Handler) { this.add("PUT", p, a, b); }
  delete(p: string, a: Hook[] | Handler, b?: Handler) { this.add("DELETE", p, a, b); }

  match(method: string, pathname: string) {
    for (const r of this.routes) {
      // HEAD is answered by the GET route (link-preview crawlers often check with HEAD first)
      if (r.method !== method && !(method === "HEAD" && r.method === "GET")) continue;
      const m = pathname.match(r.re);
      if (!m) continue;
      const params: Record<string, string> = {};
      try {
        r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
      } catch {
        return fail(400, "bad_request");   // malformed %-escape
      }
      return { route: r, params };
    }
    return null;
  }
}

const TYPES: Record<string, string> = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".json": "application/json", ".ttf": "font/ttf",
};

function send(res: http.ServerResponse, status: number, body: unknown, headers: Record<string, string | string[]> = {}) {
  const text = JSON.stringify(body ?? null);
  res.writeHead(status, { ...baseHeaders(), "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers });
  res.end(text);
}

function parseCookies(header: string | undefined) {
  const cookies: Record<string, string> = {};
  for (const part of (header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    const key = part.slice(0, i).trim();
    if (!key) continue;
    const value = part.slice(i + 1).trim();
    try { cookies[key] = decodeURIComponent(value); } catch { cookies[key] = value; }
  }
  return cookies;
}

async function readRaw(req: http.IncomingMessage, limit = 1_000_000): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const c of req) {
    size += (c as Buffer).length;
    if (size > limit) fail(413, "body_too_large");
    chunks.push(c as Buffer);
  }
  return Buffer.concat(chunks);
}

function parseBody(raw: Buffer): unknown {
  if (!raw.length) return undefined;
  try { return JSON.parse(raw.toString("utf8")); } catch { return fail(400, "invalid_json"); }
}

export function createServer(routers: Router[], opts: {
  staticDir?: string; apiPrefix: string; log?: (msg: string) => void;
  /** Rewrites index.html for a path (link preview tags). Must not throw. */
  page?: (html: string, pathname: string) => Promise<string>;
}) {
  const log = opts.log ?? ((m) => console.log(m));
  const staticRoot = opts.staticDir ? path.resolve(opts.staticDir) : null;

  return http.createServer(async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url ?? "/", "http://localhost");
    const method = req.method ?? "GET";
    let replyHeaders: Record<string, string | string[]> = {};
    try {
      // The bot calls the API from inside this process (loopback, no proxy header) and is limited per Discord user instead.
      const ip = clientIp(req);
      const internal = (ip === "127.0.0.1" || ip === "::1") && typeof req.headers["x-bb-internal"] === "string";
      if (url.pathname.startsWith(opts.apiPrefix) && !internal && overApiLimit(ip)) {
        return send(res, 429, { error: "rate_limited" }, { "retry-after": "60" });
      }
      for (const r of routers) {
        const hit = r.match(method, url.pathname);
        if (!hit) continue;
        // A browser always sends Origin on requests that change something. If it's another site's,
        // refuse: this is what stops other websites acting with a visitor's login (CSRF). The mod,
        // Stripe and curl send no Origin and are checked by their own token or signature.
        const origin = req.headers.origin;
        if (method !== "GET" && method !== "HEAD" && typeof origin === "string" && !sameSite(origin, req.headers.host)) {
          return send(res, 403, { error: "cross_site_request" });
        }
        const rawBody = method === "GET" || method === "HEAD" ? undefined : await readRaw(req);
        const request: Req = {
          method, path: url.pathname, params: hit.params,
          query: Object.fromEntries(url.searchParams),
          headers: req.headers,
          cookies: parseCookies(req.headers.cookie),
          reply: { headers: replyHeaders },
          rawBody,
          body: rawBody ? parseBody(rawBody) : undefined,
          ip,
          user: null as any,
        };
        for (const h of hit.route.hooks) await h(request);
        const out = await hit.route.handler(request);
        if (out instanceof Raw) {
          res.writeHead(200, { ...baseHeaders(), ...replyHeaders, ...out.headers, "content-type": out.type, "content-length": String(out.body.length) });
          res.end(method === "HEAD" ? undefined : out.body);
          log(`${method} ${url.pathname} 200 ${Date.now() - started}ms`);
          return;
        }
        if (out instanceof Redirect) {
          send(res, 302, null, { ...replyHeaders, location: out.location });
          log(`${method} ${url.pathname} 302 ${Date.now() - started}ms`);
          return;
        }
        send(res, 200, out, replyHeaders);
        log(`${method} ${url.pathname} 200 ${Date.now() - started}ms`);
        return;
      }

      if (url.pathname.startsWith(opts.apiPrefix)) return send(res, 404, { error: "not_found" });

      // Static site with SPA fallback
      if (staticRoot && (method === "GET" || method === "HEAD")) {
        let decoded: string;
        try {
          decoded = decodeURIComponent(url.pathname);
        } catch {
          return send(res, 400, { error: "bad_request" });
        }
        const rel = path.normalize(decoded).replace(/^([/\\])+/, "");
        let file = path.join(staticRoot, rel);
        // Only real files inside the web folder, never dotfiles, never anything reached through "..".
        const inside = file.startsWith(staticRoot + path.sep);
        const hidden = rel.split(/[/\\]/).some((part) => part.startsWith("."));
        if (!inside || hidden || decoded.includes("\0") || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
          file = path.join(staticRoot, "index.html");
        }
        const isPage = path.extname(file) === ".html";
        res.writeHead(200, {
          ...baseHeaders(),
          ...(isPage ? { "content-security-policy": CSP } : {}),
          "content-type": TYPES[path.extname(file)] ?? "application/octet-stream",
          "cache-control": file.endsWith("index.html") ? "no-cache" : "public, max-age=300",
        });
        if (method === "HEAD") return res.end();
        if (opts.page && file.endsWith("index.html")) {
          res.end(await opts.page(await fs.promises.readFile(file, "utf8"), url.pathname));
          return;
        }
        fs.createReadStream(file).pipe(res);
        return;
      }
      send(res, 404, { error: "not_found" });
    } catch (e) {
      if (e instanceof HttpError) { send(res, e.status, e.body, replyHeaders); log(`${method} ${url.pathname} ${e.status}`); return; }
      if (e instanceof ZodError) { send(res, 400, { error: "bad_request", issues: e.issues }, replyHeaders); return; }
      console.error(`${method} ${url.pathname} failed`, e);
      if (!res.headersSent) send(res, 500, { error: "server_error" }, replyHeaders);
    }
  });
}
