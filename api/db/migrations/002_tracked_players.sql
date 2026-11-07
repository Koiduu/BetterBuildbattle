-- Global ranking covers EVERY Build Battle player we can discover, not just mod users.
-- tracked_players is the ranking universe; users (people who installed the mod / signed up) link to it by uuid.

CREATE TABLE tracked_players (
  mc_uuid        UUID PRIMARY KEY,
  mc_name        CITEXT,
  bb_score       INT,                 -- NULL until first lookup
  bb_wins        INT,
  bb_games       INT,
  title          TEXT,
  source         TEXT NOT NULL,       -- 'mod_user' | 'lobby' | 'leaderboard' | 'guild' | 'search' | 'import'
  discovered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_checked   TIMESTAMPTZ,
  next_check     TIMESTAMPTZ NOT NULL DEFAULT now(),
  check_failures SMALLINT NOT NULL DEFAULT 0,
  no_bb_stats    BOOLEAN NOT NULL DEFAULT false   -- looked up, has never played Build Battle
);
CREATE INDEX tracked_next_check_idx ON tracked_players (next_check) WHERE NOT no_bb_stats;
CREATE INDEX tracked_score_idx ON tracked_players (bb_score DESC) WHERE bb_score IS NOT NULL;
CREATE INDEX tracked_name_idx ON tracked_players (mc_name);

-- Guilds already crawled (guild members are a great discovery source)
CREATE TABLE crawled_guilds (
  guild_id     TEXT PRIMARY KEY,
  name         TEXT,
  last_crawled TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rank snapshots now cover tracked players too (not only signed-up users)
ALTER TABLE rank_snapshots DROP CONSTRAINT rank_snapshots_user_id_fkey;
ALTER TABLE rank_snapshots RENAME COLUMN user_id TO player_key;   -- users.id for mod users
CREATE TABLE player_rank_history (
  mc_uuid     UUID NOT NULL,
  taken_on    DATE NOT NULL,
  global_rank INT,
  bb_score    INT,
  PRIMARY KEY (mc_uuid, taken_on)
);
SELECT create_hypertable('player_rank_history', 'taken_on', chunk_time_interval => INTERVAL '90 days');
