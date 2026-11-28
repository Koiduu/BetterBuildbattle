// External APIs. Overridable for testing (point them at a mock server).
export const HYPIXEL_API = process.env.HYPIXEL_API_BASE ?? "https://api.hypixel.net";
export const MOJANG_API = process.env.MOJANG_API_BASE ?? "https://api.mojang.com";
export const MOJANG_SESSION = process.env.MOJANG_SESSION_BASE ?? "https://sessionserver.mojang.com";
export const DISCORD_API = process.env.DISCORD_API_BASE ?? "https://discord.com/api";
export const MS_LOGIN = process.env.MS_LOGIN_BASE ?? "https://login.microsoftonline.com/consumers/oauth2/v2.0";
export const XBL_AUTH = process.env.XBL_AUTH_BASE ?? "https://user.auth.xboxlive.com";
export const XSTS_AUTH = process.env.XSTS_AUTH_BASE ?? "https://xsts.auth.xboxlive.com";
export const MC_SERVICES = process.env.MC_SERVICES_BASE ?? "https://api.minecraftservices.com";
export const GITHUB_API = process.env.GITHUB_API_BASE ?? "https://api.github.com";

export const dashUuid = (u: string) =>
  u.includes("-") ? u.toLowerCase() : u.toLowerCase().replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
