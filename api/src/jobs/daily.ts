import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { rebuildBoards } from "../services/boards";

/** Daily: record every tracked player's global rank (powers the rank graph). */
export async function snapshotRanks() {
  await pool.query(
    `INSERT INTO player_rank_history (mc_uuid, taken_on, global_rank, bb_score)
     SELECT mc_uuid, current_date, rank() OVER (ORDER BY bb_score DESC), bb_score
     FROM tracked_players WHERE bb_score IS NOT NULL
     ON CONFLICT (mc_uuid, taken_on) DO UPDATE
       SET global_rank = EXCLUDED.global_rank, bb_score = EXCLUDED.bb_score`,
  );
}

/** Nightly: rebuild the Redis leaderboard from Postgres so a Redis wipe heals itself. */
export async function rebuildLeaderboard() {
  const r = await pool.query("SELECT mc_uuid, bb_score FROM tracked_players WHERE bb_score IS NOT NULL");
  const tmp = "lb:bb_score:rebuild";
  await redis.del(tmp);
  for (let i = 0; i < r.rows.length; i += 5000) {
    const args: (string | number)[] = [];
    for (const row of r.rows.slice(i, i + 5000)) args.push(row.bb_score, row.mc_uuid);
    if (args.length) await redis.zadd(tmp, ...args);
  }
  if (r.rows.length) await redis.rename(tmp, "lb:bb_score");
  await rebuildBoards();
}

/** Run `fn` every day at hh:mm UTC. */
export function daily(hh: number, mm: number, fn: () => Promise<void>) {
  const schedule = () => {
    const now = new Date();
    const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), hh, mm));
    if (next <= now) next.setUTCDate(next.getUTCDate() + 1);
    setTimeout(async () => {
      try { await fn(); } catch (e) { console.error("daily job failed", e); }
      schedule();
    }, next.getTime() - now.getTime());
  };
  schedule();
}
