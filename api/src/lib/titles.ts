// Hypixel Build Battle titles. Mirrors mod/core/.../progress/Titles.java.
// hex = the Minecraft colour as used on the website.
export interface Title { name: string; min: number; mc: string; hex: string; bold: boolean }

export const TITLES: Title[] = [
  { name: "Rookie",            min: 0,       mc: "f", hex: "#FFFFFF", bold: false },
  { name: "Untrained",         min: 100,     mc: "7", hex: "#AAAAAA", bold: false },
  { name: "Amateur",           min: 250,     mc: "8", hex: "#555555", bold: false },
  { name: "Prospect",          min: 500,     mc: "a", hex: "#55FF55", bold: false },
  { name: "Apprentice",        min: 1_000,   mc: "2", hex: "#00AA00", bold: false },
  { name: "Experienced",       min: 2_000,   mc: "b", hex: "#55FFFF", bold: false },
  { name: "Seasoned",          min: 3_500,   mc: "3", hex: "#00AAAA", bold: false },
  { name: "Trained",           min: 5_000,   mc: "9", hex: "#5555FF", bold: false },
  { name: "Skilled",           min: 7_500,   mc: "1", hex: "#0000AA", bold: false },
  { name: "Talented",          min: 10_000,  mc: "5", hex: "#AA00AA", bold: false },
  { name: "Professional",      min: 15_000,  mc: "d", hex: "#FF55FF", bold: false },
  { name: "Artisan",           min: 20_000,  mc: "c", hex: "#FF5555", bold: false },
  { name: "Expert",            min: 40_000,  mc: "4", hex: "#AA0000", bold: false },
  { name: "Master",            min: 75_000,  mc: "6", hex: "#FFAA00", bold: false },
  { name: "Legend",            min: 100_000, mc: "a", hex: "#55FF55", bold: true },
  { name: "Grandmaster",       min: 200_000, mc: "b", hex: "#55FFFF", bold: true },
  { name: "Celestial",         min: 300_000, mc: "d", hex: "#FF55FF", bold: true },
  { name: "Divine",            min: 400_000, mc: "c", hex: "#FF5555", bold: true },
  { name: "Ascended",          min: 500_000, mc: "6", hex: "#FFAA00", bold: true },
];

export function titleFor(score: number): Title {
  let t = TITLES[0];
  for (const x of TITLES) if (score >= x.min) t = x;
  return t;
}

export function titleProgress(score: number) {
  const cur = titleFor(score);
  const next = TITLES.find((x) => x.min > score) ?? null;
  return {
    current: cur,
    next,
    into: score - cur.min,                       // e.g. 91,650 for 291,650
    span: next ? next.min - cur.min : 0,         // e.g. 100,000
    pct: next ? (score - cur.min) / (next.min - cur.min) : 1,
  };
}
