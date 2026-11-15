// Renders promo/ad.html frame by frame.
//   node promo/render.mjs stills <out-dir> t1 t2 ...        preview PNGs at the given times
//   node promo/render.mjs preview <out.mp4> [fps]           opaque H.264, with stand-ins for footage
//   node promo/render.mjs master <out.mov> [fps] [from to]  ProRes 4444 with alpha, for the editor
import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const [, , cmd, out, ...rest] = process.argv;
const here = path.dirname(fileURLToPath(import.meta.url));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await page.goto("file://" + path.join(here, "ad.html") + "?record");
await page.evaluate(() => window.ready);
const shot = async (t, alpha) => {
  await page.evaluate(([t, alpha]) => window.renderAt(t, { alpha }), [t, alpha]);
  return page.locator("canvas").screenshot({ type: "png", omitBackground: true });
};
if (cmd === "stills") {
  fs.mkdirSync(out, { recursive: true });
  for (const t of rest) fs.writeFileSync(path.join(out, `t${t}.png`), await shot(Number(t), false));
} else {
  const alpha = cmd === "master", fps = Number(rest[0] ?? 60);
  const total = await page.evaluate(() => window.TOTAL);
  const from = Number(rest[1] ?? 0), to = Number(rest[2] ?? total);
  const codec = alpha
    ? ["-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le", "-q:v", "9", "-vendor", "apl0"]
    : ["-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart"];
  const ff = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-i", "-", ...codec, out],
    { stdio: ["pipe", "inherit", "inherit"] });
  const frames = Math.round((to - from) * fps);
  for (let i = 0; i < frames; i++) {
    const buf = await shot(from + i / fps, alpha);
    if (!ff.stdin.write(buf)) await new Promise((r) => ff.stdin.once("drain", r));
    if (i % 300 === 0) console.log(`frame ${i}/${frames}`);
  }
  ff.stdin.end();
  await new Promise((r) => ff.on("close", r));
  console.log(`wrote ${out} (${(to - from).toFixed(1)}s at ${fps}fps)`);
}
await browser.close();
