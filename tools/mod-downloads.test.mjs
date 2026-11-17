import assert from "node:assert/strict";
import { test } from "node:test";
import { latestDownloads } from "../web/mod-downloads.js";

const asset = (loader, version = "0.2.1") => ({ version, size: 14 * 1024 * 1024,
  sha256: "a".repeat(64), download: `/download/${loader}` });
const response = (body) => async () => ({ ok: true, json: async () => body });

test("returns each available loader with its own version and size", async () => {
  const downloads = await latestDownloads(async (url, options) => {
    assert.equal(url, "/bb/v1/mod/latest");
    assert.ok(options.signal instanceof AbortSignal);
    return { ok: true, json: async () => ({ version: "0.2.1", fabric: asset("fabric"), forge: asset("forge") }) };
  });
  assert.deepEqual(downloads, [
    { loader: "fabric", version: "0.2.1", size: "14.0 MiB" },
    { loader: "forge", version: "0.2.1", size: "14.0 MiB" },
  ]);
});

test("does not substitute the release version for a different loader build", async () => {
  const downloads = await latestDownloads(response({ version: "0.2.1",
    fabric: asset("fabric", "0.2.0"), forge: asset("forge", "0.1.9") }));
  assert.deepEqual(downloads.map((d) => d.version), ["0.2.0", "0.1.9"]);
});

test("omits missing or unverifiable downloads instead of offering broken buttons", async () => {
  for (const bad of [null, {}, { ...asset("forge"), sha256: null }, { ...asset("forge"), sha256: "invalid" },
    { ...asset("forge"), download: "/missing" }, { ...asset("forge"), version: "" }]) {
    const downloads = await latestDownloads(response({ fabric: asset("fabric"), forge: bad }));
    assert.deepEqual(downloads.map((d) => d.loader), ["fabric"]);
  }
});

test("unknown sizes are omitted", async () => {
  for (const size of [undefined, null, 0, -1, "unknown"]) {
    const downloads = await latestDownloads(response({ fabric: { ...asset("fabric"), size } }));
    assert.equal(downloads[0].size, null);
  }
});

test("empty and malformed releases fall back to the install guide", async () => {
  for (const release of [null, {}, [], { fabric: null, forge: null }]) {
    assert.deepEqual(await latestDownloads(response(release)), []);
  }
});

test("HTTP, network, timeout and JSON failures fall back to the install guide", async () => {
  const failed = [async () => ({ ok: false }), async () => { throw new Error("offline"); },
    async () => { throw new DOMException("timeout", "TimeoutError"); },
    async () => ({ ok: true, json: async () => { throw new SyntaxError("bad JSON"); } })];
  for (const fetcher of failed) assert.deepEqual(await latestDownloads(fetcher), []);
});
