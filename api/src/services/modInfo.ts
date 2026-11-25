// What the mod's /bb rank and /bb compare show. Plain numbers only: nothing here can help anyone build.
import { pool } from "../db/pool";
import { globalRank } from "../jobs/crawler";
import { levelForXp } from "./level";
import { standings } from "./boards";

const PERIODS = ["day", "week", "month"] as const;
const STATS = ["score", "wins", "wr"] as const;   // game score, wins, win rate

/** A player's place on the all-time boards and on today / this week / this month. */
export async function rankSummary(uuid: string) {
  const ids = ["score", "level", "winrate", ...PERIODS.flatMap((p) => STATS.map((s) => `${s}${p}`))];
  const [rank, list] = await Promise.all([globalRank(uuid), standings(uuid, ids)]);
  const by = new Map(list.map((b) => [b.id, b]));
  const pick = (id: string) => {
    const b = by.get(id);
    return b ? { rank: b.rank, of: b.of, value: b.value } : { rank: null, of: 0, value: null };
  };
  return {
    globalRank: rank.rank, rankedPlayers: rank.of,
    allTime: { score: pick("score"), level: pick("level"), winRate: pick("winrate") },
    periods: Object.fromEntries(PERIODS.map((p) => [p, {
      score: pick(`score${p}`), wins: pick(`wins${p}`), winRate: pick(`wr${p}`),
    }])),
  };
}

/** One player's headline numbers, by Minecraft name or UUID. Null if we have never seen them play Build Battle. */
export async function playerSummary(who: { name?: string; uuid?: string }) {
  const p = await pool.query(
    `SELECT mc_uuid, mc_name, bb_score, bb_wins, bb_games, hypixel_bb FROM tracked_players
     WHERE ${who.uuid ? "mc_uuid = $1" : "mc_name = $1"} AND NOT no_bb_stats
     ORDER BY last_checked DESC NULLS LAST LIMIT 1`, [who.uuid ?? who.name]);
  if (!p.rowCount) return null;
  const row = p.rows[0];
  const hyp = row.hypixel_bb ?? {};
  const wins = Number(hyp.wins ?? row.bb_wins ?? 0), games = Number(hyp.games_played ?? row.bb_games ?? 0);
  const [rank, prog] = await Promise.all([
    globalRank(row.mc_uuid),
    pool.query(
      `SELECT p.xp, p.ap FROM users u JOIN user_progress p ON p.user_id = u.id WHERE u.mc_uuid = $1`, [row.mc_uuid]),
  ]);
  const xp = prog.rowCount ? Number(prog.rows[0].xp ?? 0) : null;
  return {
    name: row.mc_name as string,
    score: Number(row.bb_score ?? 0),
    globalRank: rank.rank, rankedPlayers: rank.of,
    wins, games,
    winRate: games > 0 ? Math.round((wins / games) * 1000) / 10 : null,
    level: xp === null ? null : levelForXp(xp),
    ap: prog.rowCount ? Number(prog.rows[0].ap ?? 0) : null,
    modWins: {
      solo: Number(hyp.wins_solo_normal ?? 0), teams: Number(hyp.wins_teams_normal ?? 0), pro: Number(hyp.wins_solo_pro ?? 0),
      gtb: Number(hyp.wins_guess_the_build ?? 0), speedBuilders: Number(hyp.wins_speed_builders ?? 0),
    },
  };
}
