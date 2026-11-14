// Profile style: which looks exist (shared with the site) and which links are off limits.
import { ACCENTS, BANNERS, CARD_THEMES } from "../../../web/style-options.js";
import { can, planOf } from "./plans";

export { ACCENTS, BANNERS, CARD_THEMES };

/** Vanity links live at the top level of the site, so they can't shadow a real page or file. */
export const RESERVED = new Set([
  "p", "login", "signup", "account", "leaderboard", "leaderboards", "speed-builders", "updates", "install", "compare",
  "plans", "pricing", "privacy", "terms", "cookies", "support", "groups", "wrapped", "insights", "admin", "api", "bb",
  "fonts", "assets", "static", "index", "app", "features", "card", "style", "robots", "favicon", "sitemap", "about",
  "help", "discord", "download", "billing", "settings", "me", "null", "undefined", "betterbattle", "better-battle",
  "hypixel", "mojang", "minecraft", "microsoft", "staff", "mod", "moderator", "owner", "official",
]);

export const VANITY = /^[a-z0-9][a-z0-9_-]{2,19}$/;

/** What a profile shows of its owner's style: only while their plan includes it. */
export function publicStyle(row: any) {
  const plan = planOf(row);
  const styled = can(plan, "profileStyle");
  return {
    supporter: can(plan, "badge") ? plan : null,
    accent: styled && row.accent && ACCENTS[row.accent] ? row.accent : null,
    banner: styled && row.banner && BANNERS[row.banner] ? row.banner : null,
    cardTheme: styled && row.card_theme && CARD_THEMES[row.card_theme] ? row.card_theme : null,
    featuredAchievement: can(plan, "featuredAchievement") ? row.featured_achievement ?? null : null,
    vanity: can(plan, "vanity") ? row.vanity ?? null : null,
  };
}
