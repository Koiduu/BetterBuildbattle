// Run the daily jobs by hand: npm run snapshot
import { snapshotRanks, rebuildLeaderboard } from "../src/jobs/daily";
import { pool } from "../src/db/pool";
import { redis } from "../src/db/redis";

await rebuildLeaderboard();
await snapshotRanks();
console.log("Leaderboard rebuilt and today's ranks recorded");
await pool.end();
redis.disconnect();
