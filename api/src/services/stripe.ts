// Stripe subscriptions, without the Stripe SDK: a few REST calls and one signature check.
// Card details never touch this server. Checkout and the billing portal are pages hosted by Stripe.
//
// The webhook is the only thing that changes a user's plan. Whatever the event says, the subscription
// is fetched fresh from Stripe and applied, so events arriving late or out of order can't leave a
// plan wrong.
import crypto from "node:crypto";
import { pool } from "../db/pool";
import type { Plan } from "../lib/plans";

// STRIPE_API_BASE exists for tests against a stand-in server; leave it unset in production.
const API = process.env.STRIPE_API_BASE ?? "https://api.stripe.com/v1";
const env = (k: string) => process.env[k] ?? "";

export const PRICE_ENV: Record<Exclude<Plan, "free">, string> = {
  architect: "STRIPE_PRICE_ARCHITECT",
  master: "STRIPE_PRICE_MASTER",
};

export const stripeConfigured = () =>
  !!(env("STRIPE_SECRET_KEY") && env("STRIPE_WEBHOOK_SECRET") && env(PRICE_ENV.architect) && env(PRICE_ENV.master));

export class StripeError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

/** Stripe wants nested objects as form fields: a[b][0][c]=1 */
function form(obj: Record<string, unknown>, prefix = "", out = new URLSearchParams()) {
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null) continue;
    const key = prefix ? `${prefix}[${k}]` : k;
    if (typeof v === "object") form(v as Record<string, unknown>, key, out);
    else out.append(key, String(v));
  }
  return out;
}

export async function stripe<T = any>(method: "GET" | "POST" | "DELETE", path: string, params?: Record<string, unknown>): Promise<T> {
  const body = params && method !== "GET" ? form(params) : undefined;
  const query = params && method === "GET" ? "?" + form(params) : "";
  const res = await fetch(API + path + query, {
    method,
    headers: {
      authorization: `Bearer ${env("STRIPE_SECRET_KEY")}`,
      ...(body ? { "content-type": "application/x-www-form-urlencoded" } : {}),
    },
    body,
    signal: AbortSignal.timeout(15_000),
  });
  const json: any = await res.json().catch(() => ({}));
  if (!res.ok) throw new StripeError(res.status, json?.error?.message ?? `Stripe returned ${res.status}`);
  return json as T;
}

/** Checks the Stripe-Signature header against the raw body. Returns the event, or null if it isn't genuine. */
export function verifyWebhook(raw: Buffer | undefined, header: string | undefined, secret = env("STRIPE_WEBHOOK_SECRET"), now = Date.now()) {
  if (!raw || !header || !secret) return null;
  let t = "";
  const sigs: string[] = [];
  for (const part of header.split(",")) {
    const [k, v] = part.split("=", 2);
    if (k === "t") t = v ?? "";
    if (k === "v1" && v) sigs.push(v);
  }
  const ts = Number(t);
  if (!ts || Math.abs(now / 1000 - ts) > 300) return null;   // older than 5 minutes: possibly replayed
  const expected = crypto.createHmac("sha256", secret).update(`${t}.`).update(raw).digest();
  const ok = sigs.some((s) => {
    const given = Buffer.from(s, "hex");
    return given.length === expected.length && crypto.timingSafeEqual(given, expected);
  });
  if (!ok) return null;
  try {
    return JSON.parse(raw.toString("utf8")) as { id: string; type: string; data: { object: any } };
  } catch {
    return null;
  }
}

export function planForPrice(priceId: string | undefined): Exclude<Plan, "free"> | null {
  if (!priceId) return null;
  if (priceId === env(PRICE_ENV.architect)) return "architect";
  if (priceId === env(PRICE_ENV.master)) return "master";
  return null;
}

/** Called after a plan changes, e.g. to update Discord roles. */
const planListeners: ((userId: number) => void | Promise<void>)[] = [];
export const onPlanChange = (fn: (userId: number) => void | Promise<void>) => { planListeners.push(fn); };
export function announcePlanChange(userId: number) {
  for (const fn of planListeners) Promise.resolve().then(() => fn(userId)).catch((e) => console.error("plan listener failed", e));
}

const LIVE = new Set(["active", "trialing", "past_due"]);

/** Makes a user's plan match a Stripe subscription. Returns the user id, or null if nobody matches. */
export async function applySubscription(sub: any): Promise<number | null> {
  const customer = typeof sub.customer === "string" ? sub.customer : sub.customer?.id;
  const metaUser = Number(sub.metadata?.user_id);
  const found = await pool.query(
    `SELECT id, stripe_subscription_id, plan_comped FROM users
     WHERE stripe_customer_id = $1 OR id = $2
     ORDER BY (stripe_customer_id = $1) DESC NULLS LAST LIMIT 1`,
    [customer, Number.isFinite(metaUser) ? metaUser : -1]);
  const user = found.rows[0];
  if (!user) return null;

  const item = sub.items?.data?.[0];
  const live = LIVE.has(sub.status);
  const plan = live ? planForPrice(item?.price?.id) : null;

  // An old subscription ending must not cancel a newer one the user has since started.
  if (!live && user.stripe_subscription_id && user.stripe_subscription_id !== sub.id) return user.id;

  const periodEnd = sub.current_period_end ?? item?.current_period_end ?? null;   // moved onto the item in newer API versions
  await pool.query(
    `UPDATE users SET
       stripe_customer_id = coalesce(stripe_customer_id, $2),
       stripe_subscription_id = $3,
       plan = CASE WHEN plan_comped THEN plan ELSE $4 END,
       plan_until = CASE WHEN plan_comped THEN plan_until ELSE $5 END,
       plan_cancels = $6
     WHERE id = $1`,
    [user.id, customer, live ? sub.id : null, plan ?? "free",
     plan && periodEnd ? new Date(periodEnd * 1000) : null,
     !!(live && (sub.cancel_at_period_end || sub.cancel_at))]);
  announcePlanChange(user.id);
  return user.id;
}

/** Ends a subscription now. Used when an account is deleted, so nobody is billed for an account that's gone. */
export async function cancelSubscriptionNow(subscriptionId: string) {
  try {
    await stripe("DELETE", `/subscriptions/${encodeURIComponent(subscriptionId)}`);
  } catch (e) {
    if (e instanceof StripeError && e.status === 404) return;   // already gone
    throw e;
  }
}

let priceCache: { at: number; prices: Record<string, { amount: number; currency: string; interval: string } | null> } | null = null;

/** What each plan costs, read from Stripe so the site never shows a stale price. Cached for an hour. */
export async function planPrices() {
  if (priceCache && Date.now() - priceCache.at < 3_600_000) return priceCache.prices;
  const prices: Record<string, { amount: number; currency: string; interval: string } | null> = {};
  for (const [plan, key] of Object.entries(PRICE_ENV)) {
    try {
      const p = await stripe("GET", `/prices/${encodeURIComponent(env(key))}`);
      prices[plan] = { amount: p.unit_amount, currency: p.currency, interval: p.recurring?.interval ?? "month" };
    } catch {
      prices[plan] = null;
    }
  }
  if (Object.values(prices).some(Boolean)) priceCache = { at: Date.now(), prices };
  return prices;
}
