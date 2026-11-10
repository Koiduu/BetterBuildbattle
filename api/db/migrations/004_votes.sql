-- Votes a Better Battle user cast on other people's builds (their own votes only:
-- Hypixel never shows anyone else's). Used for the admin view and Build Battle Wrapped.
CREATE TABLE match_votes (
  user_id    BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  match_nonce UUID NOT NULL,
  played_at  TIMESTAMPTZ NOT NULL,
  round      SMALLINT NOT NULL,
  vote       TEXT NOT NULL CHECK (vote IN ('super_poop','poop','meh','ok','good','epic','legendary')),
  builder    CITEXT,
  voted_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, match_nonce, round)
);
CREATE INDEX match_votes_time_idx ON match_votes (voted_at);
CREATE INDEX match_votes_builder_idx ON match_votes (builder);
