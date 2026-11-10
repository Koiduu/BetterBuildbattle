-- Raw Build Battle totals from the Hypixel API, used for retroactive achievements and profile stats.
ALTER TABLE users ADD COLUMN IF NOT EXISTS hypixel_bb JSONB;
ALTER TABLE tracked_players ADD COLUMN IF NOT EXISTS hypixel_bb JSONB;
