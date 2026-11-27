import net.aspir3.betterbattle.game.GtbGuessTracker;

import java.util.List;

/**
 * Guess the Build guess timing, with the clock under the test's control: time from the builder starting, the
 * hint letters showing and time since the last one appeared, round numbers and the word.
 */
public class GtbGuessTrackerTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        hints();
        timings();
        System.out.println("GtbGuessTrackerTest: " + checks + " assertions passed");
    }

    private static void hints() {
        int[] blank = GtbGuessTracker.hintCounts("The theme is _ _ _ _ _");
        check(blank != null && blank[0] == 0 && blank[1] == 5, "five blanks");
        int[] some = GtbGuessTracker.hintCounts("The theme is S _ r _ _   _ _ _");
        check(some != null && some[0] == 2 && some[1] == 6, "two letters out of eight, spaces between words ignored");
        int[] bare = GtbGuessTracker.hintCounts("_ a _ _");
        check(bare != null && bare[0] == 1 && bare[1] == 3, "underscores alone count as a hint");
        check(GtbGuessTracker.hintCounts("The theme is 9 characters long") == null, "the length line isn't a hint");
        check(GtbGuessTracker.hintCounts("You are currently NICKED") == null, "other action bar text isn't a hint");
    }

    private static void timings() {
        GtbGuessTracker t = new GtbGuessTracker();
        t.newGame("11/20/26", "m85AE");
        t.onGuessed("Early", false, 500);                  // before any round: ignored
        t.onRoundStart("unzf", 1_000);
        t.onRoundLine(1);
        t.onWordLength(9);
        t.onHint("The theme is _ _ _ _ _ _ _ _ _", 1_100);
        t.onGuessed("unzf", false, 2_000);                 // the builder can't guess
        t.onGuessed("Fast", false, 2_400);
        t.onGuessed("Fast", false, 2_900);                 // once per round
        t.onGuessed("Me", true, 3_000);                    // never yourself
        t.onHint("The theme is S _ _ _ _ _ _ _ _", 40_000);
        t.onHint("The theme is S _ _ _ _ _ _ _ _", 41_000); // the same hint again isn't a new letter
        t.onGuessed("Slow", false, 41_300);
        t.onWordRevealed("Surfboard!");
        t.onGuessed("Late", false, 50_000);                 // after the word is out: ignored

        t.onRoundStart("reaxz", 60_000);                    // "Round: 2/8" comes after the builder
        t.onGuessed("Fast", false, 61_000);
        t.onRoundLine(2);
        t.onGameEnd();

        List<GtbGuessTracker.Guess> g = t.take();
        check(g.size() == 3, "three guesses, got " + g.size());
        GtbGuessTracker.Guess fast = g.get(0), slow = g.get(1), next = g.get(2);
        check("Fast".equals(fast.name) && fast.msSinceStart == 1_400 && fast.revealed == 0 && fast.msSinceReveal == null,
            "Fast: 1.4 s in, no letters");
        check("Slow".equals(slow.name) && slow.msSinceStart == 40_300 && slow.revealed == 1 && slow.msSinceReveal == 1_300,
            "Slow: 40.3 s in, 1.3 s after the first letter, got " + slow.msSinceReveal);
        check("Surfboard".equals(fast.word) && fast.wordLength == 9 && fast.round == 1, "round 1 is Surfboard");
        check(next.round == 2 && "reaxz".equals(next.builder) && next.word == null && next.msSinceStart == 1_000,
            "round 2 by reaxz, no word yet, got round " + next.round);
        check(fast.gameKey.equals(next.gameKey) && "m85AE|11/20/26|unzf".equals(fast.gameKey), "one game key: " + fast.gameKey);
        check(t.take().isEmpty(), "each guess is handed over once");
    }
}
