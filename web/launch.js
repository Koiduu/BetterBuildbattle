// Launch: until LAUNCH, everyone gets a full-screen countdown. Visitors see "Sign up early"; people who
// have signed up see that they're in, and come back to the countdown, not the site, until it opens.
// From LAUNCH a bar tells visitors who aren't signed in that the site is live, for a week; it can be
// closed. The sign-up, login and password-reset pages are never covered, so those keep working.

const LAUNCH = Date.parse("2026-11-25T18:00:00Z");
const LIVE_FOR_MS = 7 * 24 * 60 * 60 * 1000;
const CLOSED_KEY = "bb_launch_closed";

let timer = null;
let signedIn = false;
let accountKnown = false;   // until /me answers, so signed-in visitors never see it flash

const pad = (n) => String(n).padStart(2, "0");

function closed() {
  try { return localStorage.getItem(CLOSED_KEY) === "1"; } catch { return false; }
}

function parts(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  return {
    d: Math.floor(total / 86400),
    h: Math.floor((total % 86400) / 3600),
    m: Math.floor((total % 3600) / 60),
    s: total % 60,
  };
}

function localTime() {
  return new Date(LAUNCH).toLocaleString(undefined, {
    weekday: "long", day: "numeric", month: "long", hour: "numeric", minute: "2-digit", timeZoneName: "short",
  });
}

// ---- the full screen, before launch

function screenEl() {
  let el = document.getElementById("launch-screen");
  if (el) return el;
  el = document.createElement("div");
  el.id = "launch-screen";
  el.className = "launch-screen";
  el.setAttribute("role", "dialog");
  el.setAttribute("aria-modal", "true");
  el.setAttribute("aria-labelledby", "launch-title");
  const unit = (key, label) => `
    <div class="launch-unit"><b data-unit="${key}">00</b><span>${label}</span></div>`;
  el.innerHTML = `
    <div class="launch-card" data-signed-in="">
      <span class="brand-mark launch-mark" aria-hidden="true">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#000" stroke-width="3" stroke-linecap="square" stroke-linejoin="miter"><path d="M4 20h16"/><path d="M6 20V10l6-6 6 6v10"/><path d="M10 20v-5h4v5"/></svg>
      </span>
      <h1 id="launch-title">Better Battle</h1>
      <p class="launch-lede">Stats, achievements, levels and Speed Builders tools for Hypixel Build Battle.</p>
      <div class="launch-timer" aria-hidden="true">
        ${unit("d", "days")}${unit("h", "hours")}${unit("m", "minutes")}${unit("s", "seconds")}
      </div>
      <p class="sr-only" id="launch-sr"></p>
      <p class="launch-when">Opens ${localTime()}</p>
      <div class="launch-actions"></div>
      <p class="launch-note"></p>
    </div>`;
  document.body.append(el);
  return el;
}

/** The buttons under the clock: sign up for visitors, "you're in" once they have. */
function renderActions(el) {
  const card = el.querySelector(".launch-card");
  const mode = signedIn ? "in" : "out";
  if (card.dataset.signedIn === mode) return;
  card.dataset.signedIn = mode;
  const actions = el.querySelector(".launch-actions");
  const note = el.querySelector(".launch-note");
  if (signedIn) {
    actions.innerHTML = `
      <p class="launch-in" role="status">You're signed up. See you at launch!</p>
      <button class="launch-login launch-logout" type="button">Log out</button>`;
    note.textContent = "This page opens into the site by itself when the countdown ends.";
    actions.querySelector(".launch-logout").addEventListener("click", async () => {
      await fetch("/bb/v1/auth/logout", { method: "POST" }).catch(() => {});
      location.reload();
    });
  } else {
    actions.innerHTML = `
      <a class="btn primary" href="/signup" data-link>Sign up early</a>
      <a class="launch-login" href="/login" data-link>Already signed up? Log in</a>`;
    note.textContent = "Sign up now to claim your profile the moment we open.";
  }
}

function showScreen(now) {
  const el = screenEl();
  renderActions(el);
  const left = parts(LAUNCH - now);
  for (const [k, v] of Object.entries(left)) {
    const cell = el.querySelector(`[data-unit="${k}"]`);
    const text = pad(v);
    if (cell.textContent !== text) cell.textContent = text;
  }
  // Screen readers get a minute-level sentence, not a number changing every second
  const sr = `Better Battle opens in ${left.d ? left.d + " days, " : ""}${left.h} hours and ${left.m} minutes.`;
  const srEl = el.querySelector("#launch-sr");
  if (srEl.textContent !== sr) srEl.textContent = sr;
  if (el.hidden !== false || !document.body.classList.contains("launch-locked")) {
    el.hidden = false;
    document.body.classList.add("launch-locked");
    document.querySelector(".shell")?.setAttribute("inert", "");   // the site behind can't be tabbed into
  }
}

function hideScreen() {
  const el = document.getElementById("launch-screen");
  if (el) el.hidden = true;
  document.body.classList.remove("launch-locked");
  document.querySelector(".shell")?.removeAttribute("inert");
}

// ---- the bar, the week after launch

function hideBar() {
  const bar = document.getElementById("launch-bar");
  if (!bar) return;
  bar.hidden = true;
  bar.innerHTML = "";
}

function showLiveBar() {
  const bar = document.getElementById("launch-bar");
  if (!bar || !bar.hidden) return;
  bar.innerHTML = `
    <div class="launch-inner">
      <span class="launch-text"><b>Better Battle is live.</b> Make an account to claim your profile and keep your stats.</span>
      <a class="btn small primary" href="/signup" data-link>Sign up</a>
      <button class="launch-close" type="button" aria-label="Close">×</button>
    </div>`;
  bar.querySelector(".launch-close").addEventListener("click", () => {
    try { localStorage.setItem(CLOSED_KEY, "1"); } catch {}
    hideBar();
  });
  bar.hidden = false;
}

/** Re-check who's looking and where. Called on every page change and whenever the account changes. */
export function updateLaunchBar(user) {
  if (user !== undefined) {
    signedIn = !!user;
    accountKnown = true;
  }
  const now = Date.now();
  const onAuthPage = /^\/(signup|login|forgot|reset)\/?$/.test(location.pathname);
  const visitor = accountKnown && !signedIn && !onAuthPage;

  if (accountKnown && !onAuthPage && now < LAUNCH) {
    hideBar();
    showScreen(now);
    if (!timer) timer = setInterval(() => updateLaunchBar(), 1000);
    return;
  }
  hideScreen();
  if (now < LAUNCH && accountKnown && !timer) {
    // On the sign-up or login page: keep ticking so the screen comes back if they go elsewhere
    timer = setInterval(() => updateLaunchBar(), 1000);
  }
  if (now >= LAUNCH) {
    clearInterval(timer);
    timer = null;
  }
  if (visitor && now >= LAUNCH && now <= LAUNCH + LIVE_FOR_MS && !closed()) showLiveBar();
  else hideBar();
}
