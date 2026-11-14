import crypto from "node:crypto";
import { hash, verify } from "@node-rs/argon2";
import { z } from "zod";
import { pool } from "../db/pool";
import { fail, Redirect, Router, type Req } from "../http";
import { DISCORD_API, HYPIXEL_API, MOJANG_API, dashUuid } from "../lib/upstream";
import { endWebSession, rateLimit, readWebSession, requireModToken, requireWebSession, startWebSession } from "../plugins/auth";
import { queuePlayers } from "../jobs/crawler";

const EMAIL = z.string().trim().email().max(254);
const PASSWORD = z.string().min(8).max(200);
const LOGIN_PASSWORD = z.string().max(200);
const LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PUBLIC_URL = () => process.env.PUBLIC_URL ?? "http://localhost:4000";
const dummyHash = await hash("not-a-real-better-battle-password");

type MeRow = {
  id: number;
  email: string | null;
  password_hash: string | null;
  discord_id: string | null;
  discord_name: string | null;
  avatar_url: string | null;
  mc_uuid: string | null;
  mc_name: string | null;
  bio: string | null;
  created_at: Date;
};

async function getMe(userId: number) {
  const result = await pool.query<MeRow>(
    `SELECT id, email, password_hash, discord_id, discord_name, avatar_url,
            mc_uuid, mc_name, bio, created_at
     FROM users WHERE id = $1`, [userId]);
  const row = result.rows[0];
  if (!row) fail(401, "not_logged_in");
  return {
    id: row.id,
    email: row.email,
    hasPassword: !!row.password_hash,
    discord: row.discord_id
      ? { id: row.discord_id, name: row.discord_name, avatarUrl: row.avatar_url }
      : null,
    minecraft: row.mc_uuid ? { uuid: row.mc_uuid, name: row.mc_name } : null,
    bio: row.bio,
    createdAt: row.created_at,
  };
}

function configuredDiscord() {
  return !!(process.env.DISCORD_CLIENT_ID && process.env.DISCORD_CLIENT_SECRET);
}

function discordRedirectUri() {
  return new URL("/bb/v1/auth/discord/callback", PUBLIC_URL()).toString();
}

function oauthCookie(req: Req, state: string | null) {
  const secure = PUBLIC_URL().startsWith("https://") ? "; Secure" : "";
  const value = state ?? "";
  const maxAge = state ? 600 : 0;
  const cookie = `bb_oauth=${value}; HttpOnly; SameSite=Lax; Max-Age=${maxAge}; Path=/bb/v1/auth/discord${secure}`;
  const current = req.reply.headers["set-cookie"];
  req.reply.headers["set-cookie"] = current
    ? [...(Array.isArray(current) ? current : [current]), cookie]
    : cookie;
}

function loginRedirect(error: string) {
  return new Redirect(`/login?error=${encodeURIComponent(error)}`);
}

function sameState(a: string, b: string) {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function uniqueViolation(error: unknown) {
  return (error as { code?: string })?.code === "23505";
}

async function discordUser(req: Req) {
  const body = z.object({ code: z.string().min(1), state: z.string().min(1) }).safeParse(req.query);
  const state = req.cookies.bb_oauth;
  oauthCookie(req, null);
  if (!body.success || !state || !sameState(state, body.data.state) || !configuredDiscord()) {
    return null;
  }

  const tokenResponse = await fetch(`${DISCORD_API}/oauth2/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: body.data.code,
      redirect_uri: discordRedirectUri(),
      client_id: process.env.DISCORD_CLIENT_ID!,
      client_secret: process.env.DISCORD_CLIENT_SECRET!,
    }),
  });
  if (!tokenResponse.ok) return null;
  const token = await tokenResponse.json() as { access_token?: string };
  if (!token.access_token) return null;

  const userResponse = await fetch(`${DISCORD_API}/users/@me`, {
    headers: { authorization: `Bearer ${token.access_token}` },
  });
  if (!userResponse.ok) return null;
  const profile = await userResponse.json() as { id?: string; username?: string; avatar?: string | null };
  if (!profile.id || !profile.username) return null;
  return {
    id: profile.id,
    name: profile.username,
    avatarUrl: profile.avatar ? `https://cdn.discordapp.com/avatars/${profile.id}/${profile.avatar}.png` : null,
  };
}

export function accountRoutes(app: Router) {
  app.get("/auth/providers", async () => ({ discord: configuredDiscord() }));

  app.post("/auth/signup", [rateLimit(10, 60_000, "/auth/signup")], async (req) => {
    const { email, password } = z.object({ email: EMAIL, password: PASSWORD }).parse(req.body);
    const passwordHash = await hash(password);
    let userId: number;
    try {
      const result = await pool.query(
        "INSERT INTO users (email, password_hash, claimed) VALUES ($1, $2, true) RETURNING id",
        [email, passwordHash]);
      userId = result.rows[0].id;
    } catch (error) {
      if (uniqueViolation(error)) fail(409, "email_taken");
      throw error;
    }
    await startWebSession(req, userId);
    return getMe(userId);
  });

  app.post("/auth/login", [rateLimit(10, 60_000, "/auth/login")], async (req) => {
    const { email, password } = z.object({ email: EMAIL, password: LOGIN_PASSWORD }).parse(req.body);
    const result = await pool.query("SELECT id, password_hash FROM users WHERE email = $1", [email]);
    const row = result.rows[0] as { id: number; password_hash: string | null } | undefined;
    const valid = row?.password_hash
      ? await verify(row.password_hash, password)
      : await verify(dummyHash, password);
    if (!row || !row.password_hash || !valid) return fail(401, "wrong_login");
    await startWebSession(req, row.id);
    return getMe(row.id);
  });

  app.post("/auth/logout", async (req) => {
    await endWebSession(req);
    return { ok: true };
  });

  app.get("/auth/discord", async (req) => {
    if (!configuredDiscord()) return new Redirect("/login?error=discord_off");
    const state = crypto.randomBytes(32).toString("base64url");
    oauthCookie(req, state);
    const url = new URL(`${DISCORD_API}/oauth2/authorize`);
    url.search = new URLSearchParams({
      client_id: process.env.DISCORD_CLIENT_ID!,
      redirect_uri: discordRedirectUri(),
      response_type: "code",
      scope: "identify",
      state,
      prompt: "none",
    }).toString();
    return new Redirect(url.toString());
  });

  app.get("/auth/discord/callback", async (req) => {
    try {
      const profile = await discordUser(req);
      if (!profile) return loginRedirect("discord");
      const session = await readWebSession(req);
      if (session) {
        const other = await pool.query("SELECT id FROM users WHERE discord_id = $1 AND id <> $2", [profile.id, session.id]);
        if (other.rowCount) return new Redirect("/account?error=discord_taken");
        await pool.query(
          "UPDATE users SET discord_id = $1, discord_name = $2, avatar_url = $3 WHERE id = $4",
          [profile.id, profile.name, profile.avatarUrl, session.id]);
        return new Redirect("/account");
      }

      const existing = await pool.query("SELECT id FROM users WHERE discord_id = $1", [profile.id]);
      let userId: number;
      if (existing.rowCount) {
        userId = existing.rows[0].id;
        await pool.query("UPDATE users SET discord_name = $1, avatar_url = $2 WHERE id = $3",
          [profile.name, profile.avatarUrl, userId]);
      } else {
        const created = await pool.query(
          `INSERT INTO users (discord_id, discord_name, avatar_url, claimed)
           VALUES ($1, $2, $3, true) RETURNING id`,
          [profile.id, profile.name, profile.avatarUrl]);
        userId = created.rows[0].id;
      }
      await startWebSession(req, userId);
      return new Redirect("/account");
    } catch {
      return loginRedirect("discord");
    }
  });

  app.get("/me", [requireWebSession], async (req) => getMe(req.user.id));

  app.get("/me/export", [rateLimit(5, 60_000, "/me/export"), requireWebSession], async (req) => {
    const result = await pool.query("SELECT * FROM users WHERE id = $1", [req.user.id]);
    const row = result.rows[0];
    if (!row) fail(401, "not_logged_in");
    const { password_hash, ...user } = row;
    const userId = req.user.id;
    const [
      sessions, matches, matchVotes, pinnedMatches, userStats, userProgress,
      userAchievements, userWeeklyChallenges, userThemes, spbPersonalBests,
      rankSnapshots, linkCodes, dailyActivity,
    ] = await Promise.all([
      pool.query(
        `SELECT id, kind, mod_version, mc_version, expires_at, created_at
         FROM sessions WHERE user_id = $1 ORDER BY created_at`, [userId]),
      pool.query("SELECT * FROM matches WHERE user_id = $1 ORDER BY played_at", [userId]),
      pool.query("SELECT * FROM match_votes WHERE user_id = $1 ORDER BY played_at, round", [userId]),
      pool.query("SELECT * FROM pinned_matches WHERE user_id = $1 ORDER BY position", [userId]),
      pool.query("SELECT * FROM user_stats WHERE user_id = $1 ORDER BY mode", [userId]),
      pool.query("SELECT * FROM user_progress WHERE user_id = $1", [userId]),
      pool.query("SELECT * FROM user_achievements WHERE user_id = $1 ORDER BY unlocked_at", [userId]),
      pool.query("SELECT * FROM user_weekly_challenges WHERE user_id = $1 ORDER BY week_start, id", [userId]),
      pool.query("SELECT * FROM user_themes WHERE user_id = $1 ORDER BY theme", [userId]),
      pool.query("SELECT * FROM spb_personal_bests WHERE user_id = $1 ORDER BY build_id", [userId]),
      pool.query("SELECT * FROM rank_snapshots WHERE user_id = $1 ORDER BY taken_on, mode", [userId]),
      pool.query("SELECT * FROM link_codes WHERE user_id = $1 ORDER BY expires_at", [userId]),
      pool.query("SELECT day::date::text AS day, games, wins FROM daily_activity WHERE user_id = $1 ORDER BY day", [userId]),
    ]);
    req.reply.headers["content-disposition"] = 'attachment; filename="better-battle-data.json"';
    return {
      user: { ...user, hasPassword: !!password_hash },
      linkedAccounts: {
        discord: user.discord_id ? { id: user.discord_id, name: user.discord_name } : null,
        minecraft: user.mc_uuid ? { uuid: user.mc_uuid, name: user.mc_name } : null,
      },
      data: {
        sessions: sessions.rows,
        matches: matches.rows,
        matchVotes: matchVotes.rows,
        pinnedMatches: pinnedMatches.rows,
        userStats: userStats.rows,
        userProgress: userProgress.rows[0] ?? null,
        userAchievements: userAchievements.rows,
        userWeeklyChallenges: userWeeklyChallenges.rows,
        userThemes: userThemes.rows,
        spbPersonalBests: spbPersonalBests.rows,
        rankSnapshots: rankSnapshots.rows,
        linkCodes: linkCodes.rows,
        dailyActivity: dailyActivity.rows,
      },
    };
  });

  app.delete("/me", [rateLimit(5, 60_000, "/me/delete"), requireWebSession], async (req) => {
    const { password, confirm } = z.object({
      password: z.string().max(200).optional(),
      confirm: z.string().optional(),
    }).parse(req.body ?? {});
    let matchRange: { min_played_at: Date | null; max_played_at: Date | null } | undefined;
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query("SELECT password_hash FROM users WHERE id = $1 FOR UPDATE", [req.user.id]);
      const user = result.rows[0] as { password_hash: string | null } | undefined;
      if (!user) return fail(401, "not_logged_in");
      if (user.password_hash) {
        if (!password || !await verify(user.password_hash, password)) fail(401, "wrong_password");
      } else if (confirm !== "DELETE") {
        fail(400, "confirm_required");
      }
      const matches = await client.query<{ min_played_at: Date | null; max_played_at: Date | null }>(
        "SELECT min(played_at) AS min_played_at, max(played_at) AS max_played_at FROM matches WHERE user_id = $1",
        [req.user.id]);
      matchRange = matches.rows[0];
      const deleted = await client.query("DELETE FROM users WHERE id = $1", [req.user.id]);
      if (!deleted.rowCount) fail(401, "not_logged_in");
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
    await endWebSession(req);
    if (matchRange?.min_played_at && matchRange.max_played_at) {
      try {
        await pool.query(
          `CALL refresh_continuous_aggregate(
            'daily_activity',
            date_trunc('day', $1::timestamptz),
            date_trunc('day', $2::timestamptz) + interval '1 day')`,
          [matchRange.min_played_at, matchRange.max_played_at]);
      } catch (error) {
        console.error("Failed to refresh daily_activity after account deletion", error);
      }
    }
    return { ok: true };
  });

  app.put("/me", [requireWebSession], async (req) => {
    const { bio } = z.object({ bio: z.string().trim().max(1000).nullable() }).parse(req.body);
    await pool.query("UPDATE users SET bio = $2 WHERE id = $1", [req.user.id, bio?.trim() || null]);
    return getMe(req.user.id);
  });

  app.post("/me/link-code", [requireWebSession], async (req) => {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const user = await client.query("SELECT mc_uuid FROM users WHERE id = $1 FOR UPDATE", [req.user.id]);
      if (!user.rowCount) fail(401, "not_logged_in");
      if (user.rows[0].mc_uuid) fail(409, "already_linked");
      await client.query("DELETE FROM link_codes WHERE user_id = $1", [req.user.id]);
      const code = Array.from({ length: 6 }, () => LINK_ALPHABET[crypto.randomInt(LINK_ALPHABET.length)]).join("");
      const result = await client.query(
        `INSERT INTO link_codes (code, user_id, expires_at)
         VALUES ($1, $2, now() + interval '10 minutes') RETURNING expires_at`,
        [code, req.user.id]);
      await client.query("COMMIT");
      return { code, expiresAt: result.rows[0].expires_at };
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  });

  app.post("/mod/link", [requireModToken, rateLimit(10, 60_000, "/mod/link")], async (req) => {
    const { code } = z.object({ code: z.string().length(6) }).parse(req.body);
    const result = await pool.query(
      `DELETE FROM link_codes WHERE upper(code) = upper($1) AND expires_at > now()
       RETURNING user_id`, [code]);
    if (!result.rowCount) fail(400, "bad_code");
    const mod = await pool.query("SELECT mc_uuid, mc_name FROM users WHERE id = $1", [req.user.id]);
    if (!mod.rows[0]?.mc_uuid) fail(400, "no_minecraft");
    const userId = await claimMinecraft(result.rows[0].user_id, mod.rows[0].mc_uuid, mod.rows[0].mc_name);
    const account = await pool.query("SELECT email, discord_name FROM users WHERE id = $1", [userId]);
    return { ok: true, account: account.rows[0].email ?? account.rows[0].discord_name };
  });

  app.post("/me/claim/hypixel", [requireWebSession], async (req) => {
    const { name } = z.object({ name: z.string().trim().min(1).max(16) }).parse(req.body);
    const account = await pool.query("SELECT discord_name FROM users WHERE id = $1", [req.user.id]);
    const discordName = account.rows[0]?.discord_name as string | undefined;
    if (!discordName) return fail(400, "discord_required");

    let uuid = "";
    let mcName = name;
    try {
      const profile = await fetch(`${MOJANG_API}/users/profiles/minecraft/${encodeURIComponent(name)}`);
      if (!profile.ok) return fail(404, "not_found");
      const data = await profile.json() as { id?: string; name?: string };
      if (!data.id) return fail(404, "not_found");
      uuid = dashUuid(data.id);
      mcName = data.name ?? name;
    } catch (error) {
      if ((error as { status?: number })?.status === 404) throw error;
      return fail(404, "not_found");
    }

    let player: { socialMedia?: { links?: { DISCORD?: unknown } } };
    try {
      const response = await fetch(`${HYPIXEL_API}/v2/player?uuid=${encodeURIComponent(uuid)}`, {
        headers: { "API-Key": process.env.HYPIXEL_API_KEY ?? "" },
      });
      if (!response.ok) return fail(503, "hypixel_unavailable");
      const data = await response.json() as { player?: { socialMedia?: { links?: { DISCORD?: unknown } } } | null };
      if (!data.player) return fail(503, "hypixel_unavailable");
      player = data.player;
    } catch (error) {
      if ((error as { status?: number })?.status === 503) throw error;
      return fail(503, "hypixel_unavailable");
    }
    const raw = player?.socialMedia?.links?.DISCORD;
    const hypixelDiscord = typeof raw === "string" ? raw : null;
    const normalize = (value: string) => value.trim().toLowerCase().replace(/^@/, "").replace(/#0$/, "");
    if (!hypixelDiscord || normalize(hypixelDiscord) !== normalize(discordName)) {
      fail(400, "discord_mismatch", { hypixelDiscord });
    }
    const userId = await claimMinecraft(req.user.id, uuid, mcName);
    return getMe(userId);
  });

  app.get("/me/matches", [requireWebSession], async (req) => {
    const { before } = z.object({ before: z.string().datetime().optional() }).parse(req.query);
    if (!req.user.mcUuid) return [];
    return (await pool.query(
      `SELECT id, played_at, mode, theme, placement, players, score, grade, crowd_score, xp_earned
       FROM matches WHERE user_id = $1 AND ($2::timestamptz IS NULL OR played_at < $2)
       ORDER BY played_at DESC LIMIT 25`, [req.user.id, before ?? null])).rows;
  });

  app.get("/me/pins", [requireWebSession], async (req) => getPins(req.user.id));

  app.put("/me/pins", [requireWebSession], async (req) => {
    const { matchIds } = z.object({
      matchIds: z.array(z.string().uuid()).max(10)
        .refine((ids) => new Set(ids.map((id) => id.toLowerCase())).size === ids.length),
    }).parse(req.body);
    if (!req.user.mcUuid) fail(400, "no_minecraft");
    const matches = await pool.query(
      "SELECT id, played_at FROM matches WHERE user_id = $1 AND id = ANY($2::uuid[])",
      [req.user.id, matchIds]);
    if (matches.rowCount !== matchIds.length) fail(400, "unknown_match");
    const byId = new Map(matches.rows.map((row: { id: string; played_at: Date }) => [row.id, row.played_at]));
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("DELETE FROM pinned_matches WHERE user_id = $1", [req.user.id]);
      for (const [index, id] of matchIds.entries()) {
        await client.query(
          "INSERT INTO pinned_matches (user_id, match_id, played_at, position) VALUES ($1, $2, $3, $4)",
          [req.user.id, id, byId.get(id.toLowerCase()) ?? byId.get(id), index + 1]);
      }
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
    return getPins(req.user.id);
  });
}

async function getPins(userId: number) {
  return (await pool.query(
    `SELECT m.id, m.theme, m.mode, m.placement, m.players, m.score, m.grade,
            m.played_at, m.crowd_score, m.xp_earned, pm.position
     FROM pinned_matches pm JOIN matches m ON m.id = pm.match_id AND m.played_at = pm.played_at
     WHERE pm.user_id = $1 ORDER BY pm.position`, [userId])).rows;
}

export async function claimMinecraft(webUserId: number, uuid: string, name: string): Promise<number> {
  const client = await pool.connect();
  let finalUserId = webUserId;
  let shouldQueue = false;
  try {
    await client.query("BEGIN");
    const web = await client.query(
      "SELECT id, mc_uuid FROM users WHERE id = $1 FOR UPDATE", [webUserId]);
    if (!web.rowCount) fail(404, "not_found");
    if (web.rows[0].mc_uuid) {
      if (web.rows[0].mc_uuid === uuid) {
        await client.query("COMMIT");
        return webUserId;
      }
      fail(409, "already_linked");
    }
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [uuid]);
    const stub = await client.query(
      "SELECT id, claimed FROM users WHERE mc_uuid = $1 AND id <> $2 FOR UPDATE", [uuid, webUserId]);
    if (!stub.rowCount) {
      await client.query("UPDATE users SET mc_uuid = $2, mc_name = $3 WHERE id = $1", [webUserId, uuid, name]);
      shouldQueue = true;
    } else {
      if (stub.rows[0].claimed) fail(409, "already_claimed");
      finalUserId = stub.rows[0].id;
      await client.query("UPDATE sessions SET user_id = $1 WHERE user_id = $2", [finalUserId, webUserId]);
      const source = await client.query(
        `DELETE FROM users WHERE id = $1
         RETURNING email, password_hash, discord_id, discord_name, avatar_url, bio`,
        [webUserId]);
      await client.query(
        `UPDATE users SET email = $2, password_hash = $3, discord_id = $4, discord_name = $5,
           avatar_url = coalesce(avatar_url, $6), bio = coalesce(bio, $7), claimed = true
         WHERE id = $1`,
        [finalUserId, source.rows[0].email, source.rows[0].password_hash, source.rows[0].discord_id,
         source.rows[0].discord_name, source.rows[0].avatar_url, source.rows[0].bio]);
    }
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
  if (shouldQueue) await queuePlayers([uuid], "search", true);
  return finalUserId;
}
