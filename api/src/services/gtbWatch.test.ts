import assert from "node:assert/strict";
import test from "node:test";
import { GTB_WATCH, observeRounds, scorePlayer, type GuessEvent } from "./gtbWatch";

const WORDS = ["surfboard", "astronaut", "pizza", "cat", "lighthouse", "dragon", "castle", "rainbow", "tree", "volcano",
  "skateboard", "waterfall", "snowman", "robot", "pirate", "garden", "rocket", "teapot", "helicopter", "bridge",
  "penguin", "trophy", "octopus", "windmill", "cupcake"];

/** A seeded random, so the tests never flake. */
function rng(seed: number) {
  return () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
}

/** Rounds of one player as `reporters` see them. guess(round) → [msSinceStart, revealed, msSinceReveal]. */
function games(guesser: string, rounds: number, reporters: number[], guess: (i: number, word: string) => [number, number, number | null],
               opts: { jitter?: number; perRound?: (i: number) => number[] } = {}): GuessEvent[] {
  const out: GuessEvent[] = [];
  const rand = rng(guesser.length * 97 + rounds);
  for (let i = 0; i < rounds; i++) {
    const word = WORDS[i % WORDS.length];
    const [ms, revealed, sinceReveal] = guess(i, word);
    const who = opts.perRound ? opts.perRound(i) : reporters;
    for (const r of who) {
      const j = Math.round((rand() - 0.5) * (opts.jitter ?? 300));
      out.push({ reporter: r, gameKey: `m${Math.floor(i / 8)}|11/20/26|b${i}`, round: (i % 8) + 1, guesser,
        msSinceStart: Math.max(0, ms + j), revealed, msSinceReveal: sinceReveal === null ? null : Math.max(0, sinceReveal + j),
        wordLength: word.length, word });
    }
  }
  return out;
}

// Common words: seen in plenty of games, so the rare-word check doesn't kick in
const common = new Map(WORDS.map((w) => [w, 50]));

test("a bot that answers instantly, before any letter, on every word scores high and shows", () => {
  const events = games("Botty", 30, [1, 2, 3, 4], () => [1200, 0, null]);
  const c = scorePlayer("Botty", events, common);
  assert.ok(c.eligible, "enough rounds and reporters");
  assert.ok(c.strong, `score ${c.score} should pass ${GTB_WATCH.showScore}`);
  assert.equal(c.signals.noHint.hits, 30);
  assert.match(c.evidence[0], /before any letter showed in 30 of 30 rounds/);
});

test("a fast, good human who waits for letters and slows down on long words stays low", () => {
  const events = games("Quick", 40, [1, 2, 3, 4], (i, w) => {
    const ms = 14_000 + w.length * 1_800 + (i % 5) * 2_000;
    const revealed = i % 3 === 0 ? 0 : 1 + (i % 3);
    return [ms, revealed, revealed ? 3_500 + w.length * 200 : null];
  });
  const c = scorePlayer("Quick", events, common);
  assert.ok(c.eligible);
  assert.ok(!c.strong, `score ${c.score} should stay under ${GTB_WATCH.showScore}`);
  assert.ok(c.score < 30, `score ${c.score}`);
});

test("nothing shows below the minimum sample, however strange", () => {
  const few = scorePlayer("Botty", games("Botty", GTB_WATCH.minRounds - 1, [1, 2, 3], () => [900, 0, null]), common);
  assert.ok(!few.eligible && !few.strong, "too few rounds");
  const lonely = scorePlayer("Botty", games("Botty", 40, [1, 2], () => [900, 0, null]), common);
  assert.ok(!lonely.eligible && !lonely.strong, "too few reporters");
});

test("one reporter can't frame someone: their rounds alone never make the sample", () => {
  // Reporter 9 invents 40 instant rounds; three honest reporters each saw a couple of normal rounds.
  const fake = games("Victim", 40, [9], () => [800, 0, null]);
  const honest = games("Victim", 6, [], (i, w) => [20_000 + w.length * 1_500, 2, 4_000],
    { perRound: (i) => [1 + (i % 3)] }).map((e) => ({ ...e, gameKey: "real" + e.gameKey }));
  const c = scorePlayer("Victim", [...fake, ...honest], common);
  assert.ok(!c.eligible, "without reporter 9 there are only 6 rounds");
  assert.ok(!c.strong);
  assert.deepEqual(c.droppedReporters, [9]);
});

test("one lying reporter in shared rounds is outvoted by the others' median", () => {
  // Four reporters saw the same rounds; reporter 4 claims instant guesses before any letter.
  const honest = games("Normal", 30, [1, 2, 3], (i, w) => [18_000 + w.length * 1_500, 2, 4_000]);
  const liar = honest.filter((e) => e.reporter === 1).map((e) => ({ ...e, reporter: 4, msSinceStart: 700, revealed: 0, msSinceReveal: null }));
  const c = scorePlayer("Normal", [...honest, ...liar], common);
  assert.ok(c.eligible);
  assert.ok(!c.strong, `score ${c.score}`);
  const rounds = observeRounds([...honest, ...liar]);
  assert.ok(rounds.every((r) => !r.reporters.includes(4)), "the outlying reading is left out of every round");
  assert.ok(rounds.every((r) => r.revealed === 2 && r.ms > 15_000));
});

test("rounds several reporters agree on count fully; lone rounds count half", () => {
  const rounds = observeRounds([
    ...games("P", 1, [1, 2], () => [5_000, 0, null]),
    ...games("P", 1, [3], () => [5_000, 0, null]).map((e) => ({ ...e, gameKey: "other" })),
  ]);
  const shared = rounds.find((r) => r.gameKey !== "other")!;
  const lone = rounds.find((r) => r.gameKey === "other")!;
  assert.equal(shared.weight, 1);
  assert.equal(lone.weight, GTB_WATCH.singleReporterWeight);
});

test("two reporters who disagree: the kinder reading is used, for little weight", () => {
  const [r] = observeRounds([
    { reporter: 1, gameKey: "g", round: 1, guesser: "P", msSinceStart: 1_000, revealed: 0, msSinceReveal: null, wordLength: 5, word: "pizza" },
    { reporter: 2, gameKey: "g", round: 1, guesser: "P", msSinceStart: 30_000, revealed: 3, msSinceReveal: 6_000, wordLength: 5, word: "pizza" },
  ]);
  assert.equal(r.ms, 30_000);
  assert.equal(r.revealed, 3);
  assert.ok(!r.flags.noHint && !r.flags.fastStart);
  assert.equal(r.weight, GTB_WATCH.singleReporterWeight / 2);
});

test("each reporter counts once per round", () => {
  const e: GuessEvent = { reporter: 1, gameKey: "g", round: 1, guesser: "P", msSinceStart: 1_000, revealed: 0, msSinceReveal: null, wordLength: 5, word: "pizza" };
  const [r] = observeRounds([e, { ...e }, { ...e, msSinceStart: 900 }]);
  assert.deepEqual(r.allReporters, [1]);
});

test("guessing right after each letter appears, faster than typing, shows up", () => {
  const events = games("Reveal", 30, [1, 2, 3], (i, w) => [25_000 + (i % 4) * 3_000, 1 + (i % 2), 250]);
  const c = scorePlayer("Reveal", events, common);
  assert.equal(c.signals.fastReveal.hits, 30);
  assert.equal(c.signals.fastReveal.strength, 1);
  assert.ok(c.evidence.some((l) => /within moments of a new letter/.test(l)));
});

test("no slowdown on long words is a signal; normal slowdown isn't", () => {
  const flat = scorePlayer("Flat", games("Flat", 40, [1, 2, 3], () => [9_000, 1, 5_000]), common);
  // Same time on every word: a ratio of about 1, well past the 1.3 humans show
  assert.ok((flat.signals.noSlowdown.strength ?? 0) > 0.5, `strength ${flat.signals.noSlowdown.strength}`);
  assert.ok(flat.evidence.some((l) => /No slowdown on long or rare words/.test(l)));
  const human = scorePlayer("Human", games("Human", 40, [1, 2, 3], (i, w) => [6_000 + w.length * 3_000, 1, 5_000]), common);
  assert.equal(human.signals.noSlowdown.strength, 0);
});

test("evidence is plain numbers, with no verdict words", () => {
  const c = scorePlayer("Botty", games("Botty", 30, [1, 2, 3, 4], () => [1200, 0, null]), common);
  for (const line of c.evidence) assert.doesNotMatch(line, /cheat|hack|bot|ban/i);
});
