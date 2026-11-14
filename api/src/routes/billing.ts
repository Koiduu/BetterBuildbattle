// Plans and payments. Everything to do with money happens on Stripe's own pages; this only starts
// those pages and listens for Stripe telling us what happened.
import { z } from "zod";
import { Router, fail } from "../http";
import { pool } from "../db/pool";
import { rateLimit, requireWebSession } from "../plugins/auth";
import { PERKS, PLANS, planOf, type Plan } from "../lib/plans";
import { siteOrigin } from "../lib/site";
import {
  PRICE_ENV, StripeError, applySubscription, planPrices, stripe, stripeConfigured, verifyWebhook,
} from "../services/stripe";

const PAID = z.enum(["architect", "master"]);

export function billingRoutes(app: Router) {
  /** The pricing page's data. Works before Stripe is set up; prices are then simply missing. */
  app.get("/billing/plans", async () => {
    const configured = stripeConfigured();
    const prices = configured ? await planPrices() : {};
    return {
      configured,
      plans: (Object.keys(PLANS) as Plan[]).map((id) => ({
        id, name: PLANS[id].name, tagline: PLANS[id].tagline, perks: PERKS[id],
        price: id === "free" ? { amount: 0, currency: "usd", interval: "month" } : prices[id] ?? null,
      })),
    };
  });

  /** Starts a Stripe Checkout page for a plan and returns its address. */
  app.post("/billing/checkout", [rateLimit(10, 60_000), requireWebSession], async (req) => {
    if (!stripeConfigured()) fail(503, "billing_unavailable");
    const { plan } = z.object({ plan: PAID }).parse(req.body);
    const r = await pool.query(
      `SELECT email, plan, plan_until, plan_comped, stripe_customer_id, stripe_subscription_id
       FROM users WHERE id = $1`, [req.user.id]);
    const u = r.rows[0];
    if (!u) return fail(401, "not_logged_in");
    // One subscription per account. Changing plan happens in the billing portal, which prorates it.
    if (u.stripe_subscription_id && planOf(u) !== "free") fail(409, "already_subscribed");

    const origin = siteOrigin();
    try {
      const session = await stripe("POST", "/checkout/sessions", {
        mode: "subscription",
        line_items: [{ price: process.env[PRICE_ENV[plan]], quantity: 1 }],
        client_reference_id: req.user.id,
        ...(u.stripe_customer_id ? { customer: u.stripe_customer_id } : u.email ? { customer_email: u.email } : {}),
        subscription_data: { metadata: { user_id: req.user.id } },
        metadata: { user_id: req.user.id },
        allow_promotion_codes: true,
        ...(process.env.STRIPE_AUTOMATIC_TAX === "1" ? { automatic_tax: { enabled: true } } : {}),
        success_url: `${origin}/account?billing=success`,
        cancel_url: `${origin}/plans`,
      });
      return { url: session.url };
    } catch (e) {
      console.error("Stripe checkout failed:", e instanceof StripeError ? e.message : e);
      return fail(502, "billing_unavailable");
    }
  });

  /** Stripe's billing portal: change plan, update the card, see invoices, cancel. */
  app.post("/billing/portal", [rateLimit(10, 60_000), requireWebSession], async (req) => {
    if (!stripeConfigured()) fail(503, "billing_unavailable");
    const r = await pool.query("SELECT stripe_customer_id FROM users WHERE id = $1", [req.user.id]);
    const customer = r.rows[0]?.stripe_customer_id;
    if (!customer) return fail(400, "no_subscription");
    try {
      const session = await stripe("POST", "/billing_portal/sessions", { customer, return_url: `${siteOrigin()}/account` });
      return { url: session.url };
    } catch (e) {
      console.error("Stripe portal failed:", e instanceof StripeError ? e.message : e);
      return fail(502, "billing_unavailable");
    }
  });

  /** Stripe calls this. Nothing is trusted until the signature checks out. */
  app.post("/billing/webhook", async (req) => {
    const event = verifyWebhook(req.rawBody, req.headers["stripe-signature"] as string | undefined);
    if (!event) return fail(400, "bad_signature");

    const first = await pool.query(
      "INSERT INTO stripe_events (id, type) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING RETURNING 1",
      [event.id, event.type]);
    if (!first.rowCount) return { received: true, duplicate: true };

    try {
      const obj = event.data.object;
      if (event.type === "checkout.session.completed" && obj.mode === "subscription") {
        const userId = Number(obj.client_reference_id);
        const customer = typeof obj.customer === "string" ? obj.customer : obj.customer?.id;
        if (Number.isFinite(userId) && customer) {
          await pool.query(
            "UPDATE users SET stripe_customer_id = $2 WHERE id = $1 AND (stripe_customer_id IS NULL OR stripe_customer_id = $2)",
            [userId, customer]);
        }
        const subId = typeof obj.subscription === "string" ? obj.subscription : obj.subscription?.id;
        if (subId) await applySubscription(await stripe("GET", `/subscriptions/${encodeURIComponent(subId)}`));
      } else if (event.type.startsWith("customer.subscription.")) {
        // Read it fresh: events can arrive out of order, the API is always current.
        let sub = obj;
        try {
          sub = await stripe("GET", `/subscriptions/${encodeURIComponent(obj.id)}`);
        } catch (e) {
          if (!(e instanceof StripeError && e.status === 404)) throw e;
          sub = { ...obj, status: "canceled" };
        }
        await applySubscription(sub);
      }
    } catch (e) {
      // Forget the event so Stripe's retry is handled, and answer 500 so it does retry.
      await pool.query("DELETE FROM stripe_events WHERE id = $1", [event.id]);
      throw e;
    }
    return { received: true };
  });
}
