// Temporary Discord voice channels for players who already chose each other: a teammate, party members,
// or the players an LFG lobby host accepted. Never strangers.
//
// Consent is mutual: a player is let into a channel only after they asked for it from their own mod, and in
// "team" scope every member must name the same group (so nobody can pull someone in by typing their name).
// Voice is opt-in (off until the player turns it on on the account page) and needs a linked Discord account
// that is in the Better Battle server, where Discord's own blocking and reporting apply.
// Nothing is recorded; the rows here only track open channels and go away with them.
import { pool } from "../db/pool";
import { fail } from "../http";

export const NOTICE = "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.";
export const MAX_TEAM = 8;
/** Waiting for a second member gives up after this long. */
export const WAIT_MS = 10 * 60_000;
/** An open channel with nobody in it is deleted after this long. Also the time to join a new one. */
export const EMPTY_MS = 5 * 60_000;
/** Hard cap on any channel. */
export const MAX_LIFETIME_MS = 4 * 60 * 60_000;

const NAME = /^[A-Za-z0-9_]{1,16}$/;

export type Scope = "team" | "lobby";

/** The Discord side. bot/voice.ts implements it when the bot runs; tests use a fake. */
export interface VoiceBackend {
  guildId: string;
  /** Where to send people who aren't in the server yet. */
  inviteUrl: string | null;
  isMember(discordId: string): Promise<boolean>;
  /** Makes a private voice channel only these Discord users can see and join. Returns its id. */
  create(label: string, discordIds: string[]): Promise<string>;
  /** Replaces who may join. */
  setMembers(channelId: string, discordIds: string[]): Promise<void>;
  remove(channelId: string): Promise<void>;
  /** People connected right now, or null if the channel no longer exists. */
  connected(channelId: string): Promise<number | null>;
}

/**
 * Accepted members of an LFG lobby, host included. The LFG lobbies branch registers this with setLobbySource
 * and calls endLobbyVoice when a lobby closes. Until then "Whole lobby" answers lobbies_unavailable.
 */
export interface LobbySource {
  members(lobbyId: string): Promise<{ host: string; userIds: number[] } | null>;
}

let backend: VoiceBackend | null = null;
let lobbies: LobbySource | null = null;
export const setVoiceBackend = (b: VoiceBackend | null) => { backend = b; };
export const setLobbySource = (s: LobbySource | null) => { lobbies = s; };

// ---------- pure rules ----------

/** Self plus the named players, checked, de-duplicated and sorted, or null if that isn't a valid group. */
export function teamMembers(self: string, others: string[]): string[] | null {
  const seen = new Map<string, string>();
  for (const name of [self, ...others]) {
    if (!NAME.test(name)) return null;
    if (!seen.has(name.toLowerCase())) seen.set(name.toLowerCase(), name);
  }
  if (seen.size < 2 || seen.size > MAX_TEAM) return null;
  return [...seen.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([, name]) => name);
}

/** Same members in any order and any capitalisation give the same key. */
export const teamKey = (members: string[]) => "team:" + members.map((m) => m.toLowerCase()).sort().join(",");
export const lobbyKey = (lobbyId: string) => "lobby:" + lobbyId;

/** "Teams · Aspir3 + Koidu", "Party · A + B + C", "Lobby · Koidu's lobby". Discord allows 100 characters. */
export function channelLabel(scope: Scope, names: string[], host?: string): string {
  if (scope === "lobby") return `Lobby · ${host ?? "Better Battle"}'s lobby`;
  const head = names.length > 2 ? "Party" : "Teams";
  const shown = names.length > 4 ? [...names.slice(0, 3), `${names.length - 3} more`] : names;
  return `${head} · ${shown.join(" + ")}`.slice(0, 100);
}

export const channelUrl = (guildId: string, channelId: string) => `https://discord.com/channels/${guildId}/${channelId}`;

export type SweepAction = "keep" | "mark_empty" | "mark_used" | "delete";

/** What the cleanup does with one tracked channel. */
export function sweepAction(row: {
  channelId: string | null; createdAt: number; emptySince: number | null; expiresAt: number;
}, connected: number | null, now: number): SweepAction {
  if (now >= row.expiresAt) return "delete";
  if (!row.channelId) return now - row.createdAt >= WAIT_MS ? "delete" : "keep";
  if (connected === null) return "delete";                     // someone removed it in Discord
  if (connected > 0) return row.emptySince === null ? "keep" : "mark_used";
  if (row.emptySince === null) return "mark_empty";
  return now - row.emptySince >= EMPTY_MS ? "delete" : "keep";
}

// ---------- database flow ----------

/** One change at a time per group, so two members pressing Voice together make one channel. */
const locks = new Map<string, Promise<unknown>>();
async function locked<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const before = locks.get(key) ?? Promise.resolve();
  const run = before.catch(() => {}).then(fn);
  locks.set(key, run);
  try { return await run; } finally { if (locks.get(key) === run) locks.delete(key); }
}

function needBackend(): VoiceBackend {
  if (!backend) return fail(503, "voice_unavailable");
  return backend;
}

type Me = { name: string; discordId: string };

/** The asking player, with the reason they can't use voice yet if there is one. */
async function eligibleMe(b: VoiceBackend, userId: number): Promise<Me> {
  const r = await pool.query(
    `SELECT u.mc_name, u.voice_enabled_at,
            COALESCE(u.discord_id, (SELECT l.discord_id FROM discord_links l WHERE l.mc_uuid = u.mc_uuid LIMIT 1)) AS discord_id
     FROM users u WHERE u.id = $1`, [userId]);
  const row = r.rows[0];
  if (!row?.mc_name) return fail(400, "no_minecraft");
  if (!row.voice_enabled_at) return fail(403, "voice_off");
  if (!row.discord_id) return fail(403, "discord_required");
  if (!await b.isMember(row.discord_id)) return fail(403, "not_in_server", { invite: b.inviteUrl });
  return { name: String(row.mc_name), discordId: String(row.discord_id) };
}

/** Rebuilds a group's channel after members changed: makes it at two, updates it, or deletes it below two. */
async function settle(b: VoiceBackend, key: string) {
  const ch = await pool.query("SELECT channel_id, label FROM voice_channels WHERE group_key = $1", [key]);
  if (!ch.rowCount) return;
  const members = await pool.query("SELECT discord_id FROM voice_members WHERE group_key = $1", [key]);
  const ids = members.rows.map((m) => String(m.discord_id));
  const channelId: string | null = ch.rows[0].channel_id;
  if (ids.length === 0) {
    if (channelId) await b.remove(channelId).catch(() => {});
    await pool.query("DELETE FROM voice_channels WHERE group_key = $1", [key]);
  } else if (!channelId && ids.length >= 2) {
    const id = await b.create(ch.rows[0].label, ids);
    await pool.query("UPDATE voice_channels SET channel_id = $2, empty_since = now() WHERE group_key = $1", [key, id]);
  } else if (channelId && ids.length < 2) {
    await b.remove(channelId).catch(() => {});
    await pool.query("DELETE FROM voice_channels WHERE group_key = $1", [key]);
  } else if (channelId) {
    await b.setMembers(channelId, ids);
  }
}

async function leaveCurrent(b: VoiceBackend, userId: number, exceptKey?: string) {
  const current = await pool.query("SELECT group_key FROM voice_members WHERE user_id = $1", [userId]);
  const key: string | undefined = current.rows[0]?.group_key;
  if (!key || key === exceptKey) return;
  await locked(key, async () => {
    await pool.query("DELETE FROM voice_members WHERE group_key = $1 AND user_id = $2", [key, userId]);
    await settle(b, key);
  });
}

export type VoiceStatus = {
  status: "none" | "waiting" | "ready";
  scope?: Scope;
  label?: string;
  url?: string;
  /** Team scope: named players who haven't pressed Voice yet. Never says why. */
  waitingFor?: string[];
  notice: string;
};

export async function voiceStatus(userId: number): Promise<VoiceStatus> {
  const b = needBackend();
  const r = await pool.query(
    `SELECT c.group_key, c.scope, c.label, c.channel_id FROM voice_members m
     JOIN voice_channels c USING (group_key) WHERE m.user_id = $1`, [userId]);
  const row = r.rows[0];
  if (!row) return { status: "none", notice: NOTICE };
  const members = await pool.query("SELECT mc_name FROM voice_members WHERE group_key = $1", [row.group_key]);
  const joined = new Set(members.rows.map((m) => String(m.mc_name).toLowerCase()));
  const waitingFor = row.scope === "team"
    ? String(row.group_key).slice(5).split(",").filter((n) => !joined.has(n))
    : [];
  return {
    status: row.channel_id ? "ready" : "waiting",
    scope: row.scope,
    label: row.label,
    url: row.channel_id ? channelUrl(b.guildId, row.channel_id) : undefined,
    waitingFor,
    notice: NOTICE,
  };
}

/** Pressing Voice: join (or start) the group's channel. */
export async function requestVoice(userId: number, req: { scope: Scope; members?: string[]; lobbyId?: string }): Promise<VoiceStatus> {
  const b = needBackend();
  const me = await eligibleMe(b, userId);
  let key: string, label: string;
  if (req.scope === "team") {
    const members = teamMembers(me.name, req.members ?? []);
    if (!members) return fail(400, "bad_members");
    key = teamKey(members);
    label = channelLabel("team", members);
  } else {
    if (!lobbies) return fail(501, "lobbies_unavailable");
    if (!req.lobbyId) return fail(400, "lobby_required");
    const lobby = await lobbies.members(req.lobbyId);
    if (!lobby || !lobby.userIds.includes(userId)) return fail(403, "not_in_lobby");
    key = lobbyKey(req.lobbyId);
    label = channelLabel("lobby", [], lobby.host);
  }
  await leaveCurrent(b, userId, key);
  await locked(key, async () => {
    await pool.query(
      `INSERT INTO voice_channels (group_key, scope, label, expires_at)
       VALUES ($1, $2, $3, now() + make_interval(secs => $4)) ON CONFLICT (group_key) DO NOTHING`,
      [key, req.scope, label, MAX_LIFETIME_MS / 1000]);
    await pool.query(
      `INSERT INTO voice_members (group_key, user_id, discord_id, mc_name) VALUES ($1, $2, $3, $4)
       ON CONFLICT (group_key, user_id) DO UPDATE SET discord_id = EXCLUDED.discord_id, mc_name = EXCLUDED.mc_name`,
      [key, userId, me.discordId, me.name]);
    await settle(b, key);
  });
  return voiceStatus(userId);
}

/** Leaving voice (the player asked, or their Teams game ended). The channel goes when fewer than two remain. */
export async function leaveVoice(userId: number) {
  await leaveCurrent(needBackend(), userId);
  return { ok: true };
}

/** For the account page: someone who turns voice off or unlinks Discord leaves voice straight away. */
export async function dropFromVoice(userId: number) {
  if (backend) await leaveCurrent(backend, userId).catch((e) => console.error("voice leave failed", e));
}

/** For the LFG lobbies branch: call when a lobby closes. */
export async function endLobbyVoice(lobbyId: string) {
  const b = backend;
  const key = lobbyKey(lobbyId);
  await locked(key, async () => {
    const r = await pool.query("DELETE FROM voice_channels WHERE group_key = $1 RETURNING channel_id", [key]);
    const channelId = r.rows[0]?.channel_id;
    if (b && channelId) await b.remove(channelId).catch(() => {});
  });
}

/** For the LFG lobbies branch: call when a member leaves or is removed from a lobby. */
export async function leaveLobbyVoice(lobbyId: string, userId: number) {
  const b = backend;
  if (!b) return;
  const key = lobbyKey(lobbyId);
  await locked(key, async () => {
    await pool.query("DELETE FROM voice_members WHERE group_key = $1 AND user_id = $2", [key, userId]);
    await settle(b, key);
  });
}

/** Runs every minute while the bot is up: deletes empty, expired and abandoned channels. */
export async function sweepVoice(now = Date.now()) {
  const b = backend;
  if (!b) return;
  const rows = await pool.query("SELECT group_key, channel_id, created_at, empty_since, expires_at FROM voice_channels");
  for (const row of rows.rows) {
    const key = String(row.group_key);
    await locked(key, async () => {
      const connected = row.channel_id ? await b.connected(row.channel_id) : 0;
      const action = sweepAction({
        channelId: row.channel_id,
        createdAt: new Date(row.created_at).getTime(),
        emptySince: row.empty_since ? new Date(row.empty_since).getTime() : null,
        expiresAt: new Date(row.expires_at).getTime(),
      }, connected, now);
      if (action === "delete") {
        if (row.channel_id) await b.remove(row.channel_id).catch(() => {});
        await pool.query("DELETE FROM voice_channels WHERE group_key = $1", [key]);
      } else if (action === "mark_empty") {
        await pool.query("UPDATE voice_channels SET empty_since = now() WHERE group_key = $1", [key]);
      } else if (action === "mark_used") {
        await pool.query("UPDATE voice_channels SET empty_since = NULL WHERE group_key = $1", [key]);
      }
    }).catch((e) => console.error(`voice sweep failed for ${key}`, e));
  }
}
