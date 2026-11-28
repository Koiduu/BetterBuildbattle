import assert from "node:assert/strict";
import test from "node:test";
import { leave, poll, send, type PingStore } from "./pings";

/** In-memory stand-in for Redis, with a clock the test moves. */
function memoryStore() {
  let now = 0;
  const vals = new Map<string, { v: string; until: number }>();
  const lists = new Map<string, { v: string[]; until: number }>();
  const live = <T extends { until: number }>(m: Map<string, T>, k: string) => {
    const e = m.get(k);
    if (e && e.until <= now) { m.delete(k); return undefined; }
    return e;
  };
  const store: PingStore = {
    async get(k) { return live(vals, k)?.v ?? null; },
    async set(k, v, ttl) { vals.set(k, { v, until: now + ttl }); },
    async claim(k, ttl) { if (live(vals, k)) return false; vals.set(k, { v: "1", until: now + ttl }); return true; },
    async push(k, v, ttl, max) {
      const l = live(lists, k)?.v ?? [];
      l.push(v);
      lists.set(k, { v: l.slice(-max), until: now + ttl });
    },
    async drain(k) { const l = live(lists, k)?.v ?? []; lists.delete(k); return l; },
    async del(...ks) { for (const k of ks) { vals.delete(k); lists.delete(k); } },
  };
  return { store, tick: (ms: number) => { now += ms; } };
}

const koidu = { id: 1, name: "Koidu" };
const mate = { id: 2, name: "Mate" };
const stranger = { id: 3, name: "Stranger" };

test("a ping needs both players to name each other", async () => {
  const { store } = memoryStore();
  await poll(store, koidu, "Mate", mate);
  assert.deepEqual(await send(store, koidu, "Mate", mate, "head", null), { ok: false, error: "not_teammates", status: 409 });
  const r = await poll(store, mate, "koidu", koidu);   // names are compared without case
  assert.equal(r.ready, true);
  assert.deepEqual(await send(store, koidu, "Mate", mate, "head", null), { ok: true });
  const got = await poll(store, mate, "Koidu", koidu);
  assert.equal(got.pings.length, 1);
  assert.equal(got.pings[0].kind, "head");
  assert.equal((await poll(store, mate, "Koidu", koidu)).pings.length, 0, "a ping is handed over once");
});

test("nobody can ping a player who named someone else", async () => {
  const { store } = memoryStore();
  await poll(store, koidu, "Mate", mate);
  await poll(store, mate, "Koidu", koidu);
  await poll(store, stranger, "Mate", mate);
  assert.equal((await send(store, stranger, "Mate", mate, "help", null)).ok, false);
  assert.equal((await send(store, koidu, "Mate", null, "help", null)).ok, false, "a teammate without the mod gets nothing");
});

test("cooldowns: one per kind and a short global one", async () => {
  const { store, tick } = memoryStore();
  await poll(store, koidu, "Mate", mate);
  await poll(store, mate, "Koidu", koidu);
  assert.equal((await send(store, koidu, "Mate", mate, "head", null)).ok, true);
  assert.equal((await send(store, koidu, "Mate", mate, "body", null)).ok, false, "global cooldown");
  tick(1_100);
  assert.equal((await send(store, koidu, "Mate", mate, "head", null)).ok, false, "same kind again too soon");
  assert.equal((await send(store, koidu, "Mate", mate, "body", null)).ok, true);
  tick(4_100);
  assert.equal((await send(store, koidu, "Mate", mate, "head", null)).ok, true);
});

test("block pings carry the spot, and everything expires", async () => {
  const { store, tick } = memoryStore();
  await poll(store, koidu, "Mate", mate);
  await poll(store, mate, "Koidu", koidu);
  await send(store, koidu, "Mate", mate, "block", { x: 10, y: 64, z: -3 });
  const got = (await poll(store, mate, "Koidu", koidu)).pings[0];
  assert.deepEqual([got.x, got.y, got.z], [10, 64, -3]);
  tick(2_000);
  await send(store, koidu, "Mate", mate, "done", null);
  tick(25_000);
  assert.equal((await poll(store, mate, "Koidu", koidu)).pings.length, 0, "unread pings expire");
  tick(100_000);
  assert.equal((await send(store, koidu, "Mate", mate, "help", null)).ok, false, "teammate claims expire");
});

test("leaving forgets the teammate", async () => {
  const { store } = memoryStore();
  await poll(store, koidu, "Mate", mate);
  await poll(store, mate, "Koidu", koidu);
  await leave(store, mate);
  assert.equal((await send(store, koidu, "Mate", mate, "help", null)).ok, false);
});
