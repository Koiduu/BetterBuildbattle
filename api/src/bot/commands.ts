import { ChannelType, PermissionFlagsBits, SlashCommandBuilder } from "discord.js";
import { statsCommands } from "./stats";

const moderate = PermissionFlagsBits.ModerateMembers;
const manageGuild = PermissionFlagsBits.ManageGuild;

export const commands = [
  ...statsCommands,
  new SlashCommandBuilder()
    .setName("setup").setDescription("Configure Better Battle moderation and ticket settings")
    .setDefaultMemberPermissions(manageGuild)
    .addRoleOption((option) => option.setName("staff-role").setDescription("Role that handles tickets").setRequired(true))
    .addChannelOption((option) => option.setName("log-channel").setDescription("Channel for moderation and ticket logs")
      .addChannelTypes(ChannelType.GuildText).setRequired(true)),
  new SlashCommandBuilder().setName("download").setDescription("Get the Better Battle mod downloads"),
  new SlashCommandBuilder().setName("verify").setDescription("Link your Minecraft account to Discord")
    .addStringOption((option) => option.setName("name").setDescription("Minecraft username").setRequired(true).setMaxLength(16)),
  new SlashCommandBuilder().setName("unverify").setDescription("Remove your verified Minecraft link"),
  new SlashCommandBuilder().setName("stats").setDescription("Show a player's Build Battle stats")
    .addStringOption((option) => option.setName("player").setDescription("Minecraft username").setMaxLength(16).setAutocomplete(true)),
  new SlashCommandBuilder().setName("ticket-panel").setDescription("Post the support ticket panel")
    .setDefaultMemberPermissions(manageGuild),
  new SlashCommandBuilder().setName("warn").setDescription("Warn a server member")
    .setDefaultMemberPermissions(moderate)
    .addUserOption((option) => option.setName("user").setDescription("Member to warn").setRequired(true))
    .addStringOption((option) => option.setName("reason").setDescription("Reason").setRequired(true).setMaxLength(1000)),
  new SlashCommandBuilder().setName("warnings").setDescription("List a member's recent warnings")
    .setDefaultMemberPermissions(moderate)
    .addUserOption((option) => option.setName("user").setDescription("Member").setRequired(true)),
  new SlashCommandBuilder().setName("clearwarn").setDescription("Clear a warning by ID")
    .setDefaultMemberPermissions(moderate)
    .addIntegerOption((option) => option.setName("id").setDescription("Warning ID").setRequired(true).setMinValue(1)),
  new SlashCommandBuilder().setName("timeout").setDescription("Timeout a server member")
    .setDefaultMemberPermissions(moderate)
    .addUserOption((option) => option.setName("user").setDescription("Member to timeout").setRequired(true))
    .addStringOption((option) => option.setName("duration").setDescription("Timeout length").setRequired(true)
      .addChoices(
        { name: "60 seconds", value: "60s" }, { name: "5 minutes", value: "5m" },
        { name: "10 minutes", value: "10m" }, { name: "1 hour", value: "1h" },
        { name: "1 day", value: "1d" }, { name: "1 week", value: "1w" },
      ))
    .addStringOption((option) => option.setName("reason").setDescription("Reason").setRequired(true).setMaxLength(1000)),
  new SlashCommandBuilder().setName("untimeout").setDescription("Remove a member's timeout")
    .setDefaultMemberPermissions(moderate)
    .addUserOption((option) => option.setName("user").setDescription("Member").setRequired(true)),
  new SlashCommandBuilder().setName("kick").setDescription("Kick a server member")
    .setDefaultMemberPermissions(PermissionFlagsBits.KickMembers)
    .addUserOption((option) => option.setName("user").setDescription("Member to kick").setRequired(true))
    .addStringOption((option) => option.setName("reason").setDescription("Reason").setRequired(true).setMaxLength(1000)),
  new SlashCommandBuilder().setName("ban").setDescription("Ban a server member")
    .setDefaultMemberPermissions(PermissionFlagsBits.BanMembers)
    .addUserOption((option) => option.setName("user").setDescription("Member to ban").setRequired(true))
    .addStringOption((option) => option.setName("reason").setDescription("Reason").setRequired(true).setMaxLength(1000))
    .addStringOption((option) => option.setName("delete_messages").setDescription("Delete recent messages").setRequired(true)
      .addChoices(
        { name: "None", value: "none" }, { name: "1 hour", value: "1h" },
        { name: "1 day", value: "1d" }, { name: "7 days", value: "7d" },
      )),
  new SlashCommandBuilder().setName("unban").setDescription("Unban a user")
    .setDefaultMemberPermissions(PermissionFlagsBits.BanMembers)
    .addStringOption((option) => option.setName("user_id").setDescription("Discord user ID").setRequired(true))
    .addStringOption((option) => option.setName("reason").setDescription("Reason").setRequired(true).setMaxLength(1000)),
  new SlashCommandBuilder().setName("purge").setDescription("Delete recent messages")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
    .addIntegerOption((option) => option.setName("amount").setDescription("Number of messages").setRequired(true).setMinValue(1).setMaxValue(100))
    .addUserOption((option) => option.setName("user").setDescription("Only messages from this member")),
];
