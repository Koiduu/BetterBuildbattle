import {
  Client,
  Events,
  GatewayIntentBits,
  PermissionFlagsBits,
  PermissionsBitField,
  REST,
  Routes,
  type Interaction,
} from "discord.js";
import { commands } from "./commands";
import { handleFeatureCommand } from "./features";
import { handleModerationCommand } from "./moderation";
import { handleTicketButton, handleTicketCommand, handleTicketModal } from "./tickets";

const oneLine = (error: unknown) => String(error instanceof Error ? error.message : error).replace(/\s+/g, " ").slice(0, 240);

async function reportInteractionError(interaction: Interaction, error: unknown) {
  console.error(`Discord interaction failed: ${oneLine(error)}`);
  if (!interaction.isRepliable()) return;
  const content = "Something went wrong. Please try again.";
  try {
    if (interaction.deferred || interaction.replied) await interaction.followUp({ content, ephemeral: true });
    else await interaction.reply({ content, ephemeral: true });
  } catch {}
}

export async function startDiscordBot(token: string, guildId: string) {
  if (!guildId) throw new Error("DISCORD_GUILD_ID is required when DISCORD_BOT_TOKEN is set");
  const client = new Client({ intents: [GatewayIntentBits.Guilds] });
  const permissions = PermissionsBitField.resolve([
    PermissionFlagsBits.ViewChannel,
    PermissionFlagsBits.SendMessages,
    PermissionFlagsBits.EmbedLinks,
    PermissionFlagsBits.AttachFiles,
    PermissionFlagsBits.ReadMessageHistory,
    PermissionFlagsBits.ManageMessages,
    PermissionFlagsBits.KickMembers,
    PermissionFlagsBits.BanMembers,
    PermissionFlagsBits.ModerateMembers,
    PermissionFlagsBits.ManageGuild,
    PermissionFlagsBits.CreatePrivateThreads,
    PermissionFlagsBits.SendMessagesInThreads,
    PermissionFlagsBits.ManageThreads,
  ]).toString();
  const guildHint = "Is the bot in the server with DISCORD_GUILD_ID? Open the invite link above; commands register as soon as it joins.";
  const registerCommands = async (client: Client) => {
    try {
      const user = client.user;
      if (!user) return;
      const guild = client.guilds.cache.get(guildId);
      const rest = new REST({ version: "10" }).setToken(token);
      await rest.put(Routes.applicationGuildCommands(user.id, guildId), { body: commands.map((command) => command.toJSON()) });
      console.log(`Registered ${commands.length} slash commands in ${guild?.name ?? guildId}`);
    } catch (error) {
      const code = Number((error as { code?: unknown }).code);
      const hint = code === 50001 || code === 10004 ? ` ${guildHint}` : "";
      console.error(`Discord bot command setup failed: ${oneLine(error)}${hint}`);
    }
  };
  client.once(Events.ClientReady, (ready) => {
    const invite = `https://discord.com/oauth2/authorize?client_id=${ready.user.id}&scope=bot+applications.commands&permissions=${permissions}`;
    console.log(`Discord bot ready as ${ready.user.tag}. Invite it: ${invite}`);
    if (client.guilds.cache.has(guildId)) {
      void registerCommands(client);
    } else {
      console.warn(`Discord bot is not in the server yet. Check DISCORD_GUILD_ID. ${guildHint}`);
    }
  });
  client.on(Events.GuildCreate, (guild) => {
    if (guild.id === guildId) void registerCommands(client);
  });
  client.on(Events.InteractionCreate, (interaction) => {
    void (async () => {
      if (interaction.isChatInputCommand()) {
        if (await handleFeatureCommand(interaction) !== false) return;
        if (await handleTicketCommand(interaction) !== false) return;
        await handleModerationCommand(interaction);
      } else if (interaction.isButton()) {
        await handleTicketButton(interaction);
      } else if (interaction.isModalSubmit()) {
        await handleTicketModal(interaction);
      }
    })().catch((error) => reportInteractionError(interaction, error));
  });
  client.on(Events.Error, (error) => console.error(`Discord client error: ${oneLine(error)}`));
  try {
    await client.login(token);
  } catch (error) {
    client.destroy();
    throw error;
  }
}
