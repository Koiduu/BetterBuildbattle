-- Switch player refreshes to on-demand discovery and account-only rank history.
DO $$
BEGIN
  CREATE TABLE IF NOT EXISTS one_time_migrations (
    name TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  INSERT INTO one_time_migrations (name) VALUES ('009_on_demand_crawler') ON CONFLICT DO NOTHING;
  IF FOUND THEN
    UPDATE tracked_players SET next_check = 'infinity' WHERE last_checked IS NOT NULL;
    DELETE FROM tracked_players WHERE source = 'guild' AND last_checked IS NULL;
    DELETE FROM player_rank_history h
      WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.mc_uuid = h.mc_uuid);
  END IF;
END $$;
