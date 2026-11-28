// Uploads approved custom heads with the skin account, one at a time and about a minute apart, and reads
// back the signed texture. Only heads the owner approved ever get here.
//
// Each tick does at most one of:
//  1. a skin was set last time: read the session profile until it shows that texture, then save the signed
//     value. No new skin is set until then, or the next upload would replace it before it was signed.
//  2. otherwise set the skin of the oldest approved head whose backoff has passed.
// Drawings that were uploaded before (same PNG hash) become ready straight away, without an upload.
import { pool } from "../db/pool";
import {
  SkinApiError, forgetSkinSession, readSignedTexture, skinConfigured, skinSession, uploadSkin,
} from "../services/skinAccount";

const TICK_MS = 60_000;
const SIGN_TIMEOUT_MS = 10 * 60_000;   // give up waiting for a signature and upload again after this long

/** Wait before the next try after `attempts` failures: 1, 2, 4 ... minutes, at most an hour. */
export function backoffMs(attempts: number) {
  return Math.min(60 * 60_000, 60_000 * 2 ** Math.max(0, attempts - 1));
}

const state = {
  pausedUntil: 0,
  lastError: null as string | null,
  lastErrorAt: null as Date | null,
  lastUploadAt: null as Date | null,
  running: false,
};

export async function skinUploaderStatus() {
  const acct = await pool.query("SELECT mc_name, mc_uuid, updated_at FROM skin_account WHERE id = 1").catch(() => ({ rows: [] as any[] }));
  return {
    configured: skinConfigured(),
    signedIn: acct.rows.length > 0 || !!process.env.SKIN_MS_REFRESH_TOKEN,
    account: acct.rows[0] ? { name: acct.rows[0].mc_name, uuid: acct.rows[0].mc_uuid, tokenSavedAt: acct.rows[0].updated_at } : null,
    pausedUntil: state.pausedUntil > Date.now() ? new Date(state.pausedUntil) : null,
    lastError: state.lastError,
    lastErrorAt: state.lastErrorAt,
    lastUploadAt: state.lastUploadAt,
  };
}

function noteError(e: unknown) {
  state.lastError = String(e instanceof Error ? e.message : e).slice(0, 300);
  state.lastErrorAt = new Date();
  if (e instanceof SkinApiError) {
    if (e.status === 401) forgetSkinSession();
    if (e.retryAfterMs != null || e.status === 429) state.pausedUntil = Date.now() + Math.max(e.retryAfterMs ?? 0, 5 * 60_000);
    // A broken sign-in won't fix itself: check every half hour rather than every minute.
    else if (e.auth) state.pausedUntil = Date.now() + 30 * 60_000;
  }
}

/** Heads whose drawing already has a signed texture become ready without an upload. */
async function readyDuplicates() {
  await pool.query(
    `UPDATE custom_heads h SET status = 'ready', texture_hash = t.texture_hash, texture_value = t.texture_value,
       texture_signature = t.texture_signature, ready_at = now(), uploaded_at = NULL, last_error = NULL
     FROM skin_textures t
     WHERE h.status = 'approved' AND h.png_sha256 = t.png_sha256 AND t.texture_signature IS NOT NULL`);
}

async function finishSigned(sha256: Buffer, hash: string, value: string, signature: string) {
  await pool.query(
    `INSERT INTO skin_textures (png_sha256, texture_hash, texture_value, texture_signature) VALUES ($1, $2, $3, $4)
     ON CONFLICT (png_sha256) DO UPDATE SET texture_hash = EXCLUDED.texture_hash,
       texture_value = EXCLUDED.texture_value, texture_signature = EXCLUDED.texture_signature`,
    [sha256, hash, value, signature]);
  await readyDuplicates();
}

/** Step 1. Returns true if a head is still waiting for its signature (so nothing new is uploaded). */
async function collectSignature(): Promise<boolean> {
  const r = await pool.query(
    `SELECT id, png_sha256, texture_hash, uploaded_at FROM custom_heads
     WHERE status = 'approved' AND uploaded_at IS NOT NULL ORDER BY uploaded_at LIMIT 1`);
  if (!r.rowCount) return false;
  const head = r.rows[0];
  const signed = await readSignedTexture();
  if (signed.hash === head.texture_hash) {
    await finishSigned(head.png_sha256, head.texture_hash, signed.value, signed.signature);
    console.log(`Custom head ${head.id} is ready (${head.texture_hash})`);
    return false;
  }
  if (Date.now() - new Date(head.uploaded_at).getTime() > SIGN_TIMEOUT_MS) {
    await pool.query(
      `UPDATE custom_heads SET uploaded_at = NULL, texture_hash = NULL, last_error = 'never showed up signed; uploading again'
       WHERE id = $1`, [head.id]);
    return false;
  }
  return true;   // the session server still shows the old skin; look again next tick
}

/** Step 2. */
async function uploadNext() {
  const r = await pool.query(
    `SELECT id, png, png_sha256, attempts FROM custom_heads
     WHERE status = 'approved' AND uploaded_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= now())
     ORDER BY reviewed_at, id LIMIT 1`);
  if (!r.rowCount) return;
  const head = r.rows[0];
  try {
    const hash = await uploadSkin(head.png);
    state.lastUploadAt = new Date();
    await pool.query(
      `UPDATE custom_heads SET texture_hash = $2, uploaded_at = now(), attempts = attempts + 1, last_error = NULL
       WHERE id = $1`, [head.id, hash]);
    // Mojang usually signs it within seconds. If not, the next tick looks again.
    await new Promise((res) => setTimeout(res, 5_000));
    await collectSignature().catch(noteError);
  } catch (e) {
    const attempts = head.attempts + 1;
    await pool.query(
      `UPDATE custom_heads SET attempts = $2, next_attempt_at = now() + $3::int * interval '1 millisecond', last_error = $4
       WHERE id = $1`,
      [head.id, attempts, backoffMs(attempts), String(e instanceof Error ? e.message : e).slice(0, 300)]);
    throw e;
  }
}

export async function skinUploadTick() {
  if (state.running || !skinConfigured() || Date.now() < state.pausedUntil) return;
  state.running = true;
  try {
    await readyDuplicates();
    const queued = await pool.query(
      "SELECT 1 FROM custom_heads WHERE status = 'approved' LIMIT 1");
    if (!queued.rowCount) return;          // nothing to do: don't even sign in
    await skinSession();
    if (await collectSignature()) return;
    await uploadNext();
  } catch (e) {
    noteError(e);
    console.error("Skin uploader:", state.lastError);
  } finally {
    state.running = false;
  }
}

export function startSkinUploader(signal: AbortSignal) {
  if (!skinConfigured()) {
    console.warn("SKIN_MS_CLIENT_ID / SKIN_TOKEN_KEY not set: approved custom heads won't be uploaded");
    return;
  }
  const timer = setInterval(() => void skinUploadTick(), TICK_MS);
  signal.addEventListener("abort", () => clearInterval(timer));
  void skinUploadTick();
}
