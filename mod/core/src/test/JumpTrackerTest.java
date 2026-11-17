import net.aspir3.betterbattle.game.JumpTracker;

public final class JumpTrackerTest {
    private static int assertions;

    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions);
    }

    public static void main(String[] args) {
        JumpTracker tracker = new JumpTracker();
        check(!tracker.update(false, 0.4, false, false, false, false));
        check(!tracker.update(true, 0, false, false, false, false));
        check(tracker.update(true, 0, true, false, false, false));
        check(tracker.update(false, 0.4, false, false, false, false));
        check(!tracker.update(false, 0.3, true, false, false, false));
        check(!tracker.update(false, -0.1, false, false, false, false));
        check(!tracker.update(true, 0, false, false, false, false));
        check(!tracker.update(false, -0.1, false, false, false, false));
        check(!tracker.update(true, 0.1, false, false, false, false));
        tracker.reset();
        check(!tracker.update(false, 0.4, false, false, false, false));

        // Ladders/vines/scaffolding, water/lava/bubbles, and flight are not jumps.
        for (int environment = 0; environment < 3; environment++) {
            boolean climbing = environment == 0, liquid = environment == 1, flying = environment == 2;
            tracker.reset();
            check(!tracker.update(true, 0, false, false, false, false));
            check(!tracker.update(false, 0.2, true, climbing, liquid, flying));
            check(!tracker.update(false, 0.2, false, climbing, liquid, flying));
            check(!tracker.update(true, 0, true, climbing, liquid, flying));
            check(!tracker.update(false, 0.2, false, false, false, false));
        }
        System.out.println("JumpTrackerTest: " + assertions + " assertions passed");
    }
}
