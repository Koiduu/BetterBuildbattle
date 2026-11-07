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
  body: unknown;
  ip: string;
  user: { id: number; mcUuid: string | null };
}

export class HttpError extends Error {
  constructor(public status: number, public body: Record<string, unknown>) { super(String(body.error ?? status)); }
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

  match(method: string, pathname: string) {
    for (const r of this.routes) {
      if (r.method !== method) continue;
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
  ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".json": "application/json",
};

function send(res: http.ServerResponse, status: number, body: unknown) {
  const text = JSON.stringify(body ?? null);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(text);
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

export function createServer(routers: Router[], opts: { staticDir?: string; apiPrefix: string; log?: (msg: string) => void }) {
  const log = opts.log ?? ((m) => console.log(m));
  const staticRoot = opts.staticDir ? path.resolve(opts.staticDir) : null;

  return http.createServer(async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url ?? "/", "http://localhost");
    const method = req.method ?? "GET";
    try {
      for (const r of routers) {
        const hit = r.match(method, url.pathname);
        if (!hit) continue;
        const request: Req = {
          method, path: url.pathname, params: hit.params,
          query: Object.fromEntries(url.searchParams),
          headers: req.headers,
          body: method === "GET" ? undefined : await readBody(req),
          ip: (req.headers["cf-connecting-ip"] as string) ?? (req.headers["x-forwarded-for"] as string)?.split(",")[0] ?? req.socket.remoteAddress ?? "",
          user: null as any,
        };
        for (const h of hit.route.hooks) await h(request);
        const out = await hit.route.handler(request);
        send(res, 200, out);
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
        fs.createReadStream(file).pipe(res);
        return;
      }
      send(res, 404, { error: "not_found" });
    } catch (e) {
      if (e instanceof HttpError) { send(res, e.status, e.body); log(`${method} ${url.pathname} ${e.status}`); return; }
      if (e instanceof ZodError) { send(res, 400, { error: "bad_request", issues: e.issues }); return; }
      console.error(`${method} ${url.pathname} failed`, e);
      if (!res.headersSent) send(res, 500, { error: "server_error" });
    }
  });
}
