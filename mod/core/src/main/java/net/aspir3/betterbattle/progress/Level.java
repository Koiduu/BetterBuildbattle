package net.aspir3.betterbattle.progress;

/**
 * Aspiring Level: Better Battle's own progression, separate from Hypixel score.
 * Same formula runs on the server (api/src/services/level.ts) so both always agree.
 *
 * XP needed to go from level n to n+1 = 500 + 100 * n   (level 1→2: 600, 10→11: 1,500, 50→51: 5,500)
 * Total XP to reach level n         = 500(n-1) + 50 n (n-1)
 */
public final class Level {
    private Level() {}

    // ---- XP sources ----
    public static final int XP_GAME_PLAYED   = 10;
    public static final int XP_WIN           = 50;
    public static final int XP_GRADE_SS      = 40;
    public static final int XP_GRADE_S       = 20;
    public static final int XP_PERFECT_BUILD = 15;  // per Speed Builders round
    public static final int XP_CORRECT_GUESS = 5;   // per GTB guess
    public static final int XP_FIRST_WIN_DAY = 100; // first win of each day
    public static final int XP_PER_AP        = 10;  // every achievement point is worth 10 XP

    public static long totalXpForLevel(int level) {
        long n = level;
        return 500L * (n - 1) + 50L * n * (n - 1);
    }

    public static int levelForXp(long xp) {
        // solve 50n^2 + 450n - 500 - xp = 0 for n, then correct for rounding
        int n = (int) Math.floor((-450 + Math.sqrt(450.0 * 450 + 200.0 * (500 + xp))) / 100.0);
        if (n < 1) n = 1;
        while (totalXpForLevel(n + 1) <= xp) n++;
        while (n > 1 && totalXpForLevel(n) > xp) n--;
        return n;
    }

    /** 0.0 – 1.0 progress toward the next level, for the XP bar. */
    public static double progress(long xp) {
        int n = levelForXp(xp);
        long base = totalXpForLevel(n), next = totalXpForLevel(n + 1);
        return (xp - base) / (double) (next - base);
    }

    public static long xpForMatch(String mode, boolean won, String grade, int perfectBuilds, int correctGuesses,
                                  boolean firstWinToday) {
        long xp = XP_GAME_PLAYED;
        if (won) xp += XP_WIN;
        if ("SS".equals(grade)) xp += XP_GRADE_SS;
        else if ("S".equals(grade)) xp += XP_GRADE_S;
        xp += (long) perfectBuilds * XP_PERFECT_BUILD;
        xp += (long) correctGuesses * XP_CORRECT_GUESS;
        if (won && firstWinToday) xp += XP_FIRST_WIN_DAY;
        return xp;
    }
}
