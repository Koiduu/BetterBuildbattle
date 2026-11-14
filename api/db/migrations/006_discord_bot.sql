CREATE TABLE IF NOT EXISTS discord_links (
  discord_id TEXT PRIMARY KEY,
  mc_uuid UUID NOT NULL,
  mc_name CITEXT NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bot_settings (
  guild_id TEXT PRIMARY KEY,
  staff_role_id TEXT,
  log_channel_id TEXT,
  ticket_channel_id TEXT
);

CREATE TABLE IF NOT EXISTS mod_warnings (
  id BIGSERIAL PRIMARY KEY,
  guild_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  moderator_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mod_warnings_guild_user_idx ON mod_warnings (guild_id, user_id);

CREATE TABLE IF NOT EXISTS tickets (
  id BIGSERIAL PRIMARY KEY,
  guild_id TEXT NOT NULL,
  thread_id TEXT UNIQUE NOT NULL,
  opener_id TEXT NOT NULL,
  topic TEXT,
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at TIMESTAMPTZ,
  closed_by TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS tickets_one_open_per_user_idx
  ON tickets (guild_id, opener_id) WHERE closed_at IS NULL;
