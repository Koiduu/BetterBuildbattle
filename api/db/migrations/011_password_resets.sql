-- Password reset links sent by email. Only a hash of the token is stored; links work once, for one hour.
-- Safe to re-run (every boot runs migrations from 003 on).
CREATE TABLE IF NOT EXISTS password_resets (
  token_hash  BYTEA PRIMARY KEY,                    -- sha256(token)
  user_id     BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS password_resets_user ON password_resets (user_id, created_at DESC);
