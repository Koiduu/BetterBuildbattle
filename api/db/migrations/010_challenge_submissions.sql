-- Community challenge ideas sent from the website. Reviewed by hand; approved ones go into the mod's catalog.
CREATE TABLE IF NOT EXISTS challenge_submissions (
  id BIGSERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  mode TEXT NOT NULL,
  mc_name TEXT,
  contact TEXT,
  ip_hash TEXT NOT NULL,
  emailed BOOLEAN NOT NULL DEFAULT false,
  status TEXT NOT NULL DEFAULT 'new',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS challenge_submissions_created ON challenge_submissions (created_at DESC);
