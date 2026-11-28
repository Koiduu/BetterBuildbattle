-- Temporary Discord voice channels for Better Battle teams, parties and LFG lobbies.
-- Renumber at merge if another branch took 014. Safe to re-run on every boot.

-- Voice chat is off until the player turns it on on their account page, and they can turn it off again.
-- (An earlier draft of this file called the column adult_confirmed_at; rename it where that ran.)
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'adult_confirmed_at')
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'voice_enabled_at') THEN
    ALTER TABLE users RENAME COLUMN adult_confirmed_at TO voice_enabled_at;
  END IF;
END $$;
ALTER TABLE users ADD COLUMN IF NOT EXISTS voice_enabled_at TIMESTAMPTZ;

-- One row per group that someone asked voice for. The channel is made once two members have asked.
-- Rows go away with the channel: nothing about a call is kept afterwards, and no audio is ever recorded.
CREATE TABLE IF NOT EXISTS voice_channels (
  group_key   TEXT PRIMARY KEY,                 -- 'team:<sorted names>' or 'lobby:<lobby id>'
  scope       TEXT NOT NULL CHECK (scope IN ('team', 'lobby')),
  label       TEXT NOT NULL,                    -- the channel name, e.g. 'Teams · Aspir3 + Koidu'
  channel_id  TEXT UNIQUE,                      -- NULL until a second member asks
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  empty_since TIMESTAMPTZ,                      -- deleted once empty for a few minutes
  expires_at  TIMESTAMPTZ NOT NULL              -- hard cap, whatever happens
);

-- Who asked to be in each group. A player is in one group at a time.
CREATE TABLE IF NOT EXISTS voice_members (
  group_key  TEXT NOT NULL REFERENCES voice_channels ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  discord_id TEXT NOT NULL,
  mc_name    CITEXT NOT NULL,
  joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (group_key, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS voice_members_one_group_idx ON voice_members (user_id);
