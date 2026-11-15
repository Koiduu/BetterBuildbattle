// Renders promo/ad.html to video, frame by frame.
//   node promo/render.mjs stills <out-dir> t1 t2 ...   PNGs at the given times (animatic timeline)
//   node promo/render.mjs video <out.mp4> [animatic|graphics] [fps]
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
const shot = async (t, mode) => {
  await page.evaluate(([t, m]) => window.renderAt(t, m), [t, mode]);
  return (await page.locator("canvas").screenshot({ type: "png" }));
};
if (cmd === "stills") {
  fs.mkdirSync(out, { recursive: true });
  for (const t of rest) fs.writeFileSync(path.join(out, `t${t}.png`), await shot(Number(t), "animatic"));
} else {
  const mode = rest[0] ?? "animatic", fps = Number(rest[1] ?? 30);
  const total = await page.evaluate((m) => m === "graphics"
    ? window.TIMELINE.filter((s) => !s.clip).reduce((a, s) => a + s.dur, 0) : window.TOTAL, mode);
  const ff = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-i", "-",
    "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out], { stdio: ["pipe", "inherit", "inherit"] });
  const frames = Math.round(total * fps);
  for (let i = 0; i < frames; i++) {
    const buf = await shot(i / fps, mode);
    if (!ff.stdin.write(buf)) await new Promise((r) => ff.stdin.once("drain", r));
    if (i % 150 === 0) console.log(`frame ${i}/${frames}`);
  }
  ff.stdin.end();
  await new Promise((r) => ff.on("close", r));
  console.log(`wrote ${out} (${total.toFixed(1)}s)`);
}
await browser.close();
