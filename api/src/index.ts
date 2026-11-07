import path from "node:path";
import { fileURLToPath } from "node:url";
import { pool } from "./db/pool";
import { Router, createServer } from "./http";
import { modRoutes } from "./routes/mod";
import { publicRoutes } from "./routes/public";
import { seedCatalog, catalog } from "./services/achievementEngine";
import { runCrawler, crawlLeaderboards, checkKey } from "./jobs/crawler";
import { daily, snapshotRanks, rebuildLeaderboard } from "./jobs/daily";

const here = path.dirname(fileURLToPath(import.meta.url));
const PREFIX = "/bb/v1";

const api = new Router(PREFIX);
modRoutes(api);
publicRoutes(api);
api.get("/health", async () => {
  await pool.query("SELECT 1");
  return { ok: true };
});

/** Pick 3 weekly challenges for the current week (idempotent). */
async function pickWeekly() {
  const w = (await pool.query("SELECT date_trunc('week', now())::date::text AS w")).rows[0].w;
  const have = await pool.query("SELECT 1 FROM weekly_challenges WHERE week_start = $1", [w]);
  if (have.rowCount) return;
  const picks = [...catalog.weekly_pool].sort(() => Math.random() - 0.5).slice(0, 3);
  for (const c of picks) {
    await pool.query(
      "INSERT INTO weekly_challenges (week_start, id, text, xp) VALUES ($1,$2,$3,$4) ON CONFLICT DO NOTHING",
      [w, c.id, c.text, c.xp]);
  }
}

await seedCatalog();
await pickWeekly();

const server = createServer([api], {
  apiPrefix: PREFIX,
  staticDir: process.env.WEB_DIR ?? path.resolve(here, "../../web"),
  log: process.env.LOG_REQUESTS === "1" ? (m) => console.log(m) : () => {},
});
const port = Number(process.env.PORT ?? 4000);
server.listen(port, process.env.HOST ?? "0.0.0.0", () => console.log(`Better Battle listening on :${port}`));

const ac = new AbortController();
const shutdown = () => { ac.abort(); server.close(); pool.end().finally(() => process.exit(0)); };
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

if (process.env.NO_JOBS !== "1") {
  if (process.env.HYPIXEL_API_KEY) {
    await checkKey();
    runCrawler(ac.signal).catch((e) => console.error("crawler stopped", e));
    crawlLeaderboards().catch((e) => console.error("leaderboard crawl failed", e));
    daily(1, 0, crawlLeaderboards);
  } else {
    console.warn("HYPIXEL_API_KEY not set: crawler disabled, rankings will not update");
  }
  daily(0, 5, snapshotRanks);
  daily(3, 0, rebuildLeaderboard);
  daily(0, 1, pickWeekly);
}
