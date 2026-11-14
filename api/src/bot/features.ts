import {
  ActionRowBuilder,
  AttachmentBuilder,
  AutoModerationActionType,
  AutoModerationRuleEventType,
  AutoModerationRuleKeywordPresetType,
  AutoModerationRuleTriggerType,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  EmbedBuilder,
  PermissionFlagsBits,
  type ChatInputCommandInteraction,
  type Guild,
} from "discord.js";
import { pool } from "../db/pool";
import { HttpError } from "../http";
import { siteOrigin } from "../lib/site";
import { verifyHypixelDiscord } from "../lib/verifyHypixelDiscord";
import { latestRelease } from "../services/modRelease";
import { renderStatsCard, type StatsCardData } from "./card";
import { resolveStatsName, saveDiscordLink } from "./data";

const verifyAttempts = new Map<string, { count: number; start: number }>();

function replyEphemeral(interaction: ChatInputCommandInteraction, content: string) {
  return interaction.reply({ content, ephemeral: true });
}

async function setup(interaction: ChatInputCommandInteraction) {
  const guild = interaction.guild;
  if (!guild) return replyEphemeral(interaction, "Use this command in a server.");
  const bot = guild.members.me ?? await guild.members.fetchMe();
  if (!bot.permissions.has(PermissionFlagsBits.ManageGuild)) {
    return replyEphemeral(interaction, "The bot is missing the Manage Server permission.");
  }
  const staff = interaction.options.getRole("staff-role", true);
  const logChannel = interaction.options.getChannel("log-channel", true);
  await pool.query(
    `INSERT INTO bot_settings (guild_id, staff_role_id, log_channel_id)
     VALUES ($1, $2, $3) ON CONFLICT (guild_id) DO UPDATE
     SET staff_role_id = EXCLUDED.staff_role_id, log_channel_id = EXCLUDED.log_channel_id`,
    [guild.id, staff.id, logChannel.id],
  );

  const common = {
    enabled: true,
    eventType: AutoModerationRuleEventType.MessageSend,
    actions: [{ type: AutoModerationActionType.BlockMessage }],
    exemptRoles: [staff.id],
  };
  const rules = [
    {
      name: "Better Battle: Invite Links",
      triggerType: AutoModerationRuleTriggerType.Keyword,
      triggerMetadata: { regexPatterns: ["(?:https?://)?(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)/[A-Za-z0-9-]+"] },
      ...common,
    },
    {
      name: "Better Battle: Mention Spam",
      triggerType: AutoModerationRuleTriggerType.MentionSpam,
      triggerMetadata: { mentionTotalLimit: 5 },
      ...common,
    },
    {
      name: "Better Battle: Keyword Presets",
      triggerType: AutoModerationRuleTriggerType.KeywordPreset,
      triggerMetadata: { presets: [
        AutoModerationRuleKeywordPresetType.Profanity,
        AutoModerationRuleKeywordPresetType.SexualContent,
        AutoModerationRuleKeywordPresetType.Slurs,
      ] },
      ...common,
    },
  ];
  try {
    const manager = guild.autoModerationRules;
    const existing = await manager.fetch();
    for (const rule of rules) {
      const prior = existing.find((item) => item.name === rule.name);
      if (prior) await manager.edit(prior.id, rule);
      else await manager.create(rule);
    }
  } catch (error) {
    if ((error as { code?: number }).code === 50013) {
      return replyEphemeral(interaction, "The bot is missing the Manage Server permission.");
    }
    throw error;
  }
  return replyEphemeral(interaction, `Set staff role to <@&${staff.id}> and logs to <#${logChannel.id}>. AutoMod rules are ready.`);
}

async function download(interaction: ChatInputCommandInteraction) {
  await interaction.deferReply();
  const release = await latestRelease();
  const origin = siteOrigin();
  const embed = new EmbedBuilder()
    .setColor(0xffaa00)
    .setTitle("Better Battle downloads")
    .setDescription(`Choose the version you play. After installing, press **B** or type **/bb** in game, then link your account at [battle.aspir3.net/account](https://battle.aspir3.net/account).\n\n${release?.version ? `Latest version: **${release.version}**.\n\n` : ""}[Installation guide](${origin}/install)`)
    .addFields(
      { name: "Fabric 26.3", value: "Install Fabric Loader 0.20.1+ for Minecraft 26.3, Fabric API, and Java 25+. Drop the jar into `.minecraft/mods`, then launch the game." },
      { name: "Forge 1.8.9", value: "Install Forge 1.8.9 (11.15.1.2318), drop the jar into `.minecraft/mods`, then launch the game." },
    );
  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setLabel("Fabric 26.3").setStyle(ButtonStyle.Link).setURL(`${origin}/download/fabric`),
    new ButtonBuilder().setLabel("Forge 1.8.9").setStyle(ButtonStyle.Link).setURL(`${origin}/download/forge`),
  );
  return interaction.editReply({ embeds: [embed], components: [row] });
}

function verifyLimited(userId: string) {
  const now = Date.now();
  const entry = verifyAttempts.get(userId);
  if (!entry || now - entry.start >= 60_000) {
    verifyAttempts.set(userId, { count: 1, start: now });
    return true;
  }
  if (entry.count >= 3) return false;
  entry.count++;
  return true;
}

async function verify(interaction: ChatInputCommandInteraction) {
  if (!verifyLimited(interaction.user.id)) return replyEphemeral(interaction, "Please wait a minute before trying /verify again.");
  await interaction.deferReply({ ephemeral: true });
  try {
    const name = interaction.options.getString("name", true);
    const result = await verifyHypixelDiscord(name, interaction.user.username);
    await saveDiscordLink(interaction.user.id, result.uuid, result.mcName);
    return interaction.editReply(`Verified as ${result.mcName}`);
  } catch (error) {
    if (error instanceof HttpError) {
      if (error.status === 400 && error.body.error === "discord_mismatch") {
        const linked = typeof error.body.hypixelDiscord === "string" ? `Hypixel has **${error.body.hypixelDiscord}**` : "Hypixel has **nothing**";
        return interaction.editReply(`${linked}. In a Hypixel lobby, right-click your head in the hotbar → Social Media → Discord → type your Discord username in chat. Hypixel can take a few minutes to update.`);
      }
      if (error.status === 503) return interaction.editReply("Hypixel isn't answering right now, try again in a minute");
      if (error.status === 404) return interaction.editReply("That Minecraft name wasn't found. Check it and try again.");
    }
    throw error;
  }
}

async function unverify(interaction: ChatInputCommandInteraction) {
  const result = await pool.query("DELETE FROM discord_links WHERE discord_id = $1", [interaction.user.id]);
  return replyEphemeral(interaction, result.rowCount ? "Your verified Minecraft link was removed." : "You don't have a verified Minecraft link.");
}

async function stats(interaction: ChatInputCommandInteraction) {
  const name = interaction.options.getString("player") ?? await resolveStatsName(interaction.user.id);
  if (!name) return replyEphemeral(interaction, "Run /verify <name> first, or use /stats <name>.");
  await interaction.deferReply();
  const port = Number(process.env.PORT ?? 4000);
  const response = await fetch(`http://127.0.0.1:${port}/bb/v1/players/${encodeURIComponent(name)}`, {
    headers: { authorization: `discord:${interaction.user.id}` },
    signal: AbortSignal.timeout(12_000),
  });
  const data = await response.json() as StatsCardData | { error?: string; name?: string };
  if (!response.ok) {
    const error = "error" in data ? data.error : null;
    if (response.status === 404 && error === "not_found") return interaction.editReply(`No Minecraft player called ${name}.`);
    if (response.status === 404 && error === "never_played_build_battle") return interaction.editReply(`${"name" in data && data.name ? data.name : name} hasn't played Build Battle.`);
    if (response.status === 503) return interaction.editReply("Hypixel isn't answering right now, try again in a minute");
    return interaction.editReply("The stats server couldn't load that player.");
  }
  const image = await renderStatsCard(data as StatsCardData);
  const url = new URL(`/p/${encodeURIComponent(name)}`, process.env.PUBLIC_URL ?? "https://battle.aspir3.net").toString();
  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setLabel("Full profile").setStyle(ButtonStyle.Link).setURL(url),
  );
  return interaction.editReply({
    files: [new AttachmentBuilder(image, { name: "betterbattle-stats.png" })],
    components: [row],
  });
}

export async function handleFeatureCommand(interaction: ChatInputCommandInteraction) {
  switch (interaction.commandName) {
    case "setup": return setup(interaction);
    case "download": return download(interaction);
    case "verify": return verify(interaction);
    case "unverify": return unverify(interaction);
    case "stats": return stats(interaction);
    default: return false;
  }
}
