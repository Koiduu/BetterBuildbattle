// Game rosters AspireBot (Koiduu/DiscordAspire) reads in the few seconds it sits in a lobby after a guild
// member parties it and queues. They feed the same discovery queue as the mod's lobby sightings, so the
// same 7-day rule applies: a player already checked in the last week isn't looked up again.
import crypto from "node:crypto";
import { z } from "zod";
import { Router, fail, type Req } from "../http";
import { queuePlayers } from "../jobs/crawler";
import { rateLimit } from "../plugins/auth";

function requireSightingsToken(req: Req) {
  const expected = process.env.SIGHTINGS_TOKEN ?? "";
  const given = String(req.headers["x-sightings-token"] ?? "");
  const ok = expected.length >= 16 && given.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
  if (!ok) fail(404, "not_found");
}

const SightingsIn = z.object({
  players: z.array(z.object({
    uuid: z.string().uuid(),
    name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/).optional(),
  })).max(200),
  game: z.string().max(64).optional(),
});

export function sightingRoutes(app: Router) {
  app.post("/sightings", [requireSightingsToken, rateLimit(60, 60_000, "/sightings")], async (req) => {
    const body = SightingsIn.parse(req.body);
    const uuids = [...new Set(body.players.map((p) => p.uuid.toLowerCase()))];
    const added = await queuePlayers(uuids, "aspirebot");
    console.log(`AspireBot sighting${body.game ? ` (${body.game})` : ""}: ${uuids.length} players, ${added} new`);
    return { received: uuids.length, added };
  });
}
