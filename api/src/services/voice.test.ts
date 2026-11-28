import assert from "node:assert/strict";
import test from "node:test";
import {
  EMPTY_MS, MAX_LIFETIME_MS, WAIT_MS,
  channelLabel, channelUrl, lobbyKey, sweepAction, teamKey, teamMembers,
} from "./voice";

test("a team is you plus the players you named, once each", () => {
  assert.deepEqual(teamMembers("Koidu", ["Aspir3"]), ["Aspir3", "Koidu"]);
  assert.deepEqual(teamMembers("Koidu", ["aspir3", "ASPIR3", "koidu"]), ["aspir3", "Koidu"]);
});

test("no voice alone, with bad names or with too many people", () => {
  assert.equal(teamMembers("Koidu", []), null);
  assert.equal(teamMembers("Koidu", ["koidu"]), null);
  assert.equal(teamMembers("Koidu", ["not a name"]), null);
  assert.equal(teamMembers("Koidu", ["way_too_long_name_x"]), null);
  assert.equal(teamMembers("Koidu", ["a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"]), null);
  assert.ok(teamMembers("Koidu", ["a1", "a2", "a3", "a4", "a5", "a6", "a7"]));
});

test("both teammates get the same group whoever presses first", () => {
  const fromKoidu = teamKey(teamMembers("Koidu", ["Aspir3"])!);
  const fromAspir3 = teamKey(teamMembers("aspir3", ["KOIDU"])!);
  assert.equal(fromKoidu, fromAspir3);
  assert.equal(fromKoidu, "team:aspir3,koidu");
});

test("naming someone extra makes a different group, so nobody is pulled into a call", () => {
  const pair = teamKey(teamMembers("Koidu", ["Aspir3"])!);
  const withStranger = teamKey(teamMembers("Stranger", ["Koidu", "Aspir3"])!);
  assert.notEqual(pair, withStranger);
  assert.notEqual(pair, lobbyKey("aspir3,koidu"));
});

test("channel names", () => {
  assert.equal(channelLabel("team", ["Aspir3", "Koidu"]), "Teams · Aspir3 + Koidu");
  assert.equal(channelLabel("team", ["A", "B", "C"]), "Party · A + B + C");
  assert.equal(channelLabel("team", ["A", "B", "C", "D", "E", "F"]), "Party · A + B + C + 3 more");
  assert.equal(channelLabel("lobby", [], "Koidu"), "Lobby · Koidu's lobby");
  assert.ok(channelLabel("team", Array.from({ length: 8 }, () => "x".repeat(16))).length <= 100);
  assert.equal(channelUrl("1", "2"), "https://discord.com/channels/1/2");
});

test("cleanup: empty channels go after a few minutes, busy ones stay", () => {
  const t0 = 1_000_000_000;
  const open = { channelId: "c", createdAt: t0, emptySince: null, expiresAt: t0 + MAX_LIFETIME_MS };
  assert.equal(sweepAction(open, 2, t0 + 60_000), "keep");
  assert.equal(sweepAction(open, 0, t0 + 60_000), "mark_empty");
  const empty = { ...open, emptySince: t0 };
  assert.equal(sweepAction(empty, 0, t0 + EMPTY_MS - 1), "keep");
  assert.equal(sweepAction(empty, 0, t0 + EMPTY_MS), "delete");
  assert.equal(sweepAction(empty, 1, t0 + EMPTY_MS), "mark_used");
});

test("cleanup: expired, removed in Discord, or never joined by a second player", () => {
  const t0 = 1_000_000_000;
  const open = { channelId: "c", createdAt: t0, emptySince: null, expiresAt: t0 + MAX_LIFETIME_MS };
  assert.equal(sweepAction(open, 3, t0 + MAX_LIFETIME_MS), "delete");
  assert.equal(sweepAction(open, null, t0 + 1), "delete");
  const waiting = { ...open, channelId: null };
  assert.equal(sweepAction(waiting, 0, t0 + WAIT_MS - 1), "keep");
  assert.equal(sweepAction(waiting, 0, t0 + WAIT_MS), "delete");
});
