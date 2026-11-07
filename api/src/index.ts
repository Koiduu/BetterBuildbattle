import path from "node:path";
import { fileURLToPath } from "node:url";
import Fastify from "fastify";
import fastifyStatic from "@fastify/static";
import { ZodError } from "zod";
import { pool } from "./db/pool";
import { registerAuth } from "./plugins/auth";
import modRoutes from "./routes/mod";
import publicRoutes from "./routes/public";
import { seedCatalog, catalog } from "./services/achievementEngine";
import { runCrawler, crawlLeaderboards } from "./jobs/crawler";
import { daily, snapshotRanks, rebuildLeaderboard } from "./jobs/daily";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = Fastify({ logger: { level: process.env.LOG_LEVEL ?? "info" }, trustProxy: true });

registerAuth(app);

app.setErrorHandler((err, req, reply) => {
  if (err instanceof ZodError) return reply.code(400).send({ error: "bad_request", issues: err.issues });
  req.log.error(err);
  reply.code((err as any).statusCode ?? 500).send({ error: "server_error" });
});

await app.register(modRoutes, { prefix: "/bb/v1" });
await app.register(publicRoutes, { prefix: "/bb/v1" });

// Website: static files from /web, with SPA fallback so /p/Koidu loads index.html.
await app.register(fastifyStatic, { root: path.resolve(here, "../../web"), wildcard: false });
app.setNotFoundHandler((req, reply) => {
  if (req.method === "GET" && !req.url.startsWith("/bb/")) return reply.sendFile("index.html");
  reply.code(404).send({ error: "not_found" });
});

/** Pick 3 weekly challenges every Monday (idempotent). */
async function pickWeekly() {
  const week = await pool.query("SELECT date_trunc('week', now())::date AS w");
  const w = week.rows[0].w;
  const have = await pool.query("SELECT 1 FROM weekly_challenges WHERE week_start = $1", [w]);
  if (have.rowCount) return;
  const pool3 = [...catalog.weekly_pool].sort(() => Math.random() - 0.5).slice(0, 3);
  for (const c of pool3) {
    await pool.query("INSERT INTO weekly_challenges (week_start, id, text, xp) VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING",
      [w, c.id, c.text, c.xp]);
  }
}

await seedCatalog();
await pickWeekly();

const port = Number(process.env.PORT ?? 4000);
await app.listen({ port, host: process.env.HOST ?? "0.0.0.0" });

// Background work (skip with NO_JOBS=1, e.g. when running a second API instance)
if (process.env.NO_JOBS !== "1") {
  if (process.env.HYPIXEL_API_KEY) {
    const ac = new AbortController();
    runCrawler(ac.signal).catch((e) => app.log.error(e, "crawler stopped"));
    process.on("SIGTERM", () => ac.abort());
    crawlLeaderboards().catch((e) => app.log.error(e));
    daily(1, 0, crawlLeaderboards);
  } else {
    app.log.warn("HYPIXEL_API_KEY not set: crawler disabled, rankings will not update");
  }
  daily(0, 5, snapshotRanks);
  daily(3, 0, rebuildLeaderboard);
  daily(0, 1, pickWeekly);
}
