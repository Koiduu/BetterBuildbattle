-- Raw Build Battle totals from the Hypixel API, used for retroactive achievements and profile stats.
ALTER TABLE users ADD COLUMN hypixel_bb JSONB;
ALTER TABLE tracked_players ADD COLUMN hypixel_bb JSONB;
