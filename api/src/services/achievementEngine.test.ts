import assert from "node:assert/strict";
import test from "node:test";
import type { PoolClient } from "pg";
import { catalog, evaluateAchievements } from "./achievementEngine";

test("catalog one-offs are accepted on sync; unknown and server-only claims are revoked", async () => {
  const grants: string[] = [];
  const client = {
    async query(sql: string, params?: unknown[]) {
      if (sql.includes("INSERT INTO user_achievements")) {
        grants.push(params![1] as string);
        return { rowCount: 1, rows: [{}] };
      }
      return { rowCount: 1, rows: [{ themes_built: 0, ss_grades: 0, crowd_90: 0, days_played: 0, precision_100: 0, n: 0 }] };
    },
  } as unknown as PoolClient;
  const ids = catalog.one_offs.filter(o => o.rule !== "ServerOnly").map(o => o.id);
  const result = await evaluateAchievements(client, 1, [...ids, "unknown", "pinned"]);
  assert.deepEqual(result.confirmed, ids);
  assert.deepEqual(grants, ids);
  assert.deepEqual(result.revoked, ["unknown", "pinned"]);
  assert.equal(result.apGained, catalog.one_offs.filter(o => o.rule !== "ServerOnly").reduce((sum, o) => sum + o.points, 0));
});

test("empty claims do not grant one-offs", async () => {
  const client = { async query() { return { rows: [{}] }; } } as unknown as PoolClient;
  const result = await evaluateAchievements(client, 1, []);
  assert.deepEqual(result, { confirmed: [], revoked: [], apGained: 0 });
});

test("flagged users cannot claim one-offs", async () => {
  const client = { async query() { return { rows: [{ flagged: true }] }; } } as unknown as PoolClient;
  const result = await evaluateAchievements(client, 1, ["score_win_200"]);
  assert.deepEqual(result.revoked, ["score_win_200"]);
  assert.deepEqual(result.confirmed, []);
});
