// Speed Builders bests and Better Battle world records. Flagged accounts never hold a record.
import { pool } from "../db/pool";

export interface RecordRow { build: string; name: string; holder: string | null; timeMs: number; players: number }

/** The fastest perfect time on every build, with how many players have a time on it. */
export async function worldRecords(): Promise<RecordRow[]> {
  const r = await pool.query(
    `WITH ok AS (
       SELECT p.build_id, p.time_ms, p.set_at, u.mc_name
       FROM spb_personal_bests p JOIN users u ON u.id = p.user_id
       WHERE NOT u.flagged)
     SELECT b.id, b.name, w.mc_name, w.time_ms, c.n
     FROM spb_builds b
     JOIN LATERAL (SELECT * FROM ok WHERE ok.build_id = b.id ORDER BY time_ms, set_at LIMIT 1) w ON true
     JOIN LATERAL (SELECT count(*)::int AS n FROM ok WHERE ok.build_id = b.id) c ON true
     ORDER BY c.n DESC, b.name`);
  return r.rows.map((x: any) => ({ build: x.id, name: x.name, holder: x.mc_name, timeMs: x.time_ms, players: x.n }));
}

/** Top times on one build. */
export async function buildLeaderboard(buildId: string, limit = 50) {
  const b = await pool.query("SELECT id, name FROM spb_builds WHERE id = $1", [buildId]);
  if (!b.rowCount) return null;
  const r = await pool.query(
    `SELECT u.mc_name, p.time_ms, p.accuracy, p.set_at
     FROM spb_personal_bests p JOIN users u ON u.id = p.user_id
     WHERE p.build_id = $1 AND NOT u.flagged
     ORDER BY p.time_ms, p.set_at LIMIT $2`, [buildId, limit]);
  return {
    build: b.rows[0].id,
    name: b.rows[0].name,
    rows: r.rows.map((x: any, i: number) => ({
      rank: i + 1, name: x.mc_name, timeMs: x.time_ms, precision: x.accuracy, setAt: x.set_at,
    })),
  };
}

/** A player's best on every build they've finished, and where it ranks. */
export async function playerBests(userId: number) {
  const r = await pool.query(
    `SELECT b.id, b.name, p.time_ms, p.set_at,
            1 + (SELECT count(*) FROM spb_personal_bests o JOIN users ou ON ou.id = o.user_id
                 WHERE o.build_id = p.build_id AND NOT ou.flagged AND o.time_ms < p.time_ms)::int AS rank,
            (SELECT count(*) FROM spb_personal_bests o JOIN users ou ON ou.id = o.user_id
             WHERE o.build_id = p.build_id AND NOT ou.flagged)::int AS players
     FROM spb_personal_bests p JOIN spb_builds b ON b.id = p.build_id
     WHERE p.user_id = $1 ORDER BY rank, p.time_ms LIMIT 100`, [userId]);
  return r.rows.map((x: any) => ({
    build: x.id, name: x.name, timeMs: x.time_ms, setAt: x.set_at, rank: x.rank, players: x.players,
  }));
}
