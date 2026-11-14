import {
  EmbedBuilder,
  PermissionFlagsBits,
  type ChatInputCommandInteraction,
  type TextChannel,
} from "discord.js";
import { pool } from "../db/pool";
import { logEmbed, memberForModeration, sendLog, settingsFor } from "./common";

const durationMs: Record<string, number> = { "60s": 60_000, "5m": 300_000, "10m": 600_000, "1h": 3_600_000, "1d": 86_400_000, "1w": 604_800_000 };
const deleteSeconds: Record<string, number> = { none: 0, "1h": 3600, "1d": 86400, "7d": 604800 };

const targetLabel = (id: string, tag?: string) => `<@${id}>${tag ? ` (${tag})` : ""}`;

async function record(interaction: ChatInputCommandInteraction, action: string, target: string, reason: string, reply: string) {
  const guild = interaction.guild!;
  const settings = await settingsFor(guild.id);
  await sendLog(guild, settings.log_channel_id, logEmbed(action, target, interaction.user.tag, reason));
  return interaction.editReply(reply);
}

async function checkedMember(interaction: ChatInputCommandInteraction) {
  const guild = interaction.guild!;
  const user = interaction.options.getUser("user", true);
  const target = await memberForModeration(guild, interaction.user.id, user.id);
  return { guild, user, target };
}

export async function handleModerationCommand(interaction: ChatInputCommandInteraction) {
  if (!["warn", "warnings", "clearwarn", "timeout", "untimeout", "kick", "ban", "unban", "purge"].includes(interaction.commandName)) return false;
  const guild = interaction.guild;
  if (!guild) return interaction.reply({ content: "Use this command in a server.", ephemeral: true });
  if (interaction.commandName === "warnings") {
    const user = interaction.options.getUser("user", true);
    await interaction.deferReply({ ephemeral: true });
    try { await memberForModeration(guild, interaction.user.id, user.id); }
    catch (error) { return interaction.editReply((error as Error).message); }
    const result = await pool.query(
      `SELECT id, moderator_id, reason, created_at FROM mod_warnings
       WHERE guild_id = $1 AND user_id = $2 ORDER BY created_at DESC LIMIT 10`, [guild.id, user.id]);
    if (!result.rowCount) return interaction.editReply(`${user.tag} has no warnings.`);
    const lines = result.rows.map((row) => `**#${row.id}** · <t:${Math.floor(new Date(row.created_at).getTime() / 1000)}:d> · <@${row.moderator_id}> — ${String(row.reason).slice(0, 120)}`);
    return interaction.editReply(`Recent warnings for ${user.tag}:\n${lines.join("\n")}`);
  }

  if (interaction.commandName === "clearwarn") {
    await interaction.deferReply({ ephemeral: true });
    const id = interaction.options.getInteger("id", true);
    const warning = await pool.query("SELECT user_id FROM mod_warnings WHERE id = $1 AND guild_id = $2", [id, guild.id]);
    if (!warning.rowCount) return interaction.editReply("No warning with that ID was found.");
    const userId = String(warning.rows[0].user_id);
    if ([interaction.user.id, guild.client.user?.id, guild.ownerId].includes(userId)) {
      return interaction.editReply("That warning can't be cleared by this command.");
    }
    try { await memberForModeration(guild, interaction.user.id, userId); }
    catch (error) {
      if (!String((error as Error).message).includes("Unknown Member")) return interaction.editReply((error as Error).message);
    }
    await pool.query("DELETE FROM mod_warnings WHERE id = $1 AND guild_id = $2", [id, guild.id]);
    const settings = await settingsFor(guild.id);
    await sendLog(guild, settings.log_channel_id, logEmbed("Warning cleared", `<@${userId}>`, interaction.user.tag, `Warning #${id} cleared`));
    return interaction.editReply(`Warning #${id} cleared.`);
  }

  if (interaction.commandName === "unban") {
    await interaction.deferReply({ ephemeral: true });
    const userId = interaction.options.getString("user_id", true);
    const reason = interaction.options.getString("reason", true);
    if ([interaction.user.id, guild.client.user?.id, guild.ownerId].includes(userId)) return interaction.editReply("That user can't be unbanned by this command.");
    try {
      const member = await guild.members.fetch(userId);
      await memberForModeration(guild, interaction.user.id, member.id);
      return interaction.editReply("That user is still in the server.");
    } catch (error) {
      if (!String((error as Error).message).includes("Unknown Member")) return interaction.editReply((error as Error).message);
    }
    try {
      const banned = await guild.bans.fetch(userId);
      await guild.members.unban(userId, reason);
      return record(interaction, "Unban", targetLabel(userId, banned.user.tag), reason, `Unbanned ${banned.user.tag}.`);
    } catch {
      return interaction.editReply("That user is not banned, or the bot cannot unban them.");
    }
  }

  if (interaction.commandName === "purge") {
    await interaction.deferReply({ ephemeral: true });
    const amount = interaction.options.getInteger("amount", true);
    const user = interaction.options.getUser("user");
    if (user) {
      try { await memberForModeration(guild, interaction.user.id, user.id); }
      catch (error) { return interaction.editReply((error as Error).message); }
    }
    const channel = interaction.channel as TextChannel | null;
    if (!channel || !("bulkDelete" in channel) || !("messages" in channel)) return interaction.editReply("Purge can only be used in a text channel.");
    try {
      const messages = await channel.messages.fetch({ limit: user ? 100 : amount });
      const selected = user ? messages.filter((message) => message.author.id === user.id).first(amount) : messages;
      const deleted = await channel.bulkDelete(selected, true);
      const message = `Deleted ${deleted.size} message${deleted.size === 1 ? "" : "s"}. Messages older than 14 days are skipped.`;
      return record(interaction, "Purge", user ? targetLabel(user.id, user.tag) : `${deleted.size} messages`, message, message);
    } catch {
      return interaction.editReply("Couldn't purge messages. Check the bot's Manage Messages permission.");
    }
  }

  await interaction.deferReply({ ephemeral: true });
  try {
    const { guild: currentGuild, user, target } = await checkedMember(interaction);
    const reason = interaction.commandName === "untimeout"
      ? `Removed by ${interaction.user.tag}`
      : interaction.options.getString("reason", true);
    const targetText = targetLabel(user.id, user.tag);
    switch (interaction.commandName) {
      case "warn": {
        const warning = await pool.query(
          `INSERT INTO mod_warnings (guild_id, user_id, moderator_id, reason)
           VALUES ($1, $2, $3, $4) RETURNING id`, [currentGuild.id, user.id, interaction.user.id, reason]);
        return record(interaction, "Warn", targetText, reason, `Warning #${warning.rows[0].id} recorded for ${user.tag}.`);
      }
      case "timeout": {
        const duration = interaction.options.getString("duration", true);
        await target.timeout(durationMs[duration], reason);
        return record(interaction, "Timeout", targetText, `${duration}: ${reason}`, `Timed out ${user.tag} for ${duration}.`);
      }
      case "untimeout":
        await target.timeout(null, reason);
        return record(interaction, "Timeout removed", targetText, "Timeout removed", `Removed ${user.tag}'s timeout.`);
      case "kick":
        await user.send(`You were kicked from ${currentGuild.name}. Reason: ${reason}`).catch(() => {});
        await target.kick(reason);
        return record(interaction, "Kick", targetText, reason, `Kicked ${user.tag}.`);
      case "ban": {
        const period = interaction.options.getString("delete_messages", true);
        await user.send(`You were banned from ${currentGuild.name}. Reason: ${reason}`).catch(() => {});
        await target.ban({ deleteMessageSeconds: deleteSeconds[period], reason });
        return record(interaction, "Ban", targetText, reason, `Banned ${user.tag}.`);
      }
    }
  } catch (error) {
    return interaction.editReply((error as Error).message || "The moderation action failed.");
  }
  return false;
}
