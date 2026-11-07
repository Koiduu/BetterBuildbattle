// Every 6h: pull Build Battle stats from the Hypixel public API.
// hypixel_bb_score is the ONLY input to global rank, so it never comes from the client.
import { pool } from "../db/pool";
import { redis } from "../db/redis";

const KEY = process.env.HYPIXEL_API_KEY!;

export async function verifyUser(userId: number, uuid: string) {
  const res = await fetch(`https://api.hypixel.net/v2/player?uuid=${uuid}`, { headers: { "API-Key": KEY } });
  if (!res.ok) return;
  const body = await res.json();
  const bb = body.player?.stats?.BuildBattle ?? {};
  const score: number = bb.score ?? 0;

  await pool.query(
    `UPDATE users SET hypixel_bb_score = $2, hypixel_synced_at = now() WHERE id = $1`,
    [userId, score],
  );
  await pool.query(
    `UPDATE user_stats SET hypixel_wins = $2, hypixel_verified_at = now()
     WHERE user_id = $1 AND mode = 'overall'`,
    [userId, bb.wins ?? 0],
  );
  // Ranking is keyed by UUID so mod users and crawled players share one leaderboard.
  await redis.zadd("lb:bb_score", score, uuid);
  // Retroactive tracks (bb_wins, bb_score, perfect_builds, correct_guesses, per-mode wins)
  // are granted from these verified numbers by achievementEngine.grantFromTotals().
}
