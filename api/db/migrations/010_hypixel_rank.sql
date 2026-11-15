-- The player's own position on Hypixel's in-game Build Battle score leaderboard, read by the mod
-- off the lobby hologram. Hypixel's API doesn't expose it, and our own ranking only counts the
-- players we've discovered, so this is the only way to show the rank people see in game.
ALTER TABLE users ADD COLUMN IF NOT EXISTS hypixel_rank INT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS hypixel_rank_score BIGINT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS hypixel_rank_at TIMESTAMPTZ;
