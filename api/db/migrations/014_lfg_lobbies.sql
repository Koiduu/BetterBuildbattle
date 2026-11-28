-- LFG (looking for group) lobbies: a linked mod user opens a lobby, others ask to join, the host accepts
-- and sends the Hypixel party invite themselves. Runs on every boot, so everything here must be re-runnable.
-- The number may need to change at merge time if another branch adds a migration first.
-- Limits (100 open lobbies, 25 h warning, 30 h lifetime, reports before a name is hidden) live in api/src/services/lfg.ts.

CREATE TABLE IF NOT EXISTS lfg_lobbies (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  host_id       BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  name          TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 32),
  tag           TEXT NOT NULL CHECK (tag IN ('teams','solo','gtb','spb','pro','other')),
  max_size      INT NOT NULL CHECK (max_size BETWEEN 2 AND 16),
  region        TEXT NOT NULL DEFAULT 'any' CHECK (region IN ('any','na','eu','as','oc','sa')),
  note          TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 60),
  password_hash TEXT,                                   -- argon2id; NULL = open to anyone
  status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
  closed_reason TEXT,                                   -- 'host' | 'offline' | 'timeout' | 'replaced' | 'admin'
  host_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),     -- the host's mod polls; stale = host went offline
  expires_at    TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at     TIMESTAMPTZ,
  renamed_at    TIMESTAMPTZ,
  -- Enough different players reported the name: everyone sees "Lobby" until an admin looks at it.
  name_hidden   BOOLEAN NOT NULL DEFAULT false,
  reviewed_at   TIMESTAMPTZ,
  -- Hooks for the Discord side. The bot keeps its lobby post here; a voice-channel feature can keep the
  -- temporary channel it makes for this lobby in voice_channel_id (and listen to the lobby events).
  discord_message_id TEXT,
  voice_channel_id   TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS lfg_lobbies_one_open_idx ON lfg_lobbies (host_id) WHERE status = 'open';
CREATE INDEX IF NOT EXISTS lfg_lobbies_open_idx ON lfg_lobbies (created_at) WHERE status = 'open';

CREATE TABLE IF NOT EXISTS lfg_requests (
  id          BIGSERIAL PRIMARY KEY,
  lobby_id    UUID NOT NULL REFERENCES lfg_lobbies ON DELETE CASCADE,
  user_id     BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  status      TEXT NOT NULL DEFAULT 'pending'
              CHECK (status IN ('pending','accepted','declined','kicked','blocked','left')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at  TIMESTAMPTZ,
  UNIQUE (lobby_id, user_id)
);
CREATE INDEX IF NOT EXISTS lfg_requests_user_idx ON lfg_requests (user_id, status);

-- One report per player per lobby.
CREATE TABLE IF NOT EXISTS lfg_reports (
  lobby_id    UUID NOT NULL REFERENCES lfg_lobbies ON DELETE CASCADE,
  user_id     BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  name        TEXT NOT NULL,                            -- the name as it was when reported
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (lobby_id, user_id)
);

-- Hosts waiting for a free slot when every lobby is taken. offer_until is set when a slot is held for them.
CREATE TABLE IF NOT EXISTS lfg_queue (
  user_id       BIGINT PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  settings      JSONB NOT NULL,                         -- name, tag, maxSize, region, note
  password_hash TEXT,
  queued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),     -- polls; a host who stops polling drops out of line
  offer_until   TIMESTAMPTZ
);

-- Admins can stop a player from hosting lobbies.
ALTER TABLE users ADD COLUMN IF NOT EXISTS lfg_host_banned BOOLEAN NOT NULL DEFAULT false;

-- Where the Discord bot posts open lobbies. NULL = don't post.
ALTER TABLE bot_settings ADD COLUMN IF NOT EXISTS lfg_channel_id TEXT;

-- Closed lobbies are kept a week (reports and the Discord clean-up), then removed.
DELETE FROM lfg_lobbies WHERE status = 'closed' AND closed_at < now() - interval '7 days';
