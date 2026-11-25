// Every Build Battle leaderboard, ranked among all the players Better Battle tracks.
// One Redis sorted set per board, keyed by Minecraft UUID. The crawler keeps the Hypixel boards
// fresh as it refreshes players; a nightly rebuild from Postgres heals anything that drifts.
import { pool } from "../db/pool";
import { redis } from "../db/redis";

export interface Board {
  id: string;
  name: string;
  /** Short label for tables and Discord. */
  short: string;
  group: "Overall" | "Modes" | "Better Battle";
  /** Hypixel BuildBattle stat names, first one present wins. Absent for Better Battle's own boards. */
  fields?: string[];
  /** "percent": the stored value is 0-100 and shown with a % sign. */
  unit?: "percent";
  /** A Better Battle board: win rate in one mode, from games the mod synced (placement 1 = a win). */
  winMode?: "solo" | "teams" | "pro" | "gtb" | "speed_builders";
  /** Win rate over all modes for the current UTC day, week (from Monday) or month. Resets at each boundary. */
  period?: "day" | "week" | "month";
  /** What a period board counts. Default "winrate". */
  metric?: "winrate" | "wins" | "games" | "xp" | "score";
  /** For a period board: the all-time board it is the today/week/month version of. */
  base?: string;
}

/** Games needed in the period before a win rate shows. Short periods need fewer. */
const PERIOD_MIN_GAMES = { day: 3, week: 5, month: 10 } as const;
/** Start of the current UTC day / week / month. Weeks start Monday 00:00 UTC, like the weekly challenges. */
const PERIOD_START = (p: "day" | "week" | "month") => `date_trunc('${p}', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'`;

/** Per-mode win rate needs fewer games than the overall one: only synced games count, so samples are small. */
export const MODE_WINRATE_MIN_GAMES = 10;

/** Win rate only ranks players with at least this many games, so 1 win from 1 game isn't 100%. */
export const WINRATE_MIN_GAMES = 100;

// NOTE: "tokens" is stored by Hypixel as "coins". Field names other than score and wins should be
// checked against a real /v2/player response (see README).
export const BOARDS: Board[] = [
  { id: "score", name: "Build Battle score", short: "Score", group: "Overall", fields: ["score"] },
  { id: "wins", name: "Total wins", short: "Wins", group: "Overall", fields: ["wins"] },
  { id: "games", name: "Games played", short: "Games", group: "Overall", fields: ["games_played"] },
  { id: "winrate", name: `Win rate (${WINRATE_MIN_GAMES}+ games)`, short: "Win rate", group: "Overall", unit: "percent" },
  { id: "tokens", name: "Tokens", short: "Tokens", group: "Overall", fields: ["coins", "tokens"] },
  { id: "votes", name: "Votes cast", short: "Votes", group: "Overall", fields: ["total_votes"] },
  { id: "solo", name: "Solo wins", short: "Solo", group: "Modes", fields: ["wins_solo_normal"] },
  { id: "teams", name: "Teams wins", short: "Teams", group: "Modes", fields: ["wins_teams_normal"] },
  { id: "pro", name: "Pro wins", short: "Pro", group: "Modes", fields: ["wins_solo_pro"] },
  { id: "gtb", name: "Guess the Build wins", short: "GTB wins", group: "Modes", fields: ["wins_guess_the_build"] },
  { id: "guesses", name: "Correct guesses", short: "Guesses", group: "Modes", fields: ["correct_guesses"] },
  { id: "spb", name: "Speed Builders wins", short: "SPB wins", group: "Modes", fields: ["wins_speed_builders"] },
  { id: "wrsolo", name: `Solo win rate (${MODE_WINRATE_MIN_GAMES}+ games)`, short: "Solo win %", group: "Better Battle", unit: "percent", winMode: "solo" },
  { id: "wrteams", name: `Teams win rate (${MODE_WINRATE_MIN_GAMES}+ games)`, short: "Teams win %", group: "Better Battle", unit: "percent", winMode: "teams" },
  { id: "wrpro", name: `Pro win rate (${MODE_WINRATE_MIN_GAMES}+ games)`, short: "Pro win %", group: "Better Battle", unit: "percent", winMode: "pro" },
  { id: "wrgtb", name: `Guess the Build win rate (${MODE_WINRATE_MIN_GAMES}+ games)`, short: "GTB win %", group: "Better Battle", unit: "percent", winMode: "gtb" },
  { id: "wrspb", name: `Speed Builders win rate (${MODE_WINRATE_MIN_GAMES}+ games)`, short: "SPB win %", group: "Better Battle", unit: "percent", winMode: "speed_builders" },
  { id: "level", name: "Aspiring Level (XP)", short: "Level XP", group: "Better Battle" },
  { id: "ap", name: "Aspiring Points", short: "AP", group: "Better Battle" },
  { id: "records", name: "Speed Builders records held", short: "Records", group: "Better Battle" },
];

/** Today / this week / this month versions of win rate, wins, games played and XP earned, from games the mod synced. */
const PERIODS = [
  { p: "day", label: "today", reset: "resets 00:00 UTC" },
  { p: "week", label: "this week", reset: "resets Monday" },
  { p: "month", label: "this month", reset: "resets on the 1st" },
] as const;
const METRICS = [
  { m: "winrate", id: "wr", base: "winrate", name: "Win rate", short: "win %", unit: "percent" as const },
  { m: "wins", id: "wins", base: "wins", name: "Wins", short: "wins", unit: undefined },
  { m: "games", id: "games", base: "games", name: "Games played", short: "games", unit: undefined },
  { m: "xp", id: "xp", base: "level", name: "XP earned", short: "XP", unit: undefined },
  // points from votes in games the mod synced; Hypixel's own score only refreshes when a player is looked up
  { m: "score", id: "score", base: "score", name: "Game score", short: "score", unit: undefined },
] as const;
for (const { m, id, base, name, short, unit } of METRICS) {
  for (const { p, label, reset } of PERIODS) {
    const min = m === "winrate" ? ` ${PERIOD_MIN_GAMES[p]}+ games,` : "";
    BOARDS.push({
      id: `${id}${p}`, name: `${name} ${label} (${min} ${reset})`.replace("( ", "("), short: `${p === "day" ? "Today" : p === "week" ? "Week" : "Month"} ${short}`,
      group: "Better Battle", unit, period: p, metric: m, base,
    });
  }
}

export const boardById = (id: string) => BOARDS.find((b) => b.id === id);
/** The score board keeps its original key so the existing global rank code is untouched. */
export const boardKey = (id: string) => (id === "score" ? "lb:bb_score" : `lb:bb:${id}`);

/** Wins as a percentage of games played, to 2 decimals; null under the minimum number of games. */
const winRate = (bb: Record<string, unknown>) => {
  const wins = typeof bb.wins === "number" ? bb.wins : 0;
  const games = typeof bb.games_played === "number" ? bb.games_played : 0;
  return games >= WINRATE_MIN_GAMES && wins > 0 ? Math.round(Math.min(wins / games, 1) * 10000) / 100 : null;
};

/** Total wins, games and wins in each mode from a player's Hypixel Build Battle stats. */
export function winsBreakdown(bb: Record<string, unknown> | null | undefined) {
  const n = (v: unknown) => (typeof v === "number" ? v : 0);
  const stats = bb ?? {};
  return {
    wins: n(stats.wins), games: n(stats.games_played),
    solo: n(stats.wins_solo_normal), teams: n(stats.wins_teams_normal), pro: n(stats.wins_solo_pro),
    gtb: n(stats.wins_guess_the_build), spb: n(stats.wins_speed_builders),
  };
}

const valueOf = (board: Board, bb: Record<string, unknown>) => {
  for (const f of board.fields ?? []) if (typeof bb[f] === "number") return bb[f] as number;
  return 0;
};

/** Called by the crawler with a player's fresh Hypixel Build Battle stats. */
export async function updateHypixelBoards(uuid: string, bb: Record<string, unknown>) {
  const multi = redis.multi();
  for (const board of BOARDS) {
    if (board.id === "winrate") {
      const w = winRate(bb);
      if (w !== null) multi.zadd(boardKey(board.id), w, uuid);
      else multi.zrem(boardKey(board.id), uuid);
      continue;
    }
    if (!board.fields || board.id === "score") continue;   // score is written by the crawler itself
    const v = valueOf(board, bb);
    if (v > 0) multi.zadd(boardKey(board.id), v, uuid);
    else multi.zrem(boardKey(board.id), uuid);
  }
  await multi.exec();
}

/** Called after a sync with the player's new XP and AP. */
export async function updateProgressBoards(uuid: string | null, xp: number, ap: number) {
  if (!uuid) return;
  const multi = redis.multi();
  if (xp > 0) multi.zadd(boardKey("level"), xp, uuid);
  if (ap > 0) multi.zadd(boardKey("ap"), ap, uuid);
  await multi.exec();
}

/** Win rate per mode for one player, from the games they've synced. Called after each sync. */
export async function updateModeWinRates(uuid: string | null, userId: number) {
  if (!uuid) return;
  const r = await pool.query(
    `SELECT mode::text AS mode, count(*)::int AS games, count(*) FILTER (WHERE placement = 1)::int AS wins
     FROM matches WHERE user_id = $1 AND NOT flagged AND (mode <> 'gtb' OR placement IS NOT NULL)
     GROUP BY mode`, [userId]);
  const byMode = new Map<string, { games: number; wins: number }>(r.rows.map((x: any) => [x.mode, x]));
  const multi = redis.multi();
  // today / this week / this month, all modes together
  const periods = await pool.query(
    `SELECT count(*) FILTER (WHERE played_at >= ${PERIOD_START("day")})::int AS day_games,
            count(*) FILTER (WHERE played_at >= ${PERIOD_START("day")} AND placement = 1)::int AS day_wins,
            count(*) FILTER (WHERE played_at >= ${PERIOD_START("week")})::int AS week_games,
            count(*) FILTER (WHERE played_at >= ${PERIOD_START("week")} AND placement = 1)::int AS week_wins,
            count(*)::int AS month_games, count(*) FILTER (WHERE placement = 1)::int AS month_wins,
            coalesce(round(sum(score) FILTER (WHERE played_at >= ${PERIOD_START("day")})), 0)::int AS day_score,
            coalesce(round(sum(score) FILTER (WHERE played_at >= ${PERIOD_START("week")})), 0)::int AS week_score,
            coalesce(round(sum(score)), 0)::int AS month_score,
            coalesce(sum(xp_earned) FILTER (WHERE played_at >= ${PERIOD_START("day")}), 0)::int AS day_xp,
            coalesce(sum(xp_earned) FILTER (WHERE played_at >= ${PERIOD_START("week")}), 0)::int AS week_xp,
            coalesce(sum(xp_earned), 0)::int AS month_xp
     FROM matches WHERE user_id = $1 AND NOT flagged AND (mode <> 'gtb' OR placement IS NOT NULL)
       AND played_at >= ${PERIOD_START("month")}`, [userId]);
  const pr = periods.rows[0] ?? {};
  for (const board of BOARDS) {
    if (!board.period) continue;
    const games = Number(pr[`${board.period}_games`] ?? 0), wins = Number(pr[`${board.period}_wins`] ?? 0);
    const xp = Number(pr[`${board.period}_xp`] ?? 0), score = Number(pr[`${board.period}_score`] ?? 0);
    const metric = board.metric ?? "winrate";
    const v = metric === "winrate" ? (games >= PERIOD_MIN_GAMES[board.period] && wins > 0 ? Math.round((wins / games) * 10000) / 100 : 0)
      : metric === "wins" ? wins : metric === "games" ? games : metric === "score" ? score : xp;
    if (v > 0) multi.zadd(boardKey(board.id), v, uuid);
    else multi.zrem(boardKey(board.id), uuid);
  }
  for (const board of BOARDS) {
    if (!board.winMode) continue;
    const s = byMode.get(board.winMode);
    if (s && s.games >= MODE_WINRATE_MIN_GAMES && s.wins > 0) multi.zadd(boardKey(board.id), Math.round((s.wins / s.games) * 10000) / 100, uuid);
    else multi.zrem(boardKey(board.id), uuid);
  }
  await multi.exec();
}

async function fill(key: string, rows: { mc_uuid: string; v: number | string }[]) {
  const tmp = `${key}:rebuild`;
  await redis.del(tmp);
  for (let i = 0; i < rows.length; i += 5000) {
    const args: (string | number)[] = [];
    for (const row of rows.slice(i, i + 5000)) args.push(Number(row.v), row.mc_uuid);
    if (args.length) await redis.zadd(tmp, ...args);
  }
  if (rows.length) await redis.rename(tmp, key);
  else await redis.del(key);
}

/** Rebuilds every board except score from Postgres. Nightly, and at boot if a board is missing. */
export async function rebuildBoards() {
  for (const board of BOARDS) {
    if (board.id === "score") continue;
    let rows: { mc_uuid: string; v: number }[];
    if (board.fields) {
      const expr = `coalesce(${board.fields.map((_, i) => `(hypixel_bb->>$${i + 1})::numeric`).join(", ")}, 0)`;
      rows = (await pool.query(
        `SELECT mc_uuid, ${expr} AS v FROM tracked_players
         WHERE hypixel_bb IS NOT NULL AND NOT no_bb_stats AND ${expr} > 0`, board.fields)).rows;
    } else if (board.period) {
      const metric = board.metric ?? "winrate";
      const value = metric === "winrate" ? "round(100.0 * count(*) FILTER (WHERE m.placement = 1) / count(*), 2)"
        : metric === "wins" ? "count(*) FILTER (WHERE m.placement = 1)"
        : metric === "games" ? "count(*)" : metric === "score" ? "coalesce(round(sum(m.score)::numeric), 0)" : "coalesce(sum(m.xp_earned), 0)";
      const having = metric === "winrate"
        ? "count(*) >= $1 AND count(*) FILTER (WHERE m.placement = 1) > 0"
        : metric === "wins" ? "count(*) FILTER (WHERE m.placement = 1) > 0 AND $1 >= 0"
        : metric === "games" ? "count(*) > 0 AND $1 >= 0"
        : metric === "score" ? "coalesce(sum(m.score), 0) > 0 AND $1 >= 0" : "coalesce(sum(m.xp_earned), 0) > 0 AND $1 >= 0";
      rows = (await pool.query(
        `SELECT u.mc_uuid, ${value} AS v
         FROM matches m JOIN users u ON u.id = m.user_id
         WHERE m.played_at >= ${PERIOD_START(board.period)} AND NOT m.flagged AND NOT u.flagged AND u.mc_uuid IS NOT NULL
           AND (m.mode <> 'gtb' OR m.placement IS NOT NULL)
         GROUP BY u.mc_uuid HAVING ${having}`,
        [PERIOD_MIN_GAMES[board.period]])).rows;
    } else if (board.winMode) {
      rows = (await pool.query(
        `SELECT u.mc_uuid, round(100.0 * count(*) FILTER (WHERE m.placement = 1) / count(*), 2) AS v
         FROM matches m JOIN users u ON u.id = m.user_id
         WHERE m.mode = $1::bb_mode AND NOT m.flagged AND NOT u.flagged AND u.mc_uuid IS NOT NULL
           AND (m.mode <> 'gtb' OR m.placement IS NOT NULL)
         GROUP BY u.mc_uuid
         HAVING count(*) >= $2 AND count(*) FILTER (WHERE m.placement = 1) > 0`,
        [board.winMode, MODE_WINRATE_MIN_GAMES])).rows;
    } else if (board.id === "winrate") {
      rows = (await pool.query(
        `SELECT mc_uuid,
                round(least(100.0 * (hypixel_bb->>'wins')::numeric / (hypixel_bb->>'games_played')::numeric, 100), 2) AS v
         FROM tracked_players
         WHERE hypixel_bb IS NOT NULL AND NOT no_bb_stats
           AND coalesce((hypixel_bb->>'games_played')::numeric, 0) >= $1
           AND coalesce((hypixel_bb->>'wins')::numeric, 0) > 0`, [WINRATE_MIN_GAMES])).rows;
    } else if (board.id === "records") {
      rows = (await pool.query(
        `SELECT u.mc_uuid, count(*)::int AS v FROM spb_builds b
         JOIN LATERAL (SELECT p.user_id FROM spb_personal_bests p JOIN users x ON x.id = p.user_id
                       WHERE p.build_id = b.id AND NOT x.flagged ORDER BY p.time_ms, p.set_at LIMIT 1) w ON true
         JOIN users u ON u.id = w.user_id WHERE u.mc_uuid IS NOT NULL GROUP BY u.mc_uuid`)).rows;
    } else {
      const col = board.id === "level" ? "xp" : "ap";
      rows = (await pool.query(
        `SELECT u.mc_uuid, p.${col} AS v FROM user_progress p JOIN users u ON u.id = p.user_id
         WHERE u.mc_uuid IS NOT NULL AND NOT u.flagged AND p.${col} > 0`)).rows;
    }
    await fill(boardKey(board.id), rows);
  }
}

/** First boot after this feature ships: the score board exists but the others don't yet. */
export async function ensureBoards() {
  const [score, wins, rate] = await Promise.all([
    redis.zcard(boardKey("score")), redis.zcard(boardKey("wins")), redis.zcard(boardKey("winrate"))]);
  // also when only the newest board (win rate) is missing, so it fills in on the first boot after an update
  const modeRatesBuilt = await redis.exists("lb:meta:winrates-v4");
  if (score > 0 && (wins === 0 || rate === 0 || !modeRatesBuilt)) {
    await rebuildBoards();
    await redis.set("lb:meta:winrates-v4", "1");
  }
}

export interface Position { id: string; name: string; short: string; group: string; unit?: "percent"; rank: number | null; of: number; value: number | null; top: number | null }

/** Where one player stands on every board. Ties share a rank. */
/** Boards that make sense as a standing; the resetting today/week/month boards would just clutter a profile. */
const standingBoards = () => BOARDS.filter((b) => !b.period);

export async function positions(uuid: string): Promise<Position[]> {
  const scores = await redis.multi(standingBoards().map((b) => ["zscore", boardKey(b.id), uuid])).exec();
  const multi = redis.multi();
  standingBoards().forEach((b, i) => {
    const s = scores?.[i]?.[1] as string | null;
    multi.zcard(boardKey(b.id));
    multi.zcount(boardKey(b.id), s === null ? "+inf" : `(${s}`, "+inf");
  });
  const res = await multi.exec();
  return standingBoards().map((b, i) => {
    const s = scores?.[i]?.[1] as string | null;
    const of = Number(res?.[i * 2]?.[1] ?? 0);
    const above = Number(res?.[i * 2 + 1]?.[1] ?? 0);
    const rank = s === null ? null : above + 1;
    return {
      id: b.id, name: b.name, short: b.short, group: b.group, unit: b.unit, rank, of,
      value: s === null ? null : Number(s),
      top: rank === null || !of ? null : rank / of,
    };
  });
}

/** Where a player sits on one board, counting from 1 in list order (the same numbers the table shows). */
export async function listPosition(id: string, uuid: string): Promise<{ rank: number; total: number } | null> {
  const [idx, total] = await Promise.all([redis.zrevrank(boardKey(id), uuid), redis.zcard(boardKey(id))]);
  return idx === null ? null : { rank: idx + 1, total };
}

/** A page of one board, best first. */
export async function boardPage(id: string, page: number, per = 50) {
  const key = boardKey(id);
  const raw = await redis.zrevrange(key, (page - 1) * per, page * per - 1, "WITHSCORES");
  const uuids: string[] = [];
  const values = new Map<string, number>();
  for (let i = 0; i < raw.length; i += 2) { uuids.push(raw[i]); values.set(raw[i], Number(raw[i + 1])); }
  const info = uuids.length
    ? await pool.query(
        `SELECT t.mc_uuid, t.mc_name, t.bb_score, t.hypixel_bb, (u.id IS NOT NULL) AS uses_mod, u.plan, u.plan_until, u.plan_comped
         FROM tracked_players t LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
         WHERE t.mc_uuid = ANY($1::uuid[])`, [uuids])
    : { rows: [] as any[] };
  const by = new Map(info.rows.map((r: any) => [r.mc_uuid, r]));
  return {
    total: await redis.zcard(key),
    rows: uuids.map((u, i) => ({ rank: (page - 1) * per + i + 1, uuid: u, value: values.get(u)!, info: by.get(u) as any })),
  };
}
