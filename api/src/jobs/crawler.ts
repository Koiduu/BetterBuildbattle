// Player discovery builds the global Build Battle ranking from anyone we can find.
// Global rank = position by bb_score among tracked_players.
//
// Discovery sources:
//   leaderboard – Hypixel /v2/leaderboards, checked hourly.
//   lobby       – UUIDs seen in mod users' game lobbies.
//   search      – players requested by website searches or profiles.
//   mod_user    – players who log in through the mod.
//   import      – one-off CSV of names.
//
// There are no timed re-checks. A newly discovered player is checked once, then
// leaderboard or lobby sightings can make them due again after 7 days. Player-driven
// lookups refresh cached data after 1 hour. Failed requests use the retry backoff below.
import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { titleFor } from "../lib/titles";
import { HYPIXEL_API, dashUuid } from "../lib/upstream";
import { updateHypixelBoards } from "../services/boards";

const KEY = process.env.HYPIXEL_API_KEY!;
const REQUESTS_PER_5_MIN = Number(process.env.HYPIXEL_RATE ?? 280); // stay under the key's limit
const INTERVAL_MS = (5 * 60_000) / REQUESTS_PER_5_MIN;

export async function queuePlayers(uuids: string[], source: string, priority = false, staleAfter = "7 days") {
  if (!uuids.length) return 0;
  const result = await pool.query(
    `INSERT INTO tracked_players (mc_uuid, source, next_check)
     SELECT DISTINCT u, $2, CASE WHEN $3 THEN now() - interval '1 day' ELSE now() END
     FROM unnest($1::uuid[]) AS input(u)
     ON CONFLICT (mc_uuid) DO UPDATE
       SET next_check = LEAST(tracked_players.next_check, EXCLUDED.next_check)
       WHERE tracked_players.last_checked < now() - $4::interval
         AND tracked_players.check_failures = 0
     RETURNING (xmax = 0) AS inserted`,
    [uuids, source, priority, staleAfter],
  );
  return result.rows.filter((row: any) => row.inserted).length;
}

export class HypixelKeyError extends Error {}

export async function refreshOne(uuid: string) {
  const fresh = await pool.query(
    "SELECT 1 FROM tracked_players WHERE mc_uuid = $1 AND last_checked >= now() - interval '1 hour'",
    [uuid],
  );
  if (fresh.rowCount) return;

  const res = await fetch(`${HYPIXEL_API}/v2/player?uuid=${uuid}`, { headers: { "API-Key": KEY } });
  if (res.status === 429) throw new Error("rate_limited");
  if (res.status === 401 || res.status === 403) {
    // The key is the problem, not this player, so don't push their next check back.
    const body = await res.json().catch(() => ({}));
    throw new HypixelKeyError(body.cause ?? `HTTP ${res.status}`);
  }
  if (!res.ok) {
    console.warn(`Hypixel returned ${res.status} for ${uuid}`);
    await pool.query(
      `UPDATE tracked_players SET check_failures = check_failures + 1,
         next_check = now() + interval '1 day' * power(2, LEAST(check_failures, 6)) WHERE mc_uuid = $1`, [uuid]);
    return;
  }
  const { player } = await res.json();
  const bb = player?.stats?.BuildBattle;
  if (!bb || !(bb.games_played || bb.score || bb.wins)) {
    await pool.query(
      `UPDATE tracked_players SET mc_name = $2, no_bb_stats = true, last_checked = now(),
         check_failures = 0, next_check = 'infinity' WHERE mc_uuid = $1`, [uuid, player?.displayname ?? null]);
    return;
  }
  const score: number = bb.score ?? 0;
  await pool.query(
    `UPDATE tracked_players SET mc_name = $2, bb_score = $3, bb_wins = $4, bb_games = $5, title = $6,
       hypixel_bb = $7, no_bb_stats = false, check_failures = 0, last_checked = now(),
       next_check = 'infinity'
     WHERE mc_uuid = $1`,
    [uuid, player.displayname, score, bb.wins ?? 0, bb.games_played ?? 0, titleFor(score).name, numericOnly(bb)],
  );
  // If this player has a Better Battle account, keep their verified totals in sync too.
  await pool.query(
    `UPDATE users SET hypixel_bb = $2, hypixel_bb_score = $3, hypixel_title = $4, hypixel_synced_at = now(),
       mc_name = $5 WHERE mc_uuid = $1`,
    [uuid, numericOnly(bb), score, titleFor(score).name, player.displayname],
  );
  await redis.zadd("lb:bb_score", score, uuid);   // global ranking keyed by UUID
  await updateHypixelBoards(uuid, bb);            // and every other Build Battle board

}

/** One cheap call at startup so a bad key shows up straight away in the logs. */
export async function checkKey() {
  try {
    const res = await fetch(`${HYPIXEL_API}/v2/counts`, { headers: { "API-Key": KEY } });
    if (res.ok) return console.log("Hypixel API key OK");
    const body = await res.json().catch(() => ({}));
    console.error(`Hypixel API key rejected (${res.status}): ${body.cause ?? "no reason given"}`);
    if (res.status === 403) console.error("New Production keys only work once Hypixel has approved them.");
  } catch (e: any) {
    console.error("Couldn't reach the Hypixel API:", e.message);
  }
}

export async function crawlLeaderboards() {
  const res = await fetch(`${HYPIXEL_API}/v2/leaderboards`, { headers: { "API-Key": KEY } });
  if (!res.ok) {
    console.warn(`Hypixel leaderboards returned ${res.status}`);
    return;
  }
  const body = await res.json().catch(() => ({}));
  const leaderboards = body && typeof body === "object" && !Array.isArray(body)
    ? (body as any).leaderboards
    : null;
  const rawBoards = leaderboards && typeof leaderboards === "object" && !Array.isArray(leaderboards)
    ? leaderboards.BUILD_BATTLE
    : null;
  const boards = Array.isArray(rawBoards) ? rawBoards : [];
  const uuids = new Set<string>();
  let validBoards = 0;
  for (const board of boards) {
    if (!board || typeof board !== "object" || !Array.isArray((board as any).leaders)) continue;
    validBoards++;
    for (const value of (board as any).leaders) {
      if (typeof value !== "string") continue;
      const uuid = dash(value);
      if (/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(uuid)) uuids.add(uuid);
    }
  }
  const players = [...uuids];
  const added = await queuePlayers(players, "leaderboard", true);
  console.log(`Hypixel leaderboards: ${players.length} players on ${validBoards} Build Battle boards (${added} new)`);
}

export async function runCrawler(signal: AbortSignal) {
  while (!signal.aborted) {
    const due = await pool.query(
      `SELECT mc_uuid FROM tracked_players WHERE next_check <= now()
       ORDER BY next_check LIMIT 100`);
    const batch: { mc_uuid: string }[] = due.rows;
    if (!batch.length) { await sleep(10_000); continue; }
    for (const row of batch) {
      if (signal.aborted) return;
      try {
        await refreshOne(row.mc_uuid);
      } catch (e: any) {
        if (e instanceof HypixelKeyError) {
          console.error(`Hypixel rejected the API key (${e.message}). Crawler paused for 5 minutes.`);
          await sleep(5 * 60_000);
          break;
        }
        if (e.message === "rate_limited") await sleep(60_000);
        else console.warn("refresh failed", row.mc_uuid, e.message);
      }
      await sleep(INTERVAL_MS);
    }
  }
}

/** Global rank for one UUID, among everyone tracked. */
export async function globalRank(uuid: string): Promise<{ rank: number | null; of: number }> {
  const [r, total] = await Promise.all([redis.zrevrank("lb:bb_score", uuid), redis.zcard("lb:bb_score")]);
  return { rank: r === null ? null : r + 1, of: total };
}

/** Keep only numeric Build Battle fields (drops cosmetics, settings, etc.). */
function numericOnly(bb: Record<string, unknown>) {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(bb)) if (typeof v === "number") out[k] = v;
  return out;
}

const dash = dashUuid;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
