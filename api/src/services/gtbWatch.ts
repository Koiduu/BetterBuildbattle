// Guess the Build: "unusual guessing" scores from the correct guesses mod users see other players make.
//
// This is evidence for the owner to review before passing anything to Hypixel, never a verdict. Fast typists,
// familiar themes and lag all look like cheating in a few rounds, so nothing shows until a player has been
// seen over many rounds by several different mod users, and nothing public ever reads it.
//
// Framing is made hard on purpose:
//  - each reporter counts once per round (a unique key in the table);
//  - several reporters in the same round are reduced to one reading, the median, and readings far from it are
//    dropped; a round only one reporter saw counts for less;
//  - when reporters disagree, the reading kinder to the accused wins;
//  - the score is worked out again with the most damning reporters left out, and the lower score is the one used,
//    so no single reporter (or the few most damning ones, see dropTopReporters) can make a case alone.
//
// Every threshold is in GTB_WATCH so it can be tuned in one place.

export const GTB_WATCH = {
  /** Guess events older than this are deleted. */
  keepDays: 90,
  /** A player needs this many rounds (after leaving out the most damning reporters) before anything shows. */
  minRounds: 20,
  /** ...seen by at least this many different mod users. */
  minReporters: 3,
  /** The score is recomputed without this many of the most damning reporters, and the lowest score counts. */
  dropTopReporters: 1,
  /** Score (0-100) from which a case shows in the admin list's "strong" group and as a hint in game. */
  showScore: 60,

  /** A round only one reporter saw counts this much (rounds two or more reporters agree on count 1). */
  singleReporterWeight: 0.5,
  /** Readings this close to the round's median agree with it: within max(ms, ratio of the median). */
  agreeToleranceMs: 1500,
  agreeToleranceRatio: 0.25,

  /** Least time to see what the builder made and type the word: minLookMs + msPerChar for each letter. */
  minLookMs: 4000,
  msPerChar: 90,
  /** Least time to read a new hint letter and type the word: reactionMs + msPerChar for each letter. */
  reactionMs: 600,
  /** Assumed word length when it isn't known. */
  defaultLength: 8,

  /** Words this long or longer are "long"; this short or shorter are "short". */
  longWord: 10,
  shortWord: 6,
  /** A word seen in this few games or fewer (across every report) is "rare". */
  rareWordMaxGames: 2,
  /** Rounds needed on each side before the long/rare vs short comparison counts. */
  minHardRounds: 5,
  minEasyRounds: 5,
  /** Rounds with a hint letter (and its time) needed before the "fast after a letter" signal counts. */
  minRevealRounds: 5,

  /**
   * How each signal maps to 0..1: at or below `normal` is 0, at or above `strong` is 1, a straight line between.
   * For noSlowdown it's the ratio of median times (long or rare words / short ones), where lower is stranger.
   * weight: how much it counts in the score. A signal without enough data is left out, not counted as 0.
   */
  signals: {
    noHint: { normal: 0.35, strong: 0.85, weight: 3 },
    fastStart: { normal: 0.05, strong: 0.35, weight: 3 },
    fastReveal: { normal: 0.1, strong: 0.5, weight: 2 },
    noSlowdown: { normal: 1.3, strong: 0.9, weight: 1 },
  },
};

export type GtbWatchConfig = typeof GTB_WATCH;

/** One correct guess as one mod user saw it. */
export interface GuessEvent {
  reporter: number;
  gameKey: string;
  round: number;
  guesser: string;
  msSinceStart: number;
  msSinceReveal: number | null;
  revealed: number;
  wordLength: number | null;
  word: string | null;
  seenAt?: string | Date;
}

/** One round of one player, after the reporters' readings are reduced to one. */
export interface ObservedRound {
  gameKey: string;
  round: number;
  ms: number;
  msSinceReveal: number | null;
  revealed: number;
  length: number | null;
  word: string | null;
  /** Reporters whose reading was kept. */
  reporters: number[];
  /** Every reporter who saw it, kept or not. */
  allReporters: number[];
  weight: number;
  seenAt: string | null;
  flags: { noHint: boolean; fastStart: boolean; fastReveal: boolean };
}

export interface SignalResult {
  /** 0..1, or null when there isn't enough data. */
  strength: number | null;
  /** Rounds that showed it, and rounds it could have shown in (unweighted, for people to read). */
  hits: number;
  of: number;
}

export interface GtbCase {
  name: string;
  /** The score used: after leaving out the most damning reporters. 0-100. */
  score: number;
  /** With every reporter. */
  rawScore: number;
  /** Enough rounds and reporters to show anything at all. */
  eligible: boolean;
  /** Eligible and score >= showScore. */
  strong: boolean;
  rounds: number;
  reporters: number;
  /** Rounds two or more reporters agreed on. */
  confirmedRounds: number;
  medianMs: number | null;
  signals: { noHint: SignalResult; fastStart: SignalResult; fastReveal: SignalResult; noSlowdown: SignalResult & { hardMs: number | null; easyMs: number | null } };
  evidence: string[];
  /** Reporters left out of the final score. */
  droppedReporters: number[];
  detail: ObservedRound[];
}

const median = (xs: number[]): number | null => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
const ramp = (x: number, normal: number, strong: number) =>
  strong === normal ? (x >= strong ? 1 : 0) : clamp01((x - normal) / (strong - normal));

function mostCommon<T>(xs: T[]): T | null {
  const counts = new Map<T, number>();
  let best: T | null = null, bestN = 0;
  for (const x of xs) {
    const n = (counts.get(x) ?? 0) + 1;
    counts.set(x, n);
    if (n > bestN) { best = x; bestN = n; }
  }
  return best;
}

/** Fastest believable guess from the round's start, for a word this long. */
export function humanMinStartMs(length: number | null, cfg: GtbWatchConfig = GTB_WATCH) {
  return cfg.minLookMs + (length ?? cfg.defaultLength) * cfg.msPerChar;
}

/** Fastest believable guess after a new hint letter. */
export function humanMinRevealMs(length: number | null, cfg: GtbWatchConfig = GTB_WATCH) {
  return cfg.reactionMs + (length ?? cfg.defaultLength) * cfg.msPerChar;
}

/** One player's events → one reading per round. Self-reports and repeats should already be gone. */
export function observeRounds(events: GuessEvent[], cfg: GtbWatchConfig = GTB_WATCH): ObservedRound[] {
  const byRound = new Map<string, GuessEvent[]>();
  for (const e of events) {
    const key = `${e.gameKey}#${e.round}`;
    const list = byRound.get(key) ?? [];
    // Once per reporter per round, even if the caller didn't dedupe
    if (!list.some((x) => x.reporter === e.reporter)) list.push(e);
    byRound.set(key, list);
  }
  const out: ObservedRound[] = [];
  for (const list of byRound.values()) {
    const med = median(list.map((e) => e.msSinceStart))!;
    const tolerance = Math.max(cfg.agreeToleranceMs, med * cfg.agreeToleranceRatio);
    let kept = list.filter((e) => Math.abs(e.msSinceStart - med) <= tolerance);
    let weight: number;
    if (list.length === 1) {
      weight = cfg.singleReporterWeight;
    } else if (kept.length >= 2) {
      weight = 1;
    } else {
      // Reporters disagree and there's no majority: count it for little, and take the reading that's kinder to
      // the player (the slowest guess, the most letters showing).
      kept = list;
      weight = cfg.singleReporterWeight / 2;
    }
    const agreed = kept.length >= 2 && weight === 1;
    const ms = agreed ? median(kept.map((e) => e.msSinceStart))! : Math.max(...kept.map((e) => e.msSinceStart));
    const revealed = agreed
      ? Math.round(median(kept.map((e) => e.revealed))!)
      : Math.max(...kept.map((e) => e.revealed));
    const reveals = kept.map((e) => e.msSinceReveal).filter((x): x is number => x !== null);
    const msSinceReveal = revealed > 0 && reveals.length
      ? (agreed ? median(reveals)! : Math.max(...reveals))
      : null;
    const word = mostCommon(kept.map((e) => e.word?.trim().toLowerCase() || null).filter((w): w is string => !!w));
    const lengths = kept.map((e) => e.wordLength).filter((x): x is number => x !== null);
    const length = word ? word.replace(/\s+/g, "").length : (lengths.length ? Math.round(median(lengths)!) : null);
    const seen = kept.map((e) => e.seenAt).filter(Boolean).map((d) => new Date(d as string).toISOString()).sort();
    out.push({
      gameKey: list[0].gameKey, round: list[0].round, ms, msSinceReveal, revealed, length, word,
      reporters: kept.map((e) => e.reporter), allReporters: list.map((e) => e.reporter), weight,
      seenAt: seen[0] ?? null,
      flags: {
        noHint: revealed === 0,
        fastStart: ms < humanMinStartMs(length, cfg),
        fastReveal: msSinceReveal !== null && msSinceReveal < humanMinRevealMs(length, cfg),
      },
    });
  }
  return out.sort((a, b) => (a.seenAt ?? "").localeCompare(b.seenAt ?? "") || a.gameKey.localeCompare(b.gameKey) || a.round - b.round);
}

function rate(rounds: ObservedRound[], hit: (r: ObservedRound) => boolean) {
  let w = 0, h = 0, hits = 0;
  for (const r of rounds) { w += r.weight; if (hit(r)) { h += r.weight; hits++; } }
  return { rate: w ? h / w : 0, hits, of: rounds.length };
}

/** Scores a set of rounds. wordGames: in how many games each word (lower case) was seen, for "rare". */
export function scoreRounds(rounds: ObservedRound[], wordGames: Map<string, number>, cfg: GtbWatchConfig = GTB_WATCH) {
  const s = cfg.signals;
  const noHintR = rate(rounds, (r) => r.flags.noHint);
  const fastStartR = rate(rounds, (r) => r.flags.fastStart);
  const revealRounds = rounds.filter((r) => r.msSinceReveal !== null);
  const fastRevealR = rate(revealRounds, (r) => r.flags.fastReveal);

  const rare = (r: ObservedRound) => !!r.word && (wordGames.get(r.word) ?? 0) <= cfg.rareWordMaxGames;
  const hard = rounds.filter((r) => (r.length !== null && r.length >= cfg.longWord) || rare(r));
  const easy = rounds.filter((r) => r.length !== null && r.length <= cfg.shortWord && !rare(r));
  const hardMs = median(hard.map((r) => r.ms)), easyMs = median(easy.map((r) => r.ms));
  const enoughSplit = hard.length >= cfg.minHardRounds && easy.length >= cfg.minEasyRounds && easyMs !== null && easyMs > 0;
  const ratio = enoughSplit ? hardMs! / easyMs! : null;

  const signals = {
    noHint: { strength: rounds.length ? ramp(noHintR.rate, s.noHint.normal, s.noHint.strong) : null, hits: noHintR.hits, of: noHintR.of },
    fastStart: { strength: rounds.length ? ramp(fastStartR.rate, s.fastStart.normal, s.fastStart.strong) : null, hits: fastStartR.hits, of: fastStartR.of },
    fastReveal: {
      strength: revealRounds.length >= cfg.minRevealRounds ? ramp(fastRevealR.rate, s.fastReveal.normal, s.fastReveal.strong) : null,
      hits: fastRevealR.hits, of: fastRevealR.of,
    },
    // Lower ratio is stranger, so the ramp runs the other way.
    noSlowdown: {
      strength: ratio === null ? null : ramp(-ratio, -s.noSlowdown.normal, -s.noSlowdown.strong),
      hits: hard.length, of: easy.length, hardMs, easyMs,
    },
  };
  let num = 0, den = 0;
  for (const key of Object.keys(signals) as (keyof typeof signals)[]) {
    const strength = signals[key].strength;
    if (strength === null) continue;
    num += strength * s[key].weight;
    den += s[key].weight;
  }
  return { score: den ? Math.round((100 * num) / den) : 0, signals };
}

const secs = (ms: number | null) => (ms === null ? "?" : (ms / 1000).toFixed(1) + " s");

/** Plain sentences for the admin page and the in-game hover. Numbers only, no judgement words. */
export function evidenceLines(c: Pick<GtbCase, "signals" | "rounds" | "reporters" | "confirmedRounds" | "medianMs">, cfg: GtbWatchConfig = GTB_WATCH): string[] {
  const out: string[] = [];
  const { noHint, fastStart, fastReveal, noSlowdown } = c.signals;
  if (noHint.of) out.push(`Guessed before any letter showed in ${noHint.hits} of ${noHint.of} rounds`);
  if (fastStart.hits) {
    out.push(`Guessed faster than the build could be seen and the word typed (about ${secs(humanMinStartMs(null, cfg))}) in ${fastStart.hits} of ${fastStart.of} rounds`);
  }
  if (fastReveal.strength !== null && fastReveal.hits) {
    out.push(`Typed the word within moments of a new letter in ${fastReveal.hits} of ${fastReveal.of} rounds with letters`);
  }
  if (noSlowdown.strength !== null && noSlowdown.strength > 0) {
    out.push(`No slowdown on long or rare words: ${secs(noSlowdown.hardMs)} on them vs ${secs(noSlowdown.easyMs)} on short ones`);
  }
  out.push(`Median guess ${secs(c.medianMs)} into the round`);
  out.push(`Seen by ${c.reporters} Better Battle players; ${c.confirmedRounds} round${c.confirmedRounds === 1 ? "" : "s"} seen by two or more`);
  return out;
}

/**
 * The full case for one player. Events: every guess of theirs (self-reports already left out).
 * wordGames: games per word across all reports (lower case), for the rare-word check.
 */
export function scorePlayer(name: string, events: GuessEvent[], wordGames: Map<string, number> = new Map(), cfg: GtbWatchConfig = GTB_WATCH): GtbCase {
  const rounds = observeRounds(events, cfg);
  const raw = scoreRounds(rounds, wordGames, cfg);
  const reporters = new Set(events.map((e) => e.reporter));

  // Leave out the reporters whose removal lowers the score the most, one at a time.
  const dropped: number[] = [];
  let remaining = events;
  let final = raw, finalRounds = rounds;
  for (let k = 0; k < cfg.dropTopReporters; k++) {
    const left = [...new Set(remaining.map((e) => e.reporter))];
    if (left.length <= 1) break;
    let best: { reporter: number; events: GuessEvent[]; rounds: ObservedRound[]; result: ReturnType<typeof scoreRounds> } | null = null;
    for (const r of left) {
      const evs = remaining.filter((e) => e.reporter !== r);
      const rs = observeRounds(evs, cfg);
      const result = scoreRounds(rs, wordGames, cfg);
      // Lowest score wins; on a tie, the reporter whose removal leaves the fewest rounds (they carried the most)
      if (!best || result.score < best.result.score || (result.score === best.result.score && rs.length < best.rounds.length)) {
        best = { reporter: r, events: evs, rounds: rs, result };
      }
    }
    dropped.push(best!.reporter);
    remaining = best!.events;
    if (best!.result.score <= final.score) { final = best!.result; }
    finalRounds = best!.rounds;
  }

  // The sample has to hold up without the dropped reporters too, so one reporter can't supply it.
  const eligible = rounds.length >= cfg.minRounds && reporters.size >= cfg.minReporters
    && finalRounds.length >= cfg.minRounds;
  const score = Math.min(final.score, raw.score);
  const confirmedRounds = rounds.filter((r) => r.weight === 1 && r.reporters.length >= 2).length;
  const medianMs = median(rounds.map((r) => r.ms));
  const base = { signals: raw.signals, rounds: rounds.length, reporters: reporters.size, confirmedRounds, medianMs };
  return {
    name,
    score,
    rawScore: raw.score,
    eligible,
    strong: eligible && score >= cfg.showScore,
    ...base,
    evidence: evidenceLines(base, cfg),
    droppedReporters: dropped,
    detail: rounds,
  };
}
