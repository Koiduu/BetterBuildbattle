-- The Speed Builders update: challenge tags and Guess the Build counts per game (for weekly
-- challenges), and Speed Builders builds learned from play. Safe to re-run on every boot.

-- Nullable and without defaults, so they can be added to the compressed matches hypertable.
ALTER TABLE matches ADD COLUMN IF NOT EXISTS tags TEXT[];          -- one-off challenge ids this game met
ALTER TABLE matches ADD COLUMN IF NOT EXISTS gtb_correct SMALLINT; -- correct guesses in a Guess the Build game

-- Builds are added the first time someone plays them; the name is as Hypixel shows it.
ALTER TABLE spb_builds ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS spb_pb_user_idx ON spb_personal_bests (user_id);
