// The dedicated "skin" Minecraft account that turns approved custom heads into Mojang-signed textures.
// It is not AspireBot: if Mojang ever flags it, sightings keep working.
//
// Sign-in: the owner runs `bb skin-login` once (scripts/skin-login.ts), which uses Microsoft's device code
// flow with our own Azure app (SKIN_MS_CLIENT_ID). The refresh token it gets is stored encrypted in the
// skin_account table, because Microsoft hands out a new one on every use. From then on the server goes
// refresh token -> Microsoft access token -> Xbox Live -> XSTS -> Minecraft token (valid for about a day)
// by itself. SKIN_MS_REFRESH_TOKEN in .env is only a seed for when the table is empty.
import crypto from "node:crypto";
import { pool } from "../db/pool";
import { MC_SERVICES, MOJANG_SESSION, MS_LOGIN, XBL_AUTH, XSTS_AUTH } from "../lib/upstream";

export const MS_SCOPE = "XboxLive.signin offline_access";

/** A failed call to Microsoft, Xbox or Mojang. retryAfterMs is set when the service asked us to wait. */
export class SkinApiError extends Error {
  constructor(message: string, public status = 0, public retryAfterMs: number | null = null, public auth = false) {
    super(message);
  }
}

export function skinConfigured() {
  return !!process.env.SKIN_MS_CLIENT_ID && (process.env.SKIN_TOKEN_KEY ?? "").length >= 16;
}

// ---- refresh token storage (AES-256-GCM, key derived from SKIN_TOKEN_KEY)

function key() {
  const secret = process.env.SKIN_TOKEN_KEY ?? "";
  if (secret.length < 16) throw new SkinApiError("SKIN_TOKEN_KEY is missing or shorter than 16 characters");
  return crypto.createHash("sha256").update(secret).digest();
}

export function encryptToken(plain: string): Buffer {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key(), iv);
  const body = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]);
}

export function decryptToken(blob: Buffer): string {
  const decipher = crypto.createDecipheriv("aes-256-gcm", key(), blob.subarray(0, 12));
  decipher.setAuthTag(blob.subarray(12, 28));
  return Buffer.concat([decipher.update(blob.subarray(28)), decipher.final()]).toString("utf8");
}

export async function saveRefreshToken(token: string, profile?: { uuid: string; name: string }) {
  await pool.query(
    `INSERT INTO skin_account (id, refresh_token_enc, mc_uuid, mc_name, updated_at) VALUES (1, $1, $2, $3, now())
     ON CONFLICT (id) DO UPDATE SET refresh_token_enc = EXCLUDED.refresh_token_enc,
       mc_uuid = COALESCE(EXCLUDED.mc_uuid, skin_account.mc_uuid), mc_name = COALESCE(EXCLUDED.mc_name, skin_account.mc_name),
       updated_at = now()`,
    [encryptToken(token), profile?.uuid ?? null, profile?.name ?? null]);
}

async function loadRefreshToken(): Promise<string | null> {
  const r = await pool.query("SELECT refresh_token_enc FROM skin_account WHERE id = 1");
  if (r.rowCount) return decryptToken(r.rows[0].refresh_token_enc);
  return process.env.SKIN_MS_REFRESH_TOKEN || null;
}

// ---- HTTP helpers

function retryAfter(res: Response): number | null {
  const h = res.headers.get("retry-after");
  if (!h) return null;
  const s = Number(h);
  if (Number.isFinite(s)) return Math.max(0, s * 1000);
  const t = Date.parse(h);
  return Number.isFinite(t) ? Math.max(0, t - Date.now()) : null;
}

async function json(res: Response, what: string, auth = false) {
  const text = await res.text();
  let body: any = null;
  try { body = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!res.ok) {
    const detail = body?.error_description ?? body?.errorMessage ?? body?.error ?? body?.XErr ?? text.slice(0, 160);
    throw new SkinApiError(`${what}: HTTP ${res.status} ${String(detail ?? "").slice(0, 200)}`.trim(),
      res.status, retryAfter(res), auth || res.status === 401);
  }
  return body;
}

const form = (fields: Record<string, string>) => ({
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams(fields).toString(),
});
const post = (body: unknown) => ({
  method: "POST",
  headers: { "content-type": "application/json", accept: "application/json" },
  body: JSON.stringify(body),
});

// ---- Microsoft device code flow (used by scripts/skin-login.ts)

export async function startDeviceCode() {
  const res = await fetch(`${MS_LOGIN}/devicecode`, form({ client_id: process.env.SKIN_MS_CLIENT_ID ?? "", scope: MS_SCOPE }));
  return await json(res, "Microsoft device code") as {
    device_code: string; user_code: string; verification_uri: string; expires_in: number; interval: number; message: string;
  };
}

/** Waits for the owner to finish signing in. Returns the Microsoft tokens. */
export async function pollDeviceCode(deviceCode: string, intervalSec: number, expiresInSec: number) {
  const until = Date.now() + expiresInSec * 1000;
  let wait = Math.max(1, intervalSec) * 1000;
  while (Date.now() < until) {
    await new Promise((r) => setTimeout(r, wait));
    const res = await fetch(`${MS_LOGIN}/token`, form({
      grant_type: "urn:ietf:params:oauth:grant-type:device_code",
      client_id: process.env.SKIN_MS_CLIENT_ID ?? "",
      device_code: deviceCode,
    }));
    const body: any = await res.json().catch(() => null);
    if (res.ok) return body as { access_token: string; refresh_token: string };
    if (body?.error === "authorization_pending") continue;
    if (body?.error === "slow_down") { wait += 5000; continue; }
    throw new SkinApiError(`Microsoft sign-in: ${body?.error_description ?? body?.error ?? res.status}`, res.status);
  }
  throw new SkinApiError("Microsoft sign-in: the code expired before anyone signed in");
}

// ---- Microsoft -> Xbox -> Minecraft

export async function refreshMicrosoft(refreshToken: string) {
  const res = await fetch(`${MS_LOGIN}/token`, form({
    grant_type: "refresh_token",
    client_id: process.env.SKIN_MS_CLIENT_ID ?? "",
    refresh_token: refreshToken,
    scope: MS_SCOPE,
  }));
  return await json(res, "Microsoft refresh", true) as { access_token: string; refresh_token?: string };
}

export async function minecraftLogin(msAccessToken: string) {
  const xbl = await json(await fetch(`${XBL_AUTH}/user/authenticate`, post({
    Properties: { AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: `d=${msAccessToken}` },
    RelyingParty: "http://auth.xboxlive.com",
    TokenType: "JWT",
  })), "Xbox Live", true);
  const xsts = await json(await fetch(`${XSTS_AUTH}/xsts/authorize`, post({
    Properties: { SandboxId: "RETAIL", UserTokens: [xbl.Token] },
    RelyingParty: "rp://api.minecraftservices.com/",
    TokenType: "JWT",
  })), "Xbox XSTS", true);
  const uhs = xsts.DisplayClaims?.xui?.[0]?.uhs ?? xbl.DisplayClaims?.xui?.[0]?.uhs;
  const mc = await json(await fetch(`${MC_SERVICES}/authentication/login_with_xbox`, post({
    identityToken: `XBL3.0 x=${uhs};${xsts.Token}`,
  })), "Minecraft login", true);
  const profile = await json(await fetch(`${MC_SERVICES}/minecraft/profile`, {
    headers: { authorization: `Bearer ${mc.access_token}` },
  }), "Minecraft profile (does this account own Minecraft?)", true);
  return {
    token: mc.access_token as string,
    expiresAt: Date.now() + Number(mc.expires_in ?? 86_400) * 1000,
    uuid: String(profile.id),
    name: String(profile.name),
  };
}

type McSession = Awaited<ReturnType<typeof minecraftLogin>>;
let session: McSession | null = null;

/** A Minecraft token for the skin account, refreshed (and the new refresh token saved) when needed. */
export async function skinSession(): Promise<McSession> {
  if (session && session.expiresAt - Date.now() > 10 * 60_000) return session;
  const refresh = await loadRefreshToken();
  if (!refresh) throw new SkinApiError("The skin account isn't signed in. Run: bb skin-login", 0, null, true);
  const ms = await refreshMicrosoft(refresh);
  // Save the new refresh token before anything else can fail: the old one may already be spent.
  if (ms.refresh_token) await saveRefreshToken(ms.refresh_token);
  session = await minecraftLogin(ms.access_token);
  await pool.query("UPDATE skin_account SET mc_uuid = $1, mc_name = $2 WHERE id = 1", [session.uuid, session.name]);
  return session;
}

/** Drop the cached Minecraft token (Mojang said it's no longer valid). */
export function forgetSkinSession() {
  session = null;
}

// ---- skins

/** "http://textures.minecraft.net/texture/<hash>" -> "<hash>" */
export function textureHashOf(url: string | null | undefined): string | null {
  const m = String(url ?? "").match(/textures\.minecraft\.net\/texture\/([0-9a-fA-F]{32,64})/);
  return m ? m[1].toLowerCase() : null;
}

/** Sets the skin account's skin. Returns the new texture's hash. */
export async function uploadSkin(png: Buffer): Promise<string> {
  const s = await skinSession();
  const body = new FormData();
  body.set("variant", "classic");
  body.set("file", new Blob([new Uint8Array(png)], { type: "image/png" }), "head.png");
  const res = await fetch(`${MC_SERVICES}/minecraft/profile/skins`, {
    method: "POST",
    headers: { authorization: `Bearer ${s.token}` },
    body,
  });
  if (res.status === 401) forgetSkinSession();
  const profile = await json(res, "Skin upload");
  const active = (profile?.skins ?? []).find((k: any) => k.state === "ACTIVE") ?? profile?.skins?.[0];
  const hash = textureHashOf(active?.url);
  if (!hash) throw new SkinApiError("Skin upload: no texture URL in Mojang's answer");
  return hash;
}

/** The skin account's current signed "textures" property, and the texture hash it points at. */
export async function readSignedTexture(): Promise<{ hash: string | null; value: string; signature: string }> {
  const s = await skinSession();
  const res = await fetch(`${MOJANG_SESSION}/session/minecraft/profile/${s.uuid.replace(/-/g, "")}?unsigned=false`);
  const profile = await json(res, "Session profile");
  const prop = (profile?.properties ?? []).find((p: any) => p.name === "textures");
  if (!prop?.value || !prop?.signature) throw new SkinApiError("Session profile: no signed textures property");
  let url: string | undefined;
  try { url = JSON.parse(Buffer.from(prop.value, "base64").toString("utf8"))?.textures?.SKIN?.url; } catch { /* bad JSON */ }
  return { hash: textureHashOf(url), value: prop.value, signature: prop.signature };
}
