// The launch countdown: a bar under the top bar for visitors who aren't signed in. Before launch it counts
// down to LAUNCH; afterwards it says the site is live for a week (it can be closed), then goes away.
// Signed-in visitors never see it, and it stays off the sign-up and login pages themselves.

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

/** "1d 02:13:05" or "02:13:05", plus the parts for the screen reader text. */
function remaining(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return { clock: `${d ? d + "d " : ""}${pad(h)}:${pad(m)}:${pad(s)}`, d, h, m };
}

function localTime() {
  return new Date(LAUNCH).toLocaleString(undefined, {
    weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit", timeZoneName: "short",
  });
}

function hidden(bar) {
  bar.hidden = true;
  bar.innerHTML = "";
  clearInterval(timer);
  timer = null;
}

/** Re-check who's looking and where. Called on every page change and whenever the account changes. */
export function updateLaunchBar(user) {
  if (user !== undefined) {
    signedIn = !!user;
    accountKnown = true;
  }
  const bar = document.getElementById("launch-bar");
  if (!bar) return;
  const now = Date.now();
  const onAuthPage = /^\/(signup|login)\/?$/.test(location.pathname);
  if (!accountKnown || signedIn || onAuthPage || now > LAUNCH + LIVE_FOR_MS || (now >= LAUNCH && closed())) return hidden(bar);

  if (now >= LAUNCH) {
    clearInterval(timer);
    timer = null;
    bar.innerHTML = `
      <div class="launch-inner">
        <span class="launch-text"><b>Better Battle is live.</b> Make an account to claim your profile and keep your stats.</span>
        <a class="btn small primary" href="/signup" data-link>Sign up</a>
        <button class="launch-close" type="button" aria-label="Close">×</button>
      </div>`;
    bar.querySelector(".launch-close").addEventListener("click", () => {
      try { localStorage.setItem(CLOSED_KEY, "1"); } catch {}
      hidden(bar);
    });
    bar.hidden = false;
    return;
  }

  if (!timer) {
    bar.innerHTML = `
      <div class="launch-inner">
        <span class="launch-text">Better Battle launches in</span>
        <span class="launch-clock" aria-hidden="true"></span>
        <span class="sr-only launch-sr"></span>
        <span class="launch-when">${localTime()}</span>
        <a class="btn small primary" href="/signup" data-link>Sign up early</a>
      </div>`;
    timer = setInterval(() => updateLaunchBar(), 1000);
  }
  const left = remaining(LAUNCH - now);
  bar.querySelector(".launch-clock").textContent = left.clock;
  // Screen readers get a minute-level sentence, not a number changing every second
  const sr = `${left.d ? left.d + " days, " : ""}${left.h} hours and ${left.m} minutes.`;
  const srEl = bar.querySelector(".launch-sr");
  if (srEl.textContent !== sr) srEl.textContent = sr;
  bar.hidden = false;
}
