// Loading placeholders shaped like the content they stand in for. They reuse the real layout
// classes (.profile-top, .stats, .tiles, table...) so nothing jumps when the data arrives, and
// they show the labels that never change ("Global rank", "Solo") as real text: only the values
// are grey. Everything here is aria-hidden; screen readers get one "Loading" line instead.

/** A grey bar. Width in px or %, height in px. */
const bar = (w, h = 12, extra = "") =>
  `<span class="sk${extra ? " " + extra : ""}" style="width:${typeof w === "number" ? w + "px" : w};height:${h}px"></span>`;

const busy = (label, inner) =>
  `<div class="sk-wrap" aria-busy="true"><span class="sr-only" role="status">${label}</span><div aria-hidden="true">${inner}</div></div>`;

// Fixed widths that vary a little row to row, so a table of placeholders reads as names and
// numbers rather than a striped block. Deterministic so the page doesn't flicker on re-render.
const NAME_W = [112, 86, 134, 98, 120, 76, 140, 92, 104, 126];
const NUM_W = [64, 58, 70, 52, 66, 60, 56, 68, 62, 54];

/** The profile page: who block, the four headline numbers, sync bar, rank graph and mode tiles. */
export function profileSkeleton() {
  const meter = (left, right) => `
    <div class="meter">
      <div class="meter-label">${bar(left)}${bar(right)}</div>
      <div class="bar sk-bar"></div>
    </div>`;
  const stat = (label) => `
    <div class="stat">
      ${label ? `<span class="stat-label">${label}</span>` : bar(96, 12, "sk-label")}
      ${bar(132, 30)}
      ${bar(110, 12, "sk-note")}
    </div>`;
  const tile = (label) => `<div class="tile"><span>${label}</span>${bar(68, 22, "sk-tile")}</div>`;
  return busy("Loading profile", `
    <section class="profile-top">
      <div class="who">
        <div class="avatar sk-block"></div>
        <div class="who-text">
          <div class="tags">${bar(104, 30)}${bar(98, 30)}</div>
          ${bar(190, 42)}
          ${meter(64, 92)}
          ${meter(150, 104)}
        </div>
      </div>
      <div class="stats">${stat("Global rank")}${stat("Build Battle score")}${stat("")}${stat("")}</div>
    </section>
    <section class="card sync">
      <div class="sync-text">${bar(10, 10)}<div>${bar(220, 14)}${bar(120, 10, "sk-note")}</div></div>
      ${bar(96, 36)}${bar(104, 36)}${bar(120, 36)}
    </section>
    <div class="cols">
      <section class="card">
        <div class="card-head"><div><h2>Rank history</h2><p class="sub">Global rank by Build Battle score</p></div></div>
        <div class="graph"><div class="sk-block" style="height:200px"></div></div>
      </section>
      <section class="card">
        <h2>Wins by mode</h2>
        <div class="tiles">
          ${tile("Solo")}${tile("Teams")}${tile("Pro")}${tile("Guess the Build")}${tile("Speed Builders")}${tile("Total votes")}
        </div>
      </section>
    </div>`);
}

/** The leaderboard table (also the Top builders list on the home page). */
export function leaderboardSkeleton(rows = 10) {
  const body = Array.from({ length: rows }, (_, i) => `
    <tr>
      <td>${bar(18 + (i % 3) * 6, 14)}</td>
      <td><span class="player">${bar(24, 24, "sk-head")}${bar(NAME_W[i % NAME_W.length], 14)}</span></td>
      <td>${bar(NAME_W[(i + 4) % NAME_W.length] - 20, 14)}</td>
      <td class="right">${bar(NUM_W[i % NUM_W.length], 14)}</td>
      <td class="right">${bar(NUM_W[(i + 3) % NUM_W.length] - 14, 14)}</td>
      <td class="right">${bar(20, 14)}</td>
    </tr>`).join("");
  return busy("Loading leaderboard", `
    <div class="table-scroll">
      <table>
        <thead>
          <tr><th>#</th><th>Player</th><th>Title</th><th class="right">Score</th><th class="right">Wins</th><th class="right">Level</th></tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
    </div>`);
}

/** A list of rows with a small square on the left, like recent games. */
export function listSkeleton(rows = 4, label = "Loading") {
  const items = Array.from({ length: rows }, (_, i) => `
    <div class="sk-row">
      ${bar(24, 24, "sk-head")}
      <span class="sk-row-text">${bar(NAME_W[i % NAME_W.length] + 40, 13)}${bar(NUM_W[i % NUM_W.length] + 30, 10)}</span>
      ${bar(NUM_W[(i + 2) % NUM_W.length], 13)}
    </div>`).join("");
  return busy(label, `<div class="sk-list">${items}</div>`);
}

/** A plain block for anything without a dedicated shape. Same size the old placeholder had. */
export function blockSkeleton(height, label = "Loading") {
  return busy(label, `<div class="sk-block" style="height:${height}px"></div>`);
}
