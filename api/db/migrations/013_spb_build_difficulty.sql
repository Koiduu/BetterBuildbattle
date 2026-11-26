-- Speed Builders builds are now identified by name AND difficulty ("turret_easy"). Until now only the name
-- was used, so Turret on Simple and Turret on Easy shared one personal best and one world record. There is no
-- way to tell which difficulty an old time came from, so the old records are removed; builds and records are
-- learned again from the next games. Runs once (migrations run on every boot).
DO $$
BEGIN
  CREATE TABLE IF NOT EXISTS one_time_migrations (
    name TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  INSERT INTO one_time_migrations (name) VALUES ('013_spb_build_difficulty') ON CONFLICT DO NOTHING;
  IF FOUND THEN
    DELETE FROM spb_personal_bests;
    DELETE FROM spb_builds;
  END IF;
END $$;
