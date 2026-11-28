// Posts open LFG lobbies to the channel picked with /lfg-channel and takes the post down when the lobby closes.
// Joining still happens in the mod: the post only says who hosts what.
import {
  ChannelType, EmbedBuilder, PermissionFlagsBits, SlashCommandBuilder,
  type ChatInputCommandInteraction, type Client,
} from "discord.js";
import { pool } from "../db/pool";
import { events, type LobbyEvent } from "../lib/events";
import { NOTICE, TAG_LABEL, setLobbyDiscordMessage, type LobbyTag } from "../services/lfg";

export const lfgCommands = [
  new SlashCommandBuilder().setName("lfg-channel").setDescription("Where Better Battle posts open LFG lobbies")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
    .addChannelOption((o) => o.setName("channel").setDescription("Leave out to stop posting lobbies")
      .addChannelTypes(ChannelType.GuildText)),
];

const TAG_COLOUR: Record<string, number> = {
  teams: 0x55ff55, solo: 0x55ffff, gtb: 0xffaa00, spb: 0xff5555, pro: 0xaa00aa, other: 0xaaaaaa,
};
const REGION: Record<string, string> = { any: "Any region", na: "NA", eu: "EU", as: "Asia", oc: "Oceania", sa: "South America" };

export function lobbyEmbed(l: LobbyEvent) {
  const host = l.members.find((m) => m.host)?.name ?? "?";
  const tag = TAG_LABEL[l.tag as LobbyTag] ?? l.tag;
  const e = new EmbedBuilder()
    .setColor(TAG_COLOUR[l.tag] ?? 0xaaaaaa)
    .setTitle(`${l.locked ? "🔒 " : ""}${l.name}`)
    .addFields(
      { name: "Tag", value: `\`${tag}\``, inline: true },
      { name: "Host", value: host.replace(/_/g, "\\_"), inline: true },
      { name: "Players", value: `${l.members.length} / ${l.maxSize}`, inline: true },
      { name: "Region", value: REGION[l.region] ?? l.region, inline: true },
    )
    .setFooter({ text: "Join from the mod: /bb lfg · " + NOTICE })
    .setTimestamp(new Date());
  if (l.note) e.setDescription(l.note);
  return e;
}

async function channelFor(client: Client, guildId: string) {
  const s = await pool.query("SELECT lfg_channel_id FROM bot_settings WHERE guild_id = $1", [guildId]);
  const id = s.rows[0]?.lfg_channel_id as string | undefined;
  if (!id) return null;
  const channel = await client.channels.fetch(id).catch(() => null);
  if (!channel?.isTextBased() || !("send" in channel) || !("messages" in channel)) return null;
  return channel;
}

/** Discord work per lobby runs in order, so a close never overtakes the post it should remove. */
const chains = new Map<string, Promise<unknown>>();
function inOrder(id: string, job: () => Promise<unknown>) {
  const next = (chains.get(id) ?? Promise.resolve()).then(job).catch((e) => console.error(`Discord lfg post failed: ${e?.message ?? e}`));
  chains.set(id, next);
  void next.finally(() => { if (chains.get(id) === next) chains.delete(id); });
}

/** The post's id as saved after the first post (the event may predate it). */
async function messageId(lobbyId: string) {
  const r = await pool.query("SELECT discord_message_id FROM lfg_lobbies WHERE id = $1", [lobbyId]);
  return (r.rows[0]?.discord_message_id as string | null) ?? null;
}

export function startLfgPosts(client: Client, guildId: string) {
  events.on("lobbyOpened", (l: LobbyEvent) => inOrder(l.id, async () => {
    const channel = await channelFor(client, guildId);
    if (!channel) return;
    const msg = await (channel as any).send({ embeds: [lobbyEmbed(l)] });
    await setLobbyDiscordMessage(l.id, msg.id);
  }));
  events.on("lobbyUpdated", (l: LobbyEvent) => inOrder(l.id, async () => {
    const id = await messageId(l.id);
    const channel = id && await channelFor(client, guildId);
    if (!channel) return;
    const msg = await (channel as any).messages.fetch(id).catch(() => null);
    if (msg) await msg.edit({ embeds: [lobbyEmbed(l)] });
  }));
  events.on("lobbyClosed", (l: LobbyEvent) => inOrder(l.id, async () => {
    const id = await messageId(l.id);
    if (!id) return;
    const channel = await channelFor(client, guildId);
    if (channel) await (channel as any).messages.delete(id).catch(() => {});
    await setLobbyDiscordMessage(l.id, null);
  }));
}

export async function handleLfgCommand(interaction: ChatInputCommandInteraction) {
  if (interaction.commandName !== "lfg-channel") return false;
  const guild = interaction.guild;
  if (!guild) return interaction.reply({ content: "Use this command in a server.", ephemeral: true });
  const channel = interaction.options.getChannel("channel");
  await pool.query(
    `INSERT INTO bot_settings (guild_id, lfg_channel_id) VALUES ($1, $2)
     ON CONFLICT (guild_id) DO UPDATE SET lfg_channel_id = EXCLUDED.lfg_channel_id`, [guild.id, channel?.id ?? null]);
  return interaction.reply({
    content: channel ? `Open LFG lobbies will be posted in <#${channel.id}>.` : "Stopped posting LFG lobbies.",
    ephemeral: true,
  });
}
