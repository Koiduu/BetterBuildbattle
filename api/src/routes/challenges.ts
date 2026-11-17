// Community challenge ideas from the website's Submit a Challenge page.
// Each one is saved, then emailed to the support inbox with the subject "[challenges] ...".
//
// Spam protection, in order: a hidden honeypot field, a minimum time on the form, 3 per hour and
// 5 per day from one address, no repeats of the same idea, at most 2 links, and a daily cap on emails
// (anything past the cap is still saved, just not emailed).
import { createHash } from "node:crypto";
import { z } from "zod";
import { Router, fail, type Req } from "../http";
import { pool } from "../db/pool";
import { redis } from "../db/redis";
import { rateLimit } from "../plugins/auth";

export const CHALLENGE_MODES = ["solo", "teams", "pro", "gtb", "speed_builders", "any"] as const;
const EMAILS_PER_DAY = Number(process.env.CHALLENGE_EMAILS_PER_DAY ?? 30);
const PER_ADDRESS_PER_DAY = 5;

const SubmissionIn = z.object({
  title: z.string().trim().min(4).max(80),
  description: z.string().trim().min(20).max(1000),
  mode: z.enum(CHALLENGE_MODES),
  mcName: z.string().trim().regex(/^[A-Za-z0-9_]{1,16}$/).optional().or(z.literal("")),
  contact: z.string().trim().max(120).optional().or(z.literal("")),
  website: z.string().max(200).optional(),          // honeypot: people never see it, bots fill it in
  startedAt: z.number().int().positive(),            // when the form was shown (ms)
});

const day = () => new Date().toISOString().slice(0, 10);
const ipOf = (req: Req) => ((req.headers["cf-connecting-ip"] as string) ?? (req.headers["x-forwarded-for"] as string)?.split(",")[0] ?? req.ip ?? "").trim();
const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const normalise = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const linkCount = (s: string) => (s.match(/(?:https?:\/\/)?www\.|https?:\/\//gi) ?? []).length;

/** Counts one use of a daily allowance in Redis; true while still under max. */
async function underDaily(key: string, max: number): Promise<boolean> {
  const k = `bb:challenges:${key}:${day()}`;
  const n = await redis.incr(k);
  if (n === 1) await redis.expire(k, 26 * 3600);
  return n <= max;
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** Sends through Resend's HTTP API (no extra npm packages). Needs RESEND_API_KEY and SUPPORT_EMAIL. */
export async function emailSupport(subject: string, text: string, replyTo?: string): Promise<boolean> {
  const key = process.env.RESEND_API_KEY, to = process.env.SUPPORT_EMAIL;
  if (!key || !to) return false;
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
    body: JSON.stringify({
      from: process.env.SUPPORT_FROM ?? "Better Battle <noreply@aspir3.net>",
      to: [to],
      subject,
      text,
      html: `<pre style="font:14px/1.5 sans-serif;white-space:pre-wrap">${escapeHtml(text)}</pre>`,
      ...(replyTo ? { reply_to: replyTo } : {}),
      tags: [{ name: "category", value: "challenges" }],
    }),
  });
  if (!res.ok) console.error(`challenge email failed: HTTP ${res.status} ${await res.text().catch(() => "")}`);
  return res.ok;
}

export function challengeRoutes(app: Router) {
  app.post("/challenges/submit", [rateLimit(3, 60 * 60_000, "/challenges/submit")], async (req) => {
    const body = SubmissionIn.parse(req.body);
    // Bots: answer like it worked so they don't learn anything
    if (body.website) return { ok: true };
    const age = Date.now() - body.startedAt;
    if (age < 5_000) fail(400, "too_fast");
    if (age > 6 * 3600_000) fail(400, "form_expired");
    if (linkCount(body.title + " " + body.description) > 2) fail(400, "too_many_links");
    if (/(.)\1{9,}/.test(body.description)) fail(400, "looks_like_spam");

    const ipHash = sha("bb-challenges:" + ipOf(req));
    if (!(await underDaily(`ip:${ipHash}`, PER_ADDRESS_PER_DAY))) fail(429, "daily_limit");
    const fingerprint = sha(normalise(body.title) + "|" + normalise(body.description));
    const fresh = await redis.set(`bb:challenges:seen:${fingerprint}`, "1", "EX", 30 * 86400, "NX");
    if (!fresh) fail(409, "already_submitted");

    const contact = body.contact || null;
    const mcName = body.mcName || null;
    const row = await pool.query(
      `INSERT INTO challenge_submissions (title, description, mode, mc_name, contact, ip_hash)
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
      [body.title, body.description, body.mode, mcName, contact, ipHash],
    ).catch(async (e) => {
      await redis.del(`bb:challenges:seen:${fingerprint}`);   // not saved, so let them try again
      throw e;
    });
    const id = row.rows[0].id as string;

    if (await underDaily("emails", EMAILS_PER_DAY)) {
      const replyTo = contact && /^\S+@\S+\.\S+$/.test(contact) ? contact : undefined;
      const text = [
        `Challenge #${id}`,
        `Title: ${body.title}`,
        `Mode: ${body.mode}`,
        `Minecraft name: ${mcName ?? "-"}`,
        `Contact: ${contact ?? "-"}`,
        "",
        body.description,
      ].join("\n");
      const sent = await emailSupport(`[challenges] ${body.title}`, text, replyTo).catch((e) => {
        console.error("challenge email failed:", e);
        return false;
      });
      if (sent) await pool.query("UPDATE challenge_submissions SET emailed = true WHERE id = $1", [id]);
    }
    return { ok: true, id };
  });
}
