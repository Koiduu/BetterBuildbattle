// Player discovery + refresh. Builds the global Build Battle ranking out of everyone we can find,
// mod user or not. Global rank = position by bb_score among tracked_players.
//
// Discovery sources (cheapest first):
//   1. lobby   – every mod user's sync reports the UUIDs in their game lobbies (tab list).
//                Thousands of new players a day once a few hundred people run the mod.
//   2. guild   – each newly found player's guild; all members get queued.
//   3. leaderboard – Hypixel /v2/leaderboards (Build Battle boards), re-read daily.
//   4. search  – anyone searched on battle.aspir3.net is queued with high priority.
//   5. import  – one-off CSV of names (e.g. from your DiscordAspire guild data).
//
// Refresh cadence (keeps API usage inside the key's limit):
//   top 10k by score: every 6 h · score > 1,000: every 3 days · everyone else: every 14 days
//   players with no Build Battle stats: marked no_bb_stats, re-checked every 60 days
import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { titleFor } from "../lib/titles";
import { HYPIXEL_API, dashUuid } from "../lib/upstream";
import { updateHypixelBoards } from "../services/boards";

const KEY = process.env.HYPIXEL_API_KEY!;
const REQUESTS_PER_5_MIN = Number(process.env.HYPIXEL_RATE ?? 280); // stay under the key's limit
const INTERVAL_MS = (5 * 60_000) / REQUESTS_PER_5_MIN;

export async function queuePlayers(uuids: string[], source: string, priority = false) {
  if (!uuids.length) return;
  await pool.query(
    `INSERT INTO tracked_players (mc_uuid, source, next_check)
     SELECT u, $2, CASE WHEN $3 THEN now() - interval '1 day' ELSE now() END
     FROM unnest($1::uuid[]) AS u
     ON CONFLICT (mc_uuid) DO NOTHING`,
    [uuids, source, priority],
  );
}

export class HypixelKeyError extends Error {}

/**
 * Lookups that a visitor can cause (searching an unknown name, claiming a profile) share one small
 * budget, so no number of visitors or addresses can use up the API key the crawler depends on.
 */
const ON_DEMAND_PER_MINUTE = Number(process.env.HYPIXEL_ON_DEMAND_RATE ?? 30);
let onDemand = { n: 0, reset: 0 };
export function takeOnDemandLookup() {
  const now = Date.now();
  if (onDemand.reset < now) onDemand = { n: 0, reset: now + 60_000 };
  return ++onDemand.n <= ON_DEMAND_PER_MINUTE;
}

export async function refreshOne(uuid: string) {
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
         next_check = now() + interval '60 days' WHERE mc_uuid = $1`, [uuid, player?.displayname ?? null]);
    return;
  }
  const score: number = bb.score ?? 0;
  await pool.query(
    `UPDATE tracked_players SET mc_name = $2, bb_score = $3, bb_wins = $4, bb_games = $5, title = $6,
       hypixel_bb = $7, no_bb_stats = false, check_failures = 0, last_checked = now(),
       next_check = now() + CASE WHEN $3 > 1000 THEN interval '3 days' ELSE interval '14 days' END
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

  // Discovery: queue this player's guild once.
  if (Math.random() < 0.2) await queueGuildOf(uuid);
}

async function queueGuildOf(uuid: string) {
  const res = await fetch(`${HYPIXEL_API}/v2/guild?player=${uuid}`, { headers: { "API-Key": KEY } });
  if (!res.ok) return;
  const { guild } = await res.json();
  if (!guild) return;
  const seen = await pool.query(
    `INSERT INTO crawled_guilds (guild_id, name) VALUES ($1, $2)
     ON CONFLICT (guild_id) DO UPDATE SET last_crawled = now()
       WHERE crawled_guilds.last_crawled < now() - interval '30 days'
     RETURNING 1`, [guild._id, guild.name]);
  if (seen.rowCount) await queuePlayers(guild.members.map((m: any) => dash(m.uuid)), "guild");
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
  if (!res.ok) return;
  const { leaderboards } = await res.json();
  const boards = leaderboards?.BUILD_BATTLE ?? [];
  const uuids = new Set<string>();
  for (const b of boards) for (const u of b.leaders ?? []) uuids.add(dash(u));
  await queuePlayers([...uuids], "leaderboard", true);
}

/** Top-10k players refresh faster so the head of the ranking stays accurate. */
async function bumpTopPlayers() {
  const top = await redis.zrevrange("lb:bb_score", 0, 9999);
  await pool.query(
    `UPDATE tracked_players SET next_check = LEAST(next_check, last_checked + interval '6 hours')
     WHERE mc_uuid = ANY($1::uuid[])`, [top]);
}

export async function runCrawler(signal: AbortSignal) {
  let lastBump = 0;
  while (!signal.aborted) {
    if (Date.now() - lastBump > 3_600_000) { await bumpTopPlayers(); lastBump = Date.now(); }
    const due = await pool.query(
      `SELECT mc_uuid FROM tracked_players WHERE next_check <= now() AND NOT no_bb_stats
       ORDER BY next_check LIMIT 100`);
    const extra: { mc_uuid: string }[] = due.rowCount ? [] : (await pool.query(
      `SELECT mc_uuid FROM tracked_players WHERE no_bb_stats AND next_check <= now() LIMIT 20`)).rows;
    const batch = [...due.rows, ...extra];
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
