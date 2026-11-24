import assert from "node:assert/strict";
import test from "node:test";
import { flaggedWord } from "./wordFilter";

test("flags plain and disguised inappropriate words", () => {
  for (const text of [
    "Penis",                         // the bio that started this
    "my bio is fuck this",
    "FUUUUCK",
    "p3n1s",
    "p e n i s",
    "s.h.i.t",
    "$h!t happens",
    "ṗëñïś",
    "you're a b1tch",
    "kill yourself",
    "kys",
    "sieg heil",
    "a55",
    "dick",
    "dicks out",
    "follow my onlyfans",
    "n1gger",
    "f a g",
    "big boobs",
    "rapist",
  ]) {
    assert.ok(flaggedWord(text), `should flag: ${text}`);
  }
});

test("leaves ordinary bios alone, including words that contain a flagged one", () => {
  for (const text of [
    "",
    null,
    "What do you like to build? I love pagodas and organics.",
    "Building since 2014. Mostly organics and pagodas. Find me in Aspiring.",
    "Scunthorpe United fan",
    "top of my class",
    "assassin builds only",
    "grape vineyard enjoyer",
    "my therapist says build more",
    "cockpit of a plane",
    "reading Dickens",
    "spicy builds",
    "feeling cocky today",
    "I farm with a diamond hoe",
    "skill yourself up",
    "grass block lover",
    "documenting every build",
    "Level 100 in 3 years, 45 wins",
    "I build cumulus clouds",
    "Sussex, England",
    "analysis of good builds",
    "button and lever contraptions",
  ]) {
    assert.equal(flaggedWord(text as string), null, `should allow: ${text}`);
  }
});
