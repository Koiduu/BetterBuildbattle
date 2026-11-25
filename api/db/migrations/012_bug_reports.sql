-- /bb report: a player's note and the filtered log of their last games (game messages, scoreboard and the
-- mod's decisions; the mod leaves out all chat before sending). Deleted after 90 days.
-- Safe to re-run (every boot runs migrations from 003 on).
CREATE TABLE IF NOT EXISTS bug_reports (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT REFERENCES users ON DELETE CASCADE,
  mod_version TEXT NOT NULL,
  mc_version  TEXT NOT NULL,
  note        TEXT NOT NULL DEFAULT '',
  log         TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'new',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS bug_reports_created ON bug_reports (created_at DESC);
