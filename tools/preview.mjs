// Preview the website with sample data. No database, no API key, no npm install.
//   node tools/preview.mjs   →  http://localhost:5173
// Response shapes match api/src/routes/public.ts.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../web");
const PORT = Number(process.env.PORT ?? 5173);

const TITLES = [
  ["Rookie", 0, "#FFFFFF", false], ["Untrained", 100, "#AAAAAA", false], ["Amateur", 250, "#555555", false],
  ["Prospect", 500, "#55FF55", false], ["Apprentice", 1000, "#00AA00", false], ["Experienced", 2000, "#55FFFF", false],
  ["Seasoned", 3500, "#00AAAA", false], ["Trained", 5000, "#5555FF", false], ["Skilled", 7500, "#0000AA", false],
  ["Talented", 10000, "#AA00AA", false], ["Professional", 15000, "#FF55FF", false], ["Artisan", 20000, "#FF5555", false],
  ["Expert", 40000, "#AA0000", false], ["Master", 75000, "#FFAA00", false], ["Legend", 100000, "#55FF55", true],
  ["Grandmaster", 200000, "#55FFFF", true], ["Celestial", 300000, "#FF55FF", true], ["Divine", 400000, "#FF5555", true],
  ["Ascended", 500000, "#FFAA00", true],
].map(([name, min, hex, bold]) => ({ name, min, hex, bold }));
function titleProgress(score) {
  let cur = TITLES[0];
  for (const t of TITLES) if (score >= t.min) cur = t;
  const next = TITLES.find((t) => t.min > score) ?? null;
  return { current: cur, next, into: score - cur.min, span: next ? next.min - cur.min : 0,
           pct: next ? (score - cur.min) / (next.min - cur.min) : 1 };
}

// ---- sample players (Koidu's Hypixel numbers are real; everything else is made up) ----
const NAMES = ["Celestia", "Bricklayer", "Ambrosia", "Koidu", "Pixelsmith", "Voxelle", "Terracotta", "MossyCobble",
  "Quartzlight", "OakenShield", "Lumen", "Kestrel", "Marbleous", "Glazed", "Nimbus", "Redstone_Ruby"];
const SCORES = [512340, 433100, 318220, 291650, 244900, 201550, 176400, 151000, 120300, 98500, 76200, 54300, 41200, 22100, 9800, 3400];
const players = NAMES.map((name, i) => ({
  name, uuid: `00000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`, score: SCORES[i],
  wins: Math.round(SCORES[i] / 48), usesMod: [0, 3, 5, 9].includes(i), level: [0, 3, 5, 9].includes(i) ? [61, 42, 28, 9][[0, 3, 5, 9].indexOf(i)] : null,
}));
players[3].uuid = "koidu";   // mc-heads resolves names too
players[3].wins = 6124;

const json = (res, code, body) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(body)); };

function profile(p) {
  const rank = players.indexOf(p) + 1;
  const isKoidu = p.name === "Koidu";
  return {
    name: p.name, uuid: p.uuid, score: p.score, title: titleProgress(p.score), rank, rankedPlayers: 412806,
    lastChecked: new Date(Date.now() - 42 * 60_000).toISOString(),
    hypixel: {
      wins: p.wins, gamesPlayed: p.wins * 3, correctGuesses: isKoidu ? 14462 : Math.round(p.score / 25), totalVotes: isKoidu ? 60227 : Math.round(p.score / 5),
      modeWins: isKoidu ? { solo: 1102, teams: 2047, pro: 353, gtb: 1866, speedBuilders: 756 }
                        : { solo: Math.round(p.wins * .3), teams: Math.round(p.wins * .3), pro: Math.round(p.wins * .05), gtb: Math.round(p.wins * .25), speedBuilders: Math.round(p.wins * .1) },
    },
    betterBattle: p.usesMod ? {
      memberSince: "2026-11-01T00:00:00Z", bio: null, level: p.level, xp: 0, xpIntoLevel: 3180, xpForNext: 4700,
      ap: 1265, bestWinStreak: 14, games: 4812, crowdScore: 0.873, themesBuilt: 1284,
      grades: { SS: 214, S: 1032, A: 1588 }, playtimeSec: 612 * 3600,
    } : null,
  };
}

function rankHistory(p, days) {
  const end = players.indexOf(p) + 1 || 50;
  const out = [];
  for (let d = days; d >= 0; d--) {
    const t = (days - d) / days;
    const rank = Math.max(1, Math.round(end + (1 - t) * end * 0.6 + Math.sin(d * 0.7) * end * 0.04));
    out.push({ date: new Date(Date.now() - d * 864e5).toISOString().slice(0, 10), rank, score: Math.round(p.score * (0.9 + 0.1 * t)) });
  }
  return out;
}

const THEMES = ["Volcano", "Bakery", "Pirate Ship", "Castle", "Pagoda", "Ice Cream", "Treehouse", "Haunted Lighthouse", "Space Station", "Waterfall"];
const MODES = ["solo", "solo", "teams", "pro", "speed_builders", "gtb", "solo", "pro", "teams", "solo"];
function matches(mode) {
  const rows = THEMES.map((theme, i) => {
    const placement = [1, 3, 2, 6, 1, 1, 4, 1, 2, 1][i];
    const players = MODES[i] === "teams" ? 12 : MODES[i] === "speed_builders" ? 8 : 12;
    const voters = players - (MODES[i] === "teams" ? 2 : 1);
    const score = MODES[i] === "gtb" || MODES[i] === "speed_builders" ? null : [190, 150, 168, 110, 0, 0, 140, 188, 160, 176][i];
    return { id: String(i), played_at: new Date(Date.now() - (i * 17 + 4) * 60_000).toISOString(), mode: MODES[i], theme, placement, players,
      score, grade: ["SS", "A", "S", "B", "S", "S", "A", "SS", "S", "SS"][i],
      crowd_score: score === null ? null : score / (voters * 18), xp_earned: placement === 1 ? 110 : 30 };
  });
  return mode ? rows.filter((r) => r.mode === mode) : rows;
}

function heatmap() {
  const out = [];
  for (let d = 0; d < 182; d++) {
    const v = (d * 37 + (d % 7) * 11) % 9;
    if (v < 2) continue;
    out.push({ day: new Date(Date.now() - d * 864e5).toISOString().slice(0, 10), games: v * 3 - 4, wins: v });
  }
  return out;
}

const ACH = [
  ["Lumberjack", "Win a game using only wood-type blocks", 20], ["Zero Waste III", "Finish 100 Speed Builders rounds at 100% precision", 20],
  ["Hat Trick", "Win 3 games in a row", 10], ["Theme Collector IV", "Build 500 different themes", 30],
  ["Winner Winner VI", "Win 5,000 Build Battle games", 35], ["Score Climber V", "Reach 200,000 Build Battle score", 30],
  ["Mind Reader V", "Correctly guess 10,000 builds in Guess The Build", 30], ["Aspiring", "Play your first game with Better Battle installed", 5],
].map(([name, description, points], i) => ({ id: "a" + i, name, description, points, category: "general", unlocked_at: new Date().toISOString() }));

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  const p = url.pathname;
  if (p.startsWith("/bb/v1/")) {
    const route = p.slice(6);
    if (route === "/stats") return json(res, 200, { rankedPlayers: 412806, modUsers: 318 });
    if (route === "/leaderboard") {
      const page = Number(url.searchParams.get("page") ?? 1);
      const rows = page === 1 ? players.map((x, i) => ({ rank: i + 1, uuid: x.uuid, name: x.name, score: x.score,
        title: titleProgress(x.score).current, wins: x.wins, usesMod: x.usesMod, level: x.level })) : [];
      return json(res, 200, { page, total: 412806, rows });
    }
    if (route === "/search") {
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      return json(res, 200, players.filter((x) => x.name.toLowerCase().startsWith(q)).slice(0, 8).map((x) => ({ name: x.name, score: x.score })));
    }
    if (route === "/challenges/weekly") return json(res, 200, [
      { id: "w_crowd_85", text: "Get 85%+ crowd score 3 times", xp: 250 },
      { id: "w_spb_precision", text: "Finish 5 SPB rounds at 100% precision", xp: 300 },
      { id: "w_palette", text: "Win with a /bb palette roll", xp: 400 }]);
    const m = route.match(/^\/players\/([^/]+)(\/[a-z-]+)?$/);
    if (m) {
      const pl = players.find((x) => x.name.toLowerCase() === decodeURIComponent(m[1]).toLowerCase());
      if (!pl) return json(res, 404, { error: "not_found" });
      const sub = m[2];
      if (!sub) return json(res, 200, profile(pl));
      if (sub === "/rank-history") return json(res, 200, rankHistory(pl, Number(url.searchParams.get("days") ?? 90)));
      if (!pl.usesMod) return json(res, 200, sub === "/achievements" ? { unlocked: [], total: 0 } : []);
      if (sub === "/matches") return json(res, 200, matches(url.searchParams.get("mode")));
      if (sub === "/heatmap") return json(res, 200, heatmap());
      if (sub === "/achievements") return json(res, 200, { unlocked: ACH, total: 104 });
      if (sub === "/pins") return json(res, 200, [
        { id: "p1", theme: "Haunted Lighthouse", mode: "pro", placement: 1, players: 12, score: 192, grade: "SS", position: 1 },
        { id: "p2", theme: "Treehouse", mode: "solo", placement: 1, players: 16, score: 260, grade: "SS", position: 2 },
        { id: "p3", theme: "Space Station", mode: "teams", placement: 2, players: 12, score: 158, grade: "S", position: 3 }]);
    }
    return json(res, 404, { error: "not_found" });
  }
  // static files with SPA fallback
  const file = path.join(WEB, path.normalize(p).replace(/^(\.\.[/\\])+/, ""));
  const target = fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(WEB, "index.html");
  const type = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" }[path.extname(target)] ?? "application/octet-stream";
  res.writeHead(200, { "content-type": type + "; charset=utf-8" });
  fs.createReadStream(target).pipe(res);
});

server.listen(PORT, () => console.log(`Better Battle preview: http://localhost:${PORT}`));
