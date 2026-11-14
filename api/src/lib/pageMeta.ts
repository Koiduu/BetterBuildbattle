// Link previews: Discord, Slack, X and others read <meta property="og:..."> tags but never run the site's
// JavaScript, so the server writes them into index.html for the pages people share.
// Reads only what's already in the database: a crawler can never trigger a Hypixel or Mojang call.
import { pool } from "../db/pool";
import { titleProgress } from "./titles";

const SITE = "Better Battle";
const DEFAULT = {
  title: SITE,
  description: "Build Battle rankings, profiles and achievements. Better Battle by Koidu.",
};

export interface PageMeta { title: string; description: string; image?: string; path: string }

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const fmt = (n: number | null | undefined) => (n == null ? "–" : Number(n).toLocaleString("en-US"));

/** Preview text for a path, or null to keep the defaults. Never throws. */
export async function metaFor(pathname: string): Promise<PageMeta | null> {
  try {
    const profile = pathname.match(/^\/p\/([A-Za-z0-9_]{1,16})\/?$/);
    if (profile) {
      const r = await pool.query(
        `SELECT t.mc_name, t.mc_uuid, t.bb_score, t.bb_wins, p.level, p.ap
         FROM tracked_players t
         LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
         LEFT JOIN user_progress p ON p.user_id = u.id
         WHERE t.mc_name = $1 AND t.last_checked IS NOT NULL
         ORDER BY t.last_checked DESC LIMIT 1`, [profile[1]]);
      const p = r.rows[0];
      if (!p) return null;
      const title = titleProgress(p.bb_score ?? 0).current.name;
      const parts = [`${title}`, `${fmt(p.bb_score)} Build Battle score`, `${fmt(p.bb_wins)} wins`];
      if (p.level != null) parts.push(`Aspiring Level ${fmt(p.level)}`);
      return {
        title: `${p.mc_name} · ${SITE}`,
        description: parts.join(" · "),
        image: `https://mc-heads.net/avatar/${p.mc_uuid}/128`,
        path: `/p/${p.mc_name}`,
      };
    }
    const build = pathname.match(/^\/speed-builders\/([a-z0-9_]{1,48})\/?$/);
    if (build) {
      const r = await pool.query(
        `SELECT b.name, pb.time_ms, u.mc_name FROM spb_builds b
         LEFT JOIN LATERAL (SELECT p.time_ms, p.user_id FROM spb_personal_bests p JOIN users x ON x.id = p.user_id
                            WHERE p.build_id = b.id AND NOT x.flagged ORDER BY p.time_ms, p.set_at LIMIT 1) pb ON true
         LEFT JOIN users u ON u.id = pb.user_id
         WHERE b.id = $1`, [build[1]]);
      const b = r.rows[0];
      if (!b) return null;
      return {
        title: `${b.name} · Speed Builders · ${SITE}`,
        description: b.time_ms
          ? `Record: ${(b.time_ms / 1000).toFixed(2)}s by ${b.mc_name}. Can you beat it?`
          : "No times yet. Be the first.",
        path: `/speed-builders/${build[1]}`,
      };
    }
    if (/^\/speed-builders\/?$/.test(pathname)) {
      return { title: `Speed Builders records · ${SITE}`, description: "The fastest Perfect Build on every Speed Builders build.", path: "/speed-builders" };
    }
    if (/^\/updates\/?$/.test(pathname)) {
      return { title: `Updates · ${SITE}`, description: "What's new in the Better Battle mod and website.", path: "/updates" };
    }
    if (/^\/leaderboard\/?$/.test(pathname)) {
      return { title: `Leaderboard · ${SITE}`, description: "Every Build Battle player on Hypixel, ranked by score.", path: "/leaderboard" };
    }
  } catch {
    // a preview is never worth a failed page load
  }
  return null;
}

/** index.html with the page's title and preview tags. origin is e.g. "https://battle.aspir3.net". */
export function withMeta(html: string, meta: PageMeta | null, origin: string) {
  const m = meta ?? { ...DEFAULT, path: "/" };
  const tags = [
    `<meta property="og:site_name" content="${SITE}">`,
    `<meta property="og:type" content="website">`,
    `<meta property="og:title" content="${esc(m.title)}">`,
    `<meta property="og:description" content="${esc(m.description)}">`,
    `<meta property="og:url" content="${esc(origin + m.path)}">`,
    m.image ? `<meta property="og:image" content="${esc(m.image)}">` : "",
    `<meta name="twitter:card" content="summary">`,
    `<meta name="theme-color" content="#ff66aa">`,
  ].filter(Boolean).join("\n");
  return html
    .replace(/<title>[^<]*<\/title>/, `<title>${esc(m.title)}</title>`)
    .replace(/<meta name="description" content="[^"]*">/, `<meta name="description" content="${esc(m.description)}">`)
    .replace("</head>", `${tags}\n</head>`);
}
