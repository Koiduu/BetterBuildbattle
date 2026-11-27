// Admin-only views. Locked behind ADMIN_TOKEN in .env; without it these routes don't exist.
import crypto from "node:crypto";
import { z } from "zod";
import { Router, fail, type Req } from "../http";
import { pool } from "../db/pool";
import { announcePlanChange } from "../services/stripe";
import { planOf } from "../lib/plans";
import { gtbAdminRoutes } from "./gtbWatch";

const VOTES = ["super_poop", "poop", "meh", "ok", "good", "epic", "legendary"] as const;

function requireAdmin(req: Req) {
  const expected = process.env.ADMIN_TOKEN ?? "";
  const given = String(req.headers["x-admin-token"] ?? "");
  const ok = expected.length >= 16 && given.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
  if (!ok) fail(404, "not_found");
}

export function adminRoutes(app: Router) {
  gtbAdminRoutes(app, requireAdmin);   // Guess the Build cases to review

  // Give (or take back) a plan without Stripe: for testers, friends and prizes.
  app.post("/admin/plan", [requireAdmin], async (req) => {
    const { name, plan } = z.object({
      name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/),
      plan: z.enum(["free", "architect", "master"]),
    }).parse(req.body);
    const r = await pool.query(
      `UPDATE users SET plan = $2, plan_comped = ($2 <> 'free'), plan_until = NULL
       WHERE mc_name = $1 AND stripe_subscription_id IS NULL RETURNING id, mc_name, plan`, [name, plan]);
    if (!r.rowCount) fail(404, "no_such_user_or_has_subscription");
    announcePlanChange(r.rows[0].id);
    return r.rows[0];
  });

  // What plan an account has right now, so the admin page can show which tier you are previewing.
  app.get("/admin/plan/:name", [requireAdmin], async (req) => {
    const { name } = z.object({ name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/) }).parse(req.params);
    const r = await pool.query(
      "SELECT mc_name, plan, plan_until, plan_comped, stripe_subscription_id FROM users WHERE mc_name = $1", [name]);
    if (!r.rowCount) fail(404, "no_such_user");
    const row = r.rows[0];
    return { name: row.mc_name, plan: planOf(row), comped: !!row.plan_comped, hasSubscription: !!row.stripe_subscription_id };
  });

  // Bug reports from /bb report, newest first.
  app.get("/admin/reports", [requireAdmin], async () => {
    const r = await pool.query(
      `SELECT b.id, b.created_at, b.mod_version, b.mc_version, b.note, b.status, length(b.log) AS size, u.mc_name
       FROM bug_reports b LEFT JOIN users u ON u.id = b.user_id ORDER BY b.created_at DESC LIMIT 200`);
    return r.rows.map((row) => ({ ...row, id: Number(row.id) }));
  });

  app.get("/admin/reports/:id", [requireAdmin], async (req) => {
    const { id } = z.object({ id: z.coerce.number().int().positive() }).parse(req.params);
    const r = await pool.query(
      `SELECT b.*, u.mc_name FROM bug_reports b LEFT JOIN users u ON u.id = b.user_id WHERE b.id = $1`, [id]);
    if (!r.rowCount) fail(404, "not_found");
    return { ...r.rows[0], id: Number(r.rows[0].id), user_id: undefined };
  });

  app.post("/admin/reports/:id/status", [requireAdmin], async (req) => {
    const { id } = z.object({ id: z.coerce.number().int().positive() }).parse(req.params);
    const { status } = z.object({ status: z.enum(["new", "looked", "fixed"]) }).parse(req.body);
    await pool.query("UPDATE bug_reports SET status = $2 WHERE id = $1", [id, status]);
    return { ok: true };
  });

  // Everyone who has logged in with the mod (has a mod session), newest activity first.
  // Versions come from their latest synced game. No emails or password data.
  app.get("/admin/mod-users", [requireAdmin], async () => {
    const r = await pool.query(
      `SELECT u.mc_name, u.mc_uuid, u.claimed, u.last_seen_at,
              s.first_login, s.last_login, s.logins,
              g.games, lm.mod_version, lm.mc_version
       FROM users u
       JOIN (SELECT user_id, min(created_at) AS first_login, max(created_at) AS last_login, count(*)::int AS logins
             FROM sessions WHERE kind = 'mod' GROUP BY user_id) s ON s.user_id = u.id
       LEFT JOIN LATERAL (SELECT count(*)::int AS games FROM matches WHERE user_id = u.id) g ON true
       LEFT JOIN LATERAL (SELECT mod_version, mc_version FROM matches
                          WHERE user_id = u.id AND mod_version IS NOT NULL
                          ORDER BY played_at DESC LIMIT 1) lm ON true
       ORDER BY GREATEST(u.last_seen_at, s.last_login) DESC NULLS LAST
       LIMIT 5000`);
    return r.rows;
  });

  // Every mod user with how they vote: counts per vote and an average on the 1-7 scale.
  app.get("/admin/votes", [requireAdmin], async (req) => {
    const { days } = z.object({ days: z.coerce.number().int().min(1).max(3650).default(3650) }).parse(req.query);
    const r = await pool.query(
      `SELECT u.mc_name, u.mc_uuid, v.vote, count(*)::int AS n
       FROM match_votes v JOIN users u ON u.id = v.user_id
       WHERE v.voted_at >= now() - $1::int * interval '1 day'
       GROUP BY u.mc_name, u.mc_uuid, v.vote`, [days]);
    const players = new Map<string, any>();
    for (const row of r.rows) {
      const key = row.mc_uuid;
      if (!players.has(key)) {
        players.set(key, { name: row.mc_name, uuid: row.mc_uuid, total: 0, counts: Object.fromEntries(VOTES.map((v) => [v, 0])) });
      }
      const p = players.get(key);
      p.counts[row.vote] = row.n;
      p.total += row.n;
    }
    return [...players.values()]
      .map((p) => ({
        ...p,
        average: p.total ? VOTES.reduce((s, v, i) => s + p.counts[v] * (i + 1), 0) / p.total : null,
      }))
      .sort((a, b) => b.total - a.total);
  });

  // One player's votes: who they voted for and their most recent votes.
  app.get("/admin/votes/:name", [requireAdmin], async (req) => {
    const name = req.params.name;
    const [byBuilder, recent] = await Promise.all([
      pool.query(
        `SELECT v.builder, count(*)::int AS n,
                avg(array_position(ARRAY['super_poop','poop','meh','ok','good','epic','legendary'], v.vote))::float AS average
         FROM match_votes v JOIN users u ON u.id = v.user_id
         WHERE u.mc_name = $1 AND v.builder IS NOT NULL
         GROUP BY v.builder ORDER BY n DESC LIMIT 50`, [name]),
      pool.query(
        `SELECT v.vote, v.builder, v.round, v.voted_at, m.theme, m.mode
         FROM match_votes v JOIN users u ON u.id = v.user_id
         LEFT JOIN matches m ON m.user_id = v.user_id AND m.client_nonce = v.match_nonce AND m.played_at = v.played_at
         WHERE u.mc_name = $1 ORDER BY v.voted_at DESC LIMIT 100`, [name]),
    ]);
    return { name, byBuilder: byBuilder.rows, recent: recent.rows };
  });
}
