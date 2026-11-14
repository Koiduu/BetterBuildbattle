// The profile card as a PNG, for link previews in Discord and elsewhere. Drawn with the same
// web/card.js the site's Share card button uses. Reads only the database and Redis, plus the
// player's head from mc-heads.net; never Hypixel or Mojang.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createCanvas, loadImage, GlobalFonts } from "@napi-rs/canvas";
import { drawCard, CARD_W, CARD_H } from "../../../web/card.js";
import { pool } from "../db/pool";
import { globalRank } from "../jobs/crawler";
import { titleProgress } from "../lib/titles";

const here = path.dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WEB_DIR ?? path.resolve(here, "../../../web");
const FONT = "Pixelify Sans";
let fontReady = false;

const TTL_MS = 10 * 60_000;
const cache = new Map<string, { at: number; png: Buffer }>();

const fmt = (n: number | null | undefined) => (n == null ? "–" : Number(n).toLocaleString("en-US"));

async function face(uuid: string) {
  try {
    const res = await fetch(`https://mc-heads.net/avatar/${uuid}/160`, { signal: AbortSignal.timeout(2500) });
    if (!res.ok) return null;
    return await loadImage(Buffer.from(await res.arrayBuffer()));
  } catch {
    return null;   // the card has a letter placeholder
  }
}

/** PNG bytes for a player's card, or null if we don't know them. origin: the site, e.g. https://battle.aspir3.net */
export async function profileCard(name: string, origin: string): Promise<Buffer | null> {
  const key = name.toLowerCase();
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.png;

  const r = await pool.query(
    `SELECT t.mc_name, t.mc_uuid, t.bb_score, t.bb_wins, t.bb_games, t.hypixel_bb,
            p.level, p.ap, u.id AS user_id
     FROM tracked_players t
     LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
     LEFT JOIN user_progress p ON p.user_id = u.id
     WHERE t.mc_name = $1 AND t.last_checked IS NOT NULL AND NOT t.no_bb_stats
     ORDER BY t.last_checked DESC LIMIT 1`, [name]);
  const p = r.rows[0];
  if (!p) return null;

  const { rank } = await globalRank(p.mc_uuid);
  const hyp = p.hypixel_bb ?? {};
  let stats: [string, string][];
  if (p.level != null) {
    const m = await pool.query(
      `SELECT avg(crowd_score) AS crowd,
              (SELECT count(*) FROM user_achievements WHERE user_id = $1)::int AS achievements
       FROM matches WHERE user_id = $1`, [p.user_id]);
    const crowd = m.rows[0].crowd;
    stats = [
      ["Global rank", rank ? "#" + fmt(rank) : "–"],
      ["Build Battle score", fmt(p.bb_score)],
      ["Wins", fmt(hyp.wins ?? p.bb_wins)],
      ["Crowd score", crowd == null ? "–" : (Number(crowd) * 100).toFixed(1) + "%"],
      ["Achievements", fmt(m.rows[0].achievements)],
      ["Aspiring Points", fmt(p.ap)],
    ];
  } else {
    stats = [
      ["Global rank", rank ? "#" + fmt(rank) : "–"],
      ["Build Battle score", fmt(p.bb_score)],
      ["Wins", fmt(hyp.wins ?? p.bb_wins)],
      ["Games played", fmt(hyp.games_played ?? p.bb_games)],
      ["Correct guesses", fmt(hyp.correct_guesses)],
      ["Speed Builders wins", fmt(hyp.wins_speed_builders)],
    ];
  }

  if (!fontReady) {
    fontReady = GlobalFonts.registerFromPath(path.join(WEB, "fonts/PixelifySans-Variable.ttf"), FONT) != null;
  }
  const canvas = createCanvas(CARD_W, CARD_H);
  drawCard(canvas.getContext("2d"), {
    name: p.mc_name,
    title: titleProgress(p.bb_score ?? 0).current,
    level: p.level,
    stats,
    url: `${new URL(origin).host}/p/${p.mc_name}`,
  }, await face(p.mc_uuid), { display: `'${FONT}', sans-serif`, body: `'${FONT}', sans-serif` });
  const png = await canvas.encode("png");

  if (cache.size > 500) cache.delete(cache.keys().next().value!);
  cache.set(key, { at: Date.now(), png });
  return png;
}
