import {
  EmbedBuilder,
  PermissionFlagsBits,
  type Guild,
  type GuildMember,
  type TextBasedChannel,
} from "discord.js";
import { pool } from "../db/pool";

export type BotSettings = {
  staff_role_id: string | null;
  log_channel_id: string | null;
  ticket_channel_id: string | null;
};

export async function settingsFor(guildId: string): Promise<BotSettings> {
  const result = await pool.query<BotSettings>(
    "SELECT staff_role_id, log_channel_id, ticket_channel_id FROM bot_settings WHERE guild_id = $1", [guildId]);
  return result.rows[0] ?? { staff_role_id: null, log_channel_id: null, ticket_channel_id: null };
}

export async function sendLog(guild: Guild, channelId: string | null, embed: EmbedBuilder) {
  if (!channelId) return false;
  const channel = await guild.channels.fetch(channelId).catch(() => null);
  if (!channel?.isTextBased() || !("send" in channel)) return false;
  await (channel as TextBasedChannel & { send: (options: { embeds: EmbedBuilder[] }) => Promise<unknown> })
    .send({ embeds: [embed] });
  return true;
}

export function logEmbed(action: string, target: string, moderator: string, reason: string) {
  return new EmbedBuilder()
    .setColor(0xffaa00)
    .setTitle(action)
    .addFields(
      { name: "Target", value: target, inline: true },
      { name: "Moderator", value: moderator, inline: true },
      { name: "Reason", value: reason.slice(0, 1024) || "No reason given" },
    )
    .setTimestamp(new Date());
}

export async function membersForModeration(guild: Guild, actorId: string, targetId: string) {
  const botId = guild.client.user?.id;
  if (targetId === actorId) throw new Error("You can't moderate yourself.");
  if (targetId === botId) throw new Error("You can't moderate the bot.");
  if (targetId === guild.ownerId) throw new Error("The server owner can't be moderated.");
  const [actor, target, bot] = await Promise.all([
    guild.members.fetch(actorId),
    guild.members.fetch(targetId),
    guild.members.me ? Promise.resolve(guild.members.me) : guild.members.fetchMe(),
  ]);
  if (target.roles.highest.comparePositionTo(actor.roles.highest) >= 0) {
    throw new Error("That member's highest role is equal to or above yours.");
  }
  if (target.roles.highest.comparePositionTo(bot.roles.highest) >= 0) {
    throw new Error("That member's highest role is equal to or above the bot's.");
  }
  return { actor, target, bot };
}

export async function memberForModeration(guild: Guild, actorId: string, targetId: string): Promise<GuildMember> {
  return (await membersForModeration(guild, actorId, targetId)).target;
}

export function botMissingPermission(guild: Guild, permission: bigint) {
  const bot = guild.members.me;
  return !bot?.permissions.has(permission);
}

export const manageThreads = PermissionFlagsBits.ManageThreads;
