// Guess the Build "unusual guessing": the mod uploads the correct guesses it saw, and gets back private hints
// for those same players only. The admin side ranks the strongest cases for the owner to review.
// Nothing here is public: no profile, leaderboard, website page or Discord message reads these tables.
import { z } from "zod";
import { Router, fail, type Hook } from "../http";
import { pool } from "../db/pool";
import { rateLimit, requireModToken } from "../plugins/auth";
import { GTB_WATCH, scorePlayer, type GtbCase, type GuessEvent } from "../services/gtbWatch";

const Name = z.string().regex(/^[A-Za-z0-9_]{1,16}$/);

const GuessIn = z.object({
  gameKey: z.string().regex(/^[A-Za-z0-9_:/|.\-]{1,80}$/),
  round: z.number().int().min(1).max(30),
  name: Name,
  builder: Name.nullable().default(null),
  msSinceStart: z.number().int().min(0).max(600_000),
  msSinceReveal: z.number().int().min(0).max(600_000).nullable().default(null),
  revealed: z.number().int().min(0).max(40),
  wordLength: z.number().int().min(1).max(40).nullable().default(null),
  word: z.string().max(40).nullable().default(null),
  difficulty: z.string().max(16).nullable().default(null),
});

const GuessesBody = z.object({
  modVersion: z.string().max(40).optional(),
  mcVersion: z.string().max(20).optional(),
  guesses: z.array(z.unknown()).max(200),
});

let lastPurgeAt = 0;
/** Deletes guess events past their keep time, at most once an hour. */
async function purgeOld() {
  if (Date.now() - lastPurgeAt < 60 * 60_000) return;
  lastPurgeAt = Date.now();
  await pool.query("DELETE FROM gtb_guess_events WHERE seen_at < now() - $1::int * interval '1 day'", [GTB_WATCH.keepDays]);
}

/** In how many games each word was seen, for the rare-word check. */
async function wordGames(): Promise<Map<string, number>> {
  const r = await pool.query(
    `SELECT lower(word) AS word, count(DISTINCT game_key)::int AS n FROM gtb_guess_events
     WHERE word IS NOT NULL AND seen_at >= now() - $1::int * interval '1 day' GROUP BY 1`, [GTB_WATCH.keepDays]);
  return new Map(r.rows.map((row: any) => [row.word, row.n]));
}

/** Every kept event for these players, grouped by name (lower case). Self-reports never get stored. */
async function eventsFor(names: string[]): Promise<Map<string, { name: string; events: GuessEvent[] }>> {
  const out = new Map<string, { name: string; events: GuessEvent[] }>();
  if (!names.length) return out;
  const r = await pool.query(
    `SELECT reporter_id, game_key, round, guesser_name, ms_since_start, ms_since_reveal, revealed, word_length, word, seen_at
     FROM gtb_guess_events
     WHERE guesser_name = ANY($1::citext[]) AND seen_at >= now() - $2::int * interval '1 day'`,
    [names, GTB_WATCH.keepDays]);
  for (const row of r.rows) {
    const key = String(row.guesser_name).toLowerCase();
    if (!out.has(key)) out.set(key, { name: row.guesser_name, events: [] });
    out.get(key)!.events.push({
      reporter: Number(row.reporter_id), gameKey: row.game_key, round: row.round, guesser: row.guesser_name,
      msSinceStart: row.ms_since_start, msSinceReveal: row.ms_since_reveal, revealed: row.revealed,
      wordLength: row.word_length, word: row.word, seenAt: row.seen_at,
    });
  }
  return out;
}

async function casesFor(names: string[]): Promise<GtbCase[]> {
  const [events, words] = await Promise.all([eventsFor(names), wordGames()]);
  return [...events.values()].map((p) => scorePlayer(p.name, p.events, words));
}

export function gtbModRoutes(app: Router) {
  // Sent by the mod after each Guess the Build round. Answers with hints for these guessers only, so the
  // endpoint can't be used to look anyone up.
  app.post("/mod/gtb/guesses", [requireModToken, rateLimit(30, 60_000, "/mod/gtb/guesses")], async (req) => {
    const body = GuessesBody.parse(req.body);
    const me = await pool.query("SELECT mc_name FROM users WHERE id = $1", [req.user.id]);
    const myName = String(me.rows[0]?.mc_name ?? "").toLowerCase();
    const guesses = body.guesses
      .map((g) => GuessIn.safeParse(g))
      .filter((p) => p.success)
      .map((p) => (p as { data: z.infer<typeof GuessIn> }).data)
      // A reporter is never evidence about themself, and the builder can't guess their own build.
      .filter((g) => g.name.toLowerCase() !== myName && g.name.toLowerCase() !== g.builder?.toLowerCase())
      .filter((g) => g.wordLength === null || g.revealed <= g.wordLength);
    for (const g of guesses) {
      await pool.query(
        `INSERT INTO gtb_guess_events (reporter_id, game_key, round, guesser_name, builder, ms_since_start,
                                       ms_since_reveal, revealed, word_length, word, difficulty)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
         ON CONFLICT (reporter_id, game_key, round, guesser_name) DO NOTHING`,
        [req.user.id, g.gameKey, g.round, g.name, g.builder, g.msSinceStart, g.msSinceReveal, g.revealed,
         g.wordLength, g.word?.trim() || null, g.difficulty]);
    }
    await purgeOld().catch(() => {});

    const names = [...new Set(guesses.map((g) => g.name.toLowerCase()))].slice(0, 24);
    if (!names.length) return { received: 0, flags: [] };
    const [cases, reviews] = await Promise.all([
      casesFor(names),
      pool.query("SELECT lower(guesser_name) AS name FROM gtb_reviews WHERE guesser_name = ANY($1::citext[]) AND status = 'dismissed'", [names]),
    ]);
    const dismissed = new Set(reviews.rows.map((r: any) => r.name));
    const flags = cases
      .filter((c) => c.strong && !dismissed.has(c.name.toLowerCase()))
      .map((c) => ({ name: c.name, score: c.score, rounds: c.rounds, evidence: c.evidence }));
    return { received: guesses.length, flags };
  });
}

export function gtbAdminRoutes(app: Router, requireAdmin: Hook) {
  // The strongest cases first. Only players with enough rounds and reporters to say anything.
  app.get("/admin/gtb", [requireAdmin], async () => {
    await purgeOld().catch(() => {});
    const candidates = await pool.query(
      `SELECT guesser_name FROM gtb_guess_events WHERE seen_at >= now() - $1::int * interval '1 day'
       GROUP BY guesser_name
       HAVING count(DISTINCT game_key || '#' || round) >= $2 AND count(DISTINCT reporter_id) >= $3
       LIMIT 2000`, [GTB_WATCH.keepDays, GTB_WATCH.minRounds, GTB_WATCH.minReporters]);
    const names = candidates.rows.map((r: any) => String(r.guesser_name));
    const [cases, reviews, totals] = await Promise.all([
      casesFor(names),
      pool.query("SELECT guesser_name, status, note, updated_at FROM gtb_reviews"),
      pool.query(
        `SELECT count(*)::int AS events, count(DISTINCT guesser_name)::int AS players, count(DISTINCT reporter_id)::int AS reporters
         FROM gtb_guess_events`),
    ]);
    const review = new Map(reviews.rows.map((r: any) => [String(r.guesser_name).toLowerCase(), r]));
    return {
      config: GTB_WATCH,
      totals: totals.rows[0],
      cases: cases
        .filter((c) => c.eligible)
        .sort((a, b) => b.score - a.score || b.rounds - a.rounds)
        .slice(0, 200)
        .map(({ detail, ...c }) => {
          const r: any = review.get(c.name.toLowerCase());
          return { ...c, status: r?.status ?? "open", note: r?.note ?? "", reviewedAt: r?.updated_at ?? null };
        }),
    };
  });

  // One case with every round, and who saw it (by Minecraft name), for the owner to check before reporting.
  app.get("/admin/gtb/:name", [requireAdmin], async (req) => {
    const { name } = z.object({ name: Name }).parse(req.params);
    const [cases, review] = await Promise.all([
      casesFor([name]),
      pool.query("SELECT status, note, updated_at FROM gtb_reviews WHERE guesser_name = $1", [name]),
    ]);
    const c = cases[0];
    if (!c) fail(404, "not_found");
    const ids = [...new Set(c.detail.flatMap((r) => r.allReporters))];
    const users = await pool.query("SELECT id, mc_name FROM users WHERE id = ANY($1::bigint[])", [ids]);
    const reporterName = new Map(users.rows.map((u: any) => [Number(u.id), u.mc_name]));
    const uuid = await pool.query("SELECT mc_uuid FROM tracked_players WHERE mc_name = $1 LIMIT 1", [name]).catch(() => ({ rows: [] as any[] }));
    return {
      ...c,
      uuid: uuid.rows[0]?.mc_uuid ?? null,
      droppedReporters: c.droppedReporters.map((id) => reporterName.get(id) ?? `#${id}`),
      detail: c.detail.map((r) => ({
        ...r,
        reporters: r.reporters.map((id) => reporterName.get(id) ?? `#${id}`),
        allReporters: r.allReporters.map((id) => reporterName.get(id) ?? `#${id}`),
      })),
      status: review.rows[0]?.status ?? "open",
      note: review.rows[0]?.note ?? "",
      reviewedAt: review.rows[0]?.updated_at ?? null,
    };
  });

  // Dismiss (stops in-game hints for this player), mark as passed on to Hypixel, or reopen; and private notes.
  app.post("/admin/gtb/:name/review", [requireAdmin], async (req) => {
    const { name } = z.object({ name: Name }).parse(req.params);
    const body = z.object({
      status: z.enum(["open", "dismissed", "reported"]).optional(),
      note: z.string().max(2000).optional(),
    }).parse(req.body);
    const r = await pool.query(
      `INSERT INTO gtb_reviews (guesser_name, status, note) VALUES ($1, COALESCE($2, 'open'), COALESCE($3, ''))
       ON CONFLICT (guesser_name) DO UPDATE SET
         status = COALESCE($2, gtb_reviews.status), note = COALESCE($3, gtb_reviews.note), updated_at = now()
       RETURNING status, note, updated_at`, [name, body.status ?? null, body.note ?? null]);
    return r.rows[0];
  });
}
