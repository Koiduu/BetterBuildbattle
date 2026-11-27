// Custom heads: paint a Minecraft head (6 faces of 8x8, plus the hat layer), send it in for review, and
// follow it until it shows up in the mod's Head Database under "My heads". The server re-checks every
// image and decides every limit (api/src/services/customHeads.ts); this page only paints and shows.
import { app, esc, get, go, request, me, meReady, timeAgo } from "./app.js";

export const NOTICE = "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.";

// Where each face of the head sits in a skin, relative to the layer's 32x16 strip (the hat strip is 32px right).
const FACES = { top: [8, 0], bottom: [16, 0], right: [0, 8], front: [8, 8], left: [16, 8], back: [24, 8] };
const W = 32, H = 16;
const isFace = (x, y) => Object.values(FACES).some(([u, v]) => x >= u && x < u + 8 && y >= v && y < v + 8);

const SWATCHES = [
  "#000000", "#3f3f3f", "#7f7f7f", "#c6c6c6", "#ffffff", "#6b3f1f", "#a0703c", "#e0b089",
  "#ffcfa6", "#aa0000", "#ff5555", "#ffaa00", "#ffff55", "#00aa00", "#55ff55", "#00aaaa",
  "#55ffff", "#0000aa", "#5555ff", "#aa00aa", "#ff55ff", "#ff66aa", "#2e5a8a", "#4a7f2e",
];

const STATUS = {
  pending: (h) => `Waiting for review${h.position ? ` · #${h.position} in line` : ""}`,
  approved: (h) => h.uploading ? "Approved · uploading now" : `Approved · #${h.position ?? "?"} in the upload queue (about ${h.position ?? 1} min)`,
  ready: () => "Ready · in the mod under My heads",
  rejected: (h) => `Not approved${h.rejectReason ? `: ${h.rejectReason}` : ""}`,
};

const ERRORS = {
  head_limit_reached: (b) => `You've used all ${b.limit} custom heads on your plan. Delete one, or see Plans for more.`,
  daily_head_limit: (b) => `That's ${b.perDay} new heads today, the most for one day. Try again tomorrow.`,
  name_not_allowed: () => "Pick a different name.",
  bad_name: () => "Give the head a name (up to 32 characters).",
  already_have_this_head: () => "You already have this exact head.",
  bad_image: () => "That image couldn't be used. Try painting it again.",
  rate_limited: () => "Slow down a little and try again.",
};

// ---- the drawing: two RGBA layers of 32x16

function blankLayer(fill) {
  const px = new Uint8ClampedArray(W * H * 4);
  if (fill) for (let i = 0; i < W * H; i++) px.set(fill, i * 4);
  return px;
}

function hexToRgba(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
}
const rgbaToHex = (p) => "#" + [p[0], p[1], p[2]].map((v) => v.toString(16).padStart(2, "0")).join("");

/** A 64x64 skin PNG with only the head faces drawn. */
function skinCanvas(base, hat) {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const ctx = c.getContext("2d");
  const put = (layer, dx, opaque) => {
    const img = ctx.createImageData(W, H);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (!isFace(x, y)) continue;
      const i = (y * W + x) * 4;
      img.data.set(layer.subarray(i, i + 4), i);
      if (opaque) img.data[i + 3] = 255;
    }
    ctx.putImageData(img, dx, 0);
  };
  put(base, 0, true);
  put(hat, 32, false);
  return c;
}

/** Splits an image (a skin, or a finished head) back into the two 32x16 layers. */
function layersFromImage(img) {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const ctx = c.getContext("2d");
  ctx.drawImage(img, 0, 0, 64, 64);
  const base = new Uint8ClampedArray(ctx.getImageData(0, 0, W, H).data);
  const hat = new Uint8ClampedArray(ctx.getImageData(32, 0, W, H).data);
  for (let i = 3; i < base.length; i += 4) base[i] = 255;
  return { base, hat };
}

// ---- a CSS 3D head, used for the live preview and for the thumbnails

function faceCanvas(layer, face, size = 8) {
  const [u, v] = FACES[face];
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const ctx = c.getContext("2d");
  const img = ctx.createImageData(8, 8);
  for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) {
    const s = ((v + y) * W + (u + x)) * 4;
    img.data.set(layer.subarray(s, s + 4), (y * 8 + x) * 4);
  }
  ctx.putImageData(img, 0, 0);
  return c;
}

/** A head made of two cubes (base and the slightly bigger hat). Returns {el, update(base, hat)}. */
function headModel(px, { spin = false } = {}) {
  const el = document.createElement("div");
  el.className = "head3d";
  el.style.setProperty("--s", px + "px");
  el.innerHTML = `<div class="head3d-rot">${["base", "hat"].map((k) => `
    <div class="cube ${k}">${Object.keys(FACES).map((f) => `<div class="face ${f}"></div>`).join("")}</div>`).join("")}</div>`;
  const rot = el.firstElementChild;
  let ry = -35, rx = -22;
  const apply = () => { rot.style.transform = `rotateX(${rx}deg) rotateY(${ry}deg)`; };
  apply();
  if (spin) {
    let drag = null;
    el.addEventListener("pointerdown", (e) => { drag = { x: e.clientX, y: e.clientY, ry, rx }; el.setPointerCapture(e.pointerId); });
    el.addEventListener("pointermove", (e) => {
      if (!drag) return;
      ry = drag.ry + (e.clientX - drag.x) * 0.6;
      rx = Math.max(-80, Math.min(80, drag.rx - (e.clientY - drag.y) * 0.6));
      apply();
    });
    el.addEventListener("pointerup", () => { drag = null; });
    el.tabIndex = 0;
    el.setAttribute("role", "img");
    el.addEventListener("keydown", (e) => {
      const step = { ArrowLeft: [0, -15], ArrowRight: [0, 15], ArrowUp: [10, 0], ArrowDown: [-10, 0] }[e.key];
      if (!step) return;
      e.preventDefault();
      rx = Math.max(-80, Math.min(80, rx + step[0]));
      ry += step[1];
      apply();
    });
  }
  return {
    el,
    update(base, hat) {
      for (const [k, layer] of [["base", base], ["hat", hat]]) {
        for (const f of Object.keys(FACES)) {
          const slot = el.querySelector(`.cube.${k} .face.${f}`);
          slot.replaceChildren(faceCanvas(layer, f));
        }
      }
    },
  };
}

function thumbnail(dataUrl, px = 44) {
  const model = headModel(px);
  const img = new Image();
  img.onload = () => { const { base, hat } = layersFromImage(img); model.update(base, hat); };
  img.src = dataUrl;
  return model.el;
}

// ---- the page

// One page at a time: a second visit replaces the first visit's timer and key handler.
let poll = null;
let keyHandler = null;

export async function headsPage() {
  document.title = "Custom heads · Better Battle";
  await meReady;
  if (!me) return go("/login");

  app.innerHTML = `
    <section class="intro">
      <h1>Custom heads</h1>
      <p>Paint your own head, pixel by pixel. Each one is checked by hand before it's made into a real Minecraft
        head, which usually takes a day or so. Then it shows up in the mod's Head Database under <b>My heads</b>.</p>
      <p class="notice-line">${esc(NOTICE)}</p>
    </section>
    <div class="painter-wrap">
      <section class="card painter" id="painter">
        <div class="card-head">
          <div><h2>Paint a head</h2><p class="sub" id="allowance">&nbsp;</p></div>
          <div class="seg" role="radiogroup" aria-label="Layer">
            <button type="button" role="radio" data-layer="base" aria-checked="true">Head</button>
            <button type="button" role="radio" data-layer="hat" aria-checked="false">Hat layer</button>
          </div>
        </div>
        <div class="painter-body">
          <div class="net-col">
            <div class="net" id="net">
              <canvas id="net-canvas" width="${W}" height="${H}" aria-label="Unfolded head: click and drag to paint"></canvas>
              ${Object.entries(FACES).map(([f, [u, v]]) =>
                `<span class="net-label" style="left:${(u / W) * 100}%;top:${(v / H) * 100}%">${f}</span>`).join("")}
            </div>
            <div class="tools" role="toolbar" aria-label="Tools">
              ${[["pencil", "Pencil", "B"], ["eraser", "Eraser", "E"], ["fill", "Fill face", "F"], ["picker", "Pick colour", "I"]]
                .map(([id, label, key]) => `<button type="button" class="btn small tool" data-tool="${id}" title="${label} (${key})" aria-pressed="${id === "pencil"}">${label}</button>`).join("")}
              <span class="tools-gap"></span>
              <button type="button" class="btn small" id="undo" title="Undo (Ctrl+Z)">Undo</button>
              <button type="button" class="btn small" id="clear-layer">Clear layer</button>
            </div>
            <div class="colours">
              <label class="colour-now"><span class="sr-only">Colour</span><input type="color" id="colour" value="#ff66aa"></label>
              <div class="swatches">${SWATCHES.map((c) => `<button type="button" class="swatch" style="background:${c}" data-colour="${c}" aria-label="${c}"></button>`).join("")}</div>
            </div>
            <p class="muted small-note" id="layer-note">The head layer is always solid. Use the hat layer for see-through parts, like glasses or hair that sticks out.</p>
          </div>
          <div class="preview-col">
            <div class="preview" id="preview"></div>
            <p class="muted small-note">Drag to turn it.</p>
            <label class="ghost-toggle"><input type="checkbox" id="show-hat" checked> Show hat layer</label>
            <label class="file-btn btn small">Start from a skin file<input type="file" id="skin-file" accept="image/png" hidden></label>
          </div>
        </div>
        <form class="inline-form" id="send-form">
          <label class="sr-only" for="head-name">Name</label>
          <input id="head-name" placeholder="Name it, e.g. Pumpkin knight" maxlength="32" required autocomplete="off">
          <button class="btn primary" type="submit" id="send">Send for review</button>
        </form>
        <p class="form-error" id="send-error" hidden></p>
      </section>
    </div>
    <section class="card" id="mine">
      <div class="card-head"><div><h2>Your heads</h2><p class="sub">Link the mod with <code>/bb link</code> to use them in game.</p></div></div>
      <div class="loading" style="height:120px"></div>
    </section>`;

  // State
  let layer = "base", tool = "pencil", colour = hexToRgba("#ff66aa");
  let base = blankLayer(hexToRgba("#c6c6c6"));
  let hat = blankLayer();
  const history = [];
  const snapshot = () => { history.push([base.slice(), hat.slice()]); if (history.length > 60) history.shift(); };
  const cur = () => (layer === "base" ? base : hat);

  const canvas = document.getElementById("net-canvas");
  const ctx = canvas.getContext("2d");
  const model = headModel(64, { spin: true });
  model.el.setAttribute("aria-label", "3D preview of the head");
  document.getElementById("preview").append(model.el);
  const showHat = document.getElementById("show-hat");

  function draw() {
    const img = ctx.createImageData(W, H);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 4;
      if (!isFace(x, y)) continue;
      const b = base.subarray(i, i + 4), h = hat.subarray(i, i + 4);
      // On the hat layer the head shows through, dimmed, so you can see what you're painting over.
      const dim = layer === "hat" ? 0.45 : 1;
      const a = layer === "hat" || showHat.checked ? h[3] / 255 : 0;
      for (let k = 0; k < 3; k++) img.data[i + k] = Math.round(h[k] * a + b[k] * dim * (1 - a));
      img.data[i + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
    model.update(base, showHat.checked ? hat : blankLayer());
  }

  function cellAt(e) {
    const r = canvas.getBoundingClientRect();
    const x = Math.floor(((e.clientX - r.left) / r.width) * W);
    const y = Math.floor(((e.clientY - r.top) / r.height) * H);
    return x >= 0 && x < W && y >= 0 && y < H && isFace(x, y) ? [x, y] : null;
  }

  function faceOf(x, y) {
    return Object.values(FACES).find(([u, v]) => x >= u && x < u + 8 && y >= v && y < v + 8);
  }

  function setColour(rgba) {
    colour = rgba;
    document.getElementById("colour").value = rgbaToHex(rgba);
  }

  function paint(x, y) {
    const px = cur();
    const i = (y * W + x) * 4;
    if (tool === "picker") {
      const src = layer === "hat" && hat[i + 3] > 0 ? hat : base;
      setColour([src[i], src[i + 1], src[i + 2], 255]);
      return;
    }
    if (tool === "fill") {
      // Flood fill within this face only, like a bucket in an image editor.
      const [u, v] = faceOf(x, y);
      const target = Array.from(px.subarray(i, i + 4));
      const next = colour;
      if (target.every((t, k) => t === next[k])) return;
      const stack = [[x, y]];
      while (stack.length) {
        const [cx, cy] = stack.pop();
        if (cx < u || cx >= u + 8 || cy < v || cy >= v + 8) continue;
        const j = (cy * W + cx) * 4;
        if (!target.every((t, k) => px[j + k] === t)) continue;
        px.set(next, j);
        stack.push([cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1]);
      }
      return;
    }
    if (tool === "eraser") {
      if (layer === "base") return;   // the head layer is solid
      px.set([0, 0, 0, 0], i);
      return;
    }
    px.set(colour, i);
  }

  let painting = false, last = null;
  // Fills in every cell between two pointer positions, so a fast stroke doesn't leave gaps.
  function strokeTo(x, y) {
    let [x0, y0] = last ?? [x, y];
    const dx = Math.abs(x - x0), dy = -Math.abs(y - y0), sx = x0 < x ? 1 : -1, sy = y0 < y ? 1 : -1;
    let err = dx + dy;
    for (;;) {
      if (isFace(x0, y0)) paint(x0, y0);
      if (x0 === x && y0 === y) break;
      const e2 = 2 * err;
      if (e2 >= dy) { err += dy; x0 += sx; }
      if (e2 <= dx) { err += dx; y0 += sy; }
    }
    last = [x, y];
  }
  canvas.addEventListener("pointerdown", (e) => {
    const cell = cellAt(e);
    if (!cell) return;
    e.preventDefault();
    canvas.setPointerCapture(e.pointerId);
    if (tool !== "picker") snapshot();
    painting = tool === "pencil" || tool === "eraser";
    last = cell;
    if (e.button === 2 && layer === "hat") { const t = tool; tool = "eraser"; paint(...cell); tool = t; painting = false; }
    else paint(...cell);
    draw();
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!painting) return;
    const cell = cellAt(e);
    if (cell) { strokeTo(...cell); draw(); }
  });
  const stop = () => { painting = false; };
  canvas.addEventListener("pointerup", stop);
  canvas.addEventListener("pointercancel", stop);
  canvas.addEventListener("contextmenu", (e) => e.preventDefault());

  const setLayer = (l) => {
    layer = l;
    document.querySelectorAll("[data-layer]").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.layer === l)));
    document.querySelector('[data-tool="eraser"]').disabled = l === "base";
    if (l === "base" && tool === "eraser") setTool("pencil");
    draw();
  };
  const setTool = (t) => {
    tool = t;
    document.querySelectorAll("[data-tool]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.tool === t)));
  };
  document.querySelectorAll("[data-layer]").forEach((b) => b.addEventListener("click", () => setLayer(b.dataset.layer)));
  document.querySelectorAll("[data-tool]").forEach((b) => b.addEventListener("click", () => setTool(b.dataset.tool)));
  document.getElementById("colour").addEventListener("input", (e) => { colour = hexToRgba(e.target.value); });
  document.querySelectorAll("[data-colour]").forEach((b) => b.addEventListener("click", () => setColour(hexToRgba(b.dataset.colour))));
  showHat.addEventListener("change", draw);
  document.getElementById("undo").addEventListener("click", () => {
    const last = history.pop();
    if (last) { [base, hat] = last; draw(); }
  });
  document.getElementById("clear-layer").addEventListener("click", () => {
    snapshot();
    if (layer === "base") base = blankLayer(hexToRgba("#c6c6c6")); else hat = blankLayer();
    draw();
  });
  document.getElementById("skin-file").addEventListener("change", (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(img.src);
      if (img.width !== 64 || (img.height !== 64 && img.height !== 32)) {
        showError("That file isn't a Minecraft skin (64×64 or 64×32 PNG).");
        return;
      }
      snapshot();
      ({ base, hat } = layersFromImage(img));
      draw();
    };
    img.src = URL.createObjectURL(file);
    e.target.value = "";
  });
  if (keyHandler) document.removeEventListener("keydown", keyHandler);
  const keys = keyHandler = (e) => {
    if (!document.getElementById("painter")) { keyHandler = null; return document.removeEventListener("keydown", keys); }
    if (e.target.closest?.("input, textarea")) return;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") { e.preventDefault(); document.getElementById("undo").click(); return; }
    const t = { b: "pencil", e: "eraser", f: "fill", i: "picker" }[e.key.toLowerCase()];
    if (t && !(t === "eraser" && layer === "base")) setTool(t);
  };
  document.addEventListener("keydown", keys);

  function showError(text) {
    const el = document.getElementById("send-error");
    el.textContent = text;
    el.hidden = !text;
  }

  document.getElementById("send-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = document.getElementById("send");
    const name = document.getElementById("head-name").value.trim();
    showError("");
    button.disabled = true;
    try {
      await request("POST", "/me/heads", { name, png: skinCanvas(base, hat).toDataURL("image/png") });
      document.getElementById("head-name").value = "";
      await loadMine();
      document.getElementById("mine").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (err) {
      const say = ERRORS[err.body?.error];
      showError(say ? say(err.body) : "Couldn't send it. Try again in a moment.");
    } finally {
      button.disabled = false;
    }
  });

  clearInterval(poll);
  async function loadMine() {
    const card = document.getElementById("mine");
    if (!card) return clearInterval(poll);
    const data = await get("/me/heads").catch(() => null);
    if (!data) return;
    document.getElementById("allowance").textContent =
      `${data.used} of ${data.limit} heads used · ${data.today} of ${data.perDay} sent today`;
    const list = card.querySelector(".head-grid, .loading, .empty");
    const grid = document.createElement("div");
    if (!data.heads.length) {
      grid.className = "empty";
      grid.textContent = "No heads yet. Paint one above.";
    } else {
      grid.className = "head-grid";
      for (const h of data.heads) {
        const tile = document.createElement("div");
        tile.className = `head-tile ${h.status}`;
        tile.innerHTML = `
          <div class="head-thumb"></div>
          <div class="head-info">
            <b>${esc(h.name)}</b>
            <span class="head-status">${esc(STATUS[h.status](h))}</span>
            <span class="muted">${esc(timeAgo(h.createdAt))}</span>
          </div>
          <div class="head-actions">
            <button type="button" class="btn small" data-edit="${h.id}" title="Copy into the painter">Edit copy</button>
            <button type="button" class="btn small danger" data-delete="${h.id}">Delete</button>
          </div>`;
        tile.querySelector(".head-thumb").append(thumbnail(h.png));
        tile.querySelector("[data-edit]").addEventListener("click", () => {
          const img = new Image();
          img.onload = () => {
            snapshot();
            ({ base, hat } = layersFromImage(img));
            document.getElementById("head-name").value = h.name.slice(0, 32);
            draw();
            document.getElementById("painter").scrollIntoView({ behavior: "smooth" });
          };
          img.src = h.png;
        });
        tile.querySelector("[data-delete]").addEventListener("click", async (ev) => {
          const what = h.status === "ready" ? "It disappears from the mod too, and the slot is freed." : "The slot is freed.";
          if (!confirm(`Delete "${h.name}"? ${what}`)) return;
          ev.target.disabled = true;
          await request("DELETE", `/me/heads/${h.id}`).catch(() => {});
          loadMine();
        });
        grid.append(tile);
      }
    }
    list.replaceWith(grid);
    const waiting = data.heads.some((h) => h.status === "pending" || h.status === "approved");
    clearInterval(poll);
    if (waiting) poll = setInterval(loadMine, 20_000);
  }

  setLayer("base");
  await loadMine();
}
