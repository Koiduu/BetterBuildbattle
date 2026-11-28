-- Custom heads: a player paints a head on the website, the owner reviews it, and once approved a worker
-- uploads it as the skin of a dedicated Minecraft account to get a Mojang-signed texture. Ready heads show
-- up in the mod's Head Database under "My heads".
-- Safe to re-run (every boot runs migrations from 003 on).

CREATE TABLE IF NOT EXISTS custom_heads (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  name              TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 32),
  png               BYTEA NOT NULL,                 -- the 64x64 skin, head region only, as the server re-encoded it
  png_sha256        BYTEA NOT NULL,                 -- identical drawings share one upload
  -- pending: waiting for the owner's review. approved: in the upload queue. ready: has a signed texture.
  -- rejected: turned down by the owner (doesn't count toward the player's limit).
  status            TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'ready', 'rejected')),
  reject_reason     TEXT CHECK (length(reject_reason) <= 200),
  texture_hash      TEXT,                           -- id on textures.minecraft.net
  texture_value     TEXT,                           -- base64 "textures" property, as signed by Mojang
  texture_signature TEXT,
  attempts          INT NOT NULL DEFAULT 0,
  next_attempt_at   TIMESTAMPTZ,
  last_error        TEXT,
  uploaded_at       TIMESTAMPTZ,                    -- set skin; waiting for the session server to sign it
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at       TIMESTAMPTZ,
  ready_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS custom_heads_user_idx ON custom_heads (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS custom_heads_queue_idx ON custom_heads (status, reviewed_at) WHERE status IN ('pending', 'approved');
CREATE INDEX IF NOT EXISTS custom_heads_png_idx ON custom_heads (png_sha256);

-- Every skin the skin account has uploaded, by the PNG's hash, so the same drawing is never uploaded twice.
CREATE TABLE IF NOT EXISTS skin_textures (
  png_sha256        BYTEA PRIMARY KEY,
  texture_hash      TEXT NOT NULL,
  texture_value     TEXT,
  texture_signature TEXT,
  uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The skin account's Microsoft refresh token. Microsoft hands out a new one every time it is used, so it
-- lives here rather than in .env. Encrypted with AES-256-GCM under SKIN_TOKEN_KEY (which stays in .env).
CREATE TABLE IF NOT EXISTS skin_account (
  id                SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  refresh_token_enc BYTEA NOT NULL,
  mc_uuid           UUID,
  mc_name           TEXT,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
