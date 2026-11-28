// LFG (looking for group) lobbies. A lobby lives in Postgres while open; the host's mod polls it, which keeps
// it alive. The server never sends anything to Hypixel: the host's mod puts "/p invite <name>" in the chat box
// and the host presses Enter.
import { z } from "zod";
import { pool } from "../db/pool";
import { lobbyEvent, type LobbyEvent, type LobbyEventKind, type LobbyMember } from "../lib/events";
import { flaggedWord } from "../lib/wordFilter";

export const NOTICE = "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.";

/** Every LFG limit in one place. */
export const LFG_LIMITS = {
  /** Open lobbies at once. Hosts past this wait in line. */
  maxOpenLobbies: 100,
  /** The host is told the lobby will close soon once it is this old. */
  warnAfterHours: 25,
  /** Every lobby closes this long after it opened, even with the host still around. */
  lifetimeHours: 30,
  /** Reports from this many different players hide the lobby's name ("Lobby") until an admin looks at it. */
  reportsToHide: 3,
  /** A host whose mod hasn't polled for this long has gone offline; the lobby closes. */
  hostTimeoutSec: 90,
  /** How long the next host in line has to claim a slot that came free before it passes on. */
  claimSeconds: 60,
  /** Open requests one player can have waiting at once, across all lobbies. */
  maxPendingRequests: 3,
  nameMin: 3,
  nameMax: 32,
  noteMax: 60,
} as const;

/** Shown to players instead of a name that was reported too often. */
export const HIDDEN_NAME = "Lobby";

export const TAGS = ["teams", "solo", "gtb", "spb", "pro", "other"] as const;
export type LobbyTag = (typeof TAGS)[number];
/** The most players one Hypixel game of each kind takes, so a lobby can't promise more than fit. */
export const TAG_MAX: Record<LobbyTag, number> = { teams: 16, solo: 12, gtb: 10, spb: 8, pro: 12, other: 16 };
export const TAG_LABEL: Record<LobbyTag, string> = {
  teams: "Teams", solo: "Solo", gtb: "GTB", spb: "SPB", pro: "Pro", other: "Other",
};
export const REGIONS = ["any", "na", "eu", "as", "oc", "sa"] as const;

const LINK = /(https?:\/\/|www\.|discord\.gg|discord(app)?\.com\/invite|dsc\.gg|\.gg\/|\b[a-z0-9-]+\.(com|net|org|gg|io|xyz|me|tv|ly|co|us|uk|ru|de|link|app)\b)/i;

/** Squeezes runs of spaces and trims. */
export function tidy(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/** Why a lobby name (or note) can't be used, as an error code, or null when it's fine. */
export function textProblem(text: string, min: number, max: number): string | null {
  if (text.length < min) return "too_short";
  if (text.length > max) return "too_long";
  // § is Minecraft's colour code; control characters and the like have no place in a name
  if (/[§\p{C}]/u.test(text)) return "bad_characters";
  if (/(.)\1{3,}/u.test(text.toLowerCase().replace(/\s/g, ""))) return "repeated_characters";
  if (LINK.test(text)) return "no_links";
  if (flaggedWord(text)) return "inappropriate";
  return null;
}

export const nameProblem = (name: string) => textProblem(name, LFG_LIMITS.nameMin, LFG_LIMITS.nameMax);
export const noteProblem = (note: string) => note === "" ? null : textProblem(note, 1, LFG_LIMITS.noteMax);

export const LobbySettings = z.object({
  name: z.string().max(64).transform(tidy),
  tag: z.enum(TAGS),
  maxSize: z.number().int().min(2).max(16),
  region: z.enum(REGIONS).default("any"),
  note: z.string().max(120).default("").transform(tidy),
}).superRefine((v, ctx) => {
  if (v.maxSize > TAG_MAX[v.tag]) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["maxSize"], message: `at most ${TAG_MAX[v.tag]} for ${v.tag}` });
  }
});
export type LobbySettings = z.infer<typeof LobbySettings>;

export const CreateLobby = z.object({
  settings: LobbySettings,
  password: z.string().max(32).nullable().optional().transform((p) => (p == null || p.trim() === "" ? null : p)),
});

export const Decision = z.object({ action: z.enum(["accept", "decline", "kick", "block"]) });
export type DecisionAction = z.infer<typeof Decision>["action"];

export interface LobbyTimes {
  status: string;
  host_seen_at: Date;
  expires_at: Date;
}

/** Why an open lobby should close now, or null if it may stay open. */
export function closeReason(l: LobbyTimes, now = new Date()): string | null {
  if (l.status !== "open") return null;
  if (l.expires_at.getTime() <= now.getTime()) return "timeout";
  if (now.getTime() - l.host_seen_at.getTime() > LFG_LIMITS.hostTimeoutSec * 1000) return "offline";
  return null;
}

/** True once a lobby is old enough that the host should hear it will close. */
export function closesSoon(createdAt: Date, now = new Date()): boolean {
  return now.getTime() - createdAt.getTime() >= LFG_LIMITS.warnAfterHours * 3_600_000;
}

/**
 * Whether this player may ask to join, as an error code, or null when they may.
 * existing: their earlier request to this lobby, if any. players: host plus accepted members.
 */
export function joinProblem(args: {
  lobby: LobbyTimes & { host_id: number; max_size: number };
  userId: number;
  players: number;
  existing: string | null;
  pendingElsewhere: number;
  passwordOk: boolean;
  now?: Date;
}): string | null {
  const { lobby, userId, players, existing, pendingElsewhere, passwordOk } = args;
  if (lobby.status !== "open" || closeReason(lobby, args.now)) return "lobby_closed";
  if (lobby.host_id === userId) return "own_lobby";
  if (existing === "blocked") return "blocked";
  if (existing === "accepted") return "already_in";
  if (existing === "pending") return "already_requested";
  if (players >= lobby.max_size) return "lobby_full";
  if (pendingElsewhere >= LFG_LIMITS.maxPendingRequests) return "too_many_requests";
  if (!passwordOk) return "wrong_password";
  return null;
}

/** What a host's decision does to a request in a given state. Null when the decision doesn't apply. */
export function nextStatus(current: string, action: DecisionAction, players: number, maxSize: number): string | null {
  switch (action) {
    case "accept":
      if (current !== "pending") return null;
      return players >= maxSize ? null : "accepted";
    case "decline":
      return current === "pending" ? "declined" : null;
    case "kick":
      return current === "pending" || current === "accepted" ? "kicked" : null;
    case "block":
      return current === "blocked" ? null : "blocked";
  }
}

/** The "/p invite" line the host's mod puts in the chat box. Names are checked so nothing else fits in. */
export function inviteCommand(name: string): string {
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) throw new Error("bad name");
  return "/p invite " + name;
}

/** Slots a waiting host can be offered: free lobbies minus offers already out. */
export function freeSlots(open: number, offersOut: number): number {
  return Math.max(0, LFG_LIMITS.maxOpenLobbies - open - offersOut);
}

// ---- Database helpers

type Q = { query: (text: string, values?: unknown[]) => Promise<{ rows: any[]; rowCount: number | null }> };

/** Host plus accepted members of a lobby, host first. */
export async function lobbyMembers(lobbyId: string, db: Q = pool): Promise<LobbyMember[]> {
  const r = await db.query(
    `SELECT u.id, u.mc_uuid, u.mc_name, u.discord_id, true AS host, l.created_at AS since
       FROM lfg_lobbies l JOIN users u ON u.id = l.host_id WHERE l.id = $1
     UNION ALL
     SELECT u.id, u.mc_uuid, u.mc_name, u.discord_id, false, r.decided_at
       FROM lfg_requests r JOIN users u ON u.id = r.user_id WHERE r.lobby_id = $1 AND r.status = 'accepted'
     ORDER BY host DESC, since`, [lobbyId]);
  return r.rows.map((m) => ({ userId: Number(m.id), mcUuid: m.mc_uuid, name: m.mc_name ?? "?", discordId: m.discord_id, host: m.host }));
}

export async function lobbyEventData(lobbyId: string, db: Q = pool): Promise<LobbyEvent | null> {
  const r = await db.query(
    `SELECT id, name, name_hidden, tag, max_size, region, note, password_hash IS NOT NULL AS locked, status,
            closed_reason, expires_at, discord_message_id, voice_channel_id FROM lfg_lobbies WHERE id = $1`, [lobbyId]);
  const l = r.rows[0];
  if (!l) return null;
  return {
    id: l.id, name: l.name_hidden ? HIDDEN_NAME : l.name, tag: l.tag, maxSize: l.max_size, region: l.region,
    note: l.name_hidden ? "" : l.note, locked: l.locked, status: l.status, reason: l.closed_reason,
    expiresAt: new Date(l.expires_at).toISOString(),
    discordMessageId: l.discord_message_id, voiceChannelId: l.voice_channel_id,
    members: await lobbyMembers(lobbyId, db),
  };
}

/** Sends a lobby event once the change is committed. Never throws: listeners must not break a request. */
export async function announce(kind: LobbyEventKind, lobbyId: string) {
  try {
    const data = await lobbyEventData(lobbyId);
    if (data) lobbyEvent(kind, data);
  } catch (e) {
    console.error(`lfg: couldn't announce ${kind} for ${lobbyId}`, e);
  }
}

/** For the voice-channel feature: remember (or clear) the temporary voice channel made for a lobby. */
export async function setLobbyVoiceChannel(lobbyId: string, channelId: string | null) {
  await pool.query("UPDATE lfg_lobbies SET voice_channel_id = $2 WHERE id = $1", [lobbyId, channelId]);
}

export async function setLobbyDiscordMessage(lobbyId: string, messageId: string | null) {
  await pool.query("UPDATE lfg_lobbies SET discord_message_id = $2 WHERE id = $1", [lobbyId, messageId]);
}

/** Closes one open lobby and tells listeners. Returns false when it was already closed. */
export async function closeLobby(lobbyId: string, reason: string): Promise<boolean> {
  const r = await pool.query(
    `UPDATE lfg_lobbies SET status = 'closed', closed_reason = $2, closed_at = now()
     WHERE id = $1 AND status = 'open' RETURNING id`, [lobbyId, reason]);
  if (!r.rowCount) return false;
  await announce("lobbyClosed", lobbyId);
  await advanceQueue().catch((e) => console.error("lfg queue failed", e));
  return true;
}

/** Serialises everything that counts open lobbies against the cap. Call inside a transaction. */
export const CAPACITY_LOCK = "SELECT pg_advisory_xact_lock(hashtext('lfg_capacity'))";

/** Open lobbies and live claim offers, leaving out one user's own (their lobby and offer free up when they start). */
export async function capacityUse(db: Q, exceptUser: number | null = null) {
  const r = await db.query(
    `SELECT (SELECT count(*)::int FROM lfg_lobbies WHERE status = 'open' AND host_id IS DISTINCT FROM $1) AS open,
            (SELECT count(*)::int FROM lfg_queue WHERE offer_until > now() AND user_id IS DISTINCT FROM $1) AS offers`,
    [exceptUser]);
  return { open: r.rows[0].open as number, offers: r.rows[0].offers as number };
}

/** Drops hosts who stopped waiting or let an offer run out, then offers free slots to the next in line. */
export async function advanceQueue(): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query(CAPACITY_LOCK);
    await client.query(
      `DELETE FROM lfg_queue WHERE seen_at < now() - make_interval(secs => $1) OR offer_until <= now()`,
      [LFG_LIMITS.hostTimeoutSec]);
    const { open, offers } = await capacityUse(client);
    const free = freeSlots(open, offers);
    if (free > 0) {
      await client.query(
        `UPDATE lfg_queue SET offer_until = now() + make_interval(secs => $2)
         WHERE user_id IN (SELECT user_id FROM lfg_queue WHERE offer_until IS NULL ORDER BY queued_at LIMIT $1)`,
        [free, LFG_LIMITS.claimSeconds]);
    }
    await client.query("COMMIT");
  } catch (e) {
    await client.query("ROLLBACK").catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

/** Closes lobbies whose host went quiet or whose time ran out, and moves the waiting line along. */
export async function sweepLobbies(): Promise<string[]> {
  const r = await pool.query(
    `UPDATE lfg_lobbies SET status = 'closed', closed_at = now(),
       closed_reason = CASE WHEN expires_at <= now() THEN 'timeout' ELSE 'offline' END
     WHERE status = 'open' AND (expires_at <= now() OR host_seen_at < now() - make_interval(secs => $1))
     RETURNING id`, [LFG_LIMITS.hostTimeoutSec]);
  const ids = r.rows.map((row) => row.id as string);
  for (const id of ids) await announce("lobbyClosed", id);
  await advanceQueue();
  await pool.query("DELETE FROM lfg_lobbies WHERE status = 'closed' AND closed_at < now() - interval '7 days'");
  return ids;
}

let sweeper: NodeJS.Timeout | null = null;
export function startLobbySweeper(everyMs = 15_000) {
  if (sweeper) return;
  sweeper = setInterval(() => { sweepLobbies().catch((e) => console.error("lfg sweep failed", e)); }, everyMs);
  sweeper.unref();
}
