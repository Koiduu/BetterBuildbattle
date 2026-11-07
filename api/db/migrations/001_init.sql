-- Better Battle — initial schema
-- PostgreSQL 16 + TimescaleDB 2.x
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============ IDENTITY ============
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  mc_uuid       UUID UNIQUE,
  mc_name       CITEXT,
  email         CITEXT UNIQUE,
  password_hash TEXT,                               -- argon2id; NULL for Discord-only / unclaimed
  discord_id    TEXT UNIQUE,
  avatar_url    TEXT,
  banner_url    TEXT,
  bio           TEXT CHECK (length(bio) <= 1000),
  role          TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user','mod','admin')),
  claimed       BOOLEAN NOT NULL DEFAULT false,     -- false = stub created by first mod login
  flagged       BOOLEAN NOT NULL DEFAULT false,     -- hidden from leaderboards
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ
);
CREATE INDEX users_mc_name_idx ON users (mc_name);

CREATE TABLE sessions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('web','mod')),
  token_hash  BYTEA NOT NULL UNIQUE,                -- sha256(token)
  mod_version TEXT,
  mc_version  TEXT,                                 -- '26.3' | '1.8.9'
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mod_challenges (                      -- Mojang handshake, short-lived
  server_id  TEXT PRIMARY KEY,
  expires_at TIMESTAMPTZ NOT NULL
);

-- ============ STATS ============
CREATE TYPE bb_mode AS ENUM ('solo','teams','pro','gtb','speed_builders','overall');

CREATE TABLE user_stats (
  user_id         BIGINT REFERENCES users ON DELETE CASCADE,
  mode            bb_mode,
  games_played    INT    NOT NULL DEFAULT 0,
  wins            INT    NOT NULL DEFAULT 0,
  total_score     BIGINT NOT NULL DEFAULT 0,
  perfect_builds  INT    NOT NULL DEFAULT 0,
  correct_guesses INT    NOT NULL DEFAULT 0,
  playtime_sec    BIGINT NOT NULL DEFAULT 0,
  grade_ss INT NOT NULL DEFAULT 0, grade_s INT NOT NULL DEFAULT 0, grade_a INT NOT NULL DEFAULT 0,
  grade_b  INT NOT NULL DEFAULT 0, grade_c INT NOT NULL DEFAULT 0,
  rating          REAL   NOT NULL DEFAULT 0,
  crowd_score     REAL,                             -- avg (your vote score / max possible in lobby)
  spb_precision   REAL,                             -- avg (blocks needed / blocks placed + broken)
  themes_built    INT    NOT NULL DEFAULT 0,        -- distinct themes ever built
  hypixel_wins    INT,                              -- last verified from Hypixel API
  hypixel_verified_at TIMESTAMPTZ,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, mode)
);

-- ============ MATCHES ============
CREATE TABLE matches (
  id            UUID NOT NULL DEFAULT gen_random_uuid(),
  user_id       BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  played_at     TIMESTAMPTZ NOT NULL,
  mode          bb_mode NOT NULL,
  theme         TEXT,
  placement     SMALLINT,
  players       SMALLINT,
  score         REAL,
  grade         TEXT CHECK (grade IN ('SS','S','A','B','C')),
  performance   REAL,                               -- input to rating
  rating_delta  REAL,
  duration_sec  INT,
  blocks_placed INT,
  blocks_broken INT,
  max_score     REAL,                               -- highest score possible in that lobby, for crowd score
  palette       JSONB,                              -- {"minecraft:oak_planks": 120, ...}
  spb_rounds    JSONB,                              -- [{"build":"pagoda","time_ms":7840,"accuracy":1.0}]
  client_nonce  UUID NOT NULL,
  mod_version   TEXT,
  mc_version    TEXT,
  flagged       BOOLEAN NOT NULL DEFAULT false,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, played_at),
  UNIQUE (user_id, client_nonce, played_at)
);
SELECT create_hypertable('matches', 'played_at', chunk_time_interval => INTERVAL '7 days');
CREATE INDEX matches_user_time_idx ON matches (user_id, played_at DESC);
CREATE INDEX matches_theme_idx ON matches (theme);
ALTER TABLE matches SET (timescaledb.compress, timescaledb.compress_segmentby = 'user_id');
SELECT add_compression_policy('matches', INTERVAL '30 days');

CREATE TABLE pinned_matches (
  user_id   BIGINT REFERENCES users ON DELETE CASCADE,
  match_id  UUID NOT NULL,
  played_at TIMESTAMPTZ NOT NULL,
  position  SMALLINT NOT NULL CHECK (position BETWEEN 1 AND 10),
  PRIMARY KEY (user_id, match_id),
  UNIQUE (user_id, position)
);

-- ============ RANK HISTORY ============
CREATE TABLE rank_snapshots (
  user_id      BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  mode         bb_mode NOT NULL,
  taken_on     DATE NOT NULL,
  global_rank  INT,
  rating       REAL,
  PRIMARY KEY (user_id, mode, taken_on)
);
SELECT create_hypertable('rank_snapshots', 'taken_on', chunk_time_interval => INTERVAL '90 days');

-- ============ HEATMAP ============
CREATE MATERIALIZED VIEW daily_activity WITH (timescaledb.continuous) AS
SELECT user_id,
       time_bucket('1 day', played_at) AS day,
       count(*)                                  AS games,
       count(*) FILTER (WHERE placement = 1)     AS wins
FROM matches
GROUP BY user_id, day
WITH NO DATA;
SELECT add_continuous_aggregate_policy('daily_activity',
  start_offset => INTERVAL '3 days', end_offset => INTERVAL '1 hour',
  schedule_interval => INTERVAL '15 minutes');

-- ============ ACHIEVEMENTS ============
-- Distinct themes per user (Theme range metric)
CREATE TABLE user_themes (
  user_id BIGINT REFERENCES users ON DELETE CASCADE,
  theme   TEXT NOT NULL,
  times   INT  NOT NULL DEFAULT 1,
  best_score REAL,
  PRIMARY KEY (user_id, theme)
);

CREATE TABLE achievements (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  description TEXT NOT NULL,
  category    TEXT NOT NULL,
  track       TEXT,
  tier        SMALLINT,
  threshold   INT,
  points      SMALLINT NOT NULL,
  secret      BOOLEAN NOT NULL DEFAULT false,
  catalog_version INT NOT NULL
);

CREATE TABLE user_achievements (
  user_id        BIGINT REFERENCES users ON DELETE CASCADE,
  achievement_id TEXT REFERENCES achievements,
  unlocked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  source         TEXT NOT NULL DEFAULT 'live' CHECK (source IN ('live','retroactive','imported')),
  PRIMARY KEY (user_id, achievement_id)
);

-- ============ SPEED BUILDERS ============
CREATE TABLE spb_builds (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  difficulty  SMALLINT,
  block_count INT,
  min_time_ms INT                                   -- anything faster is rejected
);

CREATE TABLE spb_personal_bests (
  user_id    BIGINT REFERENCES users ON DELETE CASCADE,
  build_id   TEXT REFERENCES spb_builds,
  time_ms    INT NOT NULL,
  accuracy   REAL,
  placements JSONB,                                 -- ghost: [[t_ms,x,y,z,"block"], ...]
  set_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, build_id)
);
CREATE INDEX spb_pb_wr_idx ON spb_personal_bests (build_id, time_ms);
