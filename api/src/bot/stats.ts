// The stats side of the bot: leaderboards, comparisons, Speed Builders records, weekly challenges,
// recent games and plans. Everything here reads the same data the website shows.
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  EmbedBuilder,
  PermissionFlagsBits,
  SlashCommandBuilder,
  type AutocompleteInteraction,
  type ButtonInteraction,
  type ChatInputCommandInteraction,
  type Client,
} from "discord.js";
import { pool } from "../db/pool";
import { events, type RecordBroken } from "../lib/events";
import { FEATURES, PERKS, PLANS, can, limit, planOf, type Plan } from "../lib/plans";
import { siteOrigin } from "../lib/site";
import { BOARDS, boardById, boardPage, positions } from "../services/boards";
import { buildLeaderboard, worldRecords } from "../services/speedBuilders";
import { onPlanChange } from "../services/stripe";
import { weeklyProgress } from "../services/weekly";
import { resolveStatsName } from "./data";

const GOLD = 0xffaa00;
const PINK = 0xff66aa;
const MODES: Record<string, string> = { solo: "Solo", teams: "Teams", pro: "Pro", gtb: "Guess the Build", speed_builders: "Speed Builders" };

const num = (n: number | null | undefined) => (n == null ? "–" : Number(n).toLocaleString("en-US"));
const secs = (ms: number) => (ms / 1000).toFixed(2) + "s";
const head = (uuid: string) => `https://mc-heads.net/avatar/${uuid}/128`;
const site = (path = "") => siteOrigin() + path;
const medal = (rank: number) => (["🥇", "🥈", "🥉"][rank - 1] ?? `\`#${num(rank)}\``);

function embed(title: string, color = GOLD) {
  return new EmbedBuilder().setColor(color).setTitle(title).setFooter({ text: "Better Battle · battle.aspir3.net" });
}

function linkRow(...links: [label: string, url: string][]) {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    links.map(([label, url]) => new ButtonBuilder().setLabel(label).setStyle(ButtonStyle.Link).setURL(url)));
}

const playerOption = (description = "Minecraft username (yours if left empty)") =>
  (option: any) => option.setName("player").setDescription(description).setMaxLength(16).setAutocomplete(true);

export const statsCommands = [
  new SlashCommandBuilder().setName("leaderboards").setDescription("Where you stand on every Build Battle leaderboard")
    .addStringOption(playerOption()),
  new SlashCommandBuilder().setName("top").setDescription("The top players on any Build Battle leaderboard")
    .addStringOption((o) => o.setName("board").setDescription("Which leaderboard").setRequired(true)
      .addChoices(...BOARDS.map((b) => ({ name: b.name, value: b.id })))),
  new SlashCommandBuilder().setName("compare").setDescription("Compare Build Battle players side by side")
    .addStringOption((o) => o.setName("player1").setDescription("First player").setRequired(true).setMaxLength(16).setAutocomplete(true))
    .addStringOption((o) => o.setName("player2").setDescription("Second player (you if left empty)").setMaxLength(16).setAutocomplete(true))
    .addStringOption((o) => o.setName("player3").setDescription("Third player (Master Builder)").setMaxLength(16).setAutocomplete(true))
    .addStringOption((o) => o.setName("player4").setDescription("Fourth player (Master Builder)").setMaxLength(16).setAutocomplete(true)),
  new SlashCommandBuilder().setName("records").setDescription("Speed Builders world records")
    .addStringOption((o) => o.setName("build").setDescription("A build, for its top times").setMaxLength(48).setAutocomplete(true)),
  new SlashCommandBuilder().setName("weekly").setDescription("This week's challenges and your progress")
    .addStringOption(playerOption()),
  new SlashCommandBuilder().setName("recent").setDescription("A player's latest Better Battle games")
    .addStringOption(playerOption()),
  new SlashCommandBuilder().setName("plans").setDescription("Better Battle plans, and which one you have"),
  new SlashCommandBuilder().setName("help").setDescription("What this bot can do"),
  new SlashCommandBuilder().setName("feed").setDescription("Choose a channel for new Speed Builders world records")
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
    .addChannelOption((o) => o.setName("channel").setDescription("Leave empty to turn the feed off").addChannelTypes(ChannelType.GuildText)),
];

// ---------------------------------------------------------------- who is asking, who are they asking about

/** The plan of the website account connected to this Discord user (free if there isn't one). */
async function planFor(discordId: string): Promise<Plan> {
  const r = await pool.query("SELECT plan, plan_until, plan_comped FROM users WHERE discord_id = $1", [discordId]);
  return planOf(r.rows[0]);
}

interface Player { uuid: string; name: string; score: number; wins: number; games: number; hyp: Record<string, number>; userId: number | null; level: number | null; ap: number | null; plan: Plan }

/** A player we already track. The bot never calls Hypixel for these commands; /stats looks up new players. */
async function findPlayer(name: string): Promise<Player | null> {
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) return null;
  const r = await pool.query(
    `SELECT t.mc_uuid, t.mc_name, t.bb_score, t.bb_wins, t.bb_games, t.hypixel_bb,
            u.id AS user_id, u.plan, u.plan_until, u.plan_comped, p.level, p.ap
     FROM tracked_players t
     LEFT JOIN users u ON u.mc_uuid = t.mc_uuid
     LEFT JOIN user_progress p ON p.user_id = u.id
     WHERE t.mc_name = $1 AND t.last_checked IS NOT NULL AND NOT t.no_bb_stats
     ORDER BY t.last_checked DESC LIMIT 1`, [name]);
  const x = r.rows[0];
  if (!x) return null;
  return {
    uuid: x.mc_uuid, name: x.mc_name, score: x.bb_score ?? 0, wins: x.bb_wins ?? 0, games: x.bb_games ?? 0,
    hyp: x.hypixel_bb ?? {}, userId: x.user_id ?? null, level: x.level ?? null, ap: x.ap ?? null, plan: planOf(x),
  };
}

/** The player option, or the asker's own linked account. Replies and returns null when there's nobody. */
async function target(interaction: ChatInputCommandInteraction, option = "player"): Promise<Player | null> {
  const given = interaction.options.getString(option);
  const name = given ?? await resolveStatsName(interaction.user.id);
  if (!name) {
    await interaction.reply({ content: "Run `/verify <name>` to link your Minecraft account, or give a player name.", ephemeral: true });
    return null;
  }
  const player = await findPlayer(name);
  if (!player) {
    await interaction.reply({ content: `I don't have **${name.slice(0, 16)}** yet. Run \`/stats ${name.slice(0, 16)}\` once to look them up, then try again.`, ephemeral: true });
    return null;
  }
  return player;
}

const supporterTag = (plan: Plan) => (can(plan, "badge") ? ` · ${plan === "master" ? "👑" : "⭐"} ${PLANS[plan].name}` : "");

// ---------------------------------------------------------------- /leaderboards

async function leaderboards(interaction: ChatInputCommandInteraction) {
  const player = await target(interaction);
  if (!player) return;
  const [plan, all] = await Promise.all([planFor(interaction.user.id), positions(player.uuid)]);
  const unlocked = can(plan, "leaderboardPositions");
  const e = embed(`${player.name}'s leaderboard positions`)
    .setURL(site(`/p/${player.name}`))
    .setThumbnail(head(player.uuid));

  const line = (b: (typeof all)[number]) => {
    if (b.rank === null) return `▫️ **${b.short}** · not ranked`;
    // "top 4%" only means something on a board with a real crowd on it
    const pct = b.top === null || b.of < 100 ? ` · of ${num(b.of)}` : ` · top ${b.top < 0.01 ? "<1" : Math.ceil(b.top * 100)}%`;
    return `${medal(b.rank)} **${b.short}** · ${num(b.value)}${pct}`;
  };

  if (!unlocked) {
    const score = all.find((b) => b.id === "score")!;
    e.setDescription(`${line(score)}\n\n🔒 **${all.length - 1} more leaderboards** (wins, tokens, every mode, Speed Builders and more) come with **${PLANS[FEATURES.leaderboardPositions].name}**.`)
      .setColor(PINK);
    return interaction.reply({ embeds: [e], components: [linkRow(["See plans", site("/plans")], ["Full profile", site(`/p/${player.name}`)])] });
  }

  for (const group of ["Overall", "Modes", "Better Battle"] as const) {
    const rows = all.filter((b) => b.group === group && (group !== "Better Battle" || b.rank !== null));
    if (rows.length) e.addFields({ name: group, value: rows.map(line).join("\n") });
  }
  const best = all.filter((b) => b.rank !== null).sort((a, b) => a.top! - b.top!)[0];
  if (best) e.setDescription(`Best board: **${best.name}**, #${num(best.rank)} of ${num(best.of)}${supporterTag(player.plan)}`);
  return interaction.reply({ embeds: [e], components: [linkRow(["Full profile", site(`/p/${player.name}`)], ["All leaderboards", site("/leaderboard")])] });
}

// ---------------------------------------------------------------- /top (with page buttons)

const TOP_PER = 10;

async function topMessage(boardId: string, page: number) {
  const board = boardById(boardId)!;
  const { total, rows } = await boardPage(boardId, page, TOP_PER);
  const pages = Math.max(1, Math.ceil(total / TOP_PER));
  const e = embed(`Top players · ${board.name}`).setURL(site(`/leaderboard?board=${board.id}`));
  e.setDescription(rows.length
    ? rows.map((r) => `${medal(r.rank)} **${r.info?.mc_name ?? "Unknown"}**${can(planOf(r.info), "badge") ? " ⭐" : ""} · ${num(r.value)}`).join("\n")
    : "Nobody is on this leaderboard yet.");
  e.setFooter({ text: `Page ${page} of ${num(pages)} · ${num(total)} players · Better Battle` });
  const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder().setCustomId(`bb:top:${board.id}:${page - 1}`).setLabel("Previous").setStyle(ButtonStyle.Secondary).setDisabled(page <= 1),
    new ButtonBuilder().setCustomId(`bb:top:${board.id}:${page + 1}`).setLabel("Next").setStyle(ButtonStyle.Secondary).setDisabled(page >= pages),
    new ButtonBuilder().setLabel("Open on the site").setStyle(ButtonStyle.Link).setURL(site(`/leaderboard?board=${board.id}`)),
  );
  return { embeds: [e], components: [row] };
}

async function top(interaction: ChatInputCommandInteraction) {
  return interaction.reply(await topMessage(interaction.options.getString("board", true), 1));
}

// ---------------------------------------------------------------- /compare

async function compare(interaction: ChatInputCommandInteraction) {
  const names = ["player1", "player2", "player3", "player4"].map((o) => interaction.options.getString(o)).filter((n): n is string => !!n);
  if (names.length === 1) {
    const mine = await resolveStatsName(interaction.user.id);
    if (!mine) return interaction.reply({ content: "Give two players, or run `/verify <name>` so I know who you are.", ephemeral: true });
    names.push(mine);
  }
  const plan = await planFor(interaction.user.id);
  const max = limit(plan, "comparePlayers");
  if (names.length > max) {
    return interaction.reply({
      content: `Comparing more than ${max} players comes with **${PLANS[FEATURES.compareMany].name}**.`,
      components: [linkRow(["See plans", site("/plans")])], ephemeral: true,
    });
  }
  const players: Player[] = [];
  for (const n of [...new Set(names.map((x) => x.toLowerCase()))]) {
    const p = await findPlayer(n);
    if (!p) return interaction.reply({ content: `I don't have **${n.slice(0, 16)}** yet. Run \`/stats ${n.slice(0, 16)}\` once, then try again.`, ephemeral: true });
    players.push(p);
  }
  if (players.length < 2) return interaction.reply({ content: "Pick two different players.", ephemeral: true });

  const rows: [string, (p: Player) => number | null][] = [
    ["Score", (p) => p.score], ["Wins", (p) => p.wins], ["Games", (p) => p.games],
    ["Solo wins", (p) => p.hyp.wins_solo_normal ?? null], ["Teams wins", (p) => p.hyp.wins_teams_normal ?? null],
    ["Pro wins", (p) => p.hyp.wins_solo_pro ?? null], ["GTB wins", (p) => p.hyp.wins_guess_the_build ?? null],
    ["SPB wins", (p) => p.hyp.wins_speed_builders ?? null], ["Guesses", (p) => p.hyp.correct_guesses ?? null],
    ["Level", (p) => p.level], ["Points", (p) => p.ap],
  ];
  const col = Math.max(9, ...players.map((p) => p.name.length + 1));
  const pad = (s: string, w: number) => s.padStart(w);
  let table = "".padEnd(11) + players.map((p) => pad(p.name, col)).join("") + "\n";
  const leads = players.map(() => 0);
  for (const [label, get] of rows) {
    const values = players.map(get);
    const best = Math.max(...values.map((v) => v ?? -1));
    const winners = values.filter((v) => v === best).length;
    table += label.padEnd(11) + values.map((v, i) => {
      const lead = v !== null && v === best && winners === 1;
      if (lead) leads[i]++;
      return pad((lead ? "▲" : "") + num(v), col);
    }).join("") + "\n";
  }
  const ahead = Math.max(...leads);
  const leader = players[leads.indexOf(ahead)];
  const e = embed(players.map((p) => p.name).join(" vs "))
    .setDescription("```\n" + table + "```\n" + (leads.filter((l) => l === ahead).length === 1 ? `**${leader.name}** leads in ${ahead} of ${rows.length}.` : ""));
  if (players.length === 2) e.setURL(site(`/compare/${players[0].name}/${players[1].name}`));
  return interaction.reply({
    embeds: [e],
    components: players.length === 2 ? [linkRow(["Full comparison", site(`/compare/${players[0].name}/${players[1].name}`)])] : [],
  });
}

// ---------------------------------------------------------------- /records

async function records(interaction: ChatInputCommandInteraction) {
  const build = interaction.options.getString("build");
  if (build) {
    const id = build.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 48);
    const board = id ? await buildLeaderboard(id, 10) : null;
    if (!board) return interaction.reply({ content: "No times on that build yet.", ephemeral: true });
    const record = board.rows[0];
    const e = embed(`Speed Builders · ${board.name}`).setURL(site(`/speed-builders/${board.build}`))
      .setDescription(board.rows.map((r) =>
        `${medal(r.rank)} **${r.name}** · ${secs(r.timeMs)}${r.rank === 1 ? "" : ` · +${secs(r.timeMs - record.timeMs)}`}`).join("\n") || "No times yet.");
    return interaction.reply({ embeds: [e], components: [linkRow(["Full leaderboard", site(`/speed-builders/${board.build}`)])] });
  }
  const all = await worldRecords();
  const e = embed("Speed Builders world records").setURL(site("/speed-builders"))
    .setDescription(all.length
      ? all.slice(0, 15).map((r) => `**${r.name}** · ${secs(r.timeMs)} · ${r.holder ?? "–"}`).join("\n")
        + (all.length > 15 ? `\n…and ${all.length - 15} more builds.` : "")
      : "No records yet. They appear as Better Battle players finish Speed Builders rounds.");
  return interaction.reply({ embeds: [e], components: [linkRow(["All records", site("/speed-builders")])] });
}

// ---------------------------------------------------------------- /weekly

const bar = (progress: number, target: number) => {
  const full = Math.round(Math.min(1, progress / target) * 10);
  return "▰".repeat(full) + "▱".repeat(10 - full);
};

async function weekly(interaction: ChatInputCommandInteraction) {
  const player = await target(interaction);
  if (!player) return;
  if (!player.userId) {
    return interaction.reply({ content: `**${player.name}** doesn't use the Better Battle mod, so there's no weekly progress. \`/download\` has the mod.`, ephemeral: true });
  }
  const w = await weeklyProgress(pool, player.userId);
  const ends = Math.floor(new Date(w.weekEndsAt).getTime() / 1000);
  const e = embed(`${player.name}'s weekly challenges`).setThumbnail(head(player.uuid))
    .setDescription((w.weekly.length
      ? w.weekly.map((c) => `${c.done ? "✅" : "▫️"} **${c.text}** · +${num(c.xp)} XP\n${bar(c.progress, c.target)} ${num(c.progress)} / ${num(c.target)}`).join("\n\n")
      : "No challenges yet this week.") + `\n\nNew challenges <t:${ends}:R>.`);
  return interaction.reply({ embeds: [e] });
}

// ---------------------------------------------------------------- /recent

async function recent(interaction: ChatInputCommandInteraction) {
  const player = await target(interaction);
  if (!player) return;
  const r = player.userId
    ? await pool.query(
        `SELECT played_at, mode, theme, placement, players, grade, crowd_score, xp_earned
         FROM matches WHERE user_id = $1 ORDER BY played_at DESC LIMIT 8`, [player.userId])
    : { rows: [] as any[] };
  if (!r.rows.length) {
    return interaction.reply({ content: `No Better Battle games for **${player.name}** yet. Games show up once they play with the mod.`, ephemeral: true });
  }
  const e = embed(`${player.name}'s recent games`).setURL(site(`/p/${player.name}`)).setThumbnail(head(player.uuid))
    .setDescription(r.rows.map((m: any) => {
      const place = m.placement === 1 ? "🏆 **Win**" : m.placement ? `#${m.placement}${m.players ? ` of ${m.players}` : ""}` : "–";
      const bits = [place, m.grade ? `Grade ${m.grade}` : null, m.crowd_score != null ? `${Math.round(m.crowd_score * 100)}% crowd` : null,
        m.xp_earned ? `+${m.xp_earned} XP` : null].filter(Boolean).join(" · ");
      return `**${m.theme ?? MODES[m.mode] ?? m.mode}** · ${MODES[m.mode] ?? m.mode} · <t:${Math.floor(new Date(m.played_at).getTime() / 1000)}:R>\n${bits}`;
    }).join("\n\n"));
  return interaction.reply({ embeds: [e], components: [linkRow(["Full profile", site(`/p/${player.name}`)])] });
}

// ---------------------------------------------------------------- /plans, /help, /feed

async function plans(interaction: ChatInputCommandInteraction) {
  const mine = await pool.query("SELECT plan, plan_until, plan_comped, plan_cancels FROM users WHERE discord_id = $1", [interaction.user.id]);
  const plan = planOf(mine.rows[0]);
  const e = embed("Better Battle plans", PINK)
    .setDescription("The mod, every achievement, the Speed Builders timer and the rankings are free for everyone, and always will be. Plans add looks and deeper stats on the website and here in Discord. Nothing in a game is ever paid.");
  for (const id of ["free", "architect", "master"] as Plan[]) {
    e.addFields({ name: `${id === plan ? "✅ " : ""}${PLANS[id].name}${id === "free" ? " · free" : ""}`, value: PERKS[id].map((p) => `• ${p}`).join("\n").slice(0, 1024) });
  }
  e.addFields({
    name: "Your plan",
    value: mine.rowCount
      ? `**${PLANS[plan].name}**${mine.rows[0].plan_cancels ? " (ends at the end of this period)" : ""}`
      : "No website account is connected to this Discord account. Log in at the site with Discord and your plan shows up here.",
  });
  return interaction.reply({ embeds: [e], components: [linkRow(["See plans", site("/plans")], ["My account", site("/account")])], ephemeral: true });
}

async function help(interaction: ChatInputCommandInteraction) {
  const e = embed("Better Battle bot")
    .setDescription("Build Battle stats, rankings and Speed Builders records, straight from [battle.aspir3.net](" + site() + ").")
    .addFields(
      { name: "Players", value: "`/stats` profile card\n`/leaderboards` every leaderboard position ⭐\n`/compare` side by side (up to 4 👑)\n`/recent` latest games\n`/weekly` weekly challenges" },
      { name: "Rankings", value: "`/top` top players on any of " + BOARDS.length + " leaderboards\n`/records` Speed Builders world records" },
      { name: "You", value: "`/verify` link your Minecraft account\n`/plans` plans and yours\n`/download` get the mod" },
    )
    .addFields({ name: "​", value: "⭐ Architect · 👑 Master Builder · everything else is free" });
  return interaction.reply({ embeds: [e], ephemeral: true });
}

async function feed(interaction: ChatInputCommandInteraction) {
  if (!interaction.guildId) return interaction.reply({ content: "Use this command in a server.", ephemeral: true });
  const channel = interaction.options.getChannel("channel");
  await pool.query(
    `INSERT INTO bot_settings (guild_id, feed_channel_id) VALUES ($1, $2)
     ON CONFLICT (guild_id) DO UPDATE SET feed_channel_id = EXCLUDED.feed_channel_id`,
    [interaction.guildId, channel?.id ?? null]);
  return interaction.reply({ content: channel ? `New Speed Builders world records will be posted in <#${channel.id}>.` : "The world record feed is off.", ephemeral: true });
}

// ---------------------------------------------------------------- routing

export async function handleStatsCommand(interaction: ChatInputCommandInteraction) {
  switch (interaction.commandName) {
    case "leaderboards": return leaderboards(interaction);
    case "top": return top(interaction);
    case "compare": return compare(interaction);
    case "records": return records(interaction);
    case "weekly": return weekly(interaction);
    case "recent": return recent(interaction);
    case "plans": return plans(interaction);
    case "help": return help(interaction);
    case "feed": return feed(interaction);
    default: return false;
  }
}

/** Page buttons under /top. Returns false for buttons that aren't ours. */
export async function handleStatsButton(interaction: ButtonInteraction) {
  const m = interaction.customId.match(/^bb:top:([a-z]{1,16}):(\d{1,5})$/);
  if (!m) return false;
  if (!boardById(m[1])) return interaction.reply({ content: "That leaderboard no longer exists.", ephemeral: true });
  return interaction.update(await topMessage(m[1], Math.max(1, Number(m[2]))));
}

/** Suggestions while typing a player or build name. Must answer within 3 seconds, so errors answer with nothing. */
export async function handleAutocomplete(interaction: AutocompleteInteraction) {
  try {
    const focused = interaction.options.getFocused(true);
    const q = String(focused.value).trim();
    if (focused.name === "build") {
      const r = await pool.query(
        "SELECT id, name FROM spb_builds WHERE name ILIKE $1 ORDER BY name LIMIT 20", [`%${q.replace(/[%_\\]/g, "\\$&")}%`]);
      return await interaction.respond(r.rows.map((b: any) => ({ name: String(b.name).slice(0, 100), value: b.id })));
    }
    if (!/^[A-Za-z0-9_]{1,16}$/.test(q)) return await interaction.respond([]);
    const r = await pool.query(
      `SELECT mc_name FROM tracked_players WHERE mc_name ILIKE $1 AND bb_score IS NOT NULL
       ORDER BY bb_score DESC LIMIT 10`, [q.replace(/_/g, "\\_") + "%"]);
    return await interaction.respond(r.rows.map((p: any) => ({ name: p.mc_name, value: p.mc_name })));
  } catch {
    await interaction.respond([]).catch(() => {});
  }
}

// ---------------------------------------------------------------- background: roles, record feed, status

/** Gives supporters their Discord role and takes it back when a plan ends. Roles come from .env. */
async function syncRoles(client: Client, guildId: string, userId?: number) {
  const roles: Partial<Record<Plan, string>> = {
    architect: process.env.DISCORD_ROLE_ARCHITECT || undefined,
    master: process.env.DISCORD_ROLE_MASTER || undefined,
  };
  if (!roles.architect && !roles.master) return;
  const guild = client.guilds.cache.get(guildId);
  if (!guild) return;
  const r = await pool.query(
    `SELECT discord_id, plan, plan_until, plan_comped FROM users
     WHERE discord_id IS NOT NULL AND ($1::bigint IS NULL OR id = $1)
       AND ($1::bigint IS NOT NULL OR plan <> 'free' OR stripe_customer_id IS NOT NULL)`, [userId ?? null]);
  for (const row of r.rows) {
    const member = await guild.members.fetch(row.discord_id).catch(() => null);
    if (!member) continue;
    const plan = planOf(row);
    for (const [tier, roleId] of Object.entries(roles) as [Plan, string][]) {
      if (!roleId) continue;
      // Master Builders keep the Architect role too: they have everything in it.
      const should = tier === "architect" ? can(plan, "badge") : plan === "master";
      const has = member.roles.cache.has(roleId);
      if (should && !has) await member.roles.add(roleId, "Better Battle plan").catch((e) => console.error(`Couldn't add supporter role: ${e.message}`));
      if (!should && has) await member.roles.remove(roleId, "Better Battle plan ended").catch((e) => console.error(`Couldn't remove supporter role: ${e.message}`));
    }
  }
}

async function postRecord(client: Client, r: RecordBroken) {
  const settings = await pool.query("SELECT guild_id, feed_channel_id FROM bot_settings WHERE feed_channel_id IS NOT NULL");
  for (const s of settings.rows) {
    const channel = await client.channels.fetch(s.feed_channel_id).catch(() => null);
    if (!channel?.isTextBased() || !("send" in channel)) continue;
    const e = embed("🏁 New Speed Builders world record").setURL(site(`/speed-builders/${r.build}`))
      .setDescription(`**${r.holder}** built **${r.buildName}** in **${secs(r.timeMs)}**, beating ${r.previousHolder ? `**${r.previousHolder}**'s` : "their own"} ${secs(r.previousMs)} by ${secs(r.previousMs - r.timeMs)}.`);
    await channel.send({ embeds: [e], components: [linkRow(["Leaderboard", site(`/speed-builders/${r.build}`)])] }).catch(() => {});
  }
}

async function setStatus(client: Client) {
  const r = await pool.query("SELECT count(*)::int AS n FROM tracked_players WHERE bb_score IS NOT NULL");
  client.user?.setActivity(`${num(r.rows[0].n)} builders · /help`);
}

/** Called once the bot is logged in. */
export function startStatsBackground(client: Client, guildId: string) {
  const safe = (what: string, p: Promise<unknown>) => p.catch((e) => console.error(`Discord ${what} failed: ${e?.message ?? e}`));
  void safe("role sync", syncRoles(client, guildId));
  onPlanChange((userId) => { void safe("role sync", syncRoles(client, guildId, userId)); });
  events.on("recordBroken", (r: RecordBroken) => void safe("record post", postRecord(client, r)));
  void safe("status", setStatus(client));
  setInterval(() => void safe("status", setStatus(client)), 15 * 60_000).unref();
}
