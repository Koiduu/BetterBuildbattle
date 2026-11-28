import assert from "node:assert/strict";
import test from "node:test";
import {
  CreateLobby, LFG_LIMITS, closeReason, closesSoon, freeSlots, inviteCommand, joinProblem, nameProblem, nextStatus,
  noteProblem,
} from "./lfg";
import { matchesSearch } from "../routes/lfg";

const now = new Date("2026-11-27T12:00:00Z");
const ago = (sec: number) => new Date(now.getTime() - sec * 1000);
const open = { status: "open", host_seen_at: ago(5), expires_at: new Date(now.getTime() + 3_600_000), host_id: 1, max_size: 4 };

test("lobby names: fine ones pass", () => {
  for (const name of ["Chill GTB", "EU teams, need 1", "Pro grind", "Speed Builders sweats"]) assert.equal(nameProblem(name), null, name);
});

test("lobby names: length, repeats, links, invites, colour codes and bad words are turned down", () => {
  assert.equal(nameProblem("hi"), "too_short");
  assert.equal(nameProblem("x".repeat(LFG_LIMITS.nameMax + 1)), "too_long");
  assert.equal(nameProblem("heyyyyy guys"), "repeated_characters");
  assert.equal(nameProblem("!!!!!!"), "repeated_characters");
  assert.equal(nameProblem("join discord.gg/abc"), "no_links");
  assert.equal(nameProblem("https://evil.example"), "no_links");
  assert.equal(nameProblem("visit mysite.com"), "no_links");
  assert.equal(nameProblem("§4red name"), "bad_characters");
  assert.equal(nameProblem("fuck this lobby"), "inappropriate");
  assert.equal(noteProblem(""), null);
  assert.equal(noteProblem("english only please"), null);
});

test("settings: size is capped by what one game of the tag takes, blank password means none", () => {
  const ok = CreateLobby.parse({ settings: { name: "  Fun   teams  ", tag: "teams", maxSize: 16 }, password: "  " });
  assert.equal(ok.settings.name, "Fun teams");
  assert.equal(ok.settings.region, "any");
  assert.equal(ok.password, null);
  assert.throws(() => CreateLobby.parse({ settings: { name: "Speedy", tag: "spb", maxSize: 9 } }));
  assert.throws(() => CreateLobby.parse({ settings: { name: "Speedy", tag: "minigames", maxSize: 4 } }));
});

test("lobbies close when the host goes quiet or the 30 hours run out, and warn at 25", () => {
  assert.equal(closeReason(open, now), null);
  assert.equal(closeReason({ ...open, host_seen_at: ago(LFG_LIMITS.hostTimeoutSec + 1) }, now), "offline");
  assert.equal(closeReason({ ...open, expires_at: ago(1) }, now), "timeout");
  assert.equal(closeReason({ ...open, status: "closed", expires_at: ago(1) }, now), null);
  assert.equal(closesSoon(ago(24 * 3600), now), false);
  assert.equal(closesSoon(ago(25 * 3600), now), true);
  assert.equal(LFG_LIMITS.lifetimeHours, 30);
  assert.equal(LFG_LIMITS.warnAfterHours, 25);
});

test("join checks", () => {
  const base = { lobby: open, userId: 2, players: 1, existing: null, pendingElsewhere: 0, passwordOk: true, now };
  assert.equal(joinProblem(base), null);
  assert.equal(joinProblem({ ...base, userId: 1 }), "own_lobby");
  assert.equal(joinProblem({ ...base, existing: "blocked" }), "blocked");
  assert.equal(joinProblem({ ...base, existing: "pending" }), "already_requested");
  assert.equal(joinProblem({ ...base, existing: "declined" }), null);   // may ask again (rate-limited)
  assert.equal(joinProblem({ ...base, existing: "kicked" }), null);
  assert.equal(joinProblem({ ...base, players: 4 }), "lobby_full");
  assert.equal(joinProblem({ ...base, pendingElsewhere: LFG_LIMITS.maxPendingRequests }), "too_many_requests");
  assert.equal(joinProblem({ ...base, passwordOk: false }), "wrong_password");
  assert.equal(joinProblem({ ...base, lobby: { ...open, host_seen_at: ago(600) } }), "lobby_closed");
});

test("host decisions", () => {
  assert.equal(nextStatus("pending", "accept", 1, 4), "accepted");
  assert.equal(nextStatus("pending", "accept", 4, 4), null);
  assert.equal(nextStatus("accepted", "accept", 1, 4), null);
  assert.equal(nextStatus("pending", "decline", 1, 4), "declined");
  assert.equal(nextStatus("accepted", "kick", 2, 4), "kicked");
  assert.equal(nextStatus("declined", "kick", 2, 4), null);
  assert.equal(nextStatus("accepted", "block", 2, 4), "blocked");
  assert.equal(nextStatus("blocked", "block", 2, 4), null);
});

test("the invite line only ever holds a Minecraft name", () => {
  assert.equal(inviteCommand("Steve_01"), "/p invite Steve_01");
  assert.throws(() => inviteCommand("Steve; /pay"));
  assert.throws(() => inviteCommand(""));
});

test("waiting line: slots left after open lobbies and live offers", () => {
  assert.equal(freeSlots(LFG_LIMITS.maxOpenLobbies, 0), 0);
  assert.equal(freeSlots(LFG_LIMITS.maxOpenLobbies - 3, 1), 2);
  assert.equal(freeSlots(LFG_LIMITS.maxOpenLobbies + 5, 0), 0);
});

test("search matches name, host and tag", () => {
  const l = { name: "Chill builders", host: "Notch", tag: "gtb" };
  assert.ok(matchesSearch(l, "chill"));
  assert.ok(matchesSearch(l, "notch"));
  assert.ok(matchesSearch(l, "GTB chill"));
  assert.ok(!matchesSearch(l, "teams"));
});
