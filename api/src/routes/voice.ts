// Mod routes for temporary Discord voice channels. The rules live in services/voice.ts.
import { z } from "zod";
import { Router } from "../http";
import { rateLimit, requireModToken } from "../plugins/auth";
import { leaveVoice, MAX_TEAM, requestVoice, voiceStatus } from "../services/voice";

const VoiceIn = z.discriminatedUnion("scope", [
  // Your teammate or party members, by Minecraft name. Each of them has to press Voice too, naming the same group.
  z.object({ scope: z.literal("team"), members: z.array(z.string().max(16)).min(1).max(MAX_TEAM) }),
  // Everyone the LFG lobby host accepted.
  z.object({ scope: z.literal("lobby"), lobbyId: z.string().min(1).max(64) }),
]);

export function voiceRoutes(app: Router) {
  app.post("/mod/voice", [requireModToken, rateLimit(10, 60_000, "/mod/voice")], async (req) =>
    requestVoice(req.user.id, VoiceIn.parse(req.body)));

  // The mod asks every few seconds while it waits for the others to press Voice
  app.get("/mod/voice", [requireModToken, rateLimit(30, 60_000, "/mod/voice/status")], async (req) =>
    voiceStatus(req.user.id));

  app.delete("/mod/voice", [requireModToken, rateLimit(20, 60_000, "/mod/voice/leave")], async (req) =>
    leaveVoice(req.user.id));
}
