package net.aspir3.betterbattle.game;

/** Placeholder grading until real score distributions are collected. Server re-grades on sync. */
public final class Grades {
    private Grades() {}

    public static String of(Integer placement, Integer players, Double score) {
        if (placement == null || players == null || players <= 1) return null;
        double pct = 1.0 - (placement - 1) / (double) (players - 1); // 1.0 = first, 0.0 = last
        if (placement == 1 && score != null && score >= 90) return "SS";
        if (pct >= 0.90) return "S";
        if (pct >= 0.75) return "A";
        if (pct >= 0.50) return "B";
        return "C";
    }
}
