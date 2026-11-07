package net.aspir3.betterbattle.progress;

/**
 * Hypixel Build Battle titles by score. Mirrors api/src/lib/titles.ts.
 * Colour codes are Minecraft § codes; bold titles add §l.
 */
public final class Titles {
    private Titles() {}

    public static final class Title {
        public final String name;
        public final int minScore;
        public final char color;   // § code
        public final boolean bold;
        Title(String name, int minScore, char color, boolean bold) {
            this.name = name; this.minScore = minScore; this.color = color; this.bold = bold;
        }
        /** e.g. "§b§lGrandmaster" */
        public String formatted() { return "§" + color + (bold ? "§l" : "") + name; }
    }

    public static final Title[] ALL = {
        new Title("Rookie",            0, 'f', false), // white
        new Title("Untrained",       100, '7', false), // gray
        new Title("Amateur",         250, '8', false), // dark gray
        new Title("Prospect",        500, 'a', false), // lime
        new Title("Apprentice",    1_000, '2', false), // green
        new Title("Experienced",   2_000, 'b', false), // aqua
        new Title("Seasoned",      3_500, '3', false), // dark aqua
        new Title("Trained",       5_000, '9', false), // blue
        new Title("Skilled",       7_500, '1', false), // dark blue
        new Title("Talented",     10_000, '5', false), // purple
        new Title("Professional", 15_000, 'd', false), // magenta
        new Title("Artisan",      20_000, 'c', false), // salmon (light red)
        new Title("Expert",       40_000, '4', false), // red (dark red)
        new Title("Master",       75_000, '6', false), // gold
        new Title("Legend",      100_000, 'a', true),  // lime, bold
        new Title("Grandmaster", 200_000, 'b', true),  // aqua, bold
        new Title("Celestial",   300_000, 'd', true),  // magenta, bold
        new Title("Divine",      400_000, 'c', true),  // salmon, bold
        new Title("Ascended",    500_000, '6', true),  // gold, bold
    };

    public static Title forScore(long score) {
        Title t = ALL[0];
        for (Title x : ALL) if (score >= x.minScore) t = x;
        return t;
    }

    /** Next title, or null at Ascended. */
    public static Title next(long score) {
        for (Title x : ALL) if (x.minScore > score) return x;
        return null;
    }

    /** 0.0 – 1.0 progress from current title to the next (1.0 at Ascended). */
    public static double progress(long score) {
        Title cur = forScore(score), nxt = next(score);
        if (nxt == null) return 1.0;
        return (score - cur.minScore) / (double) (nxt.minScore - cur.minScore);
    }
}
