// What the paid plans unlock: profile style and vanity links (Architect); insights, Wrapped, CSV
// export and private leaderboards (Master Builder). Every gate goes through lib/plans.ts.
import crypto from "node:crypto";
import { z } from "zod";
import { Raw, Router, fail, type Req } from "../http";
import { pool } from "../db/pool";
import { rateLimit, requireWebSession } from "../plugins/auth";
import { FEATURES, PLANS, can, limit, planOf, type Feature, type Plan } from "../lib/plans";
import { ACCENTS, BANNERS, CARD_THEMES, RESERVED, VANITY } from "../lib/style";
import { worldRecords } from "../services/speedBuilders";
import { currentWeek } from "../services/weekly";

async function myPlan(req: Req): Promise<Plan> {
  const r = await pool.query("SELECT plan, plan_until, plan_comped FROM users WHERE id = $1", [req.user.id]);
  return planOf(r.rows[0]);
}

/** Stops with a 402 naming the plan that's needed, which the site turns into an upgrade prompt. */
async function need(req: Req, feature: Feature) {
  const plan = await myPlan(req);
  if (!can(plan, feature)) fail(402, "plan_required", { feature, requires: FEATURES[feature], requiresName: PLANS[FEATURES[feature]].name });
  return plan;
}

const VOTED = "mode IN ('solo', 'teams', 'pro')";
const ROUNDS = "jsonb_array_elements(CASE WHEN jsonb_typeof(m.spb_rounds) = 'array' THEN m.spb_rounds ELSE '[]'::jsonb END)";

export function perkRoutes(app: Router) {
  // ---------------------------------------------------------------- Architect: profile style

  app.get("/style/options", async () => ({
    accents: ACCENTS, banners: BANNERS, cardThemes: Object.keys(CARD_THEMES),
  }));

  app.put("/me/style", [rateLimit(20, 60_000), requireWebSession], async (req) => {
    const body = z.object({
      accent: z.string().nullable().optional(),
      banner: z.string().nullable().optional(),
      cardTheme: z.string().nullable().optional(),
      featuredAchievement: z.string().max(64).nullable().optional(),
      vanity: z.string().trim().toLowerCase().nullable().optional(),
    }).parse(req.body);
    const plan = await myPlan(req);
    const sets: string[] = [];
    const args: unknown[] = [req.user.id];
    const set = (col: string, v: unknown) => { args.push(v); sets.push(`${col} = $${args.length}`); };

    if (body.accent !== undefined || body.banner !== undefined || body.cardTheme !== undefined) {
      if (!can(plan, "profileStyle")) await need(req, "profileStyle");
      if (body.accent !== undefined) {
        if (body.accent !== null && !ACCENTS[body.accent]) fail(400, "unknown_accent");
        set("accent", body.accent);
      }
      if (body.banner !== undefined) {
        if (body.banner !== null && !BANNERS[body.banner]) fail(400, "unknown_banner");
        set("banner", body.banner);
      }
      if (body.cardTheme !== undefined) {
        if (body.cardTheme !== null && !CARD_THEMES[body.cardTheme]) fail(400, "unknown_card_theme");
        set("card_theme", body.cardTheme);
      }
    }
    if (body.featuredAchievement !== undefined) {
      if (!can(plan, "featuredAchievement")) await need(req, "featuredAchievement");
      if (body.featuredAchievement !== null) {
        const has = await pool.query(
          "SELECT 1 FROM user_achievements WHERE user_id = $1 AND achievement_id = $2", [req.user.id, body.featuredAchievement]);
        if (!has.rowCount) fail(400, "achievement_not_unlocked");
      }
      set("featured_achievement", body.featuredAchievement);
    }
    if (body.vanity !== undefined) {
      if (!can(plan, "vanity")) await need(req, "vanity");
      if (body.vanity !== null) {
        if (!VANITY.test(body.vanity) || RESERVED.has(body.vanity)) fail(400, "vanity_not_allowed");
        // Nobody gets a link that reads as another player's name.
        const other = await pool.query(
          `SELECT 1 FROM tracked_players t WHERE t.mc_name = $1
             AND t.mc_uuid IS DISTINCT FROM (SELECT mc_uuid FROM users WHERE id = $2) LIMIT 1`, [body.vanity, req.user.id]);
        if (other.rowCount) fail(400, "vanity_not_allowed");
      }
      set("vanity", body.vanity);
    }
    if (!sets.length) return { ok: true };
    try {
      await pool.query(`UPDATE users SET ${sets.join(", ")} WHERE id = $1`, args);
    } catch (e: any) {
      if (e?.code === "23505") fail(409, "vanity_taken");
      throw e;
    }
    return { ok: true };
  });

  /** battle.aspir3.net/<vanity> → the player it belongs to (only while their plan includes it). */
  app.get("/vanity/:slug", [rateLimit(120, 60_000)], async (req) => {
    const slug = req.params.slug.toLowerCase();
    if (!VANITY.test(slug)) return fail(404, "not_found");
    const r = await pool.query(
      "SELECT mc_name, plan, plan_until, plan_comped FROM users WHERE vanity = $1 AND mc_name IS NOT NULL", [slug]);
    if (!r.rowCount || !can(planOf(r.rows[0]), "vanity")) return fail(404, "not_found");
    return { name: r.rows[0].mc_name };
  });

  // ---------------------------------------------------------------- Master Builder: insights

  /** Which themes you do best and worst on. Crowd score only exists for voted modes. */
  app.get("/me/insights/themes", [requireWebSession], async (req) => {
    await need(req, "themeAnalytics");
    const [themes, modes, months] = await Promise.all([
      pool.query(
        `SELECT min(theme) AS theme, count(*)::int AS games, count(*) FILTER (WHERE placement = 1)::int AS wins,
                avg(crowd_score) AS crowd, min(placement) AS best, max(played_at) AS last
         FROM matches WHERE user_id = $1 AND theme IS NOT NULL AND ${VOTED}
         GROUP BY lower(theme)`, [req.user.id]),
      pool.query(
        `SELECT mode, count(*)::int AS games, count(*) FILTER (WHERE placement = 1)::int AS wins, avg(crowd_score) AS crowd
         FROM matches WHERE user_id = $1 GROUP BY mode ORDER BY games DESC`, [req.user.id]),
      pool.query(
        `SELECT to_char(date_trunc('month', played_at), 'YYYY-MM') AS month, count(*)::int AS games,
                count(*) FILTER (WHERE placement = 1)::int AS wins, avg(crowd_score) AS crowd
         FROM matches WHERE user_id = $1 AND played_at > now() - interval '12 months'
         GROUP BY 1 ORDER BY 1`, [req.user.id]),
    ]);
    const rows = themes.rows.map((t: any) => ({ ...t, crowd: t.crowd === null ? null : Number(t.crowd) }));
    const scored = rows.filter((t: any) => t.crowd !== null);
    const by = (f: (t: any) => number) => [...scored].sort((a, b) => f(b) - f(a));
    const fix = (m: any) => ({ ...m, crowd: m.crowd === null ? null : Number(m.crowd) });
    return {
      themes: rows.length,
      games: rows.reduce((s: number, t: any) => s + t.games, 0),
      averageCrowd: scored.length ? scored.reduce((s: number, t: any) => s + t.crowd * t.games, 0) / scored.reduce((s: number, t: any) => s + t.games, 0) : null,
      best: by((t) => t.crowd).slice(0, 8),
      worst: by((t) => -t.crowd).slice(0, 8),
      mostPlayed: [...rows].sort((a: any, b: any) => b.games - a.games).slice(0, 8),
      byMode: modes.rows.map(fix),
      byMonth: months.rows.map(fix),
    };
  });

  /** Your Perfect Build times on every Speed Builders build, over time. */
  app.get("/me/insights/speed-builders", [requireWebSession], async (req) => {
    await need(req, "spbAnalysis");
    const [rounds, totals, records] = await Promise.all([
      pool.query(
        `SELECT m.played_at, e->>'build' AS build, e->>'name' AS name, (e->>'timeMs')::int AS time_ms
         FROM matches m, ${ROUNDS} e
         WHERE m.user_id = $1 AND m.mode = 'speed_builders'
           AND coalesce((e->>'perfect')::boolean, false) AND e->>'timeMs' IS NOT NULL
         ORDER BY m.played_at`, [req.user.id]),
      pool.query(
        `SELECT count(*)::int AS rounds, count(*) FILTER (WHERE coalesce((e->>'perfect')::boolean, false))::int AS perfect,
                avg((e->>'precision')::float) AS precision
         FROM matches m, ${ROUNDS} e WHERE m.user_id = $1 AND m.mode = 'speed_builders'`, [req.user.id]),
      worldRecords(),
    ]);
    const record = new Map(records.map((r) => [r.build, r]));
    const builds = new Map<string, { build: string; name: string; history: { at: string; timeMs: number }[] }>();
    for (const r of rounds.rows) {
      if (!builds.has(r.build)) builds.set(r.build, { build: r.build, name: r.name ?? r.build, history: [] });
      builds.get(r.build)!.history.push({ at: r.played_at, timeMs: r.time_ms });
    }
    const avg = (xs: number[]) => xs.reduce((s, x) => s + x, 0) / xs.length;
    return {
      rounds: totals.rows[0].rounds,
      perfect: totals.rows[0].perfect,
      precision: totals.rows[0].precision === null ? null : Number(totals.rows[0].precision),
      builds: [...builds.values()].map((b) => {
        const times = b.history.map((h) => h.timeMs);
        const best = Math.min(...times);
        const wr = record.get(b.build);
        const recent = times.slice(-3), before = times.slice(-6, -3);
        return {
          build: b.build, name: b.name, attempts: times.length, bestMs: best, lastMs: times[times.length - 1],
          recordMs: wr?.timeMs ?? null, recordHolder: wr?.holder ?? null, gapMs: wr ? best - wr.timeMs : null,
          // negative = getting faster: your last 3 perfect builds against the 3 before them
          trendMs: before.length ? Math.round(avg(recent) - avg(before)) : null,
          history: b.history.slice(-60),
        };
      }).sort((a, b) => b.attempts - a.attempts),
    };
  });

  /** Build Battle Wrapped for a period: this year, the last 30 days, or everything. */
  app.get("/me/wrapped", [requireWebSession], async (req) => {
    await need(req, "wrapped");
    const { period } = z.object({ period: z.enum(["year", "30d", "all"]).default("year") }).parse(req.query);
    const since = period === "year" ? "date_trunc('year', now())" : period === "30d" ? "now() - interval '30 days'" : "'-infinity'::timestamptz";
    const W = `user_id = $1 AND played_at >= ${since}`;
    const id = [req.user.id];
    const [totals, modes, themes, days, months, order, votes, voted, blocks, spb, ach] = await Promise.all([
      pool.query(
        `SELECT count(*)::int AS games, count(*) FILTER (WHERE placement = 1)::int AS wins,
                coalesce(sum(duration_sec), 0)::int AS seconds, coalesce(sum(xp_earned), 0)::int AS xp,
                coalesce(sum(blocks_placed), 0)::int AS blocks, count(DISTINCT lower(theme))::int AS themes,
                count(DISTINCT date_trunc('day', played_at))::int AS days, avg(crowd_score) AS crowd,
                count(*) FILTER (WHERE grade = 'SS')::int AS ss, count(*) FILTER (WHERE grade = 'S')::int AS s
         FROM matches WHERE ${W}`, id),
      pool.query(`SELECT mode, count(*)::int AS games, count(*) FILTER (WHERE placement = 1)::int AS wins FROM matches WHERE ${W} GROUP BY mode ORDER BY games DESC`, id),
      pool.query(
        `SELECT theme, mode, placement, players, crowd_score, grade, played_at FROM matches
         WHERE ${W} AND crowd_score IS NOT NULL ORDER BY crowd_score DESC, players DESC NULLS LAST LIMIT 3`, id),
      pool.query(`SELECT date_trunc('day', played_at)::date::text AS day, count(*)::int AS games FROM matches WHERE ${W} GROUP BY 1 ORDER BY games DESC LIMIT 1`, id),
      pool.query(
        `SELECT to_char(date_trunc('month', played_at), 'YYYY-MM') AS month, count(*)::int AS games,
                count(*) FILTER (WHERE placement = 1)::int AS wins FROM matches WHERE ${W} GROUP BY 1 ORDER BY wins DESC, games DESC LIMIT 1`, id),
      pool.query(`SELECT placement FROM matches WHERE ${W} AND (mode <> 'gtb' OR placement IS NOT NULL) ORDER BY played_at`, id),
      pool.query(`SELECT vote, count(*)::int AS n FROM match_votes WHERE user_id = $1 AND voted_at >= ${since} GROUP BY vote`, id),
      pool.query(
        `SELECT builder, count(*)::int AS n FROM match_votes WHERE user_id = $1 AND voted_at >= ${since} AND builder IS NOT NULL
         GROUP BY builder ORDER BY n DESC LIMIT 1`, id),
      pool.query(
        `SELECT k AS block, sum(v::numeric)::int AS n
         FROM matches m, jsonb_each_text(CASE WHEN jsonb_typeof(m.palette) = 'object' THEN m.palette ELSE '{}'::jsonb END) AS p(k, v)
         WHERE m.${W.replace("played_at", "m.played_at")} GROUP BY k ORDER BY n DESC LIMIT 3`, id),
      pool.query(
        `SELECT count(*) FILTER (WHERE coalesce((e->>'perfect')::boolean, false))::int AS perfect, min((e->>'timeMs')::int) AS fastest
         FROM matches m, ${ROUNDS} e WHERE m.${W.replace("played_at", "m.played_at")} AND m.mode = 'speed_builders'`, id),
      pool.query(`SELECT count(*)::int AS n FROM user_achievements WHERE user_id = $1 AND unlocked_at >= ${since}`, id),
    ]);
    let run = 0, streak = 0;
    for (const m of order.rows) { run = m.placement === 1 ? run + 1 : 0; streak = Math.max(streak, run); }
    const SCALE = ["super_poop", "poop", "meh", "ok", "good", "epic", "legendary"];
    const voteCount = votes.rows.reduce((s: number, v: any) => s + v.n, 0);
    const t = totals.rows[0];
    return {
      period,
      games: t.games, wins: t.wins, hours: Math.round(t.seconds / 360) / 10, xp: t.xp, blocks: t.blocks,
      themes: t.themes, daysPlayed: t.days, crowd: t.crowd === null ? null : Number(t.crowd), grades: { SS: t.ss, S: t.s },
      modes: modes.rows,
      bestBuilds: themes.rows,
      busiestDay: days.rows[0] ?? null,
      bestMonth: months.rows[0] ?? null,
      longestWinStreak: streak,
      votes: voteCount ? {
        total: voteCount,
        counts: Object.fromEntries(SCALE.map((v) => [v, votes.rows.find((r: any) => r.vote === v)?.n ?? 0])),
        // 1 (Super Poop) to 7 (Legendary)
        average: votes.rows.reduce((s: number, v: any) => s + (SCALE.indexOf(v.vote) + 1) * v.n, 0) / voteCount,
        mostVotedOn: voted.rows[0] ?? null,
      } : null,
      favouriteBlocks: blocks.rows.map((b: any) => ({ block: String(b.block).replace(/^minecraft:/, ""), placed: b.n })),
      speedBuilders: spb.rows[0].perfect ? { perfect: spb.rows[0].perfect, fastestMs: spb.rows[0].fastest } : null,
      achievements: ach.rows[0].n,
    };
  });

  /** Every game as a spreadsheet. (The full copy of your data on the Account page stays free for everyone.) */
  app.get("/me/export/matches.csv", [rateLimit(6, 60_000), requireWebSession], async (req) => {
    await need(req, "fullHistory");
    const r = await pool.query(
      `SELECT played_at, mode, theme, placement, players, score, max_score, crowd_score, grade, duration_sec,
              blocks_placed, blocks_broken, xp_earned, mc_version
       FROM matches WHERE user_id = $1 ORDER BY played_at`, [req.user.id]);
    const cols = ["played_at", "mode", "theme", "placement", "players", "score", "max_score", "crowd_score", "grade",
      "duration_sec", "blocks_placed", "blocks_broken", "xp_earned", "mc_version"];
    const cell = (v: unknown) => {
      if (v === null || v === undefined) return "";
      let s = v instanceof Date ? v.toISOString() : String(v);
      if (/^[=+\-@\t\r]/.test(s) && typeof v === "string") s = "'" + s;   // never let a spreadsheet run a cell as a formula
      return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const csv = [cols.join(","), ...r.rows.map((row: any) => cols.map((c) => cell(row[c])).join(","))].join("\r\n") + "\r\n";
    return new Raw(Buffer.from("﻿" + csv, "utf8"), "text/csv; charset=utf-8",
      { "content-disposition": 'attachment; filename="better-battle-games.csv"', "cache-control": "no-store" });
  });

  // ---------------------------------------------------------------- Master Builder: private leaderboards

  const MAX_MEMBERS = 100;
  const code = () => crypto.randomBytes(6).toString("base64url");

  const myGroups = (userId: number) => pool.query(
    `SELECT g.slug, g.name, g.owner_id = $1 AS owner,
            (SELECT count(*)::int FROM group_members m WHERE m.group_id = g.id) AS members
     FROM groups g JOIN group_members me ON me.group_id = g.id AND me.user_id = $1 ORDER BY g.created_at`, [userId]);

  app.get("/me/groups", [requireWebSession], async (req) => (await myGroups(req.user.id)).rows);

  app.post("/me/groups", [rateLimit(10, 60_000), requireWebSession], async (req) => {
    const plan = await need(req, "privateBoards");
    const { name } = z.object({ name: z.string().trim().min(2).max(40) }).parse(req.body);
    if (!req.user.mcUuid) fail(400, "no_minecraft");
    const owned = await pool.query("SELECT count(*)::int AS n FROM groups WHERE owner_id = $1", [req.user.id]);
    if (owned.rows[0].n >= limit(plan, "ownedBoards")) fail(400, "too_many_groups");
    const g = await pool.query(
      "INSERT INTO groups (slug, name, owner_id, invite_code) VALUES ($1, $2, $3, $4) RETURNING id, slug",
      [crypto.randomBytes(5).toString("hex"), name, req.user.id, code()]);
    await pool.query("INSERT INTO group_members (group_id, user_id) VALUES ($1, $2)", [g.rows[0].id, req.user.id]);
    return { slug: g.rows[0].slug };
  });

  /** Anyone with a linked Minecraft account can join with the invite code; only creating one is paid. */
  app.post("/groups/join", [rateLimit(10, 60_000), requireWebSession], async (req) => {
    const { code: invite } = z.object({ code: z.string().trim().min(4).max(32) }).parse(req.body);
    if (!req.user.mcUuid) fail(400, "no_minecraft");
    const g = await pool.query(
      `SELECT g.id, g.slug, (SELECT count(*)::int FROM group_members m WHERE m.group_id = g.id) AS members
       FROM groups g WHERE g.invite_code = $1`, [invite]);
    if (!g.rowCount) return fail(404, "bad_invite");
    if (g.rows[0].members >= MAX_MEMBERS) fail(400, "group_full");
    await pool.query("INSERT INTO group_members (group_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING", [g.rows[0].id, req.user.id]);
    return { slug: g.rows[0].slug };
  });

  /** The group's ranking. Members only. Weekly columns count games since Monday 00:00 UTC. */
  app.get("/groups/:slug", [requireWebSession], async (req) => {
    const g = await pool.query(
      `SELECT g.id, g.name, g.owner_id, g.invite_code, o.mc_name AS owner_name, o.plan, o.plan_until, o.plan_comped
       FROM groups g JOIN users o ON o.id = g.owner_id
       WHERE g.slug = $1 AND EXISTS (SELECT 1 FROM group_members m WHERE m.group_id = g.id AND m.user_id = $2)`,
      [req.params.slug, req.user.id]);
    if (!g.rowCount) return fail(404, "not_found");
    const group = g.rows[0];
    const isOwner = group.owner_id === req.user.id;
    const base = { name: group.name, owner: group.owner_name, isOwner, invite: isOwner ? group.invite_code : null };
    // The leaderboard runs while its owner is a Master Builder; nothing is deleted if that lapses.
    if (!can(planOf(group), "privateBoards")) return { ...base, paused: true, members: [] };
    const { week, endsAt } = await currentWeek(pool);
    const members = await pool.query(
      `SELECT u.id, u.mc_name, u.mc_uuid, t.bb_score, t.bb_wins, p.level, p.ap,
              coalesce(w.games, 0)::int AS week_games, coalesce(w.wins, 0)::int AS week_wins, coalesce(w.xp, 0)::int AS week_xp
       FROM group_members m JOIN users u ON u.id = m.user_id
       LEFT JOIN tracked_players t ON t.mc_uuid = u.mc_uuid
       LEFT JOIN user_progress p ON p.user_id = u.id
       LEFT JOIN LATERAL (
         SELECT count(*) AS games, count(*) FILTER (WHERE placement = 1) AS wins, sum(xp_earned) AS xp
         FROM matches x WHERE x.user_id = u.id AND x.played_at >= $2::date) w ON true
       WHERE m.group_id = $1`, [group.id, week]);
    return {
      ...base, paused: false, weekEndsAt: endsAt,
      members: members.rows.map((m: any) => ({
        id: isOwner ? Number(m.id) : undefined, name: m.mc_name, uuid: m.mc_uuid, score: m.bb_score, wins: m.bb_wins,
        level: m.level, ap: m.ap, weekGames: m.week_games, weekWins: m.week_wins, weekXp: m.week_xp,
        owner: Number(m.id) === Number(group.owner_id),
      })),
    };
  });

  app.post("/groups/:slug/leave", [requireWebSession], async (req) => {
    const r = await pool.query(
      `DELETE FROM group_members m USING groups g
       WHERE g.id = m.group_id AND g.slug = $1 AND m.user_id = $2 AND g.owner_id <> $2`, [req.params.slug, req.user.id]);
    if (!r.rowCount) fail(400, "owner_cannot_leave");
    return { ok: true };
  });

  app.post("/groups/:slug/kick", [requireWebSession], async (req) => {
    const { userId } = z.object({ userId: z.number().int() }).parse(req.body);
    const r = await pool.query(
      `DELETE FROM group_members m USING groups g
       WHERE g.id = m.group_id AND g.slug = $1 AND g.owner_id = $2 AND m.user_id = $3 AND m.user_id <> $2`,
      [req.params.slug, req.user.id, userId]);
    if (!r.rowCount) fail(404, "not_found");
    return { ok: true };
  });

  /** A new invite code; the old one stops working. */
  app.post("/groups/:slug/invite", [requireWebSession], async (req) => {
    const r = await pool.query(
      "UPDATE groups SET invite_code = $3 WHERE slug = $1 AND owner_id = $2 RETURNING invite_code", [req.params.slug, req.user.id, code()]);
    if (!r.rowCount) return fail(404, "not_found");
    return { invite: r.rows[0].invite_code };
  });

  app.delete("/groups/:slug", [requireWebSession], async (req) => {
    const r = await pool.query("DELETE FROM groups WHERE slug = $1 AND owner_id = $2", [req.params.slug, req.user.id]);
    if (!r.rowCount) fail(404, "not_found");
    return { ok: true };
  });
}
