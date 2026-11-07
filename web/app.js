// Better Battle website. Plain ES modules, no build step.
// Talks to the API at /bb/v1 on the same origin.

const API = "/bb/v1";
const app = document.getElementById("app");

// ---------------------------------------------------------------- helpers
const esc = (v) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const fmt = (n) => (n === null || n === undefined ? "–" : Number(n).toLocaleString("en-US"));
const pct = (x, digits = 1) => (x === null || x === undefined ? "–" : (x * 100).toFixed(digits) + "%");
const head = (uuid, size = 64) => `https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/${size}`;

async function api(path) {
  const res = await fetch(API + path, { headers: { Accept: "application/json" } });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(body.error || res.statusText), { status: res.status, body });
  return body;
}

function ago(iso) {
  if (!iso) return "never";
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + " min ago";
  if (s < 86400) return Math.floor(s / 3600) + " h ago";
  return Math.floor(s / 86400) + " d ago";
}

function titleSpan(t) {
  if (!t) return "";
  return `<span class="mc-title${t.bold ? " bold" : ""}" style="color:${esc(t.hex)}">${esc(t.name)}</span>`;
}

const MODE_LABEL = { solo: "Solo", teams: "Teams", pro: "Pro", gtb: "Guess the Build", speed_builders: "Speed Builders" };
const GRADE_COLOR = { SS: "#ff66aa", S: "#c78bff", A: "#7ab8ff", B: "#b5aac1", C: "#9a8fa6" };
const ordinal = (n) => n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : ["th", "st", "nd", "rd"][n % 10] || "th");

// ---------------------------------------------------------------- router
const routes = [
  [/^\/$/, renderHome, "home"],
  [/^\/leaderboard\/?$/, renderLeaderboard, "leaderboard"],
  [/^\/p\/([A-Za-z0-9_]{1,16})\/?$/, renderProfile, null],
];

function navigate(url, replace = false) {
  if (replace) history.replaceState(null, "", url);
  else history.pushState(null, "", url);
  route();
}

async function route() {
  const path = location.pathname;
  for (const [re, fn, nav] of routes) {
    const m = path.match(re);
    if (!m) continue;
    document.querySelectorAll("[data-nav]").forEach((a) =>
      a.dataset.nav === nav ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
    window.scrollTo(0, 0);
    try {
      await fn(...m.slice(1));
    } catch (e) {
      console.error(e);
      app.innerHTML = `<div class="card empty">Something went wrong loading this page. <a href="${esc(path)}" data-link>Try again</a></div>`;
    }
    return;
  }
  app.innerHTML = `<div class="card empty">Page not found. <a href="/" data-link>Go home</a></div>`;
}

document.addEventListener("click", (e) => {
  const a = e.target.closest("a[data-link]");
  if (!a || e.metaKey || e.ctrlKey || e.shiftKey || a.target) return;
  e.preventDefault();
  navigate(a.getAttribute("href"));
});
window.addEventListener("popstate", route);

// ---------------------------------------------------------------- search box (top bar)
const form = document.getElementById("search-form");
const input = document.getElementById("search-input");
const suggest = document.getElementById("suggest");
let suggestTimer;
input.addEventListener("input", () => {
  clearTimeout(suggestTimer);
  const q = input.value.trim();
  if (!q) { suggest.hidden = true; return; }
  suggestTimer = setTimeout(async () => {
    try {
      const rows = await api("/search?q=" + encodeURIComponent(q));
      suggest.innerHTML = rows.map((r) =>
        `<li><a href="/p/${esc(r.name)}" data-link role="option"><b>${esc(r.name)}</b><span>${fmt(r.score)}</span></a></li>`).join("");
      suggest.hidden = rows.length === 0;
    } catch { suggest.hidden = true; }
  }, 180);
});
input.addEventListener("blur", () => setTimeout(() => (suggest.hidden = true), 150));
form.addEventListener("submit", (e) => {
  e.preventDefault();
  const q = input.value.trim();
  if (/^[A-Za-z0-9_]{1,16}$/.test(q)) { suggest.hidden = true; input.value = ""; navigate("/p/" + q); }
});

// ---------------------------------------------------------------- home
async function renderHome() {
  document.title = "Better Battle · Build Battle rankings";
  app.innerHTML = `
    <section class="home-hero">
      <span class="chip accent" style="align-self:flex-start">Build Battle · Speed Builders · Guess the Build</span>
      <h1>Every Build Battle player, ranked.</h1>
      <p>Look up anyone who has played Build Battle on Hypixel. Install the Better Battle mod to add match history, levels, achievements and Speed Builders analysis to your profile.</p>
      <form class="big-search" id="home-search">
        <label for="home-q" class="sr-only">Minecraft username</label>
        <input id="home-q" placeholder="Minecraft username" maxlength="16" autocomplete="off" spellcheck="false">
        <button class="btn primary" type="submit">View profile</button>
      </form>
      <div class="muted" id="home-stats">&nbsp;</div>
    </section>
    <section class="card">
      <div class="head"><h2>Top builders</h2><a href="/leaderboard" data-link>Full leaderboard</a></div>
      <div id="home-top"><div class="skeleton" style="height:320px"></div></div>
    </section>`;
  document.getElementById("home-search").addEventListener("submit", (e) => {
    e.preventDefault();
    const q = document.getElementById("home-q").value.trim();
    if (/^[A-Za-z0-9_]{1,16}$/.test(q)) navigate("/p/" + q);
  });
  const [stats, lb] = await Promise.all([api("/stats").catch(() => null), api("/leaderboard?page=1")]);
  if (stats) document.getElementById("home-stats").textContent =
    `${fmt(stats.rankedPlayers)} players ranked · ${fmt(stats.modUsers)} using Better Battle`;
  document.getElementById("home-top").innerHTML = leaderboardTable(lb.rows.slice(0, 10));
}

// ---------------------------------------------------------------- leaderboard
function leaderboardTable(rows) {
  if (!rows.length) return `<div class="empty">No players ranked yet. The crawler fills this in as it finds players.</div>`;
  return `<div class="table-wrap"><table>
    <thead><tr><th>#</th><th>Player</th><th>Title</th><th class="r">Score</th><th class="r">Wins</th><th class="r">Level</th></tr></thead>
    <tbody>${rows.map((r) => `
      <tr>
        <td class="num">${fmt(r.rank)}</td>
        <td><a class="player" href="/p/${esc(r.name)}" data-link><img src="${head(r.uuid, 32)}" alt="" loading="lazy" width="28" height="28">${esc(r.name ?? "Unknown")}${r.usesMod ? '<span class="mod-badge" title="Uses Better Battle">BB</span>' : ""}</a></td>
        <td>${titleSpan(r.title)}</td>
        <td class="r num">${fmt(r.score)}</td>
        <td class="r">${fmt(r.wins)}</td>
        <td class="r">${r.level ? fmt(r.level) : '<span class="muted">–</span>'}</td>
      </tr>`).join("")}</tbody></table></div>`;
}

async function renderLeaderboard() {
  const page = Math.max(1, Number(new URLSearchParams(location.search).get("page")) || 1);
  document.title = "Leaderboard · Better Battle";
  app.innerHTML = `<section class="card">
    <div class="head"><div><h2>Global leaderboard</h2><div class="sub" id="lb-sub">Ranked by Build Battle score</div></div>
      <div class="pager" id="lb-pager"></div></div>
    <div id="lb-body"><div class="skeleton" style="height:600px"></div></div></section>`;
  const lb = await api("/leaderboard?page=" + page);
  const pages = Math.max(1, Math.ceil(lb.total / 50));
  document.getElementById("lb-sub").textContent = `Ranked by Build Battle score · ${fmt(lb.total)} players tracked`;
  document.getElementById("lb-body").innerHTML = leaderboardTable(lb.rows);
  document.getElementById("lb-pager").innerHTML = `
    ${page > 1 ? `<a class="btn" href="/leaderboard?page=${page - 1}" data-link>Previous</a>` : ""}
    <span class="muted">Page ${fmt(page)} of ${fmt(pages)}</span>
    ${page < pages ? `<a class="btn" href="/leaderboard?page=${page + 1}" data-link>Next</a>` : ""}`;
}

// ---------------------------------------------------------------- profile
async function renderProfile(name) {
  document.title = `${name} · Better Battle`;
  app.innerHTML = `<div class="skeleton" style="height:180px"></div><div class="skeleton" style="height:300px"></div>`;
  let p;
  try {
    p = await api("/players/" + encodeURIComponent(name));
  } catch (e) {
    const msg = e.status === 404
      ? (e.body?.error === "never_played_build_battle"
          ? `${esc(e.body.name ?? name)} hasn't played Build Battle yet.`
          : `No Minecraft player called ${esc(name)}.`)
      : "Couldn't load this profile. Hypixel's API may be busy, try again in a minute.";
    app.innerHTML = `<div class="card empty">${msg}</div>`;
    return;
  }
  document.title = `${p.name} · Better Battle`;
  if (p.name !== name) history.replaceState(null, "", "/p/" + p.name);
  const bb = p.betterBattle;
  const t = p.title;

  app.innerHTML = `
    <section class="hero">
      <div class="hero-id">
        <img class="avatar" src="${head(p.uuid, 84)}" alt="" width="84" height="84">
        <div class="hero-meta">
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <span class="chip" style="background:var(--surface)">${titleSpan(t.current)}</span>
            ${bb ? '<span class="chip accent">Better Battle</span>' : ""}
          </div>
          <h1>${esc(p.name)}</h1>
          ${bb ? `<div class="progress">
              <div class="labels"><b>Aspiring Level ${fmt(bb.level)}</b><span class="muted">${fmt(bb.xpIntoLevel)} / ${fmt(bb.xpForNext)} XP</span></div>
              <div class="bar"><div style="width:${(100 * bb.xpIntoLevel / bb.xpForNext).toFixed(1)}%"></div></div></div>` : ""}
          <div class="progress">
            ${t.next
              ? `<div class="labels"><span>${titleSpan(t.current)} <span class="muted">to</span> ${titleSpan(t.next)}</span><span class="muted">${fmt(t.into)} / ${fmt(t.span)}</span></div>
                 <div class="bar"><div style="width:${(t.pct * 100).toFixed(2)}%;background:${esc(t.next.hex)}"></div></div>`
              : `<div class="labels"><span>${titleSpan(t.current)} <span class="muted">· highest title</span></span></div>`}
          </div>
        </div>
      </div>
      <div class="kpis">
        ${kpi("Global rank", "#", p.rank ? "#" + fmt(p.rank) : "–", `of ${fmt(p.rankedPlayers)} tracked`)}
        ${kpi("Build Battle score", "S", fmt(p.score), `${fmt(p.hypixel.wins)} wins`)}
        ${bb ? kpi("Crowd score", "C", pct(bb.crowdScore), "avg share of max votes")
             : kpi("Games played", "G", fmt(p.hypixel.gamesPlayed), "all modes")}
        ${bb ? kpi("Aspiring Points", "AP", fmt(bb.ap), `${fmt(bb.themesBuilt)} themes built`)
             : kpi("Correct guesses", "?", fmt(p.hypixel.correctGuesses), "Guess the Build")}
      </div>
    </section>

    <section class="strip">
      <div class="grow-text">
        <span class="dot${bb ? "" : " off"}"></span>
        <div style="display:flex;flex-direction:column">
          <b style="font-size:14px">${bb ? "Synced via Better Battle mod" : "Hypixel stats only"}</b>
          <span class="muted" style="font-size:12px">Hypixel stats updated ${ago(p.lastChecked)}${bb ? "" : " · install the mod to add match history"}</span>
        </div>
      </div>
      <button class="btn primary" id="share">Share profile</button>
    </section>

    <section class="row">
      <div class="card grow">
        <div class="head"><div><h2>Rank history</h2><div class="sub">Global rank by Build Battle score</div></div>
          <div class="seg" role="group" aria-label="Range" id="range">
            <button data-days="30">30d</button><button data-days="90" aria-pressed="true">90d</button><button data-days="365">1y</button>
          </div></div>
        <div class="graph" id="graph"></div>
        <div class="axis"><span id="axis-start">&nbsp;</span><span>Today</span></div>
      </div>
      <div class="card side">
        <h2>Wins by mode</h2>
        <div class="modes">
          ${modeBox("Solo", p.hypixel.modeWins.solo)}${modeBox("Teams", p.hypixel.modeWins.teams)}
          ${modeBox("Pro", p.hypixel.modeWins.pro)}${modeBox("Guess the Build", p.hypixel.modeWins.gtb)}
          ${modeBox("Speed Builders", p.hypixel.modeWins.speedBuilders)}${modeBox("Total votes", p.hypixel.totalVotes)}
        </div>
      </div>
    </section>

    ${bb ? betterBattleSections(bb) : installCta()}
  `;

  document.getElementById("share").addEventListener("click", async (e) => {
    const url = location.origin + "/p/" + p.name;
    try {
      if (navigator.share) await navigator.share({ title: `${p.name} on Better Battle`, url });
      else { await navigator.clipboard.writeText(url); e.target.textContent = "Link copied"; }
    } catch { /* user cancelled */ }
  });

  // rank graph
  const loadGraph = async (days) => {
    document.querySelectorAll("#range button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.days == days)));
    document.getElementById("axis-start").textContent = days >= 365 ? "1 year ago" : `${days} days ago`;
    const pts = await api(`/players/${encodeURIComponent(p.name)}/rank-history?days=${days}`).catch(() => []);
    drawRankGraph(document.getElementById("graph"), pts);
  };
  document.getElementById("range").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-days]");
    if (b) loadGraph(Number(b.dataset.days));
  });
  loadGraph(90);

  if (bb) loadBetterBattle(p.name);
}

const kpi = (label, glyph, value, note) => `
  <div class="kpi"><div class="label"><span>${esc(label)}</span><span class="glyph" aria-hidden="true">${esc(glyph)}</span></div>
  <div class="value">${value}</div><div class="note">${esc(note)}</div></div>`;
const modeBox = (k, v) => `<div class="mode"><span class="k">${esc(k)}</span><span class="v">${fmt(v)}</span></div>`;

function installCta() {
  return `<section class="card cta">
    <h2>Unlock the full profile</h2>
    <p class="muted" style="margin:0">With the Better Battle mod you get recent games with themes and grades, an activity heatmap, Aspiring Levels, achievements, weekly challenges, crowd score and Speed Builders precision tips. Works on Fabric 26.3 and Forge 1.8.9.</p>
    <div><a class="btn primary" href="https://github.com/Koiduu/BetterBuildbattle" target="_blank" rel="noopener">Get the mod</a></div>
  </section>`;
}

function betterBattleSections(bb) {
  return `
    <section class="row">
      <div class="card grow">
        <div class="head"><div><h2>Activity</h2><div class="sub">Games per day, last 26 weeks</div></div>
          <div style="display:flex;gap:24px">
            <div><div class="num" style="font-size:22px;font-weight:600">${fmt(bb.games)}</div><div class="sub">Games logged</div></div>
            <div><div class="num" style="font-size:22px;font-weight:600">${fmt(bb.bestWinStreak)}</div><div class="sub">Best win streak</div></div>
            <div><div class="num" style="font-size:22px;font-weight:600">${fmt(Math.round(bb.playtimeSec / 3600))}h</div><div class="sub">Play time</div></div>
          </div></div>
        <div class="heat-wrap" id="heat"><div class="skeleton" style="height:122px"></div></div>
        <div class="legend">Less ${HEAT.map((c) => `<i style="background:${c}"></i>`).join("")} More</div>
      </div>
      <div class="card side">
        <div class="head"><h2>Build grades</h2></div>
        <div class="modes" style="grid-template-columns:repeat(3,minmax(0,1fr))">${["SS", "S", "A"].map((g) =>
          `<div class="mode"><span class="k" style="color:${GRADE_COLOR[g]};font-weight:700">${g}</span><span class="v">${fmt(bb.grades[g])}</span></div>`).join("")}</div>
        <h2 style="margin-top:6px">Pinned builds</h2>
        <div class="list" id="pins"><div class="muted">Loading…</div></div>
      </div>
    </section>

    <section class="row">
      <div class="card grow">
        <div class="head"><h2>Recent achievements</h2><span class="muted" id="ach-count"></span></div>
        <div class="grid-ach" id="achievements"><div class="muted">Loading…</div></div>
      </div>
      <div class="card side">
        <div class="head"><h2>Weekly challenges</h2><span class="muted" style="font-size:12px">Resets Monday</span></div>
        <div class="list" id="weekly"><div class="muted">Loading…</div></div>
      </div>
    </section>

    <section class="card">
      <div class="head"><h2>Recent games</h2>
        <div class="seg" role="group" aria-label="Mode filter" id="mode-filter">
          <button data-mode="" aria-pressed="true">All</button>
          ${Object.entries(MODE_LABEL).map(([k, v]) => `<button data-mode="${k}">${v}</button>`).join("")}
        </div></div>
      <div id="games"><div class="skeleton" style="height:240px"></div></div>
    </section>`;
}

const HEAT = ["#2b2434", "#4a2840", "#7a3358", "#b8467c", "#ff66aa"];

async function loadBetterBattle(name) {
  const n = encodeURIComponent(name);
  const [heat, ach, pins, weekly] = await Promise.all([
    api(`/players/${n}/heatmap`).catch(() => []),
    api(`/players/${n}/achievements`).catch(() => ({ unlocked: [], total: 0 })),
    api(`/players/${n}/pins`).catch(() => []),
    api(`/challenges/weekly`).catch(() => []),
  ]);

  // heatmap: 26 weeks ending this week, Monday-first columns
  const byDay = new Map(heat.map((d) => [String(d.day).slice(0, 10), d.games]));
  const today = new Date(); today.setUTCHours(0, 0, 0, 0);
  const dow = (today.getUTCDay() + 6) % 7;
  const start = new Date(today); start.setUTCDate(start.getUTCDate() - dow - 25 * 7);
  const cells = [];
  for (let i = 0; i < 26 * 7; i++) {
    const d = new Date(start); d.setUTCDate(start.getUTCDate() + i);
    const key = d.toISOString().slice(0, 10);
    const g = byDay.get(key) ?? 0;
    const lvl = d > today ? -1 : g === 0 ? 0 : g < 5 ? 1 : g < 10 ? 2 : g < 20 ? 3 : 4;
    cells.push(lvl < 0 ? `<div></div>` : `<div style="background:${HEAT[lvl]}" title="${key}: ${g} game${g === 1 ? "" : "s"}"></div>`);
  }
  document.getElementById("heat").innerHTML = `<div class="heat">
    <div class="heat-days"><span>Mon</span><span></span><span>Wed</span><span></span><span>Fri</span><span></span><span>Sun</span></div>
    <div class="heat-grid">${cells.join("")}</div></div>`;

  document.getElementById("ach-count").textContent = `${fmt(ach.unlocked.length)} of ${fmt(ach.total)}`;
  document.getElementById("achievements").innerHTML = ach.unlocked.length
    ? ach.unlocked.slice(0, 8).map((a) => `<div class="item"><div class="tile">+${fmt(a.points)}</div>
        <div class="t"><b>${esc(a.name)}</b><span>${esc(a.description)}</span></div></div>`).join("")
    : `<div class="muted">No achievements yet.</div>`;

  document.getElementById("pins").innerHTML = pins.length
    ? pins.map((m) => `<div class="item"><div class="tile" style="background:${GRADE_COLOR[m.grade] ?? "#b5aac1"}">${esc(m.grade ?? "–")}</div>
        <div class="t"><b>${esc(m.theme ?? "Untitled")}</b><span>${esc(MODE_LABEL[m.mode] ?? m.mode)} · ${m.placement ? ordinal(m.placement) : "–"} of ${fmt(m.players)}</span></div>
        <span class="num">${fmt(m.score)}</span></div>`).join("")
    : `<div class="muted">Nothing pinned yet.</div>`;

  document.getElementById("weekly").innerHTML = weekly.length
    ? weekly.map((w) => `<div class="item"><div class="t"><b>${esc(w.text)}</b><span>${fmt(w.xp)} XP</span></div></div>`).join("")
    : `<div class="muted">No challenges this week yet.</div>`;

  const loadGames = async (mode) => {
    document.querySelectorAll("#mode-filter button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.mode === mode)));
    const rows = await api(`/players/${n}/matches${mode ? "?mode=" + mode : ""}`).catch(() => []);
    document.getElementById("games").innerHTML = rows.length ? `<div class="table-wrap"><table>
      <thead><tr><th>Theme</th><th>Mode</th><th>Place</th><th class="r">Score</th><th class="r">Crowd</th><th>Grade</th><th class="r">XP</th><th class="r">When</th></tr></thead>
      <tbody>${rows.map((m) => `<tr>
        <td><b>${esc(m.theme ?? "–")}</b></td>
        <td class="muted">${esc(MODE_LABEL[m.mode] ?? m.mode)}</td>
        <td><span class="place${m.placement === 1 ? " win" : ""}">${m.placement === 1 ? "Win" : m.placement ? ordinal(m.placement) : "–"}</span></td>
        <td class="r">${fmt(m.score)}</td>
        <td class="r">${pct(m.crowd_score, 0)}</td>
        <td style="font-family:var(--display);font-weight:700;color:${GRADE_COLOR[m.grade] ?? "inherit"}">${esc(m.grade ?? "–")}</td>
        <td class="r" style="color:var(--accent-text)">${m.xp_earned ? "+" + fmt(m.xp_earned) : "–"}</td>
        <td class="r muted">${ago(m.played_at)}</td></tr>`).join("")}</tbody></table></div>`
      : `<div class="empty">No games logged${mode ? " in this mode" : ""} yet.</div>`;
  };
  document.getElementById("mode-filter").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-mode]");
    if (b) loadGames(b.dataset.mode);
  });
  loadGames("");
}

// ---------------------------------------------------------------- rank graph (SVG, lower rank number = higher on the chart)
function drawRankGraph(el, pts) {
  if (pts.length < 2) {
    el.innerHTML = `<div class="empty" style="height:220px;display:grid;place-items:center">Rank history starts building today. Check back tomorrow.</div>`;
    return;
  }
  const W = 600, H = 220, pad = 14;
  const ranks = pts.map((p) => p.rank);
  const lo = Math.min(...ranks), hi = Math.max(...ranks);
  const span = Math.max(1, hi - lo);
  const x = (i) => (i / (pts.length - 1)) * W;
  const y = (r) => pad + ((r - lo) / span) * (H - 2 * pad);   // best rank at top
  const line = pts.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(p.rank).toFixed(1)}`).join(" ");
  const last = pts[pts.length - 1];
  el.innerHTML = `
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="Global rank went from #${fmt(pts[0].rank)} to #${fmt(last.rank)}">
      <defs><linearGradient id="rf" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff66aa" stop-opacity=".28"/><stop offset="1" stop-color="#ff66aa" stop-opacity="0"/></linearGradient></defs>
      ${[0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${W}" y1="${H * f}" y2="${H * f}" stroke="rgba(255,255,255,.06)"/>`).join("")}
      <path d="${line} L${W} ${H} L0 ${H} Z" fill="url(#rf)"/>
      <path d="${line}" fill="none" stroke="#ff66aa" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    </svg>
    <div class="tip" style="left:100%;top:${(y(last.rank) / H) * 100}%;transform:translate(-100%,-130%)"><span class="muted">Today </span><b>#${fmt(last.rank)}</b></div>`;
  const svg = el.querySelector("svg");
  const tip = document.createElement("div");
  tip.className = "tip"; tip.hidden = true; el.appendChild(tip);
  svg.addEventListener("mousemove", (e) => {
    const r = svg.getBoundingClientRect();
    const i = Math.round(((e.clientX - r.left) / r.width) * (pts.length - 1));
    const p = pts[Math.max(0, Math.min(pts.length - 1, i))];
    tip.hidden = false;
    tip.style.left = ((x(i) / W) * 100) + "%";
    tip.style.top = ((y(p.rank) / H) * 100) + "%";
    tip.innerHTML = `<span class="muted">${esc(String(p.date).slice(0, 10))} </span><b>#${fmt(p.rank)}</b> · ${fmt(p.score)}`;
  });
  svg.addEventListener("mouseleave", () => (tip.hidden = true));
}

route();
