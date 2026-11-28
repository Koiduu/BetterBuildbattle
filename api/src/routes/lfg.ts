// LFG lobbies for the mod: browse, host, ask to join, accept or turn people away, report a name.
// Only players with a linked website account (/bb link) can host, join or report.
// Admin review of reported lobbies is at the bottom (/admin/lfg/...).
import { z } from "zod";
import { hash, verify } from "@node-rs/argon2";
import type { PoolClient } from "pg";
import { Router, fail, type Req } from "../http";
import { pool } from "../db/pool";
import { rateLimit, requireModToken } from "../plugins/auth";
import { requireAdmin } from "./admin";
import {
  CAPACITY_LOCK, CreateLobby, Decision, HIDDEN_NAME, LFG_LIMITS, NOTICE, TAGS, TAG_LABEL, tidy,
  advanceQueue, announce, capacityUse, closeLobby, closesSoon, inviteCommand, joinProblem, nameProblem,
  nextStatus, noteProblem, startLobbySweeper, type LobbySettings,
} from "../services/lfg";

const ID = z.string().uuid();

/** Hook: the mod user must have tied this Minecraft account to a website account. */
async function requireLinked(req: Req) {
  const r = await pool.query(
    "SELECT mc_name, (email IS NOT NULL OR discord_id IS NOT NULL) AS linked FROM users WHERE id = $1", [req.user.id]);
  if (!r.rows[0]?.mc_name) fail(403, "no_minecraft");
  if (!r.rows[0].linked) fail(403, "not_linked");
}

function checkSettings(s: LobbySettings) {
  const bad = nameProblem(s.name);
  if (bad) fail(400, "name_" + bad);
  const badNote = noteProblem(s.note);
  if (badNote) fail(400, "note_" + badNote);
}

const LOBBY_COLUMNS = `
  l.id, u.mc_name AS host, l.name, l.name_hidden, l.tag, l.max_size, l.region, l.note,
  l.password_hash IS NOT NULL AS locked, l.created_at, l.expires_at, l.host_id = $1 AS mine,
  1 + (SELECT count(*)::int FROM lfg_requests a WHERE a.lobby_id = l.id AND a.status = 'accepted') AS players`;

function lobbyView(row: any) {
  const hidden = row.name_hidden && !row.mine;
  return {
    id: row.id, host: row.host, name: hidden ? HIDDEN_NAME : row.name, nameHidden: !!row.name_hidden,
    tag: row.tag, maxSize: row.max_size, players: row.players, region: row.region,
    note: hidden ? "" : row.note, locked: row.locked, mine: row.mine,
    createdAt: new Date(row.created_at).toISOString(), expiresAt: new Date(row.expires_at).toISOString(),
    closesSoon: closesSoon(new Date(row.created_at)),
    // The joiner never learns they were blocked; it reads as declined.
    myRequest: row.my_status === "blocked" ? "declined" : (row.my_status ?? null),
  };
}

/** Does a lobby match the browser's search box? Name, host and tag. */
export function matchesSearch(l: { name: string; host: string; tag: string }, q: string): boolean {
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  const hay = `${l.name} ${l.host} ${l.tag} ${TAG_LABEL[l.tag as keyof typeof TAG_LABEL] ?? ""}`.toLowerCase();
  return words.every((w) => hay.includes(w));
}

async function tx<T>(fn: (c: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const out = await fn(client);
    await client.query("COMMIT");
    return out;
  } catch (e) {
    await client.query("ROLLBACK").catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

async function lobbyById(me: number, id: string) {
  const r = await pool.query(
    `SELECT ${LOBBY_COLUMNS}, NULL AS my_status FROM lfg_lobbies l JOIN users u ON u.id = l.host_id WHERE l.id = $2`, [me, id]);
  return lobbyView(r.rows[0]);
}

/** Opens a lobby inside a transaction that holds CAPACITY_LOCK. Closes the host's old one. */
async function openLobby(c: PoolClient, userId: number, s: LobbySettings, passwordHash: string | null) {
  const old = await c.query(
    `UPDATE lfg_lobbies SET status = 'closed', closed_reason = 'replaced', closed_at = now()
     WHERE host_id = $1 AND status = 'open' RETURNING id`, [userId]);
  const ins = await c.query(
    `INSERT INTO lfg_lobbies (host_id, name, tag, max_size, region, note, password_hash, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, now() + make_interval(hours => $8)) RETURNING id`,
    [userId, s.name, s.tag, s.maxSize, s.region, s.note, passwordHash, LFG_LIMITS.lifetimeHours]);
  await c.query("DELETE FROM lfg_queue WHERE user_id = $1", [userId]);
  return { id: ins.rows[0].id as string, replaced: old.rows.map((row: any) => row.id as string) };
}

async function queuePosition(userId: number, db: { query: PoolClient["query"] } = pool): Promise<number | null> {
  const r = await db.query(
    `SELECT count(*)::int AS n FROM lfg_queue
     WHERE queued_at <= (SELECT queued_at FROM lfg_queue WHERE user_id = $1)`, [userId]);
  return r.rows[0].n || null;
}

async function hostBanned(userId: number) {
  const r = await pool.query("SELECT lfg_host_banned FROM users WHERE id = $1", [userId]);
  return !!r.rows[0]?.lfg_host_banned;
}

export function lfgRoutes(app: Router) {
  startLobbySweeper();

  // Open lobbies, newest first, your own first. Lobbies that blocked you are left out.
  app.get("/mod/lfg", [requireModToken, rateLimit(30, 60_000, "/mod/lfg")], async (req) => {
    const { tag, q } = z.object({ tag: z.enum(TAGS).optional(), q: z.string().max(40).optional() }).parse(req.query);
    const r = await pool.query(
      `SELECT ${LOBBY_COLUMNS},
              (SELECT status FROM lfg_requests m WHERE m.lobby_id = l.id AND m.user_id = $1) AS my_status
       FROM lfg_lobbies l JOIN users u ON u.id = l.host_id
       WHERE l.status = 'open' AND l.expires_at > now() AND l.host_seen_at > now() - make_interval(secs => $2)
         AND ($3::text IS NULL OR l.tag = $3)
         AND NOT EXISTS (SELECT 1 FROM lfg_requests b WHERE b.lobby_id = l.id AND b.user_id = $1 AND b.status = 'blocked')
       ORDER BY (l.host_id = $1) DESC, l.created_at DESC LIMIT $4`,
      [req.user.id, LFG_LIMITS.hostTimeoutSec, tag ?? null, LFG_LIMITS.maxOpenLobbies]);
    let lobbies = r.rows.map(lobbyView);
    if (q && q.trim()) lobbies = lobbies.filter((l) => matchesSearch(l, q));
    return { lobbies, notice: NOTICE, max: LFG_LIMITS.maxOpenLobbies };
  });

  // Start a lobby. One open lobby per host: starting another closes the old one.
  // When every slot is taken the host goes into a waiting line instead.
  app.post("/mod/lfg", [requireModToken, requireLinked,
    rateLimit(3, 10 * 60_000, "/mod/lfg:create"), rateLimit(10, 60 * 60_000, "/mod/lfg:create-hour")], async (req) => {
    const { settings, password } = CreateLobby.parse(req.body);
    checkSettings(settings);
    if (await hostBanned(req.user.id)) fail(403, "host_banned");
    const passwordHash = password ? await hash(password) : null;
    const out = await tx(async (c) => {
      await c.query(CAPACITY_LOCK);
      const offer = await c.query("SELECT 1 FROM lfg_queue WHERE user_id = $1 AND offer_until > now()", [req.user.id]);
      const { open, offers } = await capacityUse(c, req.user.id);
      const waiting = (await c.query(
        "SELECT count(*)::int AS n FROM lfg_queue WHERE offer_until IS NULL AND user_id <> $1", [req.user.id])).rows[0].n;
      const full = open + offers >= LFG_LIMITS.maxOpenLobbies || waiting > 0;
      if (!offer.rowCount && full) {
        await c.query(
          `INSERT INTO lfg_queue (user_id, settings, password_hash) VALUES ($1, $2, $3)
           ON CONFLICT (user_id) DO UPDATE SET settings = EXCLUDED.settings, password_hash = EXCLUDED.password_hash, seen_at = now()`,
          [req.user.id, JSON.stringify(settings), passwordHash]);
        return { queued: await queuePosition(req.user.id, c) };
      }
      return { opened: await openLobby(c, req.user.id, settings, passwordHash) };
    });
    if ("queued" in out) return { queued: { position: out.queued }, notice: NOTICE };
    for (const old of out.opened!.replaced) await announce("lobbyClosed", old);
    await announce("lobbyOpened", out.opened!.id);
    return { lobby: await lobbyById(req.user.id, out.opened!.id), notice: NOTICE };
  });

  // A slot came free for a host waiting in line: open the lobby they asked for.
  app.post("/mod/lfg/claim", [requireModToken, requireLinked, rateLimit(10, 60_000, "/mod/lfg:claim")], async (req) => {
    if (await hostBanned(req.user.id)) fail(403, "host_banned");
    const opened = await tx(async (c) => {
      await c.query(CAPACITY_LOCK);
      const q = await c.query(
        "SELECT settings, password_hash FROM lfg_queue WHERE user_id = $1 AND offer_until > now() FOR UPDATE", [req.user.id]);
      if (!q.rowCount) fail(409, "no_offer");
      return openLobby(c, req.user.id, q.rows[0].settings as LobbySettings, q.rows[0].password_hash);
    });
    for (const old of opened.replaced) await announce("lobbyClosed", old);
    await announce("lobbyOpened", opened.id);
    return { lobby: await lobbyById(req.user.id, opened.id), notice: NOTICE };
  });

  // Step out of the waiting line. Registered before /:id/leave, which would otherwise catch it.
  app.post("/mod/lfg/queue/leave", [requireModToken], async (req) => {
    const r = await pool.query("DELETE FROM lfg_queue WHERE user_id = $1 RETURNING offer_until", [req.user.id]);
    if (r.rows[0]?.offer_until) await advanceQueue().catch(() => {});
    return { ok: true };
  });

  // The host closes their lobby.
  app.post("/mod/lfg/:id/close", [requireModToken, rateLimit(20, 60_000, "/mod/lfg:close")], async (req) => {
    const id = ID.parse(req.params.id);
    const own = await pool.query("SELECT 1 FROM lfg_lobbies WHERE id = $1 AND host_id = $2", [id, req.user.id]);
    if (!own.rowCount) fail(404, "not_found");
    await closeLobby(id, "host");
    return { ok: true };
  });

  // The host renames their lobby. A name hidden after reports stays hidden until an admin looks at it.
  app.post("/mod/lfg/:id/rename", [requireModToken, requireLinked,
    rateLimit(3, 10 * 60_000, "/mod/lfg:rename"), rateLimit(10, 60 * 60_000, "/mod/lfg:rename-hour")], async (req) => {
    const id = ID.parse(req.params.id);
    const name = tidy(z.object({ name: z.string().max(64) }).parse(req.body).name);
    const bad = nameProblem(name);
    if (bad) fail(400, "name_" + bad);
    const r = await pool.query(
      `UPDATE lfg_lobbies SET name = $3, renamed_at = now() WHERE id = $1 AND host_id = $2 AND status = 'open' RETURNING id`,
      [id, req.user.id, name]);
    if (!r.rowCount) fail(404, "not_found");
    await announce("lobbyUpdated", id);
    return { lobby: await lobbyById(req.user.id, id) };
  });

  // Report a lobby's name. Enough different players reporting hides it until an admin reviews it.
  app.post("/mod/lfg/:id/report", [requireModToken, requireLinked, rateLimit(10, 60 * 60_000, "/mod/lfg:report")], async (req) => {
    const id = ID.parse(req.params.id);
    const l = await pool.query("SELECT host_id, name, name_hidden, reviewed_at FROM lfg_lobbies WHERE id = $1", [id]);
    if (!l.rowCount) fail(404, "not_found");
    if (Number(l.rows[0].host_id) === req.user.id) fail(409, "own_lobby");
    await pool.query(
      "INSERT INTO lfg_reports (lobby_id, user_id, name) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING",
      [id, req.user.id, l.rows[0].name]);
    const n = (await pool.query(
      `SELECT count(*)::int AS n FROM lfg_reports
       WHERE lobby_id = $1 AND created_at > coalesce($2::timestamptz, '-infinity')`, [id, l.rows[0].reviewed_at])).rows[0].n;
    if (n >= LFG_LIMITS.reportsToHide && !l.rows[0].name_hidden) {
      await pool.query("UPDATE lfg_lobbies SET name_hidden = true WHERE id = $1", [id]);
      await announce("lobbyUpdated", id);
    }
    return { ok: true };
  });

  // Ask to join. The host sees it on their next poll.
  app.post("/mod/lfg/:id/join", [requireModToken, requireLinked,
    rateLimit(10, 60_000, "/mod/lfg:join"), rateLimit(40, 60 * 60_000, "/mod/lfg:join-hour")], async (req) => {
    const id = ID.parse(req.params.id);
    const { password } = z.object({ password: z.string().max(32).nullable().optional() }).parse(req.body ?? {});
    const first = await pool.query("SELECT password_hash FROM lfg_lobbies WHERE id = $1", [id]);
    if (!first.rowCount) fail(404, "not_found");
    const stored: string | null = first.rows[0].password_hash;
    // Checked before the lobby is locked: hashing is slow.
    const passwordOk = !stored || (!!password && await verify(stored, password).catch(() => false));
    await tx(async (c) => {
      const lobby = (await c.query(
        "SELECT host_id, status, host_seen_at, expires_at, max_size FROM lfg_lobbies WHERE id = $1 FOR UPDATE", [id])).rows[0];
      const counts = (await c.query(
        `SELECT 1 + (SELECT count(*)::int FROM lfg_requests WHERE lobby_id = $1 AND status = 'accepted') AS players,
                (SELECT status FROM lfg_requests WHERE lobby_id = $1 AND user_id = $2) AS existing,
                (SELECT count(*)::int FROM lfg_requests r JOIN lfg_lobbies o ON o.id = r.lobby_id
                  WHERE r.user_id = $2 AND r.status = 'pending' AND o.status = 'open' AND o.id <> $1) AS pending`,
        [id, req.user.id])).rows[0];
      const problem = joinProblem({
        lobby: { ...lobby, host_id: Number(lobby.host_id) }, userId: req.user.id, players: counts.players,
        existing: counts.existing, pendingElsewhere: counts.pending, passwordOk,
      });
      // Blocked reads as declined, so a blocked player can't tell.
      if (problem === "blocked") fail(409, "declined");
      if (problem) fail(problem === "wrong_password" ? 403 : 409, problem);
      await c.query(
        `INSERT INTO lfg_requests (lobby_id, user_id) VALUES ($1, $2)
         ON CONFLICT (lobby_id, user_id) DO UPDATE SET status = 'pending', created_at = now(), decided_at = NULL`,
        [id, req.user.id]);
    });
    return { ok: true, status: "pending" };
  });

  // Take back a request, or leave a lobby you were accepted into.
  app.post("/mod/lfg/:id/leave", [requireModToken, rateLimit(20, 60_000, "/mod/lfg:leave")], async (req) => {
    const id = ID.parse(req.params.id);
    const r = await pool.query(
      `UPDATE lfg_requests n SET status = 'left', decided_at = now()
       FROM lfg_requests o WHERE n.id = o.id AND n.lobby_id = $1 AND n.user_id = $2 AND n.status IN ('pending', 'accepted')
       RETURNING o.status AS was`, [id, req.user.id]);
    if (r.rows[0]?.was === "accepted") await announce("lobbyUpdated", id);
    return { ok: true };
  });

  // The host answers a request: accept, decline, kick (out now) or block (for the rest of this lobby).
  app.post("/mod/lfg/requests/:rid", [requireModToken, rateLimit(60, 60_000, "/mod/lfg:decide")], async (req) => {
    const rid = z.coerce.number().int().positive().parse(req.params.rid);
    const { action } = Decision.parse(req.body);
    const out = await tx(async (c) => {
      const row = (await c.query(
        `SELECT r.status, r.lobby_id, u.mc_name, l.host_id, l.max_size, l.status AS lobby_status
         FROM lfg_requests r JOIN lfg_lobbies l ON l.id = r.lobby_id JOIN users u ON u.id = r.user_id
         WHERE r.id = $1 FOR UPDATE OF l, r`, [rid])).rows[0];
      if (!row || Number(row.host_id) !== req.user.id) fail(404, "not_found");
      if (row.lobby_status !== "open") fail(409, "lobby_closed");
      const players = (await c.query(
        "SELECT 1 + count(*)::int AS n FROM lfg_requests WHERE lobby_id = $1 AND status = 'accepted'", [row.lobby_id])).rows[0].n;
      const next = nextStatus(row.status, action, players, row.max_size);
      if (!next) fail(409, action === "accept" && row.status === "pending" ? "lobby_full" : "cant_" + action);
      await c.query("UPDATE lfg_requests SET status = $2, decided_at = now() WHERE id = $1", [rid, next]);
      return { lobbyId: row.lobby_id as string, name: row.mc_name as string, was: row.status as string, next: next! };
    });
    if (out.next === "accepted" || out.was === "accepted") await announce("lobbyUpdated", out.lobbyId);
    return { ok: true, status: out.next, name: out.name, invite: out.next === "accepted" ? inviteCommand(out.name) : null };
  });

  // Polled every few seconds while the player hosts, waits on a request or waits in line.
  // Keeps the host's lobby open and the host's place in line.
  app.get("/mod/lfg/poll", [requireModToken, rateLimit(40, 60_000, "/mod/lfg/poll")], async (req) => {
    const me = req.user.id;
    const hostRow = await pool.query(
      `UPDATE lfg_lobbies SET host_seen_at = now()
       WHERE host_id = $1 AND status = 'open' AND expires_at > now() RETURNING id`, [me]);
    const lobbyId: string | null = hostRow.rows[0]?.id ?? null;
    let hosting = null;
    let requests: any[] = [];
    if (lobbyId) {
      hosting = await lobbyById(me, lobbyId);
      requests = (await pool.query(
        `SELECT r.id, u.mc_name AS name, r.status, r.created_at FROM lfg_requests r JOIN users u ON u.id = r.user_id
         WHERE r.lobby_id = $1 AND r.status IN ('pending', 'accepted') ORDER BY r.created_at`, [lobbyId]))
        .rows.map((r: any) => ({ id: Number(r.id), name: r.name, status: r.status, createdAt: new Date(r.created_at).toISOString() }));
    }
    // Your requests, including ones in lobbies that closed in the last few minutes (so the mod can say so).
    const joining = (await pool.query(
      `SELECT l.id AS lobby_id, u.mc_name AS host, l.name, l.name_hidden, l.tag, r.status, l.status AS lobby_status
       FROM lfg_requests r JOIN lfg_lobbies l ON l.id = r.lobby_id JOIN users u ON u.id = l.host_id
       WHERE r.user_id = $1 AND r.status <> 'left'
         AND (l.status = 'open' OR l.closed_at > now() - interval '5 minutes')
       ORDER BY r.created_at DESC LIMIT 10`, [me])).rows.map((r: any) => ({
      lobbyId: r.lobby_id, host: r.host, name: r.name_hidden ? HIDDEN_NAME : r.name, tag: r.tag,
      status: r.status === "blocked" ? "declined" : r.status,
      lobbyOpen: r.lobby_status === "open",
    }));
    const q = await pool.query(
      "UPDATE lfg_queue SET seen_at = now() WHERE user_id = $1 RETURNING offer_until", [me]);
    const queue = q.rowCount
      ? { position: await queuePosition(me), offerUntil: q.rows[0].offer_until && new Date(q.rows[0].offer_until) > new Date()
          ? new Date(q.rows[0].offer_until).toISOString() : null }
      : null;
    return { hosting, requests, joining, queue };
  });

  // ---- Admin: reported lobbies

  app.get("/admin/lfg/reported", [requireAdmin], async () => {
    const r = await pool.query(
      `SELECT l.id, l.name, l.name_hidden, l.tag, l.status, l.created_at, l.reviewed_at, u.mc_name AS host,
              u.lfg_host_banned AS host_banned,
              count(p.*) FILTER (WHERE p.created_at > coalesce(l.reviewed_at, '-infinity'))::int AS reports,
              array_agg(DISTINCT p.name) AS reported_names,
              array_agg(DISTINCT ru.mc_name) AS reporters
       FROM lfg_lobbies l JOIN users u ON u.id = l.host_id
       JOIN lfg_reports p ON p.lobby_id = l.id JOIN users ru ON ru.id = p.user_id
       GROUP BY l.id, u.mc_name, u.lfg_host_banned
       HAVING l.name_hidden OR count(p.*) FILTER (WHERE p.created_at > coalesce(l.reviewed_at, '-infinity')) > 0
       ORDER BY l.name_hidden DESC, max(p.created_at) DESC LIMIT 200`);
    return { lobbies: r.rows, reportsToHide: LFG_LIMITS.reportsToHide };
  });

  // Rename a reported lobby (or keep its name: send the same one). Either way it counts as reviewed.
  app.post("/admin/lfg/:id/review", [requireAdmin], async (req) => {
    const id = ID.parse(req.params.id);
    const { name } = z.object({ name: z.string().max(64).transform(tidy).optional() }).parse(req.body ?? {});
    if (name !== undefined && name.length < 1) fail(400, "name_too_short");
    const r = await pool.query(
      `UPDATE lfg_lobbies SET name = coalesce($2, name), name_hidden = false, reviewed_at = now()
       WHERE id = $1 RETURNING status`, [id, name ? name.slice(0, LFG_LIMITS.nameMax) : null]);
    if (!r.rowCount) fail(404, "not_found");
    if (r.rows[0].status === "open") await announce("lobbyUpdated", id);
    return { ok: true };
  });

  app.post("/admin/lfg/:id/close", [requireAdmin], async (req) => {
    const id = ID.parse(req.params.id);
    await pool.query("UPDATE lfg_lobbies SET reviewed_at = now(), name_hidden = true WHERE id = $1", [id]);
    await closeLobby(id, "admin");
    return { ok: true };
  });

  // Stop (or allow again) a player hosting lobbies. Banning closes their open lobby and drops them from the line.
  app.post("/admin/lfg/host-ban", [requireAdmin], async (req) => {
    const { name, banned } = z.object({ name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/), banned: z.boolean() }).parse(req.body);
    const u = await pool.query("UPDATE users SET lfg_host_banned = $2 WHERE mc_name = $1 RETURNING id", [name, banned]);
    if (!u.rowCount) fail(404, "no_such_user");
    if (banned) {
      const userId = u.rows[0].id;
      await pool.query("DELETE FROM lfg_queue WHERE user_id = $1", [userId]);
      const open = await pool.query("SELECT id FROM lfg_lobbies WHERE host_id = $1 AND status = 'open'", [userId]);
      for (const row of open.rows) await closeLobby(row.id, "admin");
    }
    return { ok: true };
  });
}
