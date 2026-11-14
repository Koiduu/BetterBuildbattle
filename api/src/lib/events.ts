// Things that happen on the server that other parts (the Discord bot) may want to announce.
import { EventEmitter } from "node:events";

export interface RecordBroken {
  build: string; buildName: string; holder: string; timeMs: number;
  previousMs: number; previousHolder: string | null;
}

export const events = new EventEmitter();
export const recordBroken = (r: RecordBroken) => { events.emit("recordBroken", r); };
