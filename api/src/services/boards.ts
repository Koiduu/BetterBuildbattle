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
}

// NOTE: "tokens" is stored by Hypixel as "coins". Field names other than score and wins should be
// checked against a real /v2/player response (see README).
export const BOARDS: Board[] = [
  { id: "score", name: "Build Battle score", short: "Score", group: "Overall", fields: ["score"] },
  { id: "wins", name: "Total wins", short: "Wins", group: "Overall", fields: ["wins"] },
  { id: "games", name: "Games played", short: "Games", group: "Overall", fields: ["games_played"] },
  { id: "tokens", name: "Tokens", short: "Tokens", group: "Overall", fields: ["coins", "tokens"] },
  { id: "votes", name: "Votes cast", short: "Votes", group: "Overall", fields: ["total_votes"] },
  { id: "solo", name: "Solo wins", short: "Solo", group: "Modes", fields: ["wins_solo_normal"] },
  { id: "teams", name: "Teams wins", short: "Teams", group: "Modes", fields: ["wins_teams_normal"] },
  { id: "pro", name: "Pro wins", short: "Pro", group: "Modes", fields: ["wins_solo_pro"] },
  { id: "gtb", name: "Guess the Build wins", short: "GTB wins", group: "Modes", fields: ["wins_guess_the_build"] },
  { id: "guesses", name: "Correct guesses", short: "Guesses", group: "Modes", fields: ["correct_guesses"] },
  { id: "spb", name: "Speed Builders wins", short: "SPB wins", group: "Modes", fields: ["wins_speed_builders"] },
  { id: "level", name: "Aspiring Level (XP)", short: "Level XP", group: "Better Battle" },
  { id: "ap", name: "Aspiring Points", short: "AP", group: "Better Battle" },
  { id: "records", name: "Speed Builders records held", short: "Records", group: "Better Battle" },
];

export const boardById = (id: string) => BOARDS.find((b) => b.id === id);
/** The score board keeps its original key so the existing global rank code is untouched. */
export const boardKey = (id: string) => (id === "score" ? "lb:bb_score" : `lb:bb:${id}`);

const valueOf = (board: Board, bb: Record<string, unknown>) => {
  for (const f of board.fields ?? []) if (typeof bb[f] === "number") return bb[f] as number;
  return 0;
};

/** Called by the crawler with a player's fresh Hypixel Build Battle stats. */
export async function updateHypixelBoards(uuid: string, bb: Record<string, unknown>) {
  const multi = redis.multi();
  for (const board of BOARDS) {
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
  const [score, wins] = await Promise.all([redis.zcard(boardKey("score")), redis.zcard(boardKey("wins"))]);
  if (score > 0 && wins === 0) await rebuildBoards();
}

export interface Position { id: string; name: string; short: string; group: string; rank: number | null; of: number; value: number | null; top: number | null }

/** Where one player stands on every board. Ties share a rank. */
export async function positions(uuid: string): Promise<Position[]> {
  const scores = await redis.multi(BOARDS.map((b) => ["zscore", boardKey(b.id), uuid])).exec();
  const multi = redis.multi();
  BOARDS.forEach((b, i) => {
    const s = scores?.[i]?.[1] as string | null;
    multi.zcard(boardKey(b.id));
    multi.zcount(boardKey(b.id), s === null ? "+inf" : `(${s}`, "+inf");
  });
  const res = await multi.exec();
  return BOARDS.map((b, i) => {
    const s = scores?.[i]?.[1] as string | null;
    const of = Number(res?.[i * 2]?.[1] ?? 0);
    const above = Number(res?.[i * 2 + 1]?.[1] ?? 0);
    const rank = s === null ? null : above + 1;
    return {
      id: b.id, name: b.name, short: b.short, group: b.group, rank, of,
      value: s === null ? null : Number(s),
      top: rank === null || !of ? null : rank / of,
    };
  });
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
        `SELECT t.mc_uuid, t.mc_name, t.bb_score, (u.id IS NOT NULL) AS uses_mod, u.plan, u.plan_until, u.plan_comped
         FROM tracked_players t LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
         WHERE t.mc_uuid = ANY($1::uuid[])`, [uuids])
    : { rows: [] as any[] };
  const by = new Map(info.rows.map((r: any) => [r.mc_uuid, r]));
  return {
    total: await redis.zcard(key),
    rows: uuids.map((u, i) => ({ rank: (page - 1) * per + i + 1, uuid: u, value: values.get(u)!, info: by.get(u) as any })),
  };
}
