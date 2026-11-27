-- Guess the Build: one row per correct guess a mod user saw someone else make. Evidence for the owner to
-- review before passing a case to Hypixel, never a verdict: nothing public reads these tables.
-- Kept to what the check needs (no chat), and deleted after 90 days (api/src/services/gtbWatch.ts).
-- Renumber at merge if another migration took 014. Safe to re-run (every boot runs migrations from 003 on).
CREATE TABLE IF NOT EXISTS gtb_guess_events (
  id              BIGSERIAL PRIMARY KEY,
  reporter_id     BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  -- The same for every mod user in that game: Hypixel server id, the sidebar date and the first builder.
  game_key        TEXT NOT NULL,
  round           INT NOT NULL,
  guesser_name    CITEXT NOT NULL,
  builder         CITEXT,
  ms_since_start  INT NOT NULL,     -- from the round's builder starting to the guess
  ms_since_reveal INT,              -- from the last hint letter appearing to the guess (null: no letter yet)
  revealed        INT NOT NULL,     -- hint letters showing when they guessed
  word_length     INT,
  word            TEXT,             -- the theme, from "The theme was: ..." (not personal data)
  difficulty      TEXT,
  seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Each reporter counts once per round per guesser.
  UNIQUE (reporter_id, game_key, round, guesser_name)
);
CREATE INDEX IF NOT EXISTS gtb_guess_events_guesser ON gtb_guess_events (guesser_name, seen_at DESC);
CREATE INDEX IF NOT EXISTS gtb_guess_events_seen ON gtb_guess_events (seen_at);

-- The owner's review of a case: dismissed cases stop showing in game; notes stay private.
CREATE TABLE IF NOT EXISTS gtb_reviews (
  guesser_name CITEXT PRIMARY KEY,
  status       TEXT NOT NULL DEFAULT 'open',   -- open | dismissed | reported
  note         TEXT NOT NULL DEFAULT '',
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
