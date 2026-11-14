import path from "node:path";
import { fileURLToPath } from "node:url";
import { createCanvas, GlobalFonts, loadImage } from "@napi-rs/canvas";

export type StatsCardData = {
  name: string;
  uuid: string;
  score: number | null;
  title: { current: { name: string; hex: string; bold: boolean }; next: { name: string; min: number } | null; into: number; span: number; pct: number };
  rank: number | null;
  rankedPlayers: number | null;
  hypixel: {
    wins: number | null;
    gamesPlayed: number | null;
    correctGuesses: number | null;
    modeWins: { solo: number | null; teams: number | null; pro: number | null; gtb: number | null; speedBuilders: number | null };
  };
  betterBattle: null | {
    level: number;
    xpIntoLevel: number;
    xpForNext: number;
    crowdScore: number | null;
    grades: { SS: number; S: number; A: number };
    themesBuilt: number;
  };
};

const W = 1200;
const H = 630;
const fontPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../assets/fonts/PixelifySans-Variable.ttf");
GlobalFonts.registerFromPath(fontPath, "Pixelify Sans");
const display = '"Pixelify Sans", "Courier New", monospace';
const body = 'Arial, "DejaVu Sans", sans-serif';
const colors = { bg: "#17171a", panel: "#1f1f24", panel2: "#141416", line: "#3a3a42", text: "#f0f0f0", soft: "#c6c6c6", muted: "#9d9da6", gold: "#ffaa00", pink: "#ff66aa" };

function panel(ctx: ReturnType<ReturnType<typeof createCanvas>["getContext"]>, x: number, y: number, w: number, h: number, fill = colors.panel) {
  ctx.fillStyle = fill;
  ctx.fillRect(x, y, w, h);
  ctx.strokeStyle = "#000";
  ctx.lineWidth = 4;
  ctx.strokeRect(x + 2, y + 2, w - 4, h - 4);
  ctx.strokeStyle = "#3a3a42";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(x + 7, y + 7); ctx.lineTo(x + w - 7, y + 7);
  ctx.moveTo(x + 7, y + 7); ctx.lineTo(x + 7, y + h - 7);
  ctx.stroke();
}

function fitText(ctx: any, text: string, x: number, y: number, maxWidth: number, size: number, color: string, family = display, weight = 600, minSize = 18) {
  let current = size;
  ctx.font = `${weight} ${current}px ${family}`;
  while (ctx.measureText(text).width > maxWidth && current > minSize) {
    current -= 1;
    ctx.font = `${weight} ${current}px ${family}`;
  }
  ctx.fillStyle = color;
  ctx.fillText(text, x, y);
}

function number(value: number | null | undefined) {
  return value === null || value === undefined ? "–" : value.toLocaleString("en-US");
}

function label(ctx: any, text: string, x: number, y: number) {
  ctx.font = `700 17px ${body}`;
  ctx.fillStyle = colors.gold;
  ctx.fillText(text, x, y);
}

async function avatarFor(uuid: string) {
  try {
    const response = await fetch(`https://mc-heads.net/avatar/${encodeURIComponent(uuid)}/128`, { signal: AbortSignal.timeout(3000) });
    if (!response.ok) return null;
    return await loadImage(Buffer.from(await response.arrayBuffer()));
  } catch {
    return null;
  }
}

export async function renderStatsCard(data: StatsCardData): Promise<Buffer> {
  const canvas = createCanvas(W, H);
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = colors.bg;
  ctx.fillRect(0, 0, W, H);
  panel(ctx, 20, 20, W - 40, H - 40);

  const avatar = await avatarFor(data.uuid);
  panel(ctx, 46, 42, 130, 130, colors.panel2);
  if (avatar) ctx.drawImage(avatar, 58, 54, 106, 106);
  else {
    ctx.fillStyle = "#35353d";
    ctx.fillRect(58, 54, 106, 106);
    ctx.fillStyle = colors.muted;
    ctx.fillRect(88, 75, 44, 44);
    ctx.fillRect(78, 122, 64, 30);
  }

  fitText(ctx, data.name, 200, 88, 640, 52, colors.text, display, 600, 40);
  const title = data.title.current;
  fitText(ctx, title.name, 202, 120, 580, 22, title.hex, body, title.bold ? 700 : 500);
  ctx.font = `14px ${body}`;
  ctx.fillStyle = colors.soft;
  const progressText = data.title.next
    ? `${number(data.title.into)} / ${number(data.title.span)} to ${data.title.next.name}`
    : "Maximum title";
  ctx.fillText(progressText, 202, 146);
  panel(ctx, 202, 155, 570, 14, colors.panel2);
  ctx.fillStyle = colors.pink;
  ctx.fillRect(208, 159, Math.max(0, Math.min(1, data.title.pct)) * 558, 6);

  const wins = data.hypixel.wins;
  const games = data.hypixel.gamesPlayed;
  const winRate = wins !== null && games !== null && games > 0 ? `${(wins / games * 100).toFixed(1)}%` : "–";
  const stats = [
    ["Score", number(data.score)],
    ["Global rank", data.rank && data.rank > 0 ? `#${number(data.rank)} / ${number(data.rankedPlayers)}` : "Unranked"],
    ["Wins", number(wins)],
    ["Games", number(games)],
    ["Win rate", winRate],
  ];
  const statX = 46;
  const statY = 188;
  const statGap = 12;
  const statW = 212;
  for (let i = 0; i < stats.length; i++) {
    const x = statX + i * (statW + statGap);
    panel(ctx, x, statY, statW, 86, colors.panel2);
    label(ctx, stats[i][0], x + 14, statY + 27);
    if (i === 1) {
      fitText(ctx, stats[i][1], x + 14, statY + 66, statW - 28, 21, colors.text, body, 700);
    } else {
      fitText(ctx, stats[i][1], x + 14, statY + 68, statW - 28, 40, colors.text, display, 600, 40);
    }
  }

  label(ctx, "MODE WINS", 48, 302);
  const modes: [string, number | null][] = [
    ["Solo", data.hypixel.modeWins.solo], ["Teams", data.hypixel.modeWins.teams],
    ["Pro", data.hypixel.modeWins.pro], ["Guess the Build", data.hypixel.modeWins.gtb],
    ["Speed Builders", data.hypixel.modeWins.speedBuilders],
  ];
  const modeW = 212;
  for (let i = 0; i < modes.length; i++) {
    const x = statX + i * (modeW + statGap);
    panel(ctx, x, 314, modeW, 78, colors.panel2);
    fitText(ctx, modes[i][0], x + 13, 342, modeW - 26, 16, colors.soft, body, 700);
    fitText(ctx, number(modes[i][1]), x + 13, 377, modeW - 26, 24, colors.text, body, 700);
  }
  ctx.font = `15px ${body}`;
  ctx.fillStyle = colors.soft;
  ctx.fillText(`Correct guesses: ${number(data.hypixel.correctGuesses)}`, 48, 412);

  panel(ctx, 46, 425, 1108, 130, colors.panel2);
  if (data.betterBattle) {
    const bb = data.betterBattle;
    label(ctx, "BETTER BATTLE", 64, 451);
    ctx.font = `700 20px ${body}`;
    ctx.fillStyle = colors.text;
    ctx.fillText(`Level ${number(bb.level)}`, 64, 482);
    ctx.fillStyle = colors.muted;
    ctx.font = `15px ${body}`;
    ctx.fillText(`${number(bb.xpIntoLevel)} / ${number(bb.xpForNext)} XP`, 64, 506);
    panel(ctx, 64, 516, 350, 14, colors.bg);
    ctx.fillStyle = colors.pink;
    ctx.fillRect(70, 520, Math.max(0, Math.min(1, bb.xpForNext ? bb.xpIntoLevel / bb.xpForNext : 0)) * 338, 6);
    const crowd = bb.crowdScore === null ? "–" : `${(bb.crowdScore * 100).toFixed(1)}%`;
    ctx.strokeStyle = colors.line;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(454, 442); ctx.lineTo(454, 538);
    ctx.moveTo(700, 442); ctx.lineTo(700, 538);
    ctx.stroke();
    label(ctx, "CROWD SCORE", 482, 451);
    fitText(ctx, crowd, 482, 492, 190, 28, colors.soft, body, 700);
    label(ctx, "GRADES", 730, 451);
    fitText(ctx, `SS ${number(bb.grades.SS)}   S ${number(bb.grades.S)}   A ${number(bb.grades.A)}`, 730, 482, 392, 20, colors.soft, body, 700);
    fitText(ctx, `Themes built: ${number(bb.themesBuilt)}`, 730, 520, 392, 17, colors.muted, body, 600);
  } else {
    ctx.font = `18px ${body}`;
    ctx.fillStyle = colors.soft;
    ctx.fillText("Get the mod at battle.aspir3.net for levels and grades", 64, 494);
  }

  ctx.font = `14px ${body}`;
  ctx.fillStyle = colors.muted;
  ctx.fillText(`battle.aspir3.net/p/${data.name}`, 48, 588);
  return canvas.toBuffer("image/png");
}
