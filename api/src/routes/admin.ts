// Admin-only views. Locked behind ADMIN_TOKEN in .env; without it these routes don't exist.
import crypto from "node:crypto";
import { z } from "zod";
import { Router, fail, type Req } from "../http";
import { pool } from "../db/pool";
import { noteFailure, tooManyFailures } from "../plugins/auth";
import { announcePlanChange } from "../services/stripe";

const VOTES = ["super_poop", "poop", "meh", "ok", "good", "epic", "legendary"] as const;

function requireAdmin(req: Req) {
  const expected = process.env.ADMIN_TOKEN ?? "";
  const given = String(req.headers["x-admin-token"] ?? "");
  const ok = expected.length >= 16 && given.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
  // Ten wrong tokens from one address and that address is ignored for 15 minutes.
  const subject = "admin:" + req.ip;
  if (tooManyFailures(subject, 10)) fail(404, "not_found");
  if (!ok) {
    noteFailure(subject, 15 * 60_000);
    fail(404, "not_found");
  }
}

export function adminRoutes(app: Router) {
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
