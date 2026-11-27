import assert from "node:assert/strict";
import test from "node:test";
import { createCanvas } from "@napi-rs/canvas";
import { FACES, decodePngInput, keepHeadOnly, normalizeHeadPng, pngSize } from "./headSkin";

async function png(w: number, h: number, paint: (ctx: any) => void) {
  const c = createCanvas(w, h);
  paint(c.getContext("2d"));
  return await c.encode("png");
}

test("keeps only the twelve head faces and makes the base layer opaque", () => {
  const rgba = new Uint8ClampedArray(64 * 64 * 4).fill(200);
  for (let i = 3; i < rgba.length; i += 4) rgba[i] = 10;
  const painted = keepHeadOnly(rgba);
  assert.equal(painted, 6 * 64);
  const at = (x: number, y: number) => Array.from(rgba.slice((y * 64 + x) * 4, (y * 64 + x) * 4 + 4));
  assert.deepEqual(at(FACES.front[0], FACES.front[1]), [200, 200, 200, 255]);       // base face: opaque
  assert.deepEqual(at(FACES.front[0] + 32, FACES.front[1]), [200, 200, 200, 10]);   // hat face: alpha kept
  assert.deepEqual(at(0, 0), [0, 0, 0, 0]);                                         // unused corner
  assert.deepEqual(at(10, 30), [0, 0, 0, 0]);                                       // body
});

test("normalises a painted head to the same bytes every time", async () => {
  const input = await png(64, 64, (ctx) => {
    ctx.fillStyle = "#ff0000"; ctx.fillRect(0, 0, 64, 64);   // paint everything, body included
  });
  const a = await normalizeHeadPng(input);
  const b = await normalizeHeadPng(Buffer.from(a.png));
  assert.deepEqual(pngSize(a.png), { width: 64, height: 64 });
  assert.equal(a.sha256.toString("hex"), b.sha256.toString("hex"));
});

test("turns down images that aren't a 64x64 PNG", async () => {
  await assert.rejects(normalizeHeadPng(await png(32, 32, () => {})), /must_be_64x64/);
  await assert.rejects(normalizeHeadPng(Buffer.from("hello world, not a png at all")), /not_png/);
  assert.throws(() => decodePngInput("data:image/png;base64,<script>"), /not_base64/);
  assert.throws(() => decodePngInput("A".repeat(200_000)), /too_big/);
});
