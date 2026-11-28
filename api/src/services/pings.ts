// Teammate pings: short callouts ("Do head", "Need help") and block markers between the two players of one
// Build Battle Teams game. Everything lives in Redis with a short expiry: nothing about a ping is ever kept.
//
// Who may ping whom: each player's mod says, while it's in a Teams game, who the sidebar names as its
// teammate. A ping only goes through when both players named each other within the last minute and a half,
// so nobody can send pings to a player they aren't building with.

export const PING_KINDS = ["head", "body", "floor", "background", "colours", "help", "done", "block"] as const;
export type PingKind = (typeof PING_KINDS)[number];

/** How long a "we're teammates" claim lasts without a poll to renew it. */
export const TEAM_TTL_MS = 90_000;
/** A ping nobody fetched is gone after this. */
export const INBOX_TTL_MS = 20_000;
/** The most pings waiting for one player at once. */
export const INBOX_MAX = 10;
/** The same callout again only after this long. */
export const KIND_COOLDOWN_MS = 4_000;
/** Any callout again only after this long. */
export const GLOBAL_COOLDOWN_MS = 1_000;

export interface Ping {
  from: string;
  kind: PingKind;
  x?: number;
  y?: number;
  z?: number;
  at: number;
}

/** What the ping rules need from storage. Redis in the server, a Map in the tests. */
export interface PingStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, ttlMs: number): Promise<void>;
  /** Sets the key only if it isn't there yet. True when it was set. */
  claim(key: string, ttlMs: number): Promise<boolean>;
  push(key: string, value: string, ttlMs: number, max: number): Promise<void>;
  /** Reads and removes everything in a list. */
  drain(key: string): Promise<string[]>;
  del(...keys: string[]): Promise<void>;
}

export interface Player {
  id: number;
  name: string;
}

const teamKey = (id: number) => `bb:ping:team:${id}`;
const inboxKey = (id: number) => `bb:ping:inbox:${id}`;
const lower = (s: string) => s.toLowerCase();

/** Records who this player's teammate is, and hands over the pings waiting for them from that teammate. */
export async function poll(store: PingStore, me: Player, teammate: string, mate: Player | null) {
  await store.set(teamKey(me.id), lower(teammate), TEAM_TTL_MS);
  const raw = await store.drain(inboxKey(me.id));
  const pings: Ping[] = [];
  for (const r of raw) {
    try {
      const p = JSON.parse(r) as Ping;
      if (lower(p.from) === lower(teammate)) pings.push(p);   // pings from an old teammate are dropped
    } catch { /* skip */ }
  }
  pings.sort((a, b) => a.at - b.at);
  return { ready: mate !== null && await namesMe(store, mate, me), pings };
}

async function namesMe(store: PingStore, mate: Player, me: Player) {
  return (await store.get(teamKey(mate.id))) === lower(me.name);
}

export type SendResult = { ok: true } | { ok: false; error: "not_teammates" | "cooldown"; status: number };

/**
 * Sends one ping to the teammate. mate is null when the name isn't a linked Better Battle player.
 * Both players must have named each other as teammates.
 */
export async function send(store: PingStore, me: Player, teammate: string, mate: Player | null,
                           kind: PingKind, block: { x: number; y: number; z: number } | null, now = Date.now()): Promise<SendResult> {
  if (!mate || lower(mate.name) !== lower(teammate)) return { ok: false, error: "not_teammates", status: 409 };
  if ((await store.get(teamKey(me.id))) !== lower(mate.name) || !(await namesMe(store, mate, me))) {
    return { ok: false, error: "not_teammates", status: 409 };
  }
  // A callout still cooling down mustn't use up the global cooldown, so it's checked first.
  const kindKey = `bb:ping:cd:${me.id}:${kind}`;
  if ((await store.get(kindKey)) !== null) return { ok: false, error: "cooldown", status: 429 };
  if (!(await store.claim(`bb:ping:cd:${me.id}`, GLOBAL_COOLDOWN_MS))) return { ok: false, error: "cooldown", status: 429 };
  if (!(await store.claim(kindKey, KIND_COOLDOWN_MS))) return { ok: false, error: "cooldown", status: 429 };
  const ping: Ping = { from: me.name, kind, at: now };
  if (kind === "block" && block) Object.assign(ping, block);
  await store.push(inboxKey(mate.id), JSON.stringify(ping), INBOX_TTL_MS, INBOX_MAX);
  return { ok: true };
}

/** The player left the Teams game: forget the teammate claim and anything waiting. */
export async function leave(store: PingStore, me: Player) {
  await store.del(teamKey(me.id), inboxKey(me.id));
}
