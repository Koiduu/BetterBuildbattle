import fs from "node:fs/promises";
import { renderStatsCard, type StatsCardData } from "../src/bot/card";

const title = {
  current: { name: "Grandmaster", hex: "#55ffff", bold: true },
  next: { name: "Legend", min: 100000 },
  into: 12000,
  span: 30000,
  pct: 0.4,
};

const fixtures: [string, StatsCardData][] = [
  ["mod-user", {
    name: "Koidu", uuid: "f84c6a790a4e45a09f6f6f5c3f4a4b71", score: 184250, title,
    rank: 42, rankedPlayers: 93000,
    hypixel: { wins: 2580, gamesPlayed: 9120, correctGuesses: 1240, modeWins: { solo: 1410, teams: 910, pro: 46, gtb: 173, speedBuilders: 41 } },
    betterBattle: { level: 38, xpIntoLevel: 720, xpForNext: 4300, crowdScore: 0.78, grades: { SS: 26, S: 83, A: 194 }, themesBuilt: 540 },
  }],
  ["hypixel-only", {
    name: "Builder42", uuid: "4566e69fc90748ee8d71d7ba5aa00d20", score: 63250, title,
    rank: 3200, rankedPlayers: 93000,
    hypixel: { wins: 420, gamesPlayed: 2210, correctGuesses: 56, modeWins: { solo: 311, teams: 99, pro: 4, gtb: 6, speedBuilders: 0 } },
    betterBattle: null,
  }],
  ["long-name-nulls", {
    name: "SixteenCharacter", uuid: "f84c6a790a4e45a09f6f6f5c3f4a4b71", score: null, title,
    rank: null, rankedPlayers: null,
    hypixel: { wins: null, gamesPlayed: null, correctGuesses: null, modeWins: { solo: null, teams: null, pro: null, gtb: null, speedBuilders: null } },
    betterBattle: null,
  }],
  ["unranked-player", {
    name: "NewBuilder", uuid: "4566e69fc90748ee8d71d7ba5aa00d20", score: 85, title,
    rank: null, rankedPlayers: 93000,
    hypixel: { wins: 1, gamesPlayed: 8, correctGuesses: 0, modeWins: { solo: 1, teams: 0, pro: 0, gtb: 0, speedBuilders: 0 } },
    betterBattle: null,
  }],
];

const output = "/home/ubuntu/bot-shots";
await fs.mkdir(output, { recursive: true });
for (const [name, data] of fixtures) {
  await fs.writeFile(`${output}/${name}.png`, await renderStatsCard(data));
  console.log(`${output}/${name}.png`);
}
