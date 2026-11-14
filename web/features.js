// The Speed Builders update on the website: patch notes, Speed Builders leaderboards, comparing
// two players, and the profile's weekly challenges, achievement rarity, best times and share card.
// Kept apart from app.js so the two can change independently; app.js imports these.
import { app, esc, num, percent, title, avatar, get, go, NAME_RE } from "./app.js";

const secs = (ms) => (ms == null ? "–" : (ms / 1000).toFixed(2) + "s");

function dateLabel(iso) {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

// ---------------------------------------------------------------- Updates

export async function updatesPage() {
  document.title = "Updates · Better Battle";
  app.innerHTML = `<div class="loading" style="height:420px"></div>`;
  const { updates } = await fetch("/updates.json").then((r) => r.json());
  app.innerHTML = `
    <section class="intro">
      <h1>Updates</h1>
      <p>What's new in the Better Battle mod and on this site. In game, press B and choose What's New.</p>
    </section>
    ${updates.map((u, i) => `
      <section class="card update${i === 0 ? " latest" : ""}">
        <div class="card-head">
          <div>
            <h2>${esc(u.title)}</h2>
            <p class="sub">Version ${esc(u.version)} · ${dateLabel(u.date)}</p>
          </div>
          ${i === 0 ? `<span class="chip pink">Latest</span>` : ""}
        </div>
        ${u.summary ? `<p class="update-summary">${esc(u.summary)}</p>` : ""}
        <div class="update-sections">
          ${(u.sections ?? []).map((s) => `
            <div>
              <h3>${esc(s.heading)}</h3>
              <ul>${s.items.map((it) => `<li>${esc(it)}</li>`).join("")}</ul>
            </div>`).join("")}
        </div>
      </section>`).join("")}
    <section class="card get-mod">
      <div>
        <h2>Get the update</h2>
        <p>Every new feature can be switched off in the mod's Settings screen if you'd rather play normally.</p>
      </div>
      <a class="btn primary" href="https://github.com/Koiduu/BetterBuildbattle/releases" target="_blank" rel="noopener">Download</a>
    </section>`;
}

// ---------------------------------------------------------------- Speed Builders

export async function speedBuildersPage() {
  document.title = "Speed Builders · Better Battle";
  app.innerHTML = `
    <section class="card">
      <div class="card-head">
        <div>
          <h2>Speed Builders records</h2>
          <p class="sub">The fastest Perfect Build on every build, from players using Better Battle</p>
        </div>
      </div>
      <div id="spb-body"><div class="loading" style="height:360px"></div></div>
    </section>`;
  const builds = await get("/spb/builds");
  document.getElementById("spb-body").innerHTML = builds.length
    ? `<div class="table-scroll"><table>
        <thead><tr><th>Build</th><th class="right">Record</th><th>Held by</th><th class="right">Players</th></tr></thead>
        <tbody>${builds.map((b) => `
          <tr>
            <td><a href="/speed-builders/${esc(b.build)}" data-link><b>${esc(b.name)}</b></a></td>
            <td class="right num">${secs(b.timeMs)}</td>
            <td>${b.holder ? `<a class="player" href="/p/${esc(b.holder)}" data-link>${esc(b.holder)}</a>` : "–"}</td>
            <td class="right">${num(b.players)}</td>
          </tr>`).join("")}</tbody>
      </table></div>`
    : `<div class="empty">No records yet. They appear as Better Battle players finish Speed Builders rounds.</div>`;
}

export async function speedBuildPage(id) {
  document.title = "Speed Builders · Better Battle";
  app.innerHTML = `<div class="loading" style="height:420px"></div>`;
  let board;
  try {
    board = await get("/spb/builds/" + encodeURIComponent(id));
  } catch (err) {
    app.innerHTML = `<div class="card empty">No one has a time on this build yet. <a href="/speed-builders" data-link>All builds</a></div>`;
    return;
  }
  document.title = `${board.name} · Speed Builders · Better Battle`;
  const record = board.rows[0];
  app.innerHTML = `
    <p class="crumbs"><a href="/speed-builders" data-link>Speed Builders</a> / ${esc(board.name)}</p>
    <section class="card">
      <div class="card-head">
        <div>
          <h2>${esc(board.name)}</h2>
          <p class="sub">${record ? `Record ${secs(record.timeMs)} by ${esc(record.name)}` : "No times yet"} · ${num(board.rows.length)} players</p>
        </div>
      </div>
      <div class="table-scroll"><table>
        <thead><tr><th>#</th><th>Player</th><th class="right">Time</th><th class="right">Behind</th><th class="right">Precision</th><th class="right">Set</th></tr></thead>
        <tbody>${board.rows.map((r) => `
          <tr>
            <td class="num">${num(r.rank)}</td>
            <td><a class="player" href="/p/${esc(r.name)}" data-link>${esc(r.name)}</a></td>
            <td class="right num">${secs(r.timeMs)}</td>
            <td class="right muted">${r.rank === 1 ? "–" : "+" + secs(r.timeMs - record.timeMs)}</td>
            <td class="right">${percent(r.precision, 0)}</td>
            <td class="right muted">${dateLabel(r.setAt)}</td>
          </tr>`).join("")}</tbody>
      </table></div>
    </section>`;
}

// ---------------------------------------------------------------- Compare

export async function comparePage(a, b) {
  document.title = "Compare · Better Battle";
  if (!b) {
    app.innerHTML = `
      <section class="card">
        <h2>Compare ${a ? esc(a) + " with someone" : "two players"}</h2>
        <form class="big-search" id="compare-form" style="margin-top:12px">
          ${a ? "" : `<label class="sr-only" for="cmp-a">First player</label><input id="cmp-a" placeholder="First player" maxlength="16" autocomplete="off" spellcheck="false">`}
          <label class="sr-only" for="cmp-b">Player to compare with</label>
          <input id="cmp-b" placeholder="${a ? "Another player" : "Second player"}" maxlength="16" autocomplete="off" spellcheck="false">
          <button class="btn primary" type="submit">Compare</button>
        </form>
      </section>`;
    document.getElementById("compare-form").addEventListener("submit", (e) => {
      e.preventDefault();
      const first = a ?? document.getElementById("cmp-a").value.trim();
      const second = document.getElementById("cmp-b").value.trim();
      if (NAME_RE.test(first) && NAME_RE.test(second)) go(`/compare/${first}/${second}`);
    });
    return;
  }

  app.innerHTML = `<div class="loading" style="height:420px"></div>`;
  const load = async (name) => {
    const n = encodeURIComponent(name);
    const [p, ach, spb] = await Promise.all([
      get("/players/" + n),
      get(`/players/${n}/achievements`).catch(() => ({ unlocked: [] })),
      get(`/players/${n}/spb-bests`).catch(() => []),
    ]);
    return { p, ach: ach.unlocked, spb };
  };
  let left, right;
  try {
    [left, right] = await Promise.all([load(a), load(b)]);
  } catch (err) {
    app.innerHTML = `<div class="card empty">Couldn't find one of those players. <a href="/compare" data-link>Try again</a></div>`;
    return;
  }
  const L = left.p, R = right.p;
  document.title = `${L.name} vs ${R.name} · Better Battle`;

  // [label, left, right, higher is better?]
  const rows = [
    ["Global rank", L.rank, R.rank, false, (v) => (v ? "#" + num(v) : "–")],
    ["Build Battle score", L.score, R.score, true],
    ["Wins", L.hypixel.wins, R.hypixel.wins, true],
    ["Games played", L.hypixel.gamesPlayed, R.hypixel.gamesPlayed, true],
    ...Object.entries({ solo: "Solo wins", teams: "Teams wins", pro: "Pro wins", gtb: "Guess the Build wins", speedBuilders: "Speed Builders wins" })
      .map(([k, label]) => [label, L.hypixel.modeWins[k], R.hypixel.modeWins[k], true]),
    ["Aspiring Level", L.betterBattle?.level, R.betterBattle?.level, true],
    ["Aspiring Points", L.betterBattle?.ap, R.betterBattle?.ap, true],
    ["Crowd score", L.betterBattle?.crowdScore, R.betterBattle?.crowdScore, true, (v) => percent(v)],
    ["Best win streak", L.betterBattle?.bestWinStreak, R.betterBattle?.bestWinStreak, true],
    ["Achievements", left.ach.length || null, right.ach.length || null, true],
  ];
  const cell = (v, other, higher, fmt) => {
    const better = v != null && other != null && v !== other && (higher ? v > other : v < other);
    return `<td class="right num${better ? " better" : ""}">${fmt ? fmt(v) : num(v)}</td>`;
  };

  const leftIds = new Set(left.ach.map((x) => x.id));
  const rightIds = new Set(right.ach.map((x) => x.id));
  const onlyLeft = left.ach.filter((x) => !rightIds.has(x.id));
  const onlyRight = right.ach.filter((x) => !leftIds.has(x.id));
  const achList = (list) => list.length
    ? list.slice(0, 8).map((x) => `<li>${esc(x.name)}</li>`).join("") + (list.length > 8 ? `<li class="muted">and ${num(list.length - 8)} more</li>` : "")
    : `<li class="muted">None</li>`;

  const rightBest = new Map(right.spb.map((x) => [x.build, x]));
  const shared = left.spb.filter((x) => rightBest.has(x.build)).map((x) => ({ l: x, r: rightBest.get(x.build) }));
  const leftFaster = shared.filter((s) => s.l.timeMs < s.r.timeMs).length;

  const head = (p) => `
    <a class="player cmp-head" href="/p/${esc(p.name)}" data-link>
      <img src="${avatar(p.uuid, 64)}" alt="" width="48" height="48">
      <span><b>${esc(p.name)}</b>${title(p.title.current)}</span>
    </a>`;

  app.innerHTML = `
    <section class="card">
      <div class="cmp-top">${head(L)}<span class="cmp-vs">vs</span>${head(R)}</div>
      <div class="table-scroll"><table class="cmp">
        <thead><tr><th></th><th class="right">${esc(L.name)}</th><th class="right">${esc(R.name)}</th></tr></thead>
        <tbody>${rows.map(([label, l, r, higher, fmt]) => `
          <tr><td>${esc(label)}</td>${cell(l, r, higher, fmt)}${cell(r, l, higher, fmt)}</tr>`).join("")}</tbody>
      </table></div>
      <p class="sub" style="margin-top:8px">Level, points, crowd score and streaks need the Better Battle mod.</p>
    </section>
    <div class="cols even">
      <section class="card">
        <h2>Only ${esc(L.name)} has</h2>
        <ul class="plain">${achList(onlyLeft)}</ul>
      </section>
      <section class="card">
        <h2>Only ${esc(R.name)} has</h2>
        <ul class="plain">${achList(onlyRight)}</ul>
      </section>
    </div>
    ${shared.length ? `
    <section class="card">
      <div class="card-head">
        <div>
          <h2>Speed Builders head to head</h2>
          <p class="sub">${num(shared.length)} ${shared.length === 1 ? "build" : "builds"} both have finished. Faster: ${esc(L.name)} on ${num(leftFaster)}, ${esc(R.name)} on ${num(shared.length - leftFaster)}.</p>
        </div>
      </div>
      <div class="table-scroll"><table class="cmp">
        <thead><tr><th>Build</th><th class="right">${esc(L.name)}</th><th class="right">${esc(R.name)}</th></tr></thead>
        <tbody>${shared.map((s) => `
          <tr><td><a href="/speed-builders/${esc(s.l.build)}" data-link>${esc(s.l.name)}</a></td>
          ${cell(s.l.timeMs, s.r.timeMs, false, secs)}${cell(s.r.timeMs, s.l.timeMs, false, secs)}</tr>`).join("")}</tbody>
      </table></div>
    </section>` : ""}
    <p class="muted"><a href="/compare/${esc(L.name)}" data-link>Compare ${esc(L.name)} with someone else</a></p>`;
}

// ---------------------------------------------------------------- Profile: weekly challenges

let countdownTimer;

function untilLabel(iso) {
  const ms = new Date(iso) - Date.now();
  if (ms <= 0) return "any moment";
  const min = Math.floor(ms / 60000);
  const d = Math.floor(min / 1440), h = Math.floor((min % 1440) / 60), m = min % 60;
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${Math.max(1, m)}m`;
}

/** Fills #weekly and #weekly-reset on the profile. */
export async function loadWeekly(name) {
  const box = document.getElementById("weekly");
  const reset = document.getElementById("weekly-reset");
  const w = await get(`/players/${encodeURIComponent(name)}/weekly`).catch(() => null);
  if (!w || !w.challenges.length) {
    box.innerHTML = `<p class="muted">No challenges yet this week.</p>`;
    return;
  }
  box.innerHTML = w.challenges.map((c) => {
    const target = c.target ?? 1;
    const frac = Math.min(1, c.progress / target);
    return `
      <div class="row-item weekly${c.done ? " done" : ""}">
        <div class="badge">${c.done ? "✓" : `${Math.round(frac * 100)}%`}</div>
        <div class="row-text">
          <b>${esc(c.text)}</b>
          <span>${c.target ? `${num(c.progress)} / ${num(c.target)} · ` : ""}${num(c.xp)} XP</span>
          ${c.target ? `<div class="bar"><div style="width:${(frac * 100).toFixed(1)}%"></div></div>` : ""}
        </div>
      </div>`;
  }).join("");

  clearInterval(countdownTimer);
  const tick = () => {
    if (!document.body.contains(reset)) return clearInterval(countdownTimer);
    reset.textContent = `new challenges in ${untilLabel(w.weekEndsAt)}`;
    reset.title = `Resets ${new Date(w.weekEndsAt).toLocaleString()} (Monday 00:00 UTC)`;
  };
  tick();
  countdownTimer = setInterval(tick, 30_000);
}

// ---------------------------------------------------------------- Profile: achievements with rarity

let rarity;

async function rarityTable() {
  rarity ??= get("/achievements/rarity").catch(() => ({ players: 0, counts: {} }));
  return rarity;
}

function rarityLabel(r, id) {
  if (!r.players) return "";
  const share = (r.counts[id] ?? 0) / r.players;
  const text = share < 0.001 ? "<0.1%" : share < 0.1 ? (share * 100).toFixed(1) + "%" : Math.round(share * 100) + "%";
  const tier = share <= 0.01 ? "legendary" : share <= 0.05 ? "epic" : share <= 0.2 ? "rare" : "";
  return `<span class="rarity ${tier}" title="${num(r.counts[id] ?? 0)} of ${num(r.players)} Better Battle players">${text} of players</span>`;
}

/** Fills #achievements and #ach-count, with how rare each one is and a Recent/Rarest toggle. */
export async function renderAchievements(achievements) {
  const box = document.getElementById("achievements");
  document.getElementById("ach-count").textContent = `${num(achievements.unlocked.length)} of ${num(achievements.total)}`;
  if (!achievements.unlocked.length) {
    box.innerHTML = `<p class="muted">None yet.</p>`;
    return;
  }
  const r = await rarityTable();
  let sort = "recent", all = false;
  const draw = () => {
    const list = [...achievements.unlocked];
    if (sort === "rarest") list.sort((x, y) => (r.counts[x.id] ?? 0) - (r.counts[y.id] ?? 0));
    const shown = all ? list : list.slice(0, 6);
    box.innerHTML = `
      <div class="toggle small" role="group" aria-label="Sort achievements">
        <button data-sort="recent" aria-pressed="${sort === "recent"}">Recent</button>
        <button data-sort="rarest" aria-pressed="${sort === "rarest"}">Rarest</button>
      </div>
      ${shown.map((a) => `
        <div class="row-item">
          <div class="badge">+${num(a.points)}</div>
          <div class="row-text"><b>${esc(a.name)}</b><span>${esc(a.description)}</span>${rarityLabel(r, a.id)}</div>
        </div>`).join("")}
      ${list.length > 6 ? `<button class="btn small more" data-all>${all ? "Show fewer" : `Show all ${num(list.length)}`}</button>` : ""}`;
  };
  box.addEventListener("click", (e) => {
    const s = e.target.closest("button[data-sort]");
    if (s) { sort = s.dataset.sort; draw(); }
    if (e.target.closest("button[data-all]")) { all = !all; draw(); }
  });
  draw();
}

// ---------------------------------------------------------------- Profile: Speed Builders bests

export function spbBestsSection() {
  return `
    <section class="card" id="spb-card" hidden>
      <div class="card-head">
        <div>
          <h2>Speed Builders bests</h2>
          <p class="sub">Fastest Perfect Build on each build, and where it ranks</p>
        </div>
        <a href="/speed-builders" data-link>All records</a>
      </div>
      <div id="spb-bests"></div>
    </section>`;
}

export async function loadSpbBests(name) {
  const rows = await get(`/players/${encodeURIComponent(name)}/spb-bests`).catch(() => []);
  const card = document.getElementById("spb-card");
  if (!rows.length || !card) return;
  card.hidden = false;
  document.getElementById("spb-bests").innerHTML = `
    <div class="table-scroll"><table>
      <thead><tr><th>Build</th><th class="right">Best</th><th class="right">Rank</th></tr></thead>
      <tbody>${rows.slice(0, 15).map((b) => `
        <tr>
          <td><a href="/speed-builders/${esc(b.build)}" data-link>${esc(b.name)}</a></td>
          <td class="right num">${secs(b.timeMs)}</td>
          <td class="right">${b.rank === 1 ? `<span class="place win">Record</span>` : `#${num(b.rank)} <span class="muted">of ${num(b.players)}</span>`}</td>
        </tr>`).join("")}</tbody>
    </table></div>`;
}

// ---------------------------------------------------------------- Profile: share card

function loadImage(src) {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

/** Draws a 1200×630 profile card, the size Discord and most sites preview. */
async function drawCard(p, achievementCount) {
  const W = 1200, H = 630;
  const c = document.createElement("canvas");
  c.width = W;
  c.height = H;
  const g = c.getContext("2d");
  await document.fonts.load("700 64px 'Pixelify Sans'").catch(() => {});
  const pixel = (size, weight = 700) => `${weight} ${size}px 'Pixelify Sans', 'Courier New', monospace`;

  // Panel, like the site's cards: dark fill, black outline, light bevel
  g.fillStyle = "#2a2a30";
  g.fillRect(0, 0, W, H);
  g.fillStyle = "#000";
  g.fillRect(40, 40, W - 80, H - 80);
  g.fillStyle = "#1f1f24";
  g.fillRect(46, 46, W - 92, H - 92);
  g.fillStyle = "#3a3a42";
  g.fillRect(46, 46, W - 92, 4);

  // Face in a black frame; a plain slot if the avatar can't load
  const face = await loadImage(avatar(p.uuid, 160));
  g.fillStyle = "#000";
  g.fillRect(86, 86, 168, 168);
  g.fillStyle = "#3a3a42";
  g.fillRect(90, 90, 160, 160);
  if (face) {
    g.imageSmoothingEnabled = false;
    g.drawImage(face, 90, 90, 160, 160);
  } else {
    g.fillStyle = "#9d9da6";
    g.font = pixel(96);
    const initial = p.name[0].toUpperCase();
    g.fillText(initial, 170 - g.measureText(initial).width / 2, 205);
  }

  const bb = p.betterBattle;
  g.fillStyle = "#f0f0f0";
  g.font = pixel(72);
  g.fillText(p.name, 290, 160);
  g.font = pixel(36, 600);
  g.fillStyle = p.title.current.hex ?? "#c6c6c6";
  g.fillText(p.title.current.name, 290, 212);
  if (bb) {
    g.fillStyle = "#80ff20";
    g.fillText(`Level ${bb.level}`, 290 + g.measureText(p.title.current.name).width + 36, 212);
  }

  const stats = [
    ["Global rank", p.rank ? "#" + num(p.rank) : "–"],
    ["Build Battle score", num(p.score)],
    ["Wins", num(p.hypixel.wins)],
    bb ? ["Crowd score", percent(bb.crowdScore)] : ["Games played", num(p.hypixel.gamesPlayed)],
    bb ? ["Achievements", num(achievementCount)] : ["Correct guesses", num(p.hypixel.correctGuesses)],
    bb ? ["Aspiring Points", num(bb.ap)] : ["Speed Builders wins", num(p.hypixel.modeWins.speedBuilders)],
  ];
  const colW = (W - 172) / 3;
  stats.forEach(([label, value], i) => {
    const x = 86 + (i % 3) * colW, y = 284 + Math.floor(i / 3) * 112;
    g.fillStyle = "#141416";
    g.fillRect(x, y, colW - 20, 96);
    g.fillStyle = "#9d9da6";
    g.font = "22px Verdana, sans-serif";
    g.fillText(label, x + 20, y + 34);
    g.fillStyle = i === 0 ? "#ffaa00" : "#f0f0f0";
    g.font = pixel(44);
    g.fillText(value, x + 20, y + 80);
  });

  g.fillStyle = "#ff8fc0";
  g.font = pixel(28);
  g.fillText("Better Battle", 86, H - 72);
  g.fillStyle = "#9d9da6";
  g.font = "20px Verdana, sans-serif";
  const url = `${location.host}/p/${p.name}`;
  g.fillText(url, W - 86 - g.measureText(url).width, H - 72);
  return c;
}

/** "Share card" button: preview, download, copy. */
export async function openShareCard(p) {
  const n = encodeURIComponent(p.name);
  const ach = p.betterBattle ? await get(`/players/${n}/achievements`).catch(() => ({ unlocked: [] })) : { unlocked: [] };
  const canvas = await drawCard(p, ach.unlocked.length);
  const dialog = document.createElement("dialog");
  dialog.className = "card share-dialog";
  dialog.innerHTML = `
    <div class="card-head"><h2>Share card</h2><button class="icon-btn" data-close aria-label="Close">✕</button></div>
    <p class="sub">Post it in Discord or anywhere else.</p>
    <div class="share-preview"></div>
    <div class="row-actions">
      <button class="btn primary" data-download>Download PNG</button>
      <button class="btn" data-copy>Copy image</button>
      <span class="muted" data-status role="status"></span>
    </div>`;
  dialog.querySelector(".share-preview").append(canvas);
  document.body.append(dialog);
  const status = dialog.querySelector("[data-status]");
  const blob = () => new Promise((resolve) => canvas.toBlob(resolve, "image/png"));

  dialog.addEventListener("click", async (e) => {
    if (e.target === dialog || e.target.closest("[data-close]")) dialog.close();
    if (e.target.closest("[data-download]")) {
      try {
        const url = URL.createObjectURL(await blob());
        const a = Object.assign(document.createElement("a"), { href: url, download: `${p.name}-better-battle.png` });
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch {
        status.textContent = "Couldn't make the image in this browser.";
      }
    }
    if (e.target.closest("[data-copy]")) {
      try {
        await navigator.clipboard.write([new ClipboardItem({ "image/png": blob() })]);
        status.textContent = "Copied. Paste it into Discord.";
      } catch {
        status.textContent = "Your browser can't copy images. Use Download instead.";
      }
    }
  });
  dialog.addEventListener("close", () => dialog.remove());
  dialog.showModal();
}

