// Seed the global ranking with a whole guild. Default: Aspiring.
//   npx tsx api/scripts/seed-guild.ts            → Aspiring
//   npx tsx api/scripts/seed-guild.ts "Some Guild"
import { queuePlayers } from "../src/jobs/crawler";
import { pool } from "../src/db/pool";
import { HYPIXEL_API, dashUuid } from "../src/lib/upstream";

const name = process.argv[2] ?? "Aspiring";
const res = await fetch(`${HYPIXEL_API}/v2/guild?name=${encodeURIComponent(name)}`, {
  headers: { "API-Key": process.env.HYPIXEL_API_KEY! },
});
if (!res.ok) throw new Error(`Hypixel API ${res.status}`);
const { guild } = await res.json();
if (!guild) throw new Error(`Guild "${name}" not found`);

const uuids: string[] = guild.members.map((m: { uuid: string }) => dashUuid(m.uuid));
await queuePlayers(uuids, "guild", true);      // priority: looked up first
await pool.query(
  `INSERT INTO crawled_guilds (guild_id, name) VALUES ($1, $2) ON CONFLICT (guild_id) DO UPDATE SET last_crawled = now()`,
  [guild._id, guild.name]);
console.log(`Queued ${uuids.length} members of ${guild.name}`);
await pool.end();
