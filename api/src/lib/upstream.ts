// External APIs. Overridable for testing (point them at a mock server).
export const HYPIXEL_API = process.env.HYPIXEL_API_BASE ?? "https://api.hypixel.net";
export const MOJANG_API = process.env.MOJANG_API_BASE ?? "https://api.mojang.com";
export const MOJANG_SESSION = process.env.MOJANG_SESSION_BASE ?? "https://sessionserver.mojang.com";
export const DISCORD_API = process.env.DISCORD_API_BASE ?? "https://discord.com/api";
export const GITHUB_API = process.env.GITHUB_API_BASE ?? "https://api.github.com";

export const dashUuid = (u: string) =>
  u.includes("-") ? u.toLowerCase() : u.toLowerCase().replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
