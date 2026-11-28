import assert from "node:assert/strict";
import test from "node:test";
import { decryptToken, encryptToken, minecraftLogin, textureHashOf } from "./skinAccount";
import { backoffMs } from "../jobs/skinUploader";

test("refresh tokens round-trip through encryption and can't be read with another key", () => {
  process.env.SKIN_TOKEN_KEY = "test-key-that-is-long-enough";
  const blob = encryptToken("M.C123_refresh-token");
  assert.ok(!blob.toString("latin1").includes("refresh-token"));
  assert.equal(decryptToken(blob), "M.C123_refresh-token");
  process.env.SKIN_TOKEN_KEY = "a-different-key-entirely";
  assert.throws(() => decryptToken(blob));
});

test("reads the texture hash from a texture URL", () => {
  const hash = "a".repeat(64);
  assert.equal(textureHashOf(`http://textures.minecraft.net/texture/${hash}`), hash);
  assert.equal(textureHashOf("https://example.com/texture/abc"), null);
  assert.equal(textureHashOf(null), null);
});

test("backs off 1, 2, 4 minutes and never more than an hour", () => {
  assert.equal(backoffMs(1), 60_000);
  assert.equal(backoffMs(2), 120_000);
  assert.equal(backoffMs(3), 240_000);
  assert.equal(backoffMs(50), 3_600_000);
});

test("goes Microsoft -> Xbox Live -> XSTS -> Minecraft", async () => {
  const seen: string[] = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    seen.push(String(url));
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    const reply = (o: unknown) => new Response(JSON.stringify(o), { status: 200 });
    if (String(url).endsWith("/user/authenticate")) {
      assert.equal(body.Properties.RpsTicket, "d=ms-token");
      return reply({ Token: "xbl", DisplayClaims: { xui: [{ uhs: "1234" }] } });
    }
    if (String(url).endsWith("/xsts/authorize")) {
      assert.deepEqual(body.Properties.UserTokens, ["xbl"]);
      return reply({ Token: "xsts", DisplayClaims: { xui: [{ uhs: "1234" }] } });
    }
    if (String(url).endsWith("/login_with_xbox")) {
      assert.equal(body.identityToken, "XBL3.0 x=1234;xsts");
      return reply({ access_token: "mc", expires_in: 86400 });
    }
    if (String(url).endsWith("/minecraft/profile")) return reply({ id: "0123456789abcdef0123456789abcdef", name: "SkinBot" });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
  try {
    const s = await minecraftLogin("ms-token");
    assert.equal(s.token, "mc");
    assert.equal(s.name, "SkinBot");
    assert.equal(seen.length, 4);
  } finally {
    globalThis.fetch = realFetch;
  }
});
