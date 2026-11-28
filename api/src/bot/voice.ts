// The Discord side of temporary voice channels (see services/voice.ts for the rules).
// Channels are private: @everyone can't see or join them, only the members who asked from their own mod.
import {
  ChannelType,
  PermissionFlagsBits,
  type Client,
  type Guild,
  type OverwriteResolvable,
} from "discord.js";
import { setVoiceBackend, sweepVoice, type VoiceBackend } from "../services/voice";

const CATEGORY_NAME = "Better Battle voice";
const MEMBER_ALLOW = [
  PermissionFlagsBits.ViewChannel,
  PermissionFlagsBits.Connect,
  PermissionFlagsBits.Speak,
  PermissionFlagsBits.UseVAD,
];

function overwrites(guild: Guild, discordIds: string[]): OverwriteResolvable[] {
  const botId = guild.client.user!.id;
  return [
    { id: guild.roles.everyone.id, deny: [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.Connect] },
    { id: botId, allow: [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.Connect, PermissionFlagsBits.ManageChannels, PermissionFlagsBits.MoveMembers] },
    ...discordIds.map((id) => ({ id, allow: MEMBER_ALLOW })),
  ];
}

async function categoryId(guild: Guild): Promise<string> {
  const configured = process.env.DISCORD_VOICE_CATEGORY_ID;
  if (configured) return configured;
  const channels = await guild.channels.fetch();
  const found = channels.find((c) => c?.type === ChannelType.GuildCategory && c.name === CATEGORY_NAME);
  if (found) return found.id;
  const made = await guild.channels.create({ name: CATEGORY_NAME, type: ChannelType.GuildCategory });
  return made.id;
}

export function startVoice(client: Client, guildId: string) {
  const guild = async () => client.guilds.cache.get(guildId) ?? client.guilds.fetch(guildId);
  const backend: VoiceBackend = {
    guildId,
    inviteUrl: process.env.DISCORD_INVITE_URL || null,
    async isMember(discordId) {
      const g = await guild();
      return !!await g.members.fetch(discordId).catch(() => null);
    },
    async create(label, discordIds) {
      const g = await guild();
      const channel = await g.channels.create({
        name: label,
        type: ChannelType.GuildVoice,
        parent: await categoryId(g),
        userLimit: Math.min(Math.max(discordIds.length, 2), 99),
        permissionOverwrites: overwrites(g, discordIds),
        reason: "Better Battle voice for players who chose each other",
      });
      return channel.id;
    },
    async setMembers(channelId, discordIds) {
      const g = await guild();
      const channel = await g.channels.fetch(channelId).catch(() => null);
      if (!channel || channel.type !== ChannelType.GuildVoice) return;
      await channel.permissionOverwrites.set(overwrites(g, discordIds));
      await channel.setUserLimit(Math.min(Math.max(discordIds.length, 2), 99));
      // Anyone connected who isn't allowed any more (left the group, took back 18+) is disconnected
      const allowed = new Set(discordIds);
      for (const [id, member] of channel.members) {
        if (!allowed.has(id) && id !== client.user?.id) await member.voice.disconnect().catch(() => {});
      }
    },
    async remove(channelId) {
      const g = await guild();
      const channel = await g.channels.fetch(channelId).catch(() => null);
      if (channel) await channel.delete("Better Battle voice ended").catch(() => {});
    },
    async connected(channelId) {
      const g = await guild();
      const channel = await g.channels.fetch(channelId).catch((e) => (e?.code === 10003 ? null : undefined));
      if (channel === null) return null;                       // Unknown Channel: it's gone
      if (channel === undefined) return 1;                     // couldn't ask Discord: keep it for now
      if (channel.type !== ChannelType.GuildVoice) return null;
      return channel.members.size;
    },
  };
  setVoiceBackend(backend);
  const timer = setInterval(() => void sweepVoice().catch((e) => console.error("voice sweep failed", e)), 60_000);
  timer.unref();
  void sweepVoice().catch(() => {});
}
