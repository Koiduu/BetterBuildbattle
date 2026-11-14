// Aspiring Level. Must match mod/core/.../progress/Level.java exactly.
// XP from level n to n+1 = 500 + 100n.  Total XP to reach level n = 500(n-1) + 50n(n-1).

export const totalXpForLevel = (n: number) => 500 * (n - 1) + 50 * n * (n - 1);

export function levelForXp(xp: number): number {
  let n = Math.max(1, Math.floor((-450 + Math.sqrt(450 * 450 + 200 * (500 + xp))) / 100));
  while (totalXpForLevel(n + 1) <= xp) n++;
  while (n > 1 && totalXpForLevel(n) > xp) n--;
  return n;
}

export function xpForMatch(m: {
  placement: number | null;
  grade?: string | null;
  spbRounds?: { accuracy: number | null; perfect?: boolean }[];
}, firstWinToday = false): number {
  let xp = 10;                                   // game played
  if (m.placement === 1) xp += 50;               // win
  if (m.grade === "SS") xp += 40;
  else if (m.grade === "S") xp += 20;
  xp += (m.spbRounds ?? []).filter((r) => r.perfect ?? (r.accuracy ?? 0) >= 1).length * 15; // perfect builds
  if (m.placement === 1 && firstWinToday) xp += 100;
  return xp;
}
