// Weekly challenge progress, always worked out from the player's synced games (never claimed by the mod).
// A week starts Monday 00:00 UTC, the same week pickWeekly() picks challenges for.
import type { PoolClient } from "pg";

type Db = Pick<PoolClient, "query">;

/** The player's games this week. $1 = user id, $2 = the week's Monday. */
const inWeek = (alias = "") => {
  const a = alias ? `${alias}.` : "";
  return `${a}user_id = $1 AND ${a}played_at >= $2::date AND ${a}played_at < $2::date + 7`;
};
const IN_WEEK = inWeek();

/** How far along a player is on each challenge in the pool. Ids match weekly_pool in achievements.yaml. */
const PROGRESS: Record<string, { target: number; count: (db: Db, userId: number, week: string) => Promise<number> }> = {
  w_wood_win: { target: 1, count: tagged("all_wood") },
  w_palette: { target: 1, count: tagged("random_palette_win") },
  w_crowd_85: { target: 3, count: countWhere("crowd_score >= 0.85") },
  w_pro_win: { target: 1, count: countWhere("mode = 'pro' AND placement = 1") },
  w_teams_3: { target: 3, count: countWhere("mode = 'teams' AND placement = 1") },
  w_themes_10: {
    target: 10,
    count: (db, userId, week) => scalar(db,
      `SELECT count(DISTINCT lower(m.theme)) FROM matches m
       WHERE ${inWeek("m")}
         AND m.theme IS NOT NULL AND m.mode IN ('solo', 'teams', 'pro')
         AND NOT EXISTS (SELECT 1 FROM matches o WHERE o.user_id = $1 AND o.played_at < $2::date
                         AND lower(o.theme) = lower(m.theme))`, [userId, week]),
  },
  w_spb_precision: {
    target: 5,
    count: (db, userId, week) => scalar(db,
      `SELECT count(*) FROM matches m,
         jsonb_array_elements(CASE WHEN jsonb_typeof(m.spb_rounds) = 'array' THEN m.spb_rounds ELSE '[]'::jsonb END) e
       WHERE ${inWeek("m")}
         AND coalesce((e->>'perfect')::boolean, (e->>'accuracy')::float >= 1)
         AND coalesce((e->>'precision')::float, 0) >= 1`, [userId, week]),
  },
  w_gtb_15: {
    target: 15,
    count: (db, userId, week) => scalar(db,
      `SELECT coalesce(sum(gtb_correct), 0) FROM matches WHERE ${IN_WEEK} AND mode = 'gtb'`, [userId, week]),
  },
  w_marathon: {
    target: 5,
    count: (db, userId, week) => scalar(db, `SELECT count(DISTINCT mode) FROM matches WHERE ${IN_WEEK}`, [userId, week]),
  },
  w_streak_3: {
    target: 3,
    // Longest run of wins this week, counted the same way as the sync's win streak.
    count: async (db, userId, week) => {
      const r = await db.query(
        `SELECT placement FROM matches WHERE ${IN_WEEK} AND (mode <> 'gtb' OR placement IS NOT NULL)
         ORDER BY played_at`, [userId, week]);
      let run = 0, best = 0;
      for (const row of r.rows) {
        run = row.placement === 1 ? run + 1 : 0;
        best = Math.max(best, run);
      }
      return best;
    },
  },
};

function countWhere(condition: string) {
  return (db: Db, userId: number, week: string) =>
    scalar(db, `SELECT count(*) FROM matches WHERE ${IN_WEEK} AND ${condition}`, [userId, week]);
}

function tagged(tag: string) {
  return (db: Db, userId: number, week: string) =>
    scalar(db, `SELECT count(*) FROM matches WHERE ${IN_WEEK} AND $3 = ANY(tags)`, [userId, week, tag]);
}

async function scalar(db: Db, sql: string, params: unknown[]) {
  const r = await db.query(sql, params);
  return Number(Object.values(r.rows[0] ?? { n: 0 })[0] ?? 0);
}

export interface WeeklyProgress {
  id: string; text: string; xp: number; progress: number; target: number; done: boolean;
}

/** This week's Monday, as YYYY-MM-DD, and when the week ends (ISO, UTC). */
export async function currentWeek(db: Db) {
  const w = (await db.query("SELECT date_trunc('week', now())::date::text AS w")).rows[0].w as string;
  const end = new Date(`${w}T00:00:00Z`);
  end.setUTCDate(end.getUTCDate() + 7);
  return { week: w, endsAt: end.toISOString() };
}

/**
 * Progress on this week's challenges. With save, records it and returns the ones finished just now
 * (their XP is for the caller to add, inside the same transaction).
 */
export async function weeklyProgress(db: Db, userId: number, save = false) {
  const { week, endsAt } = await currentWeek(db);
  const picked = await db.query(
    "SELECT id, text, xp FROM weekly_challenges WHERE week_start = $1 ORDER BY id", [week]);
  const before = save
    ? await db.query(
        "SELECT id FROM user_weekly_challenges WHERE user_id = $1 AND week_start = $2 AND completed_at IS NOT NULL",
        [userId, week])
    : { rows: [] as any[] };
  const doneBefore = new Set(before.rows.map((r: any) => r.id as string));

  const weekly: WeeklyProgress[] = [];
  const completedNow: string[] = [];
  let xpGained = 0;
  for (const c of picked.rows) {
    const def = PROGRESS[c.id];
    const target = def?.target ?? 1;
    const progress = def ? Math.min(await def.count(db, userId, week), target) : 0;
    const done = progress >= target;
    weekly.push({ id: c.id, text: c.text, xp: c.xp, progress, target, done });
    if (!save) continue;
    await db.query(
      `INSERT INTO user_weekly_challenges (user_id, week_start, id, progress, completed_at)
       VALUES ($1, $2, $3, $4, CASE WHEN $5 THEN now() END)
       ON CONFLICT (user_id, week_start, id) DO UPDATE SET progress = EXCLUDED.progress,
         completed_at = coalesce(user_weekly_challenges.completed_at, EXCLUDED.completed_at)`,
      [userId, week, c.id, progress, done]);
    if (done && !doneBefore.has(c.id)) {
      completedNow.push(c.id);
      xpGained += c.xp;
    }
  }
  return { weekly, weekEndsAt: endsAt, completedNow, xpGained };
}
