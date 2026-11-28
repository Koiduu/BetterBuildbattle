// Things that happen on the server that other parts (the Discord bot) may want to announce.
import { EventEmitter } from "node:events";

export interface RecordBroken {
  build: string; buildName: string; holder: string; timeMs: number;
  previousMs: number; previousHolder: string | null;
}

export const events = new EventEmitter();
export const recordBroken = (r: RecordBroken) => { events.emit("recordBroken", r); };

// ---- LFG lobbies. Listen with events.on("lobbyOpened" | "lobbyUpdated" | "lobbyClosed", (l: LobbyEvent) => ...).
// lobbyUpdated fires when someone is accepted, kicked or leaves, and on a rename or hidden name.
// Never carries the password or its hash.
// A voice-channel feature can keep its channel id in lfg_lobbies.voice_channel_id (see setLobbyVoiceChannel).

export interface LobbyMember {
  userId: number;
  mcUuid: string | null;
  name: string;
  /** The Discord account on the user's website account, if any. */
  discordId: string | null;
  host: boolean;
}

export interface LobbyEvent {
  id: string;
  /** The name players see: "Lobby" while it is hidden after reports. */
  name: string;
  /** teams | solo | gtb | spb | pro | other */
  tag: string;
  maxSize: number;
  region: string;
  note: string;
  locked: boolean;
  status: "open" | "closed";
  /** Why it closed: "host" | "offline" | "timeout" | "replaced" | "admin". Null while open. */
  reason: string | null;
  expiresAt: string;
  discordMessageId: string | null;
  voiceChannelId: string | null;
  /** The host first, then everyone the host accepted. */
  members: LobbyMember[];
}

export type LobbyEventKind = "lobbyOpened" | "lobbyUpdated" | "lobbyClosed";
export const lobbyEvent = (kind: LobbyEventKind, lobby: LobbyEvent) => { events.emit(kind, lobby); };
