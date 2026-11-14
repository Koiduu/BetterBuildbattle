-- Subscriptions (Builder = free, Architect, Master Builder) and the profile perks they unlock.
-- Safe to re-run on every boot.

ALTER TABLE users ADD COLUMN IF NOT EXISTS plan TEXT NOT NULL DEFAULT 'free';
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_until TIMESTAMPTZ;           -- end of the paid period
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_cancels BOOLEAN NOT NULL DEFAULT false;  -- won't renew
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_comped BOOLEAN NOT NULL DEFAULT false;   -- given by an admin, not Stripe
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS users_stripe_customer_idx ON users (stripe_customer_id);
DO $$ BEGIN
  ALTER TABLE users ADD CONSTRAINT users_plan_check CHECK (plan IN ('free', 'architect', 'master'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Stripe sends some events more than once; each is handled only the first time.
CREATE TABLE IF NOT EXISTS stripe_events (
  id          TEXT PRIMARY KEY,
  type        TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Profile perks. They stay stored if a subscription ends, but only show while it is active.
ALTER TABLE users ADD COLUMN IF NOT EXISTS accent TEXT;                 -- '#rrggbb'
ALTER TABLE users ADD COLUMN IF NOT EXISTS banner TEXT;                 -- a preset banner id
ALTER TABLE users ADD COLUMN IF NOT EXISTS card_theme TEXT;             -- a share-card theme id
ALTER TABLE users ADD COLUMN IF NOT EXISTS featured_achievement TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS vanity CITEXT;               -- battle.aspir3.net/<vanity>
CREATE UNIQUE INDEX IF NOT EXISTS users_vanity_idx ON users (vanity);

-- Paid plans can pin up to 20 builds.
ALTER TABLE pinned_matches DROP CONSTRAINT IF EXISTS pinned_matches_position_check;
ALTER TABLE pinned_matches ADD CONSTRAINT pinned_matches_position_check CHECK (position BETWEEN 1 AND 20);

-- Discord: a channel for automatic posts (new Speed Builders world records).
ALTER TABLE bot_settings ADD COLUMN IF NOT EXISTS feed_channel_id TEXT;

-- Private leaderboards ("groups"): a Master Builder creates one, anyone with the invite code can join.
CREATE TABLE IF NOT EXISTS groups (
  id          BIGSERIAL PRIMARY KEY,
  slug        TEXT UNIQUE NOT NULL,
  name        TEXT NOT NULL CHECK (length(name) BETWEEN 2 AND 40),
  owner_id    BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  invite_code TEXT UNIQUE NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS group_members (
  group_id  BIGINT NOT NULL REFERENCES groups ON DELETE CASCADE,
  user_id   BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX IF NOT EXISTS group_members_user_idx ON group_members (user_id);
