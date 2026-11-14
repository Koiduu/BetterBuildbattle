import { pool } from "../db/pool";

export async function saveDiscordLink(discordId: string, uuid: string, mcName: string) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [uuid]);
    await client.query("DELETE FROM discord_links WHERE mc_uuid = $1 AND discord_id <> $2", [uuid, discordId]);
    await client.query(
      `INSERT INTO discord_links (discord_id, mc_uuid, mc_name)
       VALUES ($1, $2, $3)
       ON CONFLICT (discord_id) DO UPDATE
         SET mc_uuid = EXCLUDED.mc_uuid, mc_name = EXCLUDED.mc_name, verified_at = now()`,
      [discordId, uuid, mcName],
    );
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

export async function resolveStatsName(discordId: string) {
  const linked = await pool.query("SELECT mc_name FROM discord_links WHERE discord_id = $1", [discordId]);
  if (linked.rows[0]?.mc_name) return String(linked.rows[0].mc_name);
  const account = await pool.query(
    "SELECT mc_name FROM users WHERE discord_id = $1 AND mc_name IS NOT NULL", [discordId]);
  return account.rows[0]?.mc_name ? String(account.rows[0].mc_name) : null;
}
