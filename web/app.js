const API = "/bb/v1";
const app = document.getElementById("app");

const MODES = {
  solo: "Solo",
  teams: "Teams",
  pro: "Pro",
  gtb: "Guess the Build",
  speed_builders: "Speed Builders",
};

const GRADE_COLORS = { SS: "#ff66aa", S: "#c78bff", A: "#7ab8ff", B: "#b5aac1", C: "#9a8fa6" };
const HEAT_COLORS = ["#2b2434", "#4a2840", "#7a3358", "#b8467c", "#ff66aa"];
const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function num(n) {
  return n == null ? "–" : Number(n).toLocaleString("en-US");
}

function percent(x, digits = 1) {
  return x == null ? "–" : (x * 100).toFixed(digits) + "%";
}

function ordinal(n) {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
}

function timeAgo(iso) {
  if (!iso) return "never";
  const secs = (Date.now() - new Date(iso)) / 1000;
  if (secs < 60) return "just now";
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)} h ago`;
  return `${Math.floor(secs / 86400)} d ago`;
}

function avatar(uuid, size) {
  return `https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/${size}`;
}

function title(t) {
  if (!t) return "";
  return `<span class="mc-title${t.bold ? " bold" : ""}" style="color:${esc(t.hex)}">${esc(t.name)}</span>`;
}

async function get(path) {
  const res = await fetch(API + path);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.error || res.statusText);
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

// Routing

const routes = [
  { path: /^\/$/, page: homePage, nav: "home" },
  { path: /^\/leaderboard\/?$/, page: leaderboardPage, nav: "leaderboard" },
  { path: /^\/p\/([A-Za-z0-9_]{1,16})\/?$/, page: profilePage },
];

function go(url) {
  history.pushState(null, "", url);
  render();
}

async function render() {
  const route = routes.find((r) => r.path.test(location.pathname));
  document.querySelectorAll("[data-nav]").forEach((link) => {
    if (route && link.dataset.nav === route.nav) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  window.scrollTo(0, 0);

  if (!route) {
    app.innerHTML = `<div class="card empty">Page not found. <a href="/" data-link>Back to home</a></div>`;
    return;
  }
  try {
    await route.page(...location.pathname.match(route.path).slice(1));
  } catch (err) {
    console.error(err);
    app.innerHTML = `<div class="card empty">Something went wrong loading this page. <a href="${esc(location.pathname)}" data-link>Try again</a></div>`;
  }
}

document.addEventListener("click", (e) => {
  const link = e.target.closest("a[data-link]");
  if (!link || e.metaKey || e.ctrlKey || e.shiftKey) return;
  e.preventDefault();
  go(link.getAttribute("href"));
});

window.addEventListener("popstate", render);

// Search box in the top bar

const searchForm = document.getElementById("search-form");
const searchInput = document.getElementById("search-input");
const suggestions = document.getElementById("suggest");
let searchTimer;

searchInput.addEventListener("input", () => {
  clearTimeout(searchTimer);
  const q = searchInput.value.trim();
  if (!q) {
    suggestions.hidden = true;
    return;
  }
  searchTimer = setTimeout(async () => {
    const rows = await get("/search?q=" + encodeURIComponent(q)).catch(() => []);
    suggestions.innerHTML = rows
      .map((r) => `<li><a href="/p/${esc(r.name)}" data-link><b>${esc(r.name)}</b><span>${num(r.score)}</span></a></li>`)
      .join("");
    suggestions.hidden = rows.length === 0;
  }, 200);
});

searchInput.addEventListener("blur", () => setTimeout(() => (suggestions.hidden = true), 150));

searchForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const q = searchInput.value.trim();
  if (!NAME_RE.test(q)) return;
  searchInput.value = "";
  suggestions.hidden = true;
  go("/p/" + q);
});

// Home

async function homePage() {
  document.title = "Better Battle";
  app.innerHTML = `
    <section class="intro">
      <h1>Every Build Battle player, ranked.</h1>
      <p>Look up anyone who has played Build Battle on Hypixel. Install the Better Battle mod to add your games, levels and achievements to your profile.</p>
      <form class="big-search" id="home-search">
        <label for="home-q" class="sr-only">Minecraft username</label>
        <input id="home-q" placeholder="Minecraft username" maxlength="16" autocomplete="off" spellcheck="false">
        <button class="btn primary" type="submit">View profile</button>
      </form>
      <p class="muted" id="home-stats" style="font-size:13px">&nbsp;</p>
    </section>
    <section class="card">
      <div class="card-head">
        <h2>Top builders</h2>
        <a href="/leaderboard" data-link>See all</a>
      </div>
      <div id="home-top"><div class="loading" style="height:300px"></div></div>
    </section>`;

  document.getElementById("home-search").addEventListener("submit", (e) => {
    e.preventDefault();
    const q = document.getElementById("home-q").value.trim();
    if (NAME_RE.test(q)) go("/p/" + q);
  });

  const [stats, board] = await Promise.all([get("/stats").catch(() => null), get("/leaderboard")]);
  if (stats) {
    document.getElementById("home-stats").textContent =
      `${num(stats.rankedPlayers)} players ranked so far, ${num(stats.modUsers)} using the mod`;
  }
  document.getElementById("home-top").innerHTML = leaderboardTable(board.rows.slice(0, 10));
}

// Leaderboard

function leaderboardTable(rows) {
  if (!rows.length) {
    return `<div class="empty">Nobody ranked yet. Players show up here as they're found.</div>`;
  }
  const body = rows.map((r) => `
    <tr>
      <td class="num">${num(r.rank)}</td>
      <td>
        <a class="player" href="/p/${esc(r.name)}" data-link>
          <img src="${avatar(r.uuid, 32)}" alt="" width="28" height="28" loading="lazy">
          ${esc(r.name ?? "Unknown")}
          ${r.usesMod ? `<span class="bb-tag" title="Uses Better Battle">BB</span>` : ""}
        </a>
      </td>
      <td>${title(r.title)}</td>
      <td class="right num">${num(r.score)}</td>
      <td class="right">${num(r.wins)}</td>
      <td class="right">${r.level ? num(r.level) : `<span class="muted">–</span>`}</td>
    </tr>`).join("");

  return `
    <div class="table-scroll">
      <table>
        <thead>
          <tr><th>#</th><th>Player</th><th>Title</th><th class="right">Score</th><th class="right">Wins</th><th class="right">Level</th></tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
    </div>`;
}

async function leaderboardPage() {
  const page = Math.max(1, Number(new URLSearchParams(location.search).get("page")) || 1);
  document.title = "Leaderboard · Better Battle";
  app.innerHTML = `
    <section class="card">
      <div class="card-head">
        <div>
          <h2>Leaderboard</h2>
          <p class="sub" id="lb-sub">Ranked by Build Battle score</p>
        </div>
        <div class="pager" id="lb-pager"></div>
      </div>
      <div id="lb-body"><div class="loading" style="height:560px"></div></div>
    </section>`;

  const board = await get("/leaderboard?page=" + page);
  const pages = Math.max(1, Math.ceil(board.total / 50));

  document.getElementById("lb-sub").textContent = `Ranked by Build Battle score · ${num(board.total)} players`;
  document.getElementById("lb-body").innerHTML = leaderboardTable(board.rows);
  document.getElementById("lb-pager").innerHTML = `
    ${page > 1 ? `<a class="btn" href="/leaderboard?page=${page - 1}" data-link>Previous</a>` : ""}
    <span class="muted">Page ${num(page)} of ${num(pages)}</span>
    ${page < pages ? `<a class="btn" href="/leaderboard?page=${page + 1}" data-link>Next</a>` : ""}`;
}

// Profile

function profileError(err, name) {
  if (err.status === 404 && err.body?.error === "never_played_build_battle") {
    return `${esc(err.body.name ?? name)} hasn't played Build Battle yet.`;
  }
  if (err.status === 404) return `There's no Minecraft player called ${esc(name)}.`;
  if (err.status === 503) {
    return err.body?.reason === "api_key"
      ? "Hypixel rejected our API key, so stats can't be loaded right now. If you run this site, check the logs (bb logs)."
      : "Hypixel didn't answer. Try again in a minute.";
  }
  return "Couldn't load this profile. Try again in a minute.";
}

function stat(label, value, note) {
  return `
    <div class="stat">
      <span class="stat-label">${esc(label)}</span>
      <span class="stat-value">${value}</span>
      <span class="stat-note">${esc(note)}</span>
    </div>`;
}

function tile(label, value) {
  return `<div class="tile"><span>${esc(label)}</span><b>${num(value)}</b></div>`;
}

function meter(left, right, fraction, color) {
  const width = Math.max(0, Math.min(100, fraction * 100)).toFixed(1);
  return `
    <div class="meter">
      <div class="meter-label"><span>${left}</span><span class="muted">${right}</span></div>
      <div class="bar"><div style="width:${width}%${color ? `;background:${esc(color)}` : ""}"></div></div>
    </div>`;
}

async function profilePage(name) {
  document.title = `${name} · Better Battle`;
  app.innerHTML = `<div class="loading" style="height:160px"></div><div class="loading" style="height:280px"></div>`;

  let p;
  try {
    p = await get("/players/" + encodeURIComponent(name));
  } catch (err) {
    app.innerHTML = `<div class="card empty">${profileError(err, name)}</div>`;
    return;
  }

  document.title = `${p.name} · Better Battle`;
  if (p.name !== name) history.replaceState(null, "", "/p/" + p.name);

  const bb = p.betterBattle;
  const t = p.title;
  const wins = p.hypixel.modeWins;

  const levelMeter = bb
    ? meter(`<b>Level ${num(bb.level)}</b>`, `${num(bb.xpIntoLevel)} / ${num(bb.xpForNext)} XP`, bb.xpIntoLevel / bb.xpForNext)
    : "";
  const titleMeter = t.next
    ? meter(`${title(t.current)} <span class="muted">to</span> ${title(t.next)}`, `${num(t.into)} / ${num(t.span)}`, t.pct, t.next.hex)
    : `<div class="meter-label"><span>${title(t.current)} <span class="muted">· top title</span></span></div>`;

  app.innerHTML = `
    <section class="profile-top">
      <div class="who">
        <img class="avatar" src="${avatar(p.uuid, 96)}" alt="" width="96" height="96">
        <div class="who-text">
          <div class="tags">
            <span class="chip">${title(t.current)}</span>
            ${bb ? `<span class="chip pink">Better Battle</span>` : ""}
          </div>
          <h1>${esc(p.name)}</h1>
          ${levelMeter}
          ${titleMeter}
        </div>
      </div>
      <div class="stats">
        ${stat("Global rank", p.rank ? "#" + num(p.rank) : "–", `out of ${num(p.rankedPlayers)} players`)}
        ${stat("Build Battle score", num(p.score), `${num(p.hypixel.wins)} wins`)}
        ${bb
          ? stat("Crowd score", percent(bb.crowdScore), "average share of max votes")
          : stat("Games played", num(p.hypixel.gamesPlayed), "all modes")}
        ${bb
          ? stat("Aspiring Points", num(bb.ap), `${num(bb.themesBuilt)} themes built`)
          : stat("Correct guesses", num(p.hypixel.correctGuesses), "Guess the Build")}
      </div>
    </section>

    <section class="sync">
      <div class="sync-text">
        <span class="dot${bb ? "" : " off"}"></span>
        <div>
          <b>${bb ? "Synced with the Better Battle mod" : "Hypixel stats only"}</b>
          <span>Stats updated ${timeAgo(p.lastChecked)}</span>
        </div>
      </div>
      <button class="btn primary" id="share">Share profile</button>
    </section>

    <div class="cols">
      <section class="card">
        <div class="card-head">
          <div>
            <h2>Rank history</h2>
            <p class="sub">Global rank by Build Battle score</p>
          </div>
          <div class="toggle" id="range" role="group" aria-label="Time range">
            <button data-days="30">30d</button>
            <button data-days="90" aria-pressed="true">90d</button>
            <button data-days="365">1y</button>
          </div>
        </div>
        <div class="graph" id="graph"></div>
      </section>
      <section class="card">
        <h2>Wins by mode</h2>
        <div class="tiles">
          ${tile("Solo", wins.solo)}
          ${tile("Teams", wins.teams)}
          ${tile("Pro", wins.pro)}
          ${tile("Guess the Build", wins.gtb)}
          ${tile("Speed Builders", wins.speedBuilders)}
          ${tile("Total votes", p.hypixel.totalVotes)}
        </div>
      </section>
    </div>

    ${bb ? modSections(bb) : getModCard()}`;

  document.getElementById("share").addEventListener("click", async (e) => {
    const url = `${location.origin}/p/${p.name}`;
    try {
      if (navigator.share) {
        await navigator.share({ title: `${p.name} on Better Battle`, url });
      } else {
        await navigator.clipboard.writeText(url);
        e.target.textContent = "Link copied";
      }
    } catch {
      // closed the share sheet
    }
  });

  const showRange = async (days) => {
    for (const b of document.querySelectorAll("#range button")) {
      b.setAttribute("aria-pressed", String(Number(b.dataset.days) === days));
    }
    const points = await get(`/players/${encodeURIComponent(p.name)}/rank-history?days=${days}`).catch(() => []);
    drawGraph(document.getElementById("graph"), points, days);
  };

  document.getElementById("range").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-days]");
    if (b) showRange(Number(b.dataset.days));
  });

  showRange(90);
  if (bb) loadModData(p.name);
}

function getModCard() {
  return `
    <section class="card get-mod">
      <div>
        <h2>See the rest of this profile with the mod</h2>
        <p>Better Battle adds your recent games, levels, achievements, weekly challenges and Speed Builders tips. Fabric 26.3 and Forge 1.8.9.</p>
      </div>
      <a class="btn primary" href="https://github.com/Koiduu/BetterBuildbattle" target="_blank" rel="noopener">Get the mod</a>
    </section>`;
}

function modSections(bb) {
  const modeButtons = Object.entries(MODES)
    .map(([key, label]) => `<button data-mode="${key}">${label}</button>`)
    .join("");

  const grades = ["SS", "S", "A"]
    .map((g) => `<div><b style="color:${GRADE_COLORS[g]}">${num(bb.grades[g])}</b><span>${g} grades</span></div>`)
    .join("");

  return `
    <section class="card">
      <div class="card-head">
        <div>
          <h2>Activity</h2>
          <p class="sub">Games per day, last 26 weeks</p>
        </div>
        <div class="heat-totals">
          <div><b>${num(bb.games)}</b><span>games</span></div>
          <div><b>${num(bb.bestWinStreak)}</b><span>best streak</span></div>
          <div><b>${num(Math.round(bb.playtimeSec / 3600))}h</b><span>played</span></div>
          ${grades}
        </div>
      </div>
      <div class="heat-scroll" id="heat"><div class="loading" style="height:115px"></div></div>
      <div class="legend">Less ${HEAT_COLORS.map((c) => `<i style="background:${c}"></i>`).join("")} More</div>
    </section>

    <div class="cols even">
      <section class="card">
        <div class="card-head">
          <h2>Achievements</h2>
          <span class="muted" id="ach-count"></span>
        </div>
        <div class="ach-grid" id="achievements"><div class="loading" style="height:120px"></div></div>
      </section>
      <section class="card">
        <div class="card-head">
          <h2>This week</h2>
          <span class="muted" style="font-size:12px">resets Monday</span>
        </div>
        <div class="list" id="weekly"></div>
      </section>
    </div>

    <section class="card">
      <h2>Pinned builds</h2>
      <div class="pin-grid" id="pins"></div>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>Recent games</h2>
        <div class="toggle" id="mode-filter" role="group" aria-label="Mode">
          <button data-mode="" aria-pressed="true">All</button>
          ${modeButtons}
        </div>
      </div>
      <div id="games"><div class="loading" style="height:220px"></div></div>
    </section>`;
}

async function loadModData(name) {
  const n = encodeURIComponent(name);
  const [heat, achievements, pins, weekly] = await Promise.all([
    get(`/players/${n}/heatmap`).catch(() => []),
    get(`/players/${n}/achievements`).catch(() => ({ unlocked: [], total: 0 })),
    get(`/players/${n}/pins`).catch(() => []),
    get("/challenges/weekly").catch(() => []),
  ]);

  drawHeatmap(heat);

  document.getElementById("ach-count").textContent = `${num(achievements.unlocked.length)} of ${num(achievements.total)}`;
  document.getElementById("achievements").innerHTML = achievements.unlocked.length
    ? achievements.unlocked.slice(0, 6).map((a) => `
        <div class="row-item">
          <div class="badge">+${num(a.points)}</div>
          <div class="row-text"><b>${esc(a.name)}</b><span>${esc(a.description)}</span></div>
        </div>`).join("")
    : `<p class="muted">None yet.</p>`;

  document.getElementById("weekly").innerHTML = weekly.length
    ? weekly.map((w) => `
        <div class="row-item">
          <div class="row-text"><b>${esc(w.text)}</b><span>${num(w.xp)} XP</span></div>
        </div>`).join("")
    : `<p class="muted">No challenges yet this week.</p>`;

  document.getElementById("pins").innerHTML = pins.length
    ? pins.map((m) => `
        <div class="row-item">
          <div class="badge" style="background:${GRADE_COLORS[m.grade] ?? "#b5aac1"}">${esc(m.grade ?? "–")}</div>
          <div class="row-text">
            <b>${esc(m.theme ?? "Untitled")}</b>
            <span>${esc(MODES[m.mode] ?? m.mode)} · ${m.placement ? ordinal(m.placement) : "–"} of ${num(m.players)}</span>
          </div>
          <span class="num">${num(m.score)}</span>
        </div>`).join("")
    : `<p class="muted">Nothing pinned yet.</p>`;

  const showGames = async (mode) => {
    for (const b of document.querySelectorAll("#mode-filter button")) {
      b.setAttribute("aria-pressed", String(b.dataset.mode === mode));
    }
    const rows = await get(`/players/${n}/matches${mode ? "?mode=" + mode : ""}`).catch(() => []);
    document.getElementById("games").innerHTML = rows.length ? gamesTable(rows) : `<div class="empty">No games here yet.</div>`;
  };

  document.getElementById("mode-filter").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-mode]");
    if (b) showGames(b.dataset.mode);
  });

  showGames("");
}

function gamesTable(rows) {
  const body = rows.map((m) => {
    const place = m.placement === 1 ? "Win" : m.placement ? ordinal(m.placement) : "–";
    return `
      <tr>
        <td><b>${esc(m.theme ?? "–")}</b></td>
        <td class="muted">${esc(MODES[m.mode] ?? m.mode)}</td>
        <td><span class="place${m.placement === 1 ? " win" : ""}">${place}</span></td>
        <td class="right">${num(m.score)}</td>
        <td class="right">${percent(m.crowd_score, 0)}</td>
        <td class="num" style="font-weight:700;color:${GRADE_COLORS[m.grade] ?? "inherit"}">${esc(m.grade ?? "–")}</td>
        <td class="right" style="color:var(--pink-text)">${m.xp_earned ? "+" + num(m.xp_earned) : "–"}</td>
        <td class="right muted">${timeAgo(m.played_at)}</td>
      </tr>`;
  }).join("");

  return `
    <div class="table-scroll">
      <table>
        <thead>
          <tr><th>Theme</th><th>Mode</th><th>Place</th><th class="right">Score</th><th class="right">Crowd</th><th>Grade</th><th class="right">XP</th><th class="right">When</th></tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
    </div>`;
}

function drawHeatmap(days) {
  const games = new Map(days.map((d) => [String(d.day).slice(0, 10), d.games]));
  const today = new Date();
  today.setUTCHours(0, 0, 0, 0);

  // 26 columns of Monday-to-Sunday, ending with the current week
  const start = new Date(today);
  start.setUTCDate(start.getUTCDate() - ((today.getUTCDay() + 6) % 7) - 25 * 7);

  let cells = "";
  for (let i = 0; i < 26 * 7; i++) {
    const day = new Date(start);
    day.setUTCDate(start.getUTCDate() + i);
    if (day > today) {
      cells += "<div></div>";
      continue;
    }
    const key = day.toISOString().slice(0, 10);
    const count = games.get(key) ?? 0;
    const level = count === 0 ? 0 : count < 5 ? 1 : count < 10 ? 2 : count < 20 ? 3 : 4;
    cells += `<div style="background:${HEAT_COLORS[level]}" title="${key}: ${count} game${count === 1 ? "" : "s"}"></div>`;
  }

  document.getElementById("heat").innerHTML = `
    <div class="heat">
      <div class="heat-days"><span>Mon</span><span></span><span>Wed</span><span></span><span>Fri</span><span></span><span>Sun</span></div>
      <div class="heat-grid">${cells}</div>
    </div>`;
}

function drawGraph(el, points, days) {
  if (points.length < 2) {
    el.className = "graph empty-graph";
    el.innerHTML = `<p class="empty">Not enough history yet. A point is added every day, so check back tomorrow.</p>`;
    return;
  }
  el.className = "graph";

  const W = 600;
  const H = 200;
  const ranks = points.map((p) => p.rank);
  const best = Math.min(...ranks);
  const worst = Math.max(...ranks);
  const x = (i) => (i / (points.length - 1)) * W;
  // best rank sits at the top
  const y = (rank) => 12 + ((rank - best) / Math.max(1, worst - best)) * (H - 24);
  const line = points.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(p.rank).toFixed(1)}`).join(" ");
  const last = points[points.length - 1];

  el.innerHTML = `
    <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="Rank went from #${num(points[0].rank)} to #${num(last.rank)}">
      <defs>
        <linearGradient id="rank-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#ff66aa" stop-opacity=".25"/>
          <stop offset="1" stop-color="#ff66aa" stop-opacity="0"/>
        </linearGradient>
      </defs>
      <line x1="0" x2="${W}" y1="${H / 2}" y2="${H / 2}" stroke="rgba(255,255,255,.06)"/>
      <path d="${line} L${W} ${H} L0 ${H} Z" fill="url(#rank-fill)"/>
      <path d="${line}" fill="none" stroke="#ff66aa" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    </svg>
    <div class="tip" style="left:100%;top:${(y(last.rank) / H) * 100}%;transform:translate(-100%,-130%)">#${num(last.rank)}</div>
    <div class="axis"><span>${days >= 365 ? "1 year ago" : `${days} days ago`}</span><span>Today</span></div>`;

  const svg = el.querySelector("svg");
  const tip = document.createElement("div");
  tip.className = "tip";
  tip.hidden = true;
  el.appendChild(tip);

  svg.addEventListener("mousemove", (e) => {
    const box = svg.getBoundingClientRect();
    const i = Math.max(0, Math.min(points.length - 1, Math.round(((e.clientX - box.left) / box.width) * (points.length - 1))));
    const p = points[i];
    tip.hidden = false;
    tip.style.left = (x(i) / W) * 100 + "%";
    tip.style.top = (y(p.rank) / H) * 100 + "%";
    tip.innerHTML = `<span class="muted">${esc(String(p.date).slice(0, 10))}</span> #${num(p.rank)}`;
  });
  svg.addEventListener("mouseleave", () => (tip.hidden = true));
}

render();
