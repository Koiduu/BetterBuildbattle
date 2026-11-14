import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";
import type { PoolClient } from "pg";
import { pool } from "../db/pool";

interface Track { track: string; name: string; description: string; category: string; tiers: number[]; points: number[] }
interface OneOff { id: string; name: string; description: string; category: string; points: number; secret?: boolean; rule: string }
interface Catalog { catalog_version: number; tracks: Track[]; one_offs: OneOff[]; weekly_pool: { id: string; text: string; xp: number }[] }

const here = path.dirname(fileURLToPath(import.meta.url));
export const catalog: Catalog = YAML.parse(
  fs.readFileSync(path.resolve(here, "../../db/seeds/achievements.yaml"), "utf8"),
);

const ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
const tierId = (track: string, i: number) => `${track}_t${i + 1}`;
const oneOffById = new Map(catalog.one_offs.map((o) => [o.id, o]));
const pointsById = new Map<string, number>();
for (const t of catalog.tracks) t.tiers.forEach((_, i) => pointsById.set(tierId(t.track, i), t.points[i]));
for (const o of catalog.one_offs) pointsById.set(o.id, o.points);

/** "Win {n} games" → "Win 1 game" / "Win 1,000 games". */
function describe(template: string, n: number) {
  const text = template.replace("{n}", n.toLocaleString("en-US"));
  return n === 1 ? text.replace(/\b(games|grades|rounds|bests|builds|themes|days)\b/, (w) => w.slice(0, -1)) : text;
}

/** Upsert the YAML catalog into the achievements table. Runs at startup. */
export async function seedCatalog() {
  const rows: any[][] = [];
  for (const t of catalog.tracks) {
    t.tiers.forEach((n, i) => rows.push([
      tierId(t.track, i), `${t.name} ${ROMAN[i]}`, describe(t.description, n),
      t.category, t.track, i + 1, n, t.points[i], false,
    ]));
  }
  for (const o of catalog.one_offs) {
    rows.push([o.id, o.name, o.description, o.category, null, null, null, o.points, !!o.secret]);
  }
  for (const r of rows) {
    await pool.query(
      `INSERT INTO achievements (id, name, description, category, track, tier, threshold, points, secret, catalog_version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
       ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
         category = EXCLUDED.category, threshold = EXCLUDED.threshold, points = EXCLUDED.points,
         secret = EXCLUDED.secret, catalog_version = EXCLUDED.catalog_version`,
      [...r, catalog.catalog_version],
    );
  }
}

/**
 * Hypixel API field for each Hypixel-backed track.
 * NOTE: verify these key names against a real /v2/player response before launch.
 */
const HYPIXEL_FIELDS: Record<string, string> = {
  bb_wins: "wins",
  bb_score: "score",
  correct_guesses: "correct_guesses",
  perfect_builds: "total_perfect_builds",      // unverified key name
  pro_wins: "wins_solo_pro",
  teams_wins: "wins_teams_normal",
  solo_wins: "wins_solo_normal",
  spb_wins: "wins_speed_builders",             // unverified key name
};

/** Totals for the Better Battle-only tracks, computed from synced matches. */
async function localTotals(client: PoolClient, userId: number): Promise<Record<string, number>> {
  const r = await client.query(
    `SELECT count(DISTINCT lower(theme))                       AS themes_built,
            count(*) FILTER (WHERE grade = 'SS')               AS ss_grades,
            count(*) FILTER (WHERE crowd_score >= 0.9)         AS crowd_90,
            count(DISTINCT date_trunc('day', played_at))       AS days_played,
            coalesce(sum((SELECT count(*) FROM jsonb_array_elements(CASE WHEN jsonb_typeof(spb_rounds) = 'array' THEN spb_rounds ELSE '[]'::jsonb END) e
                          WHERE coalesce((e->>'precision')::float, (e->>'accuracy')::float) >= 1
                            AND coalesce((e->>'perfect')::boolean, true))), 0) AS precision_100
     FROM matches WHERE user_id = $1`,
    [userId],
  );
  const pbs = await client.query("SELECT count(*) AS n FROM spb_personal_bests WHERE user_id = $1", [userId]);
  const row = r.rows[0];
  return {
    themes_built: Number(row.themes_built), ss_grades: Number(row.ss_grades), crowd_90: Number(row.crowd_90),
    days_played: Number(row.days_played), precision_100: Number(row.precision_100), pbs_set: Number(pbs.rows[0].n),
  };
}

async function grant(client: PoolClient, userId: number, id: string, source: string): Promise<boolean> {
  const r = await client.query(
    `INSERT INTO user_achievements (user_id, achievement_id, source) VALUES ($1, $2, $3)
     ON CONFLICT DO NOTHING RETURNING 1`,
    [userId, id, source],
  );
  return !!r.rowCount;
}

/**
 * Called inside the sync transaction.
 * - Tiered tracks are granted from totals (Hypixel-verified or counted from matches), never from claims.
 * - One-off challenges are confirmed from the mod's claims (rules run client-side), except server-only ones.
 * - Claims for ids that don't exist are revoked.
 */
export async function evaluateAchievements(client: PoolClient, userId: number, claims: string[]) {
  const confirmed: string[] = [];
  const revoked: string[] = [];

  // Hypixel totals: from the user row, or from the crawler's copy if the user row hasn't been refreshed yet.
  const u = await client.query(
    `SELECT coalesce(u.hypixel_bb, t.hypixel_bb) AS hypixel_bb, u.flagged
     FROM users u LEFT JOIN tracked_players t ON t.mc_uuid = u.mc_uuid WHERE u.id = $1`, [userId]);
  const hyp = (u.rows[0]?.hypixel_bb ?? {}) as Record<string, number>;
  const flagged = !!u.rows[0]?.flagged;
  const local = await localTotals(client, userId);

  for (const t of catalog.tracks) {
    const field = HYPIXEL_FIELDS[t.track];
    const total = field ? Number(hyp[field] ?? 0) : local[t.track] ?? 0;
    for (let i = 0; i < t.tiers.length; i++) {
      if (total < t.tiers[i]) break;
      const id = tierId(t.track, i);
      if (await grant(client, userId, id, field ? "retroactive" : "live")) confirmed.push(id);
    }
  }

  for (const id of claims) {
    if (pointsById.has(id) && id.match(/_t\d+$/)) continue;        // tiers come from totals, handled above
    const def = oneOffById.get(id);
    if (!def || def.rule === "ServerOnly" || flagged) { revoked.push(id); continue; }
    if (await grant(client, userId, id, "live")) confirmed.push(id);
  }

  const apGained = confirmed.reduce((s, id) => s + (pointsById.get(id) ?? 0), 0);
  return { confirmed, revoked, apGained };
}

/** Award a server-side achievement (e.g. "pinned") outside a sync. */
export async function grantServerAchievement(userId: number, id: string) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    if (await grant(client, userId, id, "live")) {
      const ap = pointsById.get(id) ?? 0;
      await client.query(
        `INSERT INTO user_progress (user_id, xp, ap) VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE SET xp = user_progress.xp + $2, ap = user_progress.ap + $3`,
        [userId, ap * 10, ap],
      );
    }
    await client.query("COMMIT");
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}
