import { fail } from "../http";
import { dashUuid, HYPIXEL_API, MOJANG_API } from "./upstream";

type HypixelPlayer = {
  socialMedia?: { links?: { DISCORD?: unknown } };
};

export function normalizeDiscordName(value: string) {
  return value.trim().toLowerCase().replace(/^@/, "").replace(/#0$/, "");
}

export async function verifyHypixelDiscord(name: string, discordName: string) {
  let uuid = "";
  let mcName = name;
  try {
    const profile = await fetch(`${MOJANG_API}/users/profiles/minecraft/${encodeURIComponent(name)}`);
    if (!profile.ok) return fail(404, "not_found");
    const data = await profile.json() as { id?: string; name?: string };
    if (!data.id) return fail(404, "not_found");
    uuid = dashUuid(data.id);
    mcName = data.name ?? name;
  } catch (error) {
    if ((error as { status?: number })?.status === 404) throw error;
    return fail(404, "not_found");
  }

  let player: HypixelPlayer;
  try {
    const response = await fetch(`${HYPIXEL_API}/v2/player?uuid=${encodeURIComponent(uuid)}`, {
      headers: { "API-Key": process.env.HYPIXEL_API_KEY ?? "" },
    });
    if (!response.ok) return fail(503, "hypixel_unavailable");
    const data = await response.json() as { player?: HypixelPlayer | null };
    if (!data.player) return fail(503, "hypixel_unavailable");
    player = data.player;
  } catch (error) {
    if ((error as { status?: number })?.status === 503) throw error;
    return fail(503, "hypixel_unavailable");
  }

  const raw = player.socialMedia?.links?.DISCORD;
  const hypixelDiscord = typeof raw === "string" ? raw : null;
  if (!hypixelDiscord || normalizeDiscordName(hypixelDiscord) !== normalizeDiscordName(discordName)) {
    fail(400, "discord_mismatch", { hypixelDiscord });
  }
  return { uuid, mcName, hypixelDiscord };
}
