// The three plans and what each one unlocks. This file is the only place that decides who gets what:
// change a feature's plan here and the API, the website and the Discord bot all follow.
//
// The rule: nothing that touches a game is ever paid. The mod, tracking, achievements, the Speed
// Builders timer, weekly challenges and the rankings are the same for everyone. Paid plans add looks
// (Architect) and deeper statistics (Master Builder), on the website and in Discord only.

export type Plan = "free" | "architect" | "master";

export const PLANS: Record<Plan, { name: string; rank: number; tagline: string; color: number }> = {
  free: { name: "Builder", rank: 0, tagline: "Everything you need to play", color: 0xc6c6c6 },
  architect: { name: "Architect", rank: 1, tagline: "Make your profile yours", color: 0xff66aa },
  master: { name: "Master Builder", rank: 2, tagline: "Every statistic, as deep as it goes", color: 0xffaa00 },
};

/** The lowest plan that includes each feature. */
export const FEATURES = {
  // Architect
  badge: "architect",                // supporter badge on profile, leaderboards, cards and in Discord
  profileStyle: "architect",         // accent colour, banner, share-card themes
  featuredAchievement: "architect",
  vanity: "architect",               // battle.aspir3.net/<name>
  leaderboardPositions: "architect", // where you stand on every Build Battle leaderboard
  // Master Builder
  themeAnalytics: "master",
  spbAnalysis: "master",
  fullHistory: "master",             // every game ever, and CSV/JSON export
  compareMany: "master",             // compare up to 4 players
  wrapped: "master",                 // Build Battle Wrapped at any time
  privateBoards: "master",           // create private leaderboards (anyone can join one)
} as const satisfies Record<string, Plan>;

export type Feature = keyof typeof FEATURES;

export const LIMITS = {
  pins: { free: 10, architect: 20, master: 20 },
  comparePlayers: { free: 2, architect: 2, master: 4 },
  historyGames: { free: 100, architect: 100, master: Infinity },
  ownedBoards: { free: 0, architect: 0, master: 5 },
} as const satisfies Record<string, Record<Plan, number>>;

export const can = (plan: Plan, feature: Feature) => PLANS[plan].rank >= PLANS[FEATURES[feature]].rank;
export const limit = (plan: Plan, what: keyof typeof LIMITS) => LIMITS[what][plan];

/** The plan a user row has right now. A paid plan lapses a day after its period ends unless renewed. */
export function planOf(row: { plan?: string | null; plan_until?: Date | string | null; plan_comped?: boolean | null } | undefined | null): Plan {
  if (!row || (row.plan !== "architect" && row.plan !== "master")) return "free";
  if (row.plan_comped) return row.plan;
  if (row.plan_until && new Date(row.plan_until).getTime() + 86_400_000 < Date.now()) return "free";
  return row.plan;
}

/** What the pricing page and the bot show. */
export const PERKS: Record<Plan, string[]> = {
  free: [
    "The mod, with every achievement and challenge",
    "Speed Builders timer, bests and world records",
    "Weekly challenges and game recaps",
    "Your full profile, global rank and share card",
    "Compare with another player",
  ],
  architect: [
    "Supporter badge on your profile, the leaderboards, cards and in Discord",
    "Profile accent colour and banner",
    "Share-card themes",
    "Feature an achievement at the top of your profile",
    "Pin up to 20 builds",
    "Your own link: battle.aspir3.net/yourname",
    "Your position on every Build Battle leaderboard, on the site and with /leaderboards",
  ],
  master: [
    "Everything in Architect",
    "Build Battle Wrapped whenever you like",
    "Theme analytics: your best and worst themes",
    "Speed Builders analysis: time history and trend for every build",
    "Your full game history, with CSV and JSON export",
    "Compare up to 4 players at once",
    "Private leaderboards for your guild or friends, with a weekly race",
  ],
};
