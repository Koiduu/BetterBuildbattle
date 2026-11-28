// Custom heads: submissions, the owner's review queue, and the player's collection.
// Life of a head: pending (waiting for review) -> approved (in the upload queue) -> ready (signed texture),
// or pending -> rejected. Pending, approved and ready heads count toward the plan's limit; rejected and
// deleted ones give the slot back.
import { pool } from "../db/pool";
import { fail } from "../http";
import { limit, planOf } from "../lib/plans";
import { flaggedWord } from "../lib/wordFilter";
import { BadHeadImage, decodePngInput, normalizeHeadPng } from "./headSkin";

export type HeadStatus = "pending" | "approved" | "ready" | "rejected";

const dataUrl = (png: Buffer) => "data:image/png;base64," + png.toString("base64");

/** How many heads count toward the limit, how many were sent in the last 24 hours, and the plan's caps. */
export async function headAllowance(userId: number) {
  const r = await pool.query(
    `SELECT u.plan, u.plan_until, u.plan_comped,
            (SELECT count(*)::int FROM custom_heads WHERE user_id = u.id AND status <> 'rejected') AS used,
            (SELECT count(*)::int FROM custom_heads WHERE user_id = u.id AND created_at > now() - interval '1 day') AS today
     FROM users u WHERE u.id = $1`, [userId]);
  if (!r.rowCount) fail(404, "not_found");
  const plan = planOf(r.rows[0]);
  return {
    plan,
    used: r.rows[0].used as number,
    limit: limit(plan, "customHeads"),
    today: r.rows[0].today as number,
    perDay: limit(plan, "customHeadsPerDay"),
  };
}

export async function submitHead(userId: number, rawName: string, pngInput: string) {
  const name = rawName.trim().replace(/\s+/g, " ");
  if (!name || name.length > 32) fail(400, "bad_name");
  if (flaggedWord(name)) fail(400, "name_not_allowed");
  let normalized;
  try {
    normalized = await normalizeHeadPng(decodePngInput(pngInput));
  } catch (e) {
    if (e instanceof BadHeadImage) fail(400, "bad_image", { reason: e.message });
    throw e;
  }
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    // Lock the user's row so two submissions at once can't both slip under the limit.
    await client.query("SELECT 1 FROM users WHERE id = $1 FOR UPDATE", [userId]);
    const a = await headAllowance(userId);
    if (a.used >= a.limit) fail(400, "head_limit_reached", { limit: a.limit });
    if (a.today >= a.perDay) fail(429, "daily_head_limit", { perDay: a.perDay });
    const dup = await client.query(
      "SELECT 1 FROM custom_heads WHERE user_id = $1 AND png_sha256 = $2 AND status <> 'rejected'", [userId, normalized.sha256]);
    if (dup.rowCount) fail(409, "already_have_this_head");
    const r = await client.query(
      `INSERT INTO custom_heads (user_id, name, png, png_sha256) VALUES ($1, $2, $3, $4) RETURNING id`,
      [userId, name, normalized.png, normalized.sha256]);
    await client.query("COMMIT");
    return { id: Number(r.rows[0].id), status: "pending" as HeadStatus };
  } catch (e) {
    await client.query("ROLLBACK").catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

/** The player's heads, newest first, with where each one is in its queue. */
export async function myHeads(userId: number) {
  const [allowance, rows] = await Promise.all([
    headAllowance(userId),
    pool.query(
      `SELECT h.id, h.name, h.status, h.png, h.texture_hash, h.reject_reason, h.created_at, h.ready_at,
              h.uploaded_at IS NOT NULL AS uploading,
              CASE h.status
                WHEN 'pending' THEN (SELECT count(*)::int FROM custom_heads q
                                     WHERE q.status = 'pending' AND (q.created_at, q.id) <= (h.created_at, h.id))
                WHEN 'approved' THEN (SELECT count(*)::int FROM custom_heads q
                                      WHERE q.status = 'approved' AND (q.reviewed_at, q.id) <= (h.reviewed_at, h.id))
              END AS position
       FROM custom_heads h WHERE h.user_id = $1 ORDER BY h.created_at DESC LIMIT 200`, [userId]),
  ]);
  return {
    ...allowance,
    heads: rows.rows.map((h) => ({
      id: Number(h.id),
      name: h.name,
      status: h.status as HeadStatus,
      position: h.position ?? null,           // 1 = next to be reviewed / uploaded
      uploading: h.uploading,
      png: dataUrl(h.png),
      textureHash: h.status === "ready" ? h.texture_hash : null,
      rejectReason: h.reject_reason,
      createdAt: h.created_at,
      readyAt: h.ready_at,
    })),
  };
}

export async function deleteHead(userId: number, id: number) {
  const r = await pool.query("DELETE FROM custom_heads WHERE id = $1 AND user_id = $2", [id, userId]);
  if (!r.rowCount) fail(404, "not_found");
  return { ok: true };
}

/** Ready heads for the mod's "My heads" section. */
export async function readyHeads(userId: number) {
  const r = await pool.query(
    `SELECT id, name, texture_hash FROM custom_heads
     WHERE user_id = $1 AND status = 'ready' AND texture_hash IS NOT NULL ORDER BY ready_at DESC, id DESC`, [userId]);
  return r.rows.map((h) => ({ id: Number(h.id), name: h.name, hash: h.texture_hash }));
}

// ---- the owner's review queue

export async function reviewQueue(status: HeadStatus) {
  const order = status === "pending" ? "h.created_at ASC" : status === "approved" ? "h.reviewed_at ASC" : "h.reviewed_at DESC NULLS LAST";
  const r = await pool.query(
    `SELECT h.id, h.name, h.status, h.png, h.created_at, h.reviewed_at, h.reject_reason, h.attempts, h.last_error,
            h.next_attempt_at, h.uploaded_at, h.texture_hash, u.mc_name, u.mc_uuid, u.plan, u.plan_until, u.plan_comped,
            EXISTS (SELECT 1 FROM skin_textures t WHERE t.png_sha256 = h.png_sha256) AS already_uploaded,
            (SELECT count(*)::int FROM custom_heads o WHERE o.user_id = h.user_id AND o.status = 'rejected') AS user_rejected,
            (SELECT count(*)::int FROM custom_heads o WHERE o.user_id = h.user_id AND o.status = 'ready') AS user_ready
     FROM custom_heads h JOIN users u ON u.id = h.user_id
     WHERE h.status = $1 ORDER BY ${order} LIMIT 300`, [status]);
  return r.rows.map((h) => ({
    id: Number(h.id), name: h.name, status: h.status, png: dataUrl(h.png),
    createdAt: h.created_at, reviewedAt: h.reviewed_at, rejectReason: h.reject_reason,
    attempts: h.attempts, lastError: h.last_error, nextAttemptAt: h.next_attempt_at, uploadedAt: h.uploaded_at,
    textureHash: h.texture_hash,
    player: { name: h.mc_name, uuid: h.mc_uuid, plan: planOf(h), ready: h.user_ready, rejected: h.user_rejected },
    alreadyUploaded: h.already_uploaded,
  }));
}

export async function approveHead(id: number) {
  const r = await pool.query(
    `UPDATE custom_heads SET status = 'approved', reviewed_at = now(), reject_reason = NULL,
       attempts = 0, next_attempt_at = NULL, last_error = NULL
     WHERE id = $1 AND status = 'pending' RETURNING id`, [id]);
  if (!r.rowCount) fail(409, "not_pending");
  return { ok: true };
}

export async function rejectHead(id: number, reason: string | null) {
  // An approved head can still be pulled back before it's uploaded.
  const r = await pool.query(
    `UPDATE custom_heads SET status = 'rejected', reviewed_at = now(), reject_reason = $2
     WHERE id = $1 AND (status = 'pending' OR (status = 'approved' AND uploaded_at IS NULL)) RETURNING id`,
    [id, reason?.trim() || null]);
  if (!r.rowCount) fail(409, "cannot_reject_now");
  return { ok: true };
}

/** Try an approved head's upload again now instead of waiting out its backoff. */
export async function retryHead(id: number) {
  await pool.query("UPDATE custom_heads SET next_attempt_at = NULL WHERE id = $1 AND status = 'approved'", [id]);
  return { ok: true };
}

export async function queueCounts() {
  const r = await pool.query(
    `SELECT status, count(*)::int AS n FROM custom_heads WHERE status IN ('pending', 'approved') GROUP BY status`);
  const out = { pending: 0, approved: 0 };
  for (const row of r.rows) out[row.status as "pending" | "approved"] = row.n;
  return out;
}
