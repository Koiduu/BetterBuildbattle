// Turning a painted head into the exact skin PNG that gets uploaded. The website builds a 64x64 PNG, but
// the server never trusts it as-is: it checks the size, keeps only the twelve 8x8 head faces (base + hat),
// makes the base layer opaque like Minecraft does, and encodes a fresh PNG. Identical drawings therefore
// give identical bytes, which is what upload dedupe hashes.
import crypto from "node:crypto";
import { createCanvas, loadImage } from "@napi-rs/canvas";

/** Top-left corner of each 8x8 face of the head's base layer in a 64x64 skin. The hat layer is 32px right. */
export const FACES = {
  top: [8, 0], bottom: [16, 0], right: [0, 8], front: [8, 8], left: [16, 8], back: [24, 8],
} as const;

export const MAX_PNG_BYTES = 64 * 1024;

export class BadHeadImage extends Error {}

/** Width and height from a PNG's IHDR chunk, or null if this isn't a PNG. */
export function pngSize(buf: Buffer): { width: number; height: number } | null {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (buf.length < 24 || !buf.subarray(0, 8).equals(sig) || buf.toString("latin1", 12, 16) !== "IHDR") return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

/** Accepts "data:image/png;base64,..." or bare base64. */
export function decodePngInput(input: string): Buffer {
  const b64 = input.replace(/^data:image\/png;base64,/, "");
  if (!/^[A-Za-z0-9+/=\s]+$/.test(b64)) throw new BadHeadImage("not_base64");
  const buf = Buffer.from(b64, "base64");
  if (buf.length > MAX_PNG_BYTES) throw new BadHeadImage("too_big");
  return buf;
}

/** Keeps only the head faces of RGBA pixels (64x64x4), in place. Base layer alpha becomes 255. */
export function keepHeadOnly(rgba: Uint8ClampedArray | Uint8Array) {
  const keep = new Uint8Array(64 * 64);   // 0 = clear, 1 = base face, 2 = hat face
  for (const [u, v] of Object.values(FACES)) {
    for (let y = v; y < v + 8; y++) for (let x = u; x < u + 8; x++) {
      keep[y * 64 + x] = 1;
      keep[y * 64 + x + 32] = 2;
    }
  }
  let painted = 0;
  for (let i = 0; i < 64 * 64; i++) {
    const o = i * 4;
    if (keep[i] === 0) { rgba[o] = rgba[o + 1] = rgba[o + 2] = rgba[o + 3] = 0; continue; }
    if (keep[i] === 1) { rgba[o + 3] = 255; painted++; continue; }
    if (rgba[o + 3] === 0) rgba[o] = rgba[o + 1] = rgba[o + 2] = 0;   // fully clear hat pixels: one canonical form
  }
  return painted;
}

/** The canonical skin PNG for a painted head, and its SHA-256. Throws BadHeadImage. */
export async function normalizeHeadPng(input: Buffer): Promise<{ png: Buffer; sha256: Buffer }> {
  const size = pngSize(input);
  if (!size) throw new BadHeadImage("not_png");
  if (size.width !== 64 || size.height !== 64) throw new BadHeadImage("must_be_64x64");
  let image;
  try { image = await loadImage(input); } catch { throw new BadHeadImage("unreadable_png"); }
  const canvas = createCanvas(64, 64);
  const ctx = canvas.getContext("2d");
  ctx.drawImage(image, 0, 0);
  const data = ctx.getImageData(0, 0, 64, 64);
  keepHeadOnly(data.data);
  ctx.clearRect(0, 0, 64, 64);
  ctx.putImageData(data, 0, 0);
  const png = await canvas.encode("png");
  return { png, sha256: crypto.createHash("sha256").update(png).digest() };
}
