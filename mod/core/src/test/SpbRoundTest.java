import net.aspir3.betterbattle.game.SpbTracker;

import java.util.List;

/**
 * Speed Builders round changes, from a real /bb report (SadiePaws, Nov 2026): Hypixel moves the sidebar to
 * "Round: 4" a moment before it swaps round 3's theme ("Enchanting") for round 4's ("Fire Exit"), so round 4
 * was filed under Enchanting and compared against its times.
 * Compile next to the core classes and run: java SpbRoundTest
 */
public class SpbRoundTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        SpbTracker t = new SpbTracker("SadiePaws", null);
        long now = 1000000L;
        t.onRound(3, "Enchanting", "Easy", 6, now);
        t.onBuildClock(45, now += 1000);
        // 23:51:48: "Round: 4 | Judging: 00:01 | Theme: Enchanting", then a second later "Theme: Fire Exit"
        t.onRound(4, "Enchanting", "Easy", 5, now += 15000);
        t.onRound(4, "Fire Exit", "Easy", 5, now += 500);
        t.onBuildClock(40, now += 14000);
        // A stale theme after round 4 has started must not rename it
        t.onRound(4, "Enchanting", "Easy", 5, now += 1000);
        List<SpbTracker.Round> rounds = t.finish();

        check(rounds.size() == 2, "two rounds, got " + rounds.size());
        SpbTracker.Round r4 = rounds.get(rounds.size() - 1);
        check(r4.number == 4, "last round is round 4, got " + r4.number);
        check("Fire Exit".equals(r4.name), "round 4 is Fire Exit, got " + r4.name);
        check(SpbTracker.buildId("Fire Exit Easy").equals(r4.build), "round 4 build id is fire exit, got " + r4.build);
        check(r4.limitMs != null && r4.limitMs == 40000, "round 4 keeps its own 40s limit, got " + r4.limitMs);
        check("Enchanting".equals(rounds.get(0).name), "round 3 stays Enchanting, got " + rounds.get(0).name);

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        if (failures > 0) System.exit(1);
    }
}
