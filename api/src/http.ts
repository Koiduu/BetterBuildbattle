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
      r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
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
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers });
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

async function readBody(req: http.IncomingMessage, limit = 1_000_000): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const c of req) {
    size += (c as Buffer).length;
    if (size > limit) fail(413, "body_too_large");
    chunks.push(c as Buffer);
  }
  if (!size) return undefined;
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { return fail(400, "invalid_json"); }
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
      for (const r of routers) {
        const hit = r.match(method, url.pathname);
        if (!hit) continue;
        const request: Req = {
          method, path: url.pathname, params: hit.params,
          query: Object.fromEntries(url.searchParams),
          headers: req.headers,
          cookies: parseCookies(req.headers.cookie),
          reply: { headers: replyHeaders },
          body: method === "GET" ? undefined : await readBody(req),
          ip: (req.headers["cf-connecting-ip"] as string) ?? (req.headers["x-forwarded-for"] as string)?.split(",")[0] ?? req.socket.remoteAddress ?? "",
          user: null as any,
        };
        for (const h of hit.route.hooks) await h(request);
        const out = await hit.route.handler(request);
        if (out instanceof Raw) {
          res.writeHead(200, { ...replyHeaders, ...out.headers, "content-type": out.type, "content-length": String(out.body.length) });
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
        const rel = path.normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, "");
        let file = path.join(staticRoot, rel);
        if (!file.startsWith(staticRoot) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
          file = path.join(staticRoot, "index.html");
        }
        res.writeHead(200, {
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
