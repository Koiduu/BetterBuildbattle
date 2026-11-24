// Flags inappropriate words in text people write for others to see: bios and vanity links.
//
// Text is normalised first so the usual disguises don't slip through: case, accents and look-alike letters
// ("ṗ€ñïś"), digits and symbols for letters ("p3n1s", "$h!t"), stretched letters ("fuuuuck"), and words
// spelled out with gaps ("p e n i s", "p.e.n.i.s"). Then:
//  - ANYWHERE terms are caught even inside other words; only strings no ordinary word contains belong here.
//  - WORDS are caught only as a whole word (plus a plural or -ing/-ed/-er ending), so "class", "assassin",
//    "Scunthorpe", "grape", "therapist", "cockpit" and "Dickens" stay fine.
//  - PHRASES are caught across word gaps ("kill yourself").
// It is a first line of defence, not moderation: people can always find spellings it doesn't know.

const ANYWHERE = [
  "fuck", "motherfuck", "nigger", "nigga", "faggot", "retard", "penis", "vagina", "dildo", "porn",
  "blowjob", "handjob", "jizz", "cumshot", "masturbat", "pedophil", "paedophil", "whore",
  "asshole", "bitch", "shithead", "bullshit", "dickhead", "cocksuck", "pussies", "hentai", "xvideos", "pornhub",
  "onlyfans", "chingchong", "kike", "tranny", "kkk", "hitler", "nazi",
];

const WORDS = [
  "ass", "arse", "shit", "shitty", "cunt", "cock", "cocks", "dick", "dicks", "pussy", "slut", "sluts", "rape", "raped",
  "raping", "sex", "sexy", "cum", "tits", "titties", "boobs", "boob", "nude", "nudes", "horny", "anal", "piss",
  "bastard", "wank", "wanker", "twat", "prick", "fag", "fags", "spic", "chink", "coon", "gook", "wetback",
  "kys", "heil", "jihad", "nazis", "milf", "thot", "nsfw", "ballsack", "rapist", "rapists",
  "scrotum", "clit", "erection", "orgasm", "deepthroat",
];

const PHRASES = ["kill yourself", "killyourself", "neck yourself", "sieg heil", "white power", "heil hitler"];

const LOOKALIKES: Record<string, string> = {
  "0": "o", "1": "i", "!": "i", "|": "i", "3": "e", "€": "e", "4": "a", "@": "a", "5": "s", "$": "s",
  "7": "t", "+": "t", "8": "b", "9": "g", "ß": "ss", "æ": "ae", "œ": "oe", "ø": "o", "ł": "l", "đ": "d",
};

// No "-y": it would turn "spic" into "spicy" and "cock" into "cocky".
const SUFFIXES = ["", "s", "es", "ed", "ing", "er", "ers"];

/** Lowercase, accents off, look-alikes swapped for letters, everything else a space. */
function normalise(text: string): string {
  return text
    .normalize("NFKD")
    .replace(/[̀-ͯ​-‏⁠﻿]/g, "")   // accents and zero-width characters
    .toLowerCase()
    .replace(/./gu, (c) => LOOKALIKES[c] ?? c)
    .replace(/[^a-z]+/g, " ")
    .trim();
}

/** "fuuuck" → "fuck"; keeps doubles ("ass", "boobs") since some words need them. */
const squeeze = (s: string) => s.replace(/(.)\1{2,}/g, "$1$1");
const single = (s: string) => s.replace(/(.)\1+/g, "$1");

function wordMatches(token: string): string | null {
  for (const form of new Set([token, squeeze(token), single(token)])) {
    for (const bad of ANYWHERE) if (form.includes(bad)) return bad;
    for (const bad of WORDS) {
      for (const end of SUFFIXES) if (form === bad + end) return bad;
    }
  }
  return null;
}

/** The first flagged term in the text, or null if it looks fine. */
export function flaggedWord(text: string | null | undefined): string | null {
  if (!text) return null;
  const clean = normalise(text);
  if (!clean) return null;
  const tokens = clean.split(" ");

  for (const t of tokens) {
    const hit = wordMatches(t);
    if (hit) return hit;
  }
  const spaced = " " + tokens.join(" ") + " ";
  // Whole words only: gluing the words together would flag "skill yourself"
  for (const p of PHRASES) if (spaced.includes(" " + p + " ")) return p;

  // Letters spelled out with gaps: "p e n i s", "s.h.i.t" (runs of 3+ single letters joined up)
  let run: string[] = [];
  const checkRun = () => {
    if (run.length >= 3) {
      const joined = run.join("");
      const hit = wordMatches(joined);
      if (hit) return hit;
      for (const bad of WORDS) if (joined.includes(bad) && bad.length >= 4) return bad;
    }
    return null;
  };
  for (const t of tokens) {
    if (t.length === 1) { run.push(t); continue; }
    const hit = checkRun();
    if (hit) return hit;
    run = [];
  }
  return checkRun();
}
