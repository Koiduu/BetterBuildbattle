# Setting up paid plans

Better Battle has three plans: **Builder** (free), **Architect** and **Master Builder**. Until the steps below are
done the Plans page shows "Coming soon" and nothing can be bought. Everything about who gets what lives in one
file, `api/src/lib/plans.ts`.

The rule the plans follow: **nothing inside a game is ever paid.** The mod is identical for everyone. Paid plans only
add things on the website and in Discord.

## Before you take money

1. **Hypixel API.** Their API policy allows monetised projects only when the app is registered as a *production
   application* on the [Hypixel Developer Dashboard](https://developer.hypixel.net), and the monetisation must not be
   "unfair". Register it and describe the plans. One perk depends on Hypixel data: the all-leaderboards position list.
   If Hypixel objects, move `leaderboardPositions` to `"free"` in `plans.ts` (one line).
2. **Mojang.** The Minecraft EULA says mods can't be sold. That is why no plan changes the mod. Keep it that way, keep
   "Minecraft" out of the product's name, and keep the "not an official Minecraft product" line in the footer.
   Read the current [Usage Guidelines](https://www.minecraft.net/usage-guidelines) yourself before launch.
3. **Your side.** Taking payments is running a business: Stripe will ask who you are, and income is taxable. If you
   are under 18 you need a parent or guardian to own the Stripe account.

## Stripe

1. In Stripe, create two **products** with a **monthly recurring price** each: Architect and Master Builder.
   Copy the two price IDs (`price_...`).
2. **Developers → Webhooks → Add endpoint**: `https://battle.aspir3.net/bb/v1/billing/webhook`, with these events:
   `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`,
   `customer.subscription.deleted`. Copy the signing secret (`whsec_...`).
3. **Settings → Billing → Customer portal**: switch it on, allow cancelling, and allow switching between the two
   products (that is how people upgrade or downgrade).
4. Put the values in `.env` (see `.env.example`) and run `bb restart`.
5. Test with Stripe's test mode first: use `sk_test_...` keys, test price IDs and card `4242 4242 4242 4242`.

How it works: the site sends people to Stripe's own checkout page, so card details never reach this server. Stripe
then calls the webhook, which is the only thing that changes a plan. Cancelling keeps the plan until the paid period
ends. Deleting an account cancels its subscription first.

## Discord

- Optional supporter roles: create two roles, put their IDs in `DISCORD_ROLE_ARCHITECT` and `DISCORD_ROLE_MASTER`,
  and make sure the bot's own role is **above** them. The bot needs the Manage Roles permission (re-invite it with
  the link it prints at startup).
- Plans follow the Discord account connected on the website's Account page.
- `/feed #channel` posts new Speed Builders world records there.

## Giving a plan for free

For testers, friends or prizes (also the easiest way to try the perks without Stripe):

```bash
curl -X POST https://battle.aspir3.net/bb/v1/admin/plan -H "x-admin-token: $ADMIN_TOKEN" \
  -H "content-type: application/json" -d '{"name":"Koidu","plan":"master"}'
```

Use `"plan":"free"` to take it back. It only works for accounts without a Stripe subscription.
