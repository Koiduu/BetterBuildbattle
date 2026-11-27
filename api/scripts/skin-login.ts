// One-time sign-in for the custom-head skin account (not AspireBot). Run with: bb skin-login
//
// Needs SKIN_MS_CLIENT_ID (your Azure app) and SKIN_TOKEN_KEY in .env. Prints a code: open the link, sign in
// with the skin account's Microsoft account, and enter the code. The refresh token is stored encrypted in
// the database and replaced automatically from then on; it is never printed.
import { pool } from "../src/db/pool";
import { migrate } from "../src/db/migrate";
import {
  minecraftLogin, pollDeviceCode, saveRefreshToken, skinConfigured, startDeviceCode,
} from "../src/services/skinAccount";

if (!skinConfigured()) {
  console.error("Set SKIN_MS_CLIENT_ID and SKIN_TOKEN_KEY (16+ characters) in .env first. See docs/custom-heads-setup.md.");
  process.exit(1);
}

try {
  await migrate();
  const code = await startDeviceCode();
  console.log(`\nSign in with the SKIN account (not your own, not AspireBot):\n  1. Open ${code.verification_uri}\n  2. Enter the code ${code.user_code}\n`);
  console.log("Waiting for you to finish...");
  const ms = await pollDeviceCode(code.device_code, code.interval, code.expires_in);
  const mc = await minecraftLogin(ms.access_token);
  await saveRefreshToken(ms.refresh_token, { uuid: mc.uuid, name: mc.name });
  console.log(`\nSigned in as ${mc.name} (${mc.uuid}). The token is saved, encrypted, in the database.`);
  console.log("Approved custom heads will now be uploaded with this account, about one a minute.");
} catch (e) {
  console.error("\nSign-in failed:", e instanceof Error ? e.message : e);
  process.exitCode = 1;
} finally {
  await pool.end();
}
