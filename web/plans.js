// Plans on the website: the pricing page, the account's plan and style editor, what supporters'
// profiles show, leaderboard positions, and the Master Builder pages (insights, Wrapped, private
// leaderboards). The server decides every gate (api/src/lib/plans.ts); this only shows the result.
import { app, esc, num, percent, avatar, get, go, request, me, meReady, loadMe, API, MODES, ordinal } from "./app.js";
import { ACCENTS, BANNERS, CARD_THEMES } from "./style-options.js";

const NAMES = { free: "Builder", architect: "Architect", master: "Master Builder" };
const RANK = { free: 0, architect: 1, master: 2 };
const myPlan = () => me?.plan?.id ?? "free";
export const has = (plan) => RANK[myPlan()] >= RANK[plan];

const secs = (ms) => (ms == null ? "–" : (ms / 1000).toFixed(2) + "s");
const dateLabel = (iso) => new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
const pretty = (id) => String(id).replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());

function money(price) {
  if (!price) return "";
  if (!price.amount) return "Free";
  const amount = new Intl.NumberFormat("en-US", { style: "currency", currency: price.currency.toUpperCase() }).format(price.amount / 100);
  return `${amount}<span class="per"> / ${esc(price.interval)}</span>`;
}

/** A small plate next to a supporter's name. */
export function supporterChip(plan) {
  if (!plan) return "";
  return `<span class="chip supporter ${plan}" title="Supports Better Battle">${plan === "master" ? "Master Builder" : "Architect"}</span>`;
}

/** The mark in leaderboard rows. */
export const supporterMark = (plan) => (plan ? `<span class="sup-mark ${plan}" title="${NAMES[plan]}">${plan === "master" ? "♛" : "★"}</span>` : "");

/** What shows in place of a feature the viewer's plan doesn't include. */
function upsell(plan, heading, text) {
  return `
    <div class="upsell">
      <div>
        <b>${esc(heading)}</b>
        <p>${esc(text)} Included with ${NAMES[plan]}.</p>
      </div>
      <a class="btn primary" href="/plans" data-link>See plans</a>
    </div>`;
}

async function startCheckout(plan, button) {
  await meReady;
  if (!me) return go("/signup");
  button.disabled = true;
  try {
    const { url } = await request("POST", "/billing/checkout", { plan });
    location.href = url;
  } catch (err) {
    if (err.body?.error === "already_subscribed") return openPortal(button);
    button.disabled = false;
    button.insertAdjacentHTML("afterend", `<p class="form-error">Payments aren't available right now. Try again in a little while.</p>`);
  }
}

async function openPortal(button) {
  button.disabled = true;
  try {
    const { url } = await request("POST", "/billing/portal", {});
    location.href = url;
  } catch {
    button.disabled = false;
    button.insertAdjacentHTML("afterend", `<p class="form-error">Couldn't open billing right now. Try again in a little while.</p>`);
  }
}

// ---------------------------------------------------------------- /plans

export async function plansPage() {
  document.title = "Plans · Better Battle";
  app.innerHTML = `<div class="loading" style="height:480px"></div>`;
  const [data] = await Promise.all([get("/billing/plans"), meReady]);
  const current = myPlan();
  const action = (p) => {
    if (p.id === current) return `<span class="chip pink">Your plan</span>`;
    if (p.id === "free") return "";
    if (!data.configured || !p.price) return `<span class="muted">Coming soon</span>`;
    if (me?.plan?.hasBilling && current !== "free") return `<button class="btn" data-portal>Change plan</button>`;
    return `<button class="btn primary" data-plan="${p.id}">Choose ${esc(p.name)}</button>`;
  };
  app.innerHTML = `
    <section class="intro">
      <h1>Support Better Battle</h1>
      <p>The mod, every achievement, the Speed Builders timer and the rankings are free, and always will be.
        Plans add looks and deeper stats on this site and in Discord. Nothing inside a game is ever paid.</p>
    </section>
    <div class="plan-grid">
      ${data.plans.map((p) => `
        <section class="card plan ${p.id}${p.id === current ? " current" : ""}">
          <h2>${esc(p.name)}</h2>
          <p class="sub">${esc(p.tagline)}</p>
          <div class="price">${p.id === "free" ? "Free" : money(p.price) || "&nbsp;"}</div>
          <ul class="plain ticks">${p.perks.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>
          <div class="plan-action">${action(p)}</div>
        </section>`).join("")}
    </div>
    <section class="card">
      <h2>Good to know</h2>
      <ul class="plain ticks">
        <li>Payments are handled by Stripe. Better Battle never sees your card.</li>
        <li>Cancel any time from your Account page. You keep your plan until the end of the period you paid for.</li>
        <li>If a plan ends, nothing is deleted: your style and private leaderboards come back if you return.</li>
        <li>Under 18? Ask a parent or guardian before paying. See the <a href="/terms" data-link>Terms</a> for refunds.</li>
        <li>Billing question? Email <a href="mailto:KoiduSupport@gmail.com">KoiduSupport@gmail.com</a>.</li>
        <li>Discord perks follow the Discord account connected on your <a href="/account" data-link>Account page</a>.</li>
      </ul>
    </section>`;
  // app.onclick is cleared by the router on every page change
  app.onclick = (e) => {
    const choose = e.target.closest("button[data-plan]");
    if (choose) startCheckout(choose.dataset.plan, choose);
    const portal = e.target.closest("button[data-portal]");
    if (portal) openPortal(portal);
  };
}

// ---------------------------------------------------------------- Account: plan and profile style

const STYLE_ERRORS = {
  vanity_not_allowed: "That link isn't available. Use 3–20 letters, numbers, dashes or underscores.",
  vanity_taken: "Someone already has that link.",
  achievement_not_unlocked: "You haven't unlocked that achievement.",
};

/** Fills #plan-card on the Account page. */
export async function renderPlanCard(el) {
  if (!el) return;
  const plan = me.plan;
  const justPaid = new URLSearchParams(location.search).get("billing") === "success";
  if (justPaid) history.replaceState(null, "", "/account");
  const status = plan.id === "free" ? "The free plan: the mod, achievements and rankings."
    : plan.comped ? "A gift from the Better Battle team."
    : plan.cancels ? `Ends on ${dateLabel(plan.until)} and won't renew.`
    : plan.until ? `Renews on ${dateLabel(plan.until)}.` : "";
  el.innerHTML = `
    <div class="card-head">
      <div>
        <h2>Plan: ${esc(plan.name)}</h2>
        <p class="sub">${esc(status)}</p>
      </div>
      <div class="row-actions">
        ${plan.hasBilling && !plan.comped ? `<button class="btn" id="manage-billing">Manage billing</button>` : ""}
        <a class="btn${plan.id === "free" ? " primary" : ""}" href="/plans" data-link>${plan.id === "free" ? "See plans" : "All plans"}</a>
      </div>
    </div>
    ${justPaid ? `<p class="status-notice" role="status">Thank you for supporting Better Battle! Your plan appears here within a few seconds.</p>` : ""}
    ${has("master") ? `
      <div class="perk-links">
        <a class="btn small" href="/insights" data-link>Insights</a>
        <a class="btn small" href="/wrapped" data-link>Wrapped</a>
        <a class="btn small" href="/groups" data-link>Private leaderboards</a>
        <a class="btn small" href="${API}/me/export/matches.csv">Download games (CSV)</a>
      </div>` : ""}
    ${has("architect") ? `<div id="style-editor"></div>` : ""}`;
  el.querySelector("#manage-billing")?.addEventListener("click", (e) => openPortal(e.target));
  if (justPaid) setTimeout(async () => { await loadMe(); if (document.body.contains(el)) renderPlanCard(el); }, 4000);
  if (has("architect")) renderStyleEditor(el.querySelector("#style-editor"));
}

async function renderStyleEditor(el) {
  const style = me.style ?? {};
  const mine = me.minecraft
    ? await get(`/players/${encodeURIComponent(me.minecraft.name)}/achievements`).catch(() => ({ unlocked: [] }))
    : { unlocked: [] };
  const swatch = (kind, id, css, current) => `
    <button type="button" class="swatch${id === (current ?? "") ? " on" : ""}" data-kind="${kind}" data-id="${id}"
      style="${css}" title="${id ? pretty(id) : "None"}" aria-label="${id ? pretty(id) : "None"}" aria-pressed="${id === (current ?? "")}">${id ? "" : "✕"}</button>`;
  el.innerHTML = `
    <h2 class="minor">Profile style</h2>
    <div class="style-grid">
      <div><span class="field-label">Accent colour</span>
        <div class="swatches">${swatch("accent", "", "", style.accent)}${Object.entries(ACCENTS).map(([id, hex]) => swatch("accent", id, `background:${hex}`, style.accent)).join("")}</div></div>
      <div><span class="field-label">Banner</span>
        <div class="swatches wide">${swatch("banner", "", "", style.banner)}${Object.entries(BANNERS).map(([id, css]) => swatch("banner", id, `background:${css}`, style.banner)).join("")}</div></div>
      <div><span class="field-label">Share card theme</span>
        <div class="swatches wide">${Object.entries(CARD_THEMES).map(([id, t]) => swatch("cardTheme", id === "classic" ? "" : id, `background:linear-gradient(135deg, ${t.panel} 60%, ${t.accent} 60%)`, style.cardTheme)).join("")}</div></div>
      <label class="field"><span>Featured achievement</span>
        <select id="featured">
          <option value="">None</option>
          ${mine.unlocked.map((a) => `<option value="${esc(a.id)}"${a.id === style.featuredAchievement ? " selected" : ""}>${esc(a.name)}</option>`).join("")}
        </select></label>
      <form class="field" id="vanity-form"><span>Your link</span>
        <div class="inline-form"><span class="muted">${esc(location.host)}/</span>
          <input id="vanity" maxlength="20" value="${esc(style.vanity ?? "")}" placeholder="yourname" autocomplete="off" spellcheck="false">
          <button class="btn small" type="submit">Save</button></div></form>
    </div>
    <p class="muted saved" id="style-status" role="status"></p>`;
  const status = el.querySelector("#style-status");
  const save = async (patch) => {
    try {
      await request("PUT", "/me/style", patch);
      Object.assign(me.style, patch);
      status.textContent = "Saved.";
      return true;
    } catch (err) {
      status.textContent = STYLE_ERRORS[err.body?.error] ?? "Couldn't save. Try again.";
      return false;
    }
  };
  el.addEventListener("click", async (e) => {
    const b = e.target.closest("button.swatch");
    if (!b) return;
    if (await save({ [b.dataset.kind]: b.dataset.id || null })) {
      for (const x of el.querySelectorAll(`button.swatch[data-kind="${b.dataset.kind}"]`)) {
        x.classList.toggle("on", x === b);
        x.setAttribute("aria-pressed", String(x === b));
      }
    }
  });
  el.querySelector("#featured").addEventListener("change", (e) => save({ featuredAchievement: e.target.value || null }));
  el.querySelector("#vanity-form").addEventListener("submit", (e) => {
    e.preventDefault();
    save({ vanity: el.querySelector("#vanity").value.trim().toLowerCase() || null });
  });
}

// ---------------------------------------------------------------- Profile: style, featured achievement, positions

/** Applies a supporter's accent and banner to their profile header. */
export function decorateProfile(p) {
  const style = p.style ?? {};
  const top = document.querySelector(".profile-top");
  if (!top) return;
  if (style.accent && ACCENTS[style.accent]) {
    top.style.setProperty("--accent", ACCENTS[style.accent]);
    top.classList.add("accented");
  }
  if (style.banner && BANNERS[style.banner]) {
    top.insertAdjacentHTML("beforebegin", `<div class="profile-banner" style="background:${BANNERS[style.banner]}"></div>`);
    top.classList.add("has-banner");
  }
}

/** The featured achievement, shown above the achievements list. */
export function featuredAchievement(style, achievements) {
  const a = style?.featuredAchievement && achievements.unlocked.find((x) => x.id === style.featuredAchievement);
  if (!a) return "";
  return `
    <div class="row-item featured">
      <div class="badge">★</div>
      <div class="row-text"><b>${esc(a.name)}</b><span>${esc(a.description)}</span><span class="rarity legendary">Featured</span></div>
    </div>`;
}

export function positionsSection() {
  return `
    <section class="card" id="positions-card">
      <div class="card-head">
        <div>
          <h2>Leaderboard positions</h2>
          <p class="sub" id="positions-sub">Where this player stands on every Build Battle leaderboard</p>
        </div>
        <a href="/leaderboard" data-link>All leaderboards</a>
      </div>
      <div id="positions"><div class="loading" style="height:90px"></div></div>
    </section>`;
}

export async function loadPositions(name) {
  const el = document.getElementById("positions");
  if (!el) return;
  const data = await get(`/players/${encodeURIComponent(name)}/leaderboards`).catch(() => null);
  if (!data) return document.getElementById("positions-card").remove();
  const tile = (b) => `
    <a class="tile pos${b.rank && b.rank <= 3 ? " podium" : ""}" href="/leaderboard?board=${esc(b.id)}" data-link>
      <span>${esc(b.name)}</span>
      <b>${b.rank ? "#" + num(b.rank) : "–"}</b>
      <small>${b.rank ? `${num(b.value)} · of ${num(b.of)}` : "not ranked"}</small>
    </a>`;
  el.innerHTML = `<div class="tiles positions">${data.boards.filter((b) => b.rank || b.group !== "Better Battle").map(tile).join("")}</div>`
    + (data.locked ? upsell(data.requires, `${num(data.lockedBoards.length)} more leaderboards`,
        "Wins, tokens, every mode, Speed Builders and more, for any player.") : "");
}

// ---------------------------------------------------------------- Leaderboard: any board

/** The board picker and, for boards other than score, the table. */
export async function boardPicker(current) {
  const boards = await get("/boards").catch(() => []);
  const groups = [...new Set(boards.map((b) => b.group))];
  return `
    <label class="sr-only" for="board-pick">Leaderboard</label>
    <select id="board-pick">
      ${groups.map((g) => `<optgroup label="${esc(g)}">${boards.filter((b) => b.group === g)
        .map((b) => `<option value="${esc(b.id)}"${b.id === current ? " selected" : ""}>${esc(b.name)}</option>`).join("")}</optgroup>`).join("")}
    </select>`;
}

export function boardTable(board) {
  if (!board.rows.length) return `<div class="empty">Nobody is on this leaderboard yet.</div>`;
  return `
    <div class="table-scroll"><table>
      <thead><tr><th>#</th><th>Player</th><th class="right">${esc(board.board.short)}</th><th class="right">Score</th></tr></thead>
      <tbody>${board.rows.map((r) => `
        <tr>
          <td class="num">${num(r.rank)}</td>
          <td><a class="player" href="/p/${esc(r.name)}" data-link>
            <img src="${avatar(r.uuid, 32)}" alt="" width="28" height="28" loading="lazy">${esc(r.name ?? "Unknown")}
            ${r.usesMod ? `<span class="bb-tag" title="Uses Better Battle">BB</span>` : ""}${supporterMark(r.supporter)}</a></td>
          <td class="right num">${num(r.value)}</td>
          <td class="right muted">${num(r.score)}</td>
        </tr>`).join("")}</tbody>
    </table></div>`;
}

// ---------------------------------------------------------------- /insights (Master Builder)

async function gated(plan, title, pitch, load) {
  await meReady;
  if (!me) { go("/login"); return null; }
  if (!has(plan)) {
    app.innerHTML = `<section class="card"><h2>${esc(title)}</h2>${upsell(plan, title, pitch)}</section>`;
    return null;
  }
  app.innerHTML = `<div class="loading" style="height:420px"></div>`;
  try {
    return await load();
  } catch (err) {
    app.innerHTML = err.status === 402
      ? `<section class="card"><h2>${esc(title)}</h2>${upsell(plan, title, pitch)}</section>`
      : `<div class="card empty">Couldn't load this. Try again in a minute.</div>`;
    return null;
  }
}

function themeRows(list) {
  if (!list.length) return `<p class="muted">Play some Solo, Teams or Pro games first.</p>`;
  return list.map((t) => `
    <div class="bar-row">
      <span class="bar-name"><b>${esc(t.theme)}</b> <span class="muted">${num(t.games)} ${t.games === 1 ? "game" : "games"}${t.wins ? `, ${num(t.wins)} won` : ""}</span></span>
      <div class="bar"><div style="width:${((t.crowd ?? 0) * 100).toFixed(1)}%"></div></div>
      <span class="num">${percent(t.crowd, 0)}</span>
    </div>`).join("");
}

function sparkline(history, recordMs) {
  if (history.length < 2) return `<span class="muted">One perfect build so far</span>`;
  const W = 220, H = 44, times = history.map((h) => h.timeMs);
  const lo = Math.min(...times, recordMs ?? Infinity), hi = Math.max(...times);
  const y = (ms) => 4 + (H - 8) * (1 - (hi - ms) / Math.max(1, hi - lo));
  const pts = times.map((ms, i) => `${(i * (W - 8) / (times.length - 1) + 4).toFixed(1)},${y(ms).toFixed(1)}`).join(" ");
  return `<svg class="spark" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="Times over your last ${times.length} perfect builds; lower is faster">
    ${recordMs ? `<line x1="0" x2="${W}" y1="${y(recordMs).toFixed(1)}" y2="${y(recordMs).toFixed(1)}" class="spark-record"/>` : ""}
    <polyline points="${pts}" fill="none"/></svg>`;
}

export async function insightsPage() {
  document.title = "Insights · Better Battle";
  const data = await gated("master", "Insights", "Your best and worst themes, and how your Speed Builders times are moving.",
    () => Promise.all([get("/me/insights/themes"), get("/me/insights/speed-builders")]));
  if (!data) return;
  const [themes, spb] = data;
  const trend = (ms) => ms == null ? `<span class="muted">–</span>`
    : ms < 0 ? `<span class="better">${secs(-ms)} faster</span>` : ms > 0 ? `<span class="worse">${secs(ms)} slower</span>` : "level";
  app.innerHTML = `
    <section class="intro">
      <h1>Insights</h1>
      <p>Only you can see this page. Crowd score is your share of the most votes a build could get.</p>
    </section>
    <section class="stats four">
      <div class="stat"><span class="stat-label">Themes built</span><span class="stat-value">${num(themes.themes)}</span><span class="stat-note">${num(themes.games)} voted games</span></div>
      <div class="stat"><span class="stat-label">Average crowd score</span><span class="stat-value">${percent(themes.averageCrowd)}</span><span class="stat-note">across all themes</span></div>
      <div class="stat"><span class="stat-label">Perfect builds</span><span class="stat-value">${num(spb.perfect)}</span><span class="stat-note">of ${num(spb.rounds)} Speed Builders rounds</span></div>
      <div class="stat"><span class="stat-label">Precision</span><span class="stat-value">${percent(spb.precision)}</span><span class="stat-note">clicks that ended up in the build</span></div>
    </section>
    <div class="cols even">
      <section class="card"><h2>Your best themes</h2><div class="bars">${themeRows(themes.best)}</div></section>
      <section class="card"><h2>Your toughest themes</h2><div class="bars weak">${themeRows(themes.worst)}</div></section>
    </div>
    <div class="cols even">
      <section class="card"><h2>Most played</h2><div class="bars">${themeRows(themes.mostPlayed)}</div></section>
      <section class="card">
        <h2>By mode</h2>
        <div class="table-scroll"><table>
          <thead><tr><th>Mode</th><th class="right">Games</th><th class="right">Won</th><th class="right">Crowd</th></tr></thead>
          <tbody>${themes.byMode.map((m) => `<tr><td>${esc(MODES[m.mode] ?? m.mode)}</td><td class="right num">${num(m.games)}</td>
            <td class="right">${percent(m.games ? m.wins / m.games : null, 0)}</td><td class="right">${percent(m.crowd, 0)}</td></tr>`).join("")}</tbody>
        </table></div>
        <h2 class="minor" style="margin-top:14px">Last 12 months</h2>
        <div class="months">${themes.byMonth.map((m) => {
          const max = Math.max(...themes.byMonth.map((x) => x.games));
          return `<div class="month" title="${esc(m.month)}: ${num(m.games)} games, ${num(m.wins)} wins"><i style="height:${Math.max(4, m.games / max * 60)}px"></i><span>${esc(m.month.slice(5))}</span></div>`;
        }).join("") || `<p class="muted">No games yet.</p>`}</div>
      </section>
    </div>
    <section class="card">
      <div class="card-head"><div>
        <h2>Speed Builders, build by build</h2>
        <p class="sub">Your perfect-build times over time (lower is faster). The dashed line is the record. Trend compares your last 3 with the 3 before.</p>
      </div></div>
      ${spb.builds.length ? `<div class="table-scroll"><table>
        <thead><tr><th>Build</th><th class="right">Best</th><th class="right">Record</th><th class="right">Gap</th><th>History</th><th class="right">Trend</th></tr></thead>
        <tbody>${spb.builds.map((b) => `
          <tr>
            <td><a href="/speed-builders/${esc(b.build)}" data-link><b>${esc(b.name)}</b></a> <span class="muted">×${num(b.attempts)}</span></td>
            <td class="right num">${secs(b.bestMs)}</td>
            <td class="right muted">${b.recordMs ? secs(b.recordMs) : "–"}</td>
            <td class="right">${b.gapMs == null ? "–" : b.gapMs <= 0 ? `<span class="place win">Record</span>` : "+" + secs(b.gapMs)}</td>
            <td>${sparkline(b.history, b.recordMs)}</td>
            <td class="right">${trend(b.trendMs)}</td>
          </tr>`).join("")}</tbody></table></div>` : `<p class="muted">Finish a Speed Builders round with a Perfect Build and it shows up here.</p>`}
    </section>`;
}

// ---------------------------------------------------------------- /wrapped (Master Builder)

const VOTE_NAMES = { super_poop: "Super Poop", poop: "Poop", meh: "Meh", ok: "OK", good: "Good", epic: "Epic", legendary: "Legendary" };

export async function wrappedPage() {
  document.title = "Wrapped · Better Battle";
  const period = new URLSearchParams(location.search).get("period") ?? "year";
  const w = await gated("master", "Build Battle Wrapped", "Your year in Build Battle, whenever you want it: favourite themes, voting habits, best months.",
    () => get("/me/wrapped?period=" + encodeURIComponent(period)));
  if (!w) return;
  const label = { year: `Your ${new Date().getFullYear()}`, "30d": "Your last 30 days", all: "All time" }[w.period];
  const judge = !w.votes ? null : w.votes.average >= 5.2 ? "a generous judge" : w.votes.average <= 3.8 ? "a tough judge" : "a fair judge";
  const big = (value, text) => `<div class="wrap-tile"><b>${value}</b><span>${text}</span></div>`;
  const topMode = w.modes[0];
  app.innerHTML = `
    <section class="intro">
      <h1>Build Battle Wrapped</h1>
      <div class="toggle" role="group" aria-label="Period">
        ${[["year", "This year"], ["30d", "Last 30 days"], ["all", "All time"]].map(([id, text]) =>
          `<a class="tab" href="/wrapped?period=${id}" data-link aria-current="${id === w.period ? "true" : "false"}">${text}</a>`).join("")}
      </div>
    </section>
    ${w.games === 0 ? `<div class="card empty">No games in this period yet.</div>` : `
    <section class="card wrap">
      <h2>${esc(label)}</h2>
      <div class="wrap-grid">
        ${big(num(w.games), "games played")}
        ${big(num(w.wins), "wins")}
        ${big(num(w.hours) + "h", "spent building")}
        ${big(num(w.blocks), "blocks placed")}
        ${big(num(w.themes), "different themes")}
        ${big(num(w.daysPlayed), "days played")}
        ${big(num(w.longestWinStreak), "longest win streak")}
        ${big("+" + num(w.xp), "XP earned")}
      </div>
    </section>
    <div class="cols even">
      <section class="card">
        <h2>Your best builds</h2>
        <div class="list">${w.bestBuilds.map((b) => `
          <div class="row-item"><div class="badge">${esc(b.grade ?? "★")}</div>
            <div class="row-text"><b>${esc(b.theme ?? "Untitled")}</b>
              <span>${esc(MODES[b.mode] ?? b.mode)}, ${b.placement ? ordinal(b.placement) : "–"} of ${num(b.players)} · ${percent(b.crowd_score, 0)} crowd score</span></div></div>`).join("")
          || `<p class="muted">Play a voted mode to see these.</p>`}</div>
        <h2 class="minor" style="margin-top:14px">Highlights</h2>
        <ul class="plain ticks">
          ${topMode ? `<li>Favourite mode: <b>${esc(MODES[topMode.mode] ?? topMode.mode)}</b> (${num(topMode.games)} games)</li>` : ""}
          ${w.bestMonth ? `<li>Best month: <b>${esc(new Date(w.bestMonth.month + "-01").toLocaleDateString("en-GB", { month: "long", year: "numeric" }))}</b> with ${num(w.bestMonth.wins)} wins</li>` : ""}
          ${w.busiestDay ? `<li>Busiest day: <b>${dateLabel(w.busiestDay.day)}</b>, ${num(w.busiestDay.games)} games</li>` : ""}
          ${w.crowd != null ? `<li>Average crowd score: <b>${percent(w.crowd)}</b></li>` : ""}
          <li><b>${num(w.grades.SS)}</b> SS grades and <b>${num(w.grades.S)}</b> S grades</li>
          ${w.achievements ? `<li><b>${num(w.achievements)}</b> achievements unlocked</li>` : ""}
          ${w.speedBuilders ? `<li><b>${num(w.speedBuilders.perfect)}</b> perfect Speed Builders rounds, fastest ${secs(w.speedBuilders.fastestMs)}</li>` : ""}
          ${w.favouriteBlocks.length ? `<li>Favourite blocks: ${w.favouriteBlocks.map((b) => `<b>${esc(pretty(b.block))}</b>`).join(", ")}</li>` : ""}
        </ul>
      </section>
      <section class="card">
        <h2>How you vote</h2>
        ${w.votes ? `
          <p class="sub">You cast ${num(w.votes.total)} votes and you're <b>${judge}</b>. Only you can see this.</p>
          <div class="bars votes">${Object.entries(w.votes.counts).reverse().map(([v, n]) => `
            <div class="bar-row"><span class="bar-name">${VOTE_NAMES[v]}</span>
              <div class="bar"><div style="width:${(n / Math.max(1, ...Object.values(w.votes.counts)) * 100).toFixed(1)}%"></div></div>
              <span class="num">${num(n)}</span></div>`).join("")}</div>
          ${w.votes.mostVotedOn ? `<p class="muted" style="margin-top:8px">You judged <b>${esc(w.votes.mostVotedOn.builder)}</b>'s builds most often (${num(w.votes.mostVotedOn.n)} times).</p>` : ""}`
          : `<p class="muted">No votes saved in this period. Vote saving can be switched on in the mod's Settings.</p>`}
      </section>
    </div>`}`;
}

// ---------------------------------------------------------------- /groups: private leaderboards

const GROUP_ERRORS = {
  no_minecraft: "Link your Minecraft account on the Account page first.",
  bad_invite: "That invite code doesn't work. Ask for a new one.",
  group_full: "That leaderboard is full.",
  too_many_groups: "You've reached the number of leaderboards you can own.",
};

export async function groupsPage() {
  document.title = "Private leaderboards · Better Battle";
  await meReady;
  if (!me) return go("/login");
  const groups = await get("/me/groups").catch(() => []);
  app.innerHTML = `
    <section class="intro">
      <h1>Private leaderboards</h1>
      <p>A leaderboard for your guild or friends, with a weekly race that resets every Monday. Only members can see it.</p>
    </section>
    <section class="card">
      <h2>Your leaderboards</h2>
      <div class="list">${groups.map((g) => `
        <a class="row-item link" href="/groups/${esc(g.slug)}" data-link>
          <div class="badge">${num(g.members)}</div>
          <div class="row-text"><b>${esc(g.name)}</b><span>${num(g.members)} ${g.members === 1 ? "member" : "members"}${g.owner ? " · you own this" : ""}</span></div>
        </a>`).join("") || `<p class="muted">You're not in any yet.</p>`}</div>
    </section>
    <div class="cols even">
      <section class="card">
        <h2>Join one</h2>
        <p class="sub">Joining is free. Paste the invite code a friend gave you.</p>
        <form class="inline-form" id="join-form">
          <label class="sr-only" for="join-code">Invite code</label>
          <input id="join-code" placeholder="Invite code" maxlength="32" autocomplete="off" spellcheck="false" value="${esc(new URLSearchParams(location.search).get("code") ?? "")}">
          <button class="btn primary" type="submit">Join</button>
        </form>
        <p class="form-error" id="join-error" hidden></p>
      </section>
      <section class="card">
        <h2>Create one</h2>
        ${has("master") ? `
          <form class="inline-form" id="create-form">
            <label class="sr-only" for="group-name">Name</label>
            <input id="group-name" placeholder="Name, e.g. Aspiring" minlength="2" maxlength="40" required>
            <button class="btn primary" type="submit">Create</button>
          </form>
          <p class="form-error" id="create-error" hidden></p>`
          : upsell("master", "Create a private leaderboard", "Start one for your guild or friends; they join for free.")}
      </section>
    </div>`;
  const submit = (formId, errorId, send) => document.getElementById(formId)?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const error = document.getElementById(errorId);
    try {
      const { slug } = await send();
      go("/groups/" + slug);
    } catch (err) {
      error.textContent = GROUP_ERRORS[err.body?.error] ?? "That didn't work. Try again.";
      error.hidden = false;
    }
  });
  submit("join-form", "join-error", () => request("POST", "/groups/join", { code: document.getElementById("join-code").value.trim() }));
  submit("create-form", "create-error", () => request("POST", "/me/groups", { name: document.getElementById("group-name").value.trim() }));
}

const GROUP_SORTS = {
  weekXp: ["This week's XP", (m) => m.weekXp],
  weekWins: ["Wins this week", (m) => m.weekWins],
  score: ["Score", (m) => m.score ?? 0],
  wins: ["Total wins", (m) => m.wins ?? 0],
  level: ["Level", (m) => m.level ?? 0],
};

export async function groupPage(slug) {
  document.title = "Private leaderboard · Better Battle";
  await meReady;
  if (!me) return go("/login");
  app.innerHTML = `<div class="loading" style="height:420px"></div>`;
  let g;
  try {
    g = await get("/groups/" + encodeURIComponent(slug));
  } catch {
    app.innerHTML = `<div class="card empty">This leaderboard doesn't exist, or you're not a member. <a href="/groups" data-link>Your leaderboards</a></div>`;
    return;
  }
  document.title = `${g.name} · Better Battle`;
  let sort = "weekXp";
  const inviteLink = g.invite ? `${location.origin}/groups?code=${g.invite}` : null;
  const draw = () => {
    const rows = [...g.members].sort((a, b) => GROUP_SORTS[sort][1](b) - GROUP_SORTS[sort][1](a));
    const ends = g.weekEndsAt ? Math.max(0, new Date(g.weekEndsAt) - Date.now()) : 0;
    const left = `${Math.floor(ends / 86400000)}d ${Math.floor(ends % 86400000 / 3600000)}h`;
    app.innerHTML = `
      <p class="crumbs"><a href="/groups" data-link>Private leaderboards</a> / ${esc(g.name)}</p>
      <section class="card">
        <div class="card-head">
          <div>
            <h2>${esc(g.name)}</h2>
            <p class="sub">${num(g.members.length)} members · owned by ${esc(g.owner ?? "–")}${g.paused ? "" : ` · weekly race ends in ${left}`}</p>
          </div>
          <div class="toggle" role="group" aria-label="Sort by">
            ${Object.entries(GROUP_SORTS).map(([id, [text]]) => `<button data-sort="${id}" aria-pressed="${id === sort}">${text}</button>`).join("")}
          </div>
        </div>
        ${g.paused ? `<div class="empty">This leaderboard is paused because its owner's Master Builder plan ended. Nothing is lost: it comes back if they return.</div>` : `
        <div class="table-scroll"><table>
          <thead><tr><th>#</th><th>Player</th><th class="right">Week XP</th><th class="right">Week wins</th><th class="right">Week games</th>
            <th class="right">Score</th><th class="right">Wins</th><th class="right">Level</th>${g.isOwner ? "<th></th>" : ""}</tr></thead>
          <tbody>${rows.map((m, i) => `
            <tr${m.name === me.minecraft?.name ? ` class="me"` : ""}>
              <td class="num">${i + 1}</td>
              <td><a class="player" href="/p/${esc(m.name)}" data-link><img src="${avatar(m.uuid, 32)}" alt="" width="28" height="28" loading="lazy">${esc(m.name)}</a>${m.owner ? ` <span class="muted">owner</span>` : ""}</td>
              <td class="right num">${num(m.weekXp)}</td><td class="right num">${num(m.weekWins)}</td><td class="right">${num(m.weekGames)}</td>
              <td class="right num">${num(m.score)}</td><td class="right">${num(m.wins)}</td><td class="right">${num(m.level)}</td>
              ${g.isOwner ? `<td class="right">${m.owner ? "" : `<button class="btn small" data-kick="${m.id}" data-name="${esc(m.name)}">Remove</button>`}</td>` : ""}
            </tr>`).join("")}</tbody>
        </table></div>`}
      </section>
      <section class="card">
        ${g.isOwner ? `
          <h2>Invite people</h2>
          <p class="sub">Anyone with this link can join for free. Making a new link turns the old one off.</p>
          <div class="inline-form">
            <input id="invite-link" readonly value="${esc(inviteLink)}" aria-label="Invite link">
            <button class="btn primary" id="copy-invite">Copy</button>
            <button class="btn" id="new-invite">New link</button>
          </div>
          <div class="row-actions" style="margin-top:12px"><button class="btn danger" id="delete-group">Delete this leaderboard</button></div>`
        : `<div class="row-actions"><button class="btn" id="leave-group">Leave this leaderboard</button></div>`}
      </section>`;
  };
  draw();
  app.onclick = async (e) => {
    const s = e.target.closest("button[data-sort]");
    if (s) { sort = s.dataset.sort; return draw(); }
    const kick = e.target.closest("button[data-kick]");
    if (kick && confirm(`Remove ${kick.dataset.name} from this leaderboard?`)) {
      await request("POST", `/groups/${slug}/kick`, { userId: Number(kick.dataset.kick) }).catch(() => {});
      g.members = g.members.filter((m) => String(m.id) !== kick.dataset.kick);
      return draw();
    }
    if (e.target.id === "copy-invite") {
      await navigator.clipboard.writeText(document.getElementById("invite-link").value).catch(() => {});
      e.target.textContent = "Copied";
    }
    if (e.target.id === "new-invite") {
      const r = await request("POST", `/groups/${slug}/invite`, {}).catch(() => null);
      if (r) { g.invite = r.invite; document.getElementById("invite-link").value = `${location.origin}/groups?code=${r.invite}`; }
    }
    if (e.target.id === "leave-group" && confirm("Leave this leaderboard?")) {
      await request("POST", `/groups/${slug}/leave`, {}).catch(() => {});
      app.onclick = null;
      go("/groups");
    }
    if (e.target.id === "delete-group" && confirm(`Delete "${g.name}" for everyone? This can't be undone.`)) {
      await request("DELETE", `/groups/${slug}`).catch(() => {});
      app.onclick = null;
      go("/groups");
    }
  };
}

/** battle.aspir3.net/<vanity>: returns the player's name, or null. */
export async function resolveVanity(pathname) {
  const m = pathname.match(/^\/([a-z0-9][a-z0-9_-]{2,19})\/?$/i);
  if (!m) return null;
  const r = await get("/vanity/" + encodeURIComponent(m[1].toLowerCase())).catch(() => null);
  return r?.name ?? null;
}
