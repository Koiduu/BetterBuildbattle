import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  EmbedBuilder,
  ModalBuilder,
  PermissionFlagsBits,
  TextInputBuilder,
  TextInputStyle,
  type ButtonInteraction,
  type ChatInputCommandInteraction,
  type ModalSubmitInteraction,
  type TextChannel,
} from "discord.js";
import { pool } from "../db/pool";
import { logEmbed, sendLog, settingsFor } from "./common";

const ticketButton = "bb:ticket:open";

export async function ticketPanel(interaction: ChatInputCommandInteraction) {
  const guild = interaction.guild;
  const channel = interaction.channel;
  if (!guild || !channel || channel.type !== ChannelType.GuildText) {
    return interaction.reply({ content: "Use this command in a server text channel.", ephemeral: true });
  }
  const bot = guild.members.me ?? await guild.members.fetchMe();
  const missing = [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages, PermissionFlagsBits.EmbedLinks]
    .find((permission) => !bot.permissionsIn(channel).has(permission));
  if (missing) return interaction.reply({ content: `The bot is missing ${new PermissionsLabel(missing).name} in this channel.`, ephemeral: true });
  await pool.query(
    `INSERT INTO bot_settings (guild_id, ticket_channel_id) VALUES ($1, $2)
     ON CONFLICT (guild_id) DO UPDATE SET ticket_channel_id = EXCLUDED.ticket_channel_id`,
    [guild.id, channel.id],
  );
  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setCustomId(ticketButton).setLabel("Open a ticket").setStyle(ButtonStyle.Primary),
  );
  await channel.send({
    embeds: [new EmbedBuilder().setColor(0xff66aa).setTitle("Need help?").setDescription("Open a private support ticket with the Better Battle staff.")],
    components: [row],
  });
  return interaction.reply({ content: "Ticket panel posted in this channel.", ephemeral: true });
}

class PermissionsLabel {
  name: string;
  constructor(permission: bigint) {
    this.name = permission === PermissionFlagsBits.CreatePrivateThreads ? "Create Private Threads"
      : permission === PermissionFlagsBits.SendMessagesInThreads ? "Send Messages in Threads"
        : permission === PermissionFlagsBits.ManageThreads ? "Manage Threads"
          : permission === PermissionFlagsBits.EmbedLinks ? "Embed Links"
            : permission === PermissionFlagsBits.SendMessages ? "Send Messages" : "View Channel";
  }
}

export async function handleTicketButton(interaction: ButtonInteraction) {
  if (interaction.customId === ticketButton) {
    const guild = interaction.guild;
    if (!guild) return interaction.reply({ content: "Use this button in the support server.", ephemeral: true });
    const settings = await settingsFor(guild.id);
    if (settings.ticket_channel_id !== interaction.channelId) {
      return interaction.reply({ content: "This ticket panel is no longer configured.", ephemeral: true });
    }
    const modal = new ModalBuilder().setCustomId("bb:ticket:topic").setTitle("Open a support ticket");
    const input = new TextInputBuilder().setCustomId("topic").setLabel("What do you need help with?")
      .setStyle(TextInputStyle.Paragraph).setMinLength(10).setMaxLength(1000).setRequired(true);
    return interaction.showModal(modal.addComponents(new ActionRowBuilder<TextInputBuilder>().addComponents(input)));
  }
  if (!interaction.customId.startsWith("bb:ticket:close:")) return;
  const guild = interaction.guild;
  if (!guild) return interaction.reply({ content: "Use this button in the support server.", ephemeral: true });
  const id = Number(interaction.customId.split(":").at(-1));
  const ticket = (await pool.query(
    "SELECT id, thread_id, opener_id FROM tickets WHERE id = $1 AND guild_id = $2 AND closed_at IS NULL",
    [id, guild.id])).rows[0];
  if (!ticket) return interaction.reply({ content: "This ticket is already closed.", ephemeral: true });
  const settings = await settingsFor(guild.id);
  const member = await guild.members.fetch(interaction.user.id);
  const staff = settings.staff_role_id && member.roles.cache.has(settings.staff_role_id);
  if (ticket.opener_id !== interaction.user.id && !staff && !member.permissions.has(PermissionFlagsBits.ManageThreads)) {
    return interaction.reply({ content: "Only the ticket opener or staff can close this ticket.", ephemeral: true });
  }
  await interaction.deferReply({ ephemeral: true });
  const thread = await guild.channels.fetch(ticket.thread_id);
  if (!thread || !thread.isThread()) return interaction.editReply("The ticket thread could not be found.");
  await thread.send(`Closed by ${interaction.user}.`);
  await thread.setLocked(true);
  await thread.setArchived(true);
  await pool.query("UPDATE tickets SET closed_at = now(), closed_by = $2 WHERE id = $1 AND closed_at IS NULL", [id, interaction.user.id]);
  await sendLog(guild, settings.log_channel_id, logEmbed("Ticket closed", `<@${ticket.opener_id}>`, interaction.user.tag, "Ticket closed"));
  return interaction.editReply("Ticket closed.");
}

export async function handleTicketModal(interaction: ModalSubmitInteraction) {
  const guild = interaction.guild;
  if (!guild) return interaction.reply({ content: "Use this form in the support server.", ephemeral: true });
  const settings = await settingsFor(guild.id);
  if (!settings.ticket_channel_id) return interaction.reply({ content: "Tickets are not configured. Ask a server admin to run /ticket-panel.", ephemeral: true });
  const existing = (await pool.query(
    "SELECT thread_id FROM tickets WHERE guild_id = $1 AND opener_id = $2 AND closed_at IS NULL",
    [guild.id, interaction.user.id])).rows[0];
  if (existing) return interaction.reply({ content: `You already have an open ticket: https://discord.com/channels/${guild.id}/${existing.thread_id}`, ephemeral: true });
  const parent = await guild.channels.fetch(settings.ticket_channel_id);
  if (!parent || parent.type !== ChannelType.GuildText) return interaction.reply({ content: "The ticket channel is unavailable.", ephemeral: true });
  const bot = guild.members.me ?? await guild.members.fetchMe();
  const needed = [PermissionFlagsBits.CreatePrivateThreads, PermissionFlagsBits.SendMessagesInThreads, PermissionFlagsBits.ManageThreads];
  const missing = needed.find((permission) => !bot.permissionsIn(parent).has(permission));
  if (missing) return interaction.reply({ content: `The bot is missing ${new PermissionsLabel(missing).name} in the ticket channel.`, ephemeral: true });
  await interaction.deferReply({ ephemeral: true });
  let thread;
  try {
    thread = await parent.threads.create({
      name: `ticket-${interaction.user.username}`.slice(0, 100),
      type: ChannelType.PrivateThread,
      invitable: false,
      reason: `Support ticket opened by ${interaction.user.tag}`,
    });
    await thread.members.add(interaction.user.id);
    const insert = await pool.query(
      `INSERT INTO tickets (guild_id, thread_id, opener_id, topic)
       VALUES ($1, $2, $3, $4) RETURNING id`,
      [guild.id, thread.id, interaction.user.id, interaction.fields.getTextInputValue("topic")],
    );
    const ticketId = insert.rows[0].id;
    const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder().setCustomId(`bb:ticket:close:${ticketId}`).setLabel("Close ticket").setStyle(ButtonStyle.Secondary),
    );
    await thread.send({
      content: settings.staff_role_id ? `<@${interaction.user.id}> <@&${settings.staff_role_id}>` : `<@${interaction.user.id}>`,
      allowedMentions: { users: [interaction.user.id], roles: settings.staff_role_id ? [settings.staff_role_id] : [] },
      embeds: [new EmbedBuilder().setColor(0xff66aa).setTitle("Support ticket").setDescription(interaction.fields.getTextInputValue("topic"))],
      components: [row],
    });
  } catch (error) {
    if (thread) {
      await pool.query("DELETE FROM tickets WHERE thread_id = $1 AND closed_at IS NULL", [thread.id]).catch(() => {});
      await thread.setLocked(true).catch(() => {});
      await thread.setArchived(true).catch(() => {});
    }
    if ((error as { code?: string }).code === "23505") {
      const open = (await pool.query(
        "SELECT thread_id FROM tickets WHERE guild_id = $1 AND opener_id = $2 AND closed_at IS NULL",
        [guild.id, interaction.user.id])).rows[0];
      if (open) return interaction.editReply(`You already have an open ticket: https://discord.com/channels/${guild.id}/${open.thread_id}`);
    }
    throw error;
  }
  const link = `https://discord.com/channels/${guild.id}/${thread!.id}`;
  await sendLog(guild, settings.log_channel_id, logEmbed("Ticket opened", `<@${interaction.user.id}>`, interaction.user.tag, "New support ticket"));
  return interaction.editReply(`Your ticket is open: ${link}`);
}

export async function handleTicketCommand(interaction: ChatInputCommandInteraction) {
  if (interaction.commandName === "ticket-panel") return ticketPanel(interaction);
  return false;
}
