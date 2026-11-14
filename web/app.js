const API = "/bb/v1";
const app = document.getElementById("app");
const SUPPORT = { email: null, discord: "https://discord.gg/zgQcwWpkrq" };
let flashNotice = null;

const MODES = {
  solo: "Solo",
  teams: "Teams",
  pro: "Pro",
  gtb: "Guess the Build",
  speed_builders: "Speed Builders",
};

const GRADE_COLORS = { SS: "#ffaa00", S: "#ff55ff", A: "#55ffff", B: "#c6c6c6", C: "#9d9da6" };
const HEAT_COLORS = ["#141416", "#1d4a12", "#2f7a1c", "#4fb82a", "#80ff20"];
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

async function request(method, path, payload) {
  const res = await fetch(API + path, {
    method,
    headers: payload === undefined ? {} : { "content-type": "application/json" },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.error || res.statusText);
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

const get = (path) => request("GET", path);

// Routing

const routes = [
  { path: /^\/$/, page: homePage, nav: "home" },
  { path: /^\/leaderboard\/?$/, page: leaderboardPage, nav: "leaderboard" },
  { path: /^\/p\/([A-Za-z0-9_]{1,16})\/?$/, page: profilePage },
  { path: /^\/login\/?$/, page: () => authPage("login") },
  { path: /^\/signup\/?$/, page: () => authPage("signup") },
  { path: /^\/account\/?$/, page: accountPage },
  { path: /^\/privacy\/?$/, page: privacyPage },
  { path: /^\/terms\/?$/, page: termsPage },
  { path: /^\/cookies\/?$/, page: cookiesPage },
  { path: /^\/support\/?$/, page: supportPage },
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
  clearInterval(linkPoll);

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

function supportContact() {
  const links = [];
  if (SUPPORT.email) links.push(`<a href="mailto:${esc(SUPPORT.email)}">${esc(SUPPORT.email)}</a>`);
  if (SUPPORT.discord) links.push(`<a href="${esc(SUPPORT.discord)}" target="_blank" rel="noopener noreferrer">Discord server</a>`);
  return links.length ? links.join(" · ") : "Contact details coming soon";
}

function legalPage(name, content) {
  document.title = `${name} · Better Battle`;
  app.innerHTML = `
    <article class="card legal-page">
      <header class="legal-head">
        <h1>${name}</h1>
        <p class="sub">Last updated 13 November 2026</p>
      </header>
      ${content}
    </article>`;
}

function privacyPage() {
  legalPage("Privacy", `
    <section><h2>Who runs Better Battle</h2>
      <p>Better Battle is a hobby project run by Koidu, not a company. It is not affiliated with Hypixel, Mojang or Microsoft.</p></section>
    <section><h2>Website accounts</h2>
      <p>An account stores your email and an Argon2id password hash, never your password itself. If you use Discord login, we receive your Discord ID, username and avatar URL. Discord login asks only for the <code>identify</code> scope, not your email.</p>
      <p>When linked, we store your Minecraft UUID and name, bio and pinned matches.</p></section>
    <section><h2>Mod data</h2>
      <p>When you log in with the mod, the server verifies your Minecraft name with Mojang's session server. The mod never sees or sends your Microsoft password.</p>
      <p>Each sync sends your finished games: time, mode, theme, placement, player count, score, grade, duration, blocks placed and broken, palette, and your votes with the builder name. Votes are visible only to Better Battle admins and are used to check results. Syncs also send local achievement claims, mod and Minecraft version, and the names and UUIDs of players seen in your lobbies.</p>
      <p>The mod also saves this information on your PC in a SQLite file. A <code>/bb debug</code> log stays on your PC unless you share it.</p></section>
    <section><h2>Other players and public profiles</h2>
      <p>Better Battle fetches public Build Battle stats from the official Hypixel API by UUID for leaderboard players, players seen in lobbies, and players someone searches for. Players who have never signed up can appear in the global ranking. We keep only their name, UUID and public Hypixel Build Battle stats while they are in the ranking, and do not record who saw them or in which lobby. They can ask us on Discord to be removed.</p>
      <p>Profiles are public. They can show a name, Hypixel stats, rank, bio, pins, Better Battle matches, achievements and heatmap. Email, Discord ID and votes are never public.</p></section>
    <section><h2>Services involved</h2>
      <p>Cloudflare carries all traffic and sees IP addresses. Player heads load from mc-heads.net, which sees the visitor's IP. Discord avatars load from cdn.discordapp.com. The server contacts Hypixel and Mojang, and contacts Discord only if you use Discord login.</p>
      <p>IP addresses are used only in memory for rate limiting and are never written to the database.</p></section>
    <section><h2>How long data stays</h2>
      <p>Your Better Battle account data stays until you delete your account. Deleting removes your Better Battle data immediately. Public Hypixel stats in the global ranking stay unless you ask support to remove them. Login sessions expire after 30 days.</p></section>
    <section><h2>Your choices and age</h2>
      <p>You can delete your account or download a copy of your data from the Account page. To request a correction or removal of account data, contact us on Discord using the link below. Public Hypixel stats can also be removed on request.</p>
      <p>You must be at least 13, or older if your country requires it, to make an account. If we learn that an account belongs to someone under 13, we delete it. Parents or guardians can ask us to do so on Discord.</p>
      <p class="contact-block">${supportContact()}</p></section>`);
}

function termsPage() {
  legalPage("Terms", `
    <section><h2>About the service</h2>
      <p>Better Battle is a hobby project run by Koidu, not a company. It is not affiliated with Hypixel, Mojang or Microsoft.</p>
      <p>The site and mod are provided as-is, without warranty. They may change or go offline.</p></section>
    <section><h2>Accounts and games</h2>
      <p>You must be at least 13, or older if your country requires it, to make an account.</p>
      <p>Do not cheat, bot or fake synced games. Faked results can be removed, and the account can be hidden from leaderboards.</p></section>
    <section><h2>Profiles and bios</h2>
      <p>Bios must not be hateful, sexual or harassing, and must not contain personal information about other people. Bios that break this rule can be removed.</p></section>
    <section><h2>Using the mod</h2>
      <p>Using the mod on Hypixel is at your own risk under Hypixel's rules. The mod reads chat and the scoreboard; it never plays for you.</p></section>
    <section><h2>Changes</h2>
      <p>These terms can change. The date at the top shows the latest version.</p></section>`);
}

function cookiesPage() {
  legalPage("Cookies", `
    <section><h2>Login cookies</h2>
      <p><code>bb_session</code> is an HttpOnly login cookie that lasts 30 days. It is cleared when you log out. <code>bb_oauth</code> is a short-lived state cookie used only during Discord login.</p></section>
    <section><h2>Browser storage</h2>
      <p><code>localStorage</code> holds <code>bb_cookie_notice</code> to remember that you dismissed this notice. The admin page alone stores the admin token there.</p></section>
    <section><h2>Other requests</h2>
      <p>Better Battle does not use analytics, ads or tracking cookies. Cloudflare carries site traffic. Player heads load from mc-heads.net and Discord avatars load from cdn.discordapp.com.</p></section>`);
}

function supportPage() {
  legalPage("Support", `
    <section><h2>Installing the mod</h2>
      <p>Put the Fabric 26.3 or Forge 1.8.9 jar in your Minecraft <code>mods</code> folder.</p></section>
    <section><h2>Linking your account</h2>
      <p>Get a code from the Account page, then run <code>/bb link &lt;code&gt;</code> in game.</p></section>
    <section><h2>The stats server cannot be reached</h2>
      <p>If you see “Can't reach the stats server” or HTTP 502, games are saved locally and upload on your next <code>/bb sync</code>.</p></section>
    <section><h2>Stats are not updating</h2>
      <p>Hypixel totals refresh every few days.</p></section>
    <section><h2>Getting a copy of your data</h2>
      <p>Sign in and use Download my data on the Account page. For help, corrections or removal requests, contact us on Discord.</p></section>
    <section><h2>Deleting your account</h2>
      <p>Use the Delete account section at the bottom of the Account page. If your account has no password, type <code>DELETE</code> to confirm.</p></section>
    <section><h2>Contact</h2>
      <p>For account data copies, corrections or removal requests, contact us on Discord.</p>
      <p class="contact-block">${supportContact()}</p></section>`);
}

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
  const notice = flashNotice;
  flashNotice = null;
  app.innerHTML = `
    ${notice ? `<p class="status-notice" role="status">${esc(notice)}</p>` : ""}
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

  document.getElementById("lb-sub").textContent = `Ranked by Build Battle score, ${num(board.total)} players`;
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
    [p] = await Promise.all([get("/players/" + encodeURIComponent(name)), meReady]);
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
    : `<div class="meter-label"><span>${title(t.current)} <span class="muted">(top title)</span></span></div>`;

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
          ${p.bio ? `<p class="bio">${esc(p.bio)}</p>` : ""}
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

    <section class="card sync">
      <div class="sync-text">
        <span class="dot${bb ? "" : " off"}"></span>
        <div>
          <b>${bb ? "Synced with the Better Battle mod" : "Hypixel stats only"}</b>
          <span>Stats updated ${timeAgo(p.lastChecked)}</span>
        </div>
      </div>
      ${profileAction(p)}
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

function profileAction(p) {
  if (me?.minecraft?.uuid === p.uuid) return `<a class="btn" href="/account" data-link>Edit profile</a>`;
  if (p.claimed || me?.minecraft) return "";
  return `<a class="btn" href="${me ? "/account" : "/signup"}" data-link>This is me</a>`;
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
            <span>${esc(MODES[m.mode] ?? m.mode)}, ${m.placement ? ordinal(m.placement) : "–"} of ${num(m.players)}</span>
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
      <line x1="0" x2="${W}" y1="${H / 2}" y2="${H / 2}" stroke="#2c2c33" stroke-dasharray="4 4"/>
      <path d="${line}" fill="none" stroke="#55ff55" stroke-width="3" stroke-linejoin="miter" vector-effect="non-scaling-stroke"/>
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

// Accounts

let me = null;
let linkPoll;

const DISCORD_ICON = `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"/></svg>`;

const AUTH_ERRORS = {
  email_taken: `There's already an account with that email. <a href="/login" data-link>Log in instead</a>`,
  wrong_login: "That email and password don't match.",
  rate_limited: "Too many tries. Wait a minute and try again.",
  bad_request: "Enter a valid email and a password of at least 8 characters.",
  discord: "Discord login didn't go through. Try again.",
  discord_off: "Discord login isn't set up on this site yet.",
};

function displayName(user) {
  return user.minecraft?.name ?? user.discord?.name ?? user.email?.split("@")[0] ?? "You";
}

function faceFor(user, size) {
  if (user.minecraft) return avatar(user.minecraft.uuid, size);
  return user.discord?.avatarUrl ?? null;
}

function renderAccountLink() {
  const link = document.getElementById("account-link");
  if (!me) {
    link.href = "/login";
    link.className = "account-link";
    link.textContent = "Log in";
    return;
  }
  const face = faceFor(me, 32);
  link.href = "/account";
  link.className = "account-link signed-in";
  link.innerHTML = `
    ${face ? `<img src="${esc(face)}" alt="" width="28" height="28">` : `<span class="initial">${esc(displayName(me)[0].toUpperCase())}</span>`}
    <span>${esc(displayName(me))}</span>`;
}

async function loadMe() {
  me = await get("/me").catch(() => null);
  renderAccountLink();
}

function setMe(user) {
  me = user;
  renderAccountLink();
}

function queryError() {
  return new URLSearchParams(location.search).get("error");
}

async function authPage(mode) {
  await meReady;
  if (me) {
    history.replaceState(null, "", "/account");
    return accountPage();
  }

  const signup = mode === "signup";
  document.title = `${signup ? "Sign up" : "Log in"} · Better Battle`;
  const providers = await get("/auth/providers").catch(() => ({ discord: false }));
  const error = AUTH_ERRORS[queryError()];

  app.innerHTML = `
    <section class="card auth">
      <div class="auth-head">
        <h1>${signup ? "Create your account" : "Welcome back"}</h1>
        <p class="sub">${signup
          ? "Claim your profile, write a bio and pin your best builds."
          : "Log in to edit your profile and pins."}</p>
      </div>
      ${providers.discord ? `
        <a class="btn discord" href="${API}/auth/discord">${DISCORD_ICON}Continue with Discord</a>
        <div class="or"><span>or with email</span></div>` : ""}
      <form class="form" id="auth-form" novalidate>
        <label class="field">
          <span>Email</span>
          <input type="email" name="email" autocomplete="email" required maxlength="254">
        </label>
        <label class="field">
          <span>Password</span>
          <input type="password" name="password" autocomplete="${signup ? "new-password" : "current-password"}" required minlength="8" maxlength="200">
          ${signup ? `<small>At least 8 characters.</small>` : ""}
        </label>
        <p class="form-error" id="auth-error" role="alert"${error ? "" : " hidden"}>${error ?? ""}</p>
        <button class="btn primary wide" type="submit">${signup ? "Create account" : "Log in"}</button>
      </form>
      ${signup ? `<p class="auth-terms">By creating an account you agree to the <a href="/terms" data-link>Terms</a> and the <a href="/privacy" data-link>Privacy Policy</a>.</p>` : ""}
      <p class="auth-switch">${signup
        ? `Already have an account? <a href="/login" data-link>Log in</a>`
        : `New here? <a href="/signup" data-link>Create an account</a>`}</p>
    </section>`;

  const form = document.getElementById("auth-form");
  const errorBox = document.getElementById("auth-error");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = form.email.value.trim();
    const password = form.password.value;
    if (!email || password.length < 8) {
      errorBox.innerHTML = AUTH_ERRORS.bad_request;
      errorBox.hidden = false;
      return;
    }
    const button = form.querySelector("button");
    button.disabled = true;
    try {
      setMe(await request("POST", signup ? "/auth/signup" : "/auth/login", { email, password }));
      go("/account");
    } catch (err) {
      errorBox.innerHTML = AUTH_ERRORS[err.message] ?? "Something went wrong. Try again in a minute.";
      errorBox.hidden = false;
      button.disabled = false;
    }
  });
}

async function accountPage() {
  await meReady;
  if (!me) {
    history.replaceState(null, "", "/login");
    return authPage("login");
  }
  document.title = "Your account · Better Battle";
  const providers = await get("/auth/providers").catch(() => ({ discord: false }));
  const error = queryError() === "discord_taken"
    ? "That Discord account is already linked to a different Better Battle account."
    : null;
  if (queryError()) history.replaceState(null, "", "/account");

  app.innerHTML = `
    <section class="account-top">
      <div>
        <h1>Your account</h1>
        <p class="sub">Signed in as ${esc(me.email ?? me.discord?.name ?? displayName(me))}</p>
      </div>
      <button class="btn" id="logout">Log out</button>
    </section>
    ${error ? `<p class="form-error">${esc(error)}</p>` : ""}

    <section class="card" id="mc-card"></section>

    <div class="cols even">
      <section class="card">
        <div class="card-head">
          <div>
            <h2>Bio</h2>
            <p class="sub">${me.minecraft ? "Shown on your public profile" : "Shown on your profile once your Minecraft account is linked"}</p>
          </div>
          <span class="muted counter" id="bio-count"></span>
        </div>
        <form class="form" id="bio-form">
          <label class="sr-only" for="bio">Bio</label>
          <textarea id="bio" name="bio" maxlength="1000" rows="5" placeholder="What do you like to build?">${esc(me.bio ?? "")}</textarea>
          <div class="form-row">
            <span class="muted saved" id="bio-status" role="status"></span>
            <button class="btn primary" type="submit">Save bio</button>
          </div>
        </form>
      </section>
      <section class="card">
        <h2>Sign-in methods</h2>
        <div class="list">
          <div class="row-item">
            <div class="row-icon">@</div>
            <div class="row-text"><b>Email</b><span>${me.email ? esc(me.email) : "Not set"}</span></div>
          </div>
          <div class="row-item">
            <div class="row-icon discord-icon">${DISCORD_ICON}</div>
            <div class="row-text"><b>Discord</b><span>${me.discord ? esc(me.discord.name) : "Not connected"}</span></div>
            ${me.discord
              ? `<span class="chip pink">Connected</span>`
              : providers.discord ? `<a class="btn small" href="${API}/auth/discord">Connect</a>` : ""}
          </div>
        </div>
      </section>
    </div>

    <section class="card" id="pins-card"></section>

    <section class="card export-card">
      <div>
        <h2>Download my data</h2>
        <p class="muted">Save a copy of the information linked to your account.</p>
      </div>
      <a class="btn" href="${API}/me/export">Download my data</a>
    </section>

    <section class="card danger-card">
      <div>
        <h2>Delete account</h2>
        <p class="muted">This removes your Better Battle account data, including matches, pins and achievements. Public Hypixel stats in the global ranking are not deleted.</p>
      </div>
      <form class="form" id="delete-account-form">
        ${me.hasPassword ? `
          <label class="field">
            <span>Confirm with your password</span>
            <input type="password" name="password" autocomplete="current-password" maxlength="200" required>
          </label>` : `
          <label class="field">
            <span>Type DELETE to confirm</span>
            <input type="text" name="confirm" autocomplete="off" maxlength="6" pattern="DELETE" required>
          </label>`}
        <p class="form-error" id="delete-account-error" role="alert" hidden></p>
        <div class="form-row"><button class="btn danger" type="submit">Delete account</button></div>
      </form>
    </section>`;

  document.getElementById("logout").addEventListener("click", async () => {
    await request("POST", "/auth/logout").catch(() => {});
    setMe(null);
    go("/");
  });

  renderMinecraftCard(providers);
  setUpBio();
  setUpPins();
  setUpAccountDeletion();
}

function renderMinecraftCard(providers) {
  const card = document.getElementById("mc-card");
  if (me.minecraft) {
    card.innerHTML = `
      <div class="card-head">
        <div class="linked">
          <img class="avatar small" src="${avatar(me.minecraft.uuid, 64)}" alt="" width="56" height="56">
          <div>
            <span class="sub">Minecraft account</span>
            <h2 class="linked-name">${esc(me.minecraft.name)}</h2>
          </div>
          <span class="chip pink">Linked</span>
        </div>
        <a class="btn" href="/p/${esc(me.minecraft.name)}" data-link>View your profile</a>
      </div>`;
    return;
  }

  card.innerHTML = `
    <div>
      <h2>Link your Minecraft account</h2>
      <p class="sub">This claims your profile: your games from the mod show up on it, and your bio and pins appear on it.</p>
    </div>
    <div class="link-ways">
      <div class="link-way">
        <div class="step-title"><span class="step">1</span><b>In game, with the mod</b></div>
        <p class="muted">Get a code, then type the command in Minecraft chat while the Better Battle mod is running.</p>
        <div id="code-box"><button class="btn primary" id="get-code">Get a link code</button></div>
      </div>
      <div class="link-way">
        <div class="step-title"><span class="step">2</span><b>Through your Hypixel profile</b></div>
        ${me.discord ? `
          <p class="muted">On Hypixel, open <b>My Profile</b> (your head in the hotbar) → <b>Social Media</b> → <b>Discord</b>, and enter <code>${esc(me.discord.name)}</code>. Then check it here.</p>
          <form class="inline-form" id="hypixel-form">
            <label class="sr-only" for="mc-name">Minecraft username</label>
            <input id="mc-name" placeholder="Minecraft username" maxlength="16" autocomplete="off" spellcheck="false">
            <button class="btn" type="submit">Check</button>
          </form>
          <p class="form-error" id="hypixel-error" role="alert" hidden></p>` : `
          <p class="muted">Uses the Discord link on your Hypixel profile, so it needs Discord connected here first.</p>
          ${providers.discord ? `<a class="btn" href="${API}/auth/discord">${DISCORD_ICON}Connect Discord</a>` : `<p class="muted">Discord login isn't set up on this site yet.</p>`}`}
      </div>
    </div>`;

  document.getElementById("get-code").addEventListener("click", getLinkCode);

  const form = document.getElementById("hypixel-form");
  if (!form) return;
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = document.getElementById("mc-name").value.trim();
    const errorBox = document.getElementById("hypixel-error");
    if (!NAME_RE.test(name)) {
      errorBox.textContent = "That isn't a valid Minecraft username.";
      errorBox.hidden = false;
      return;
    }
    const button = form.querySelector("button");
    button.disabled = true;
    button.textContent = "Checking…";
    try {
      setMe(await request("POST", "/me/claim/hypixel", { name }));
      accountPage();
    } catch (err) {
      errorBox.innerHTML = hypixelError(err, name);
      errorBox.hidden = false;
      button.disabled = false;
      button.textContent = "Check";
    }
  });
}

function hypixelError(err, name) {
  switch (err.message) {
    case "not_found": return `There's no Minecraft player called ${esc(name)}.`;
    case "discord_mismatch": {
      const found = err.body?.hypixelDiscord;
      return found
        ? `${esc(name)}'s Hypixel profile says <code>${esc(found)}</code>, not <code>${esc(me.discord.name)}</code>.`
        : `${esc(name)} hasn't set a Discord on Hypixel yet. It can take a minute to show up after you set it.`;
    }
    case "already_claimed": return `${esc(name)} is already linked to another Better Battle account.`;
    case "already_linked": return "Your account is already linked to a Minecraft account.";
    case "hypixel_unavailable": return "Hypixel didn't answer. Try again in a minute.";
    case "rate_limited": return "Too many tries. Wait a minute and try again.";
    default: return "Couldn't check that right now. Try again in a minute.";
  }
}

async function getLinkCode() {
  const box = document.getElementById("code-box");
  let link;
  try {
    link = await request("POST", "/me/link-code");
  } catch {
    box.insertAdjacentHTML("beforeend", `<p class="form-error">Couldn't get a code. Try again.</p>`);
    return;
  }
  const command = `/bb link ${link.code}`;
  box.innerHTML = `
    <div class="code">
      <code>${esc(command)}</code>
      <button class="btn small" id="copy-code">Copy</button>
    </div>
    <p class="muted waiting"><span class="dot pulse"></span>Waiting for you in game… The code works for 10 minutes.</p>`;

  document.getElementById("copy-code").addEventListener("click", async (e) => {
    await navigator.clipboard.writeText(command).catch(() => {});
    e.target.textContent = "Copied";
  });

  const expires = new Date(link.expiresAt).getTime();
  clearInterval(linkPoll);
  linkPoll = setInterval(async () => {
    if (location.pathname !== "/account") return clearInterval(linkPoll);
    if (Date.now() > expires) {
      clearInterval(linkPoll);
      box.innerHTML = `<p class="muted">That code expired.</p><button class="btn primary" id="get-code">Get a new code</button>`;
      document.getElementById("get-code").addEventListener("click", getLinkCode);
      return;
    }
    const fresh = await get("/me").catch(() => null);
    if (fresh?.minecraft) {
      clearInterval(linkPoll);
      setMe(fresh);
      accountPage();
    }
  }, 3000);
}

function setUpBio() {
  const form = document.getElementById("bio-form");
  const input = document.getElementById("bio");
  const count = document.getElementById("bio-count");
  const status = document.getElementById("bio-status");
  const update = () => (count.textContent = `${num(input.value.length)} / 1,000`);
  update();
  input.addEventListener("input", () => {
    update();
    status.textContent = "";
  });
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      setMe(await request("PUT", "/me", { bio: input.value }));
      status.textContent = "Saved";
    } catch {
      status.textContent = "Couldn't save. Try again.";
    }
  });
}

function setUpAccountDeletion() {
  const form = document.getElementById("delete-account-form");
  const errorBox = document.getElementById("delete-account-error");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const payload = me.hasPassword
      ? { password: form.password.value }
      : { confirm: form.confirm.value };
    if (!me.hasPassword && payload.confirm !== "DELETE") {
      errorBox.textContent = "Type DELETE to confirm.";
      errorBox.hidden = false;
      return;
    }
    const button = form.querySelector("button");
    button.disabled = true;
    errorBox.hidden = true;
    try {
      await request("DELETE", "/me", payload);
      setMe(null);
      flashNotice = "Your account was deleted.";
      go("/");
    } catch (err) {
      errorBox.textContent = ({
        wrong_password: "That password is not correct.",
        confirm_required: "Type DELETE to confirm.",
        rate_limited: "Too many attempts. Wait a minute and try again.",
      })[err.message] ?? "Couldn't delete the account. Try again.";
      errorBox.hidden = false;
      button.disabled = false;
    }
  });
}

function pinRow(m, actions) {
  return `
    <div class="row-item">
      <div class="badge" style="background:${GRADE_COLORS[m.grade] ?? "#b5aac1"}">${esc(m.grade ?? "–")}</div>
      <div class="row-text">
        <b>${esc(m.theme ?? "Untitled")}</b>
        <span>${esc(MODES[m.mode] ?? m.mode)}, ${m.placement ? ordinal(m.placement) : "–"} of ${num(m.players)}, ${timeAgo(m.played_at)}</span>
      </div>
      <span class="num">${num(m.score)}</span>
      <div class="row-actions">${actions}</div>
    </div>`;
}

async function setUpPins() {
  const card = document.getElementById("pins-card");
  if (!me.minecraft) {
    card.innerHTML = `
      <h2>Pinned builds</h2>
      <p class="muted">Link your Minecraft account first. Then you can pin up to 10 of your games to the top of your profile.</p>`;
    return;
  }

  card.innerHTML = `
    <div class="card-head">
      <div>
        <h2>Pinned builds</h2>
        <p class="sub">Up to 10 games, shown in this order on your profile</p>
      </div>
      <div class="form-row">
        <span class="muted saved" id="pins-status" role="status"></span>
        <button class="btn primary" id="save-pins" disabled>Save pins</button>
      </div>
    </div>
    <div class="list" id="pinned"><div class="loading" style="height:64px"></div></div>
    <div class="card-head">
      <h2 class="minor">Your recent games</h2>
      <span class="muted counter" id="pin-count"></span>
    </div>
    <div class="list" id="recent"><div class="loading" style="height:180px"></div></div>
    <button class="btn more" id="more-games" hidden>Show older games</button>`;

  let [pins, games] = await Promise.all([get("/me/pins").catch(() => []), get("/me/matches").catch(() => [])]);
  let saved = JSON.stringify(pins.map((m) => m.id));

  const status = document.getElementById("pins-status");
  const saveButton = document.getElementById("save-pins");
  const moreButton = document.getElementById("more-games");
  const icon = (d) => `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${d}"/></svg>`;

  const draw = () => {
    const ids = new Set(pins.map((m) => m.id));
    const full = pins.length >= 10;
    document.getElementById("pin-count").textContent = `${pins.length} of 10 pinned`;
    document.getElementById("pinned").innerHTML = pins.length
      ? pins.map((m, i) => pinRow(m, `
          <button class="icon-btn" data-up="${i}" aria-label="Move up"${i === 0 ? " disabled" : ""}>${icon("m6 15 6-6 6 6")}</button>
          <button class="icon-btn" data-down="${i}" aria-label="Move down"${i === pins.length - 1 ? " disabled" : ""}>${icon("m6 9 6 6 6-6")}</button>
          <button class="icon-btn" data-unpin="${i}" aria-label="Unpin">${icon("M6 6l12 12M18 6 6 18")}</button>`)).join("")
      : `<p class="muted">Nothing pinned yet. Pick games from the list below.</p>`;
    document.getElementById("recent").innerHTML = games.length
      ? games.map((m, i) => pinRow(m, ids.has(m.id)
          ? `<span class="chip">Pinned</span>`
          : `<button class="btn small" data-pin="${i}"${full ? " disabled" : ""}>Pin</button>`)).join("")
      : `<p class="muted">No games yet. Play a round with the mod and it shows up here.</p>`;
    const changed = JSON.stringify(pins.map((m) => m.id)) !== saved;
    saveButton.disabled = !changed;
    if (changed) status.textContent = "Unsaved changes";
  };

  card.addEventListener("click", async (e) => {
    const b = e.target.closest("button");
    if (!b || b.disabled) return;
    if (b.dataset.pin) pins.push(games[Number(b.dataset.pin)]);
    else if (b.dataset.unpin) pins.splice(Number(b.dataset.unpin), 1);
    else if (b.dataset.up || b.dataset.down) {
      const i = Number(b.dataset.up ?? b.dataset.down);
      const j = b.dataset.up ? i - 1 : i + 1;
      [pins[i], pins[j]] = [pins[j], pins[i]];
    } else return;
    draw();
  });

  saveButton.addEventListener("click", async () => {
    saveButton.disabled = true;
    try {
      pins = await request("PUT", "/me/pins", { matchIds: pins.map((m) => m.id) });
      saved = JSON.stringify(pins.map((m) => m.id));
      draw();
      status.textContent = "Saved";
    } catch {
      status.textContent = "Couldn't save. Try again.";
      saveButton.disabled = false;
    }
  });

  moreButton.hidden = games.length < 25;
  moreButton.addEventListener("click", async () => {
    const older = await get("/me/matches?before=" + encodeURIComponent(games[games.length - 1].played_at)).catch(() => []);
    games = games.concat(older);
    moreButton.hidden = older.length < 25;
    draw();
  });

  draw();
  status.textContent = "";
}

function setupCookieNotice() {
  const notice = document.getElementById("cookie-notice");
  const button = document.getElementById("cookie-notice-ok");
  let dismissed = false;
  try { dismissed = localStorage.getItem("bb_cookie_notice") === "1"; } catch {}
  notice.hidden = dismissed;
  button.addEventListener("click", () => {
    try { localStorage.setItem("bb_cookie_notice", "1"); } catch {}
    notice.hidden = true;
  });
}

setupCookieNotice();
const meReady = loadMe();
render();
