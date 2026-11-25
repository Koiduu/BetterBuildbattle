package net.aspir3.betterbattle.pages;

import net.aspir3.betterbattle.sync.ApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The chat lines for /bb rank and /bb compare. Plain numbers only; built here so both Minecraft versions match. */
public final class RankText {
    private RankText() {}

    private static final String HEAD = "\u00a7d\u00a7lBetter Battle \u00a78\u00bb ";

    private static String num(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String value(ApiClient.Standing s, boolean percent) {
        if (s == null || s.value == null) return "";
        return " \u00a78(" + (percent ? String.format(Locale.US, "%.1f%%", s.value) : num(Math.round(s.value))) + ")";
    }

    private static String place(ApiClient.Standing s, boolean percent) {
        if (s == null || s.rank == null) return "\u00a78not ranked";
        return "\u00a7f#" + num(s.rank) + value(s, percent);
    }

    private static String period(String label, ApiClient.PeriodStanding p) {
        if (p == null) return "\u00a7e" + label + " \u00a78not ranked yet";
        return "\u00a7e" + label + " \u00a77Score " + place(p.score, false)
            + " \u00a77Wins " + place(p.wins, false) + " \u00a77Win rate " + place(p.winRate, true);
    }

    public static List<String> rank(ApiClient.Rank r) {
        List<String> out = new ArrayList<String>();
        out.add(HEAD + "\u00a76Your standing");
        out.add("\u00a77Global rank \u00a7f" + (r.globalRank == null ? "\u00a78not ranked" : "#" + num(r.globalRank))
            + (r.rankedPlayers > 0 ? " \u00a77of \u00a7f" + num(r.rankedPlayers) : ""));
        out.add("\u00a77Aspiring Level XP " + place(r.allTimeLevel, false) + "  \u00a77Win rate " + place(r.allTimeWinRate, true)
            + " \u00a78(100+ games)");
        out.add(period("Today", r.day));
        out.add(period("This week", r.week));
        out.add(period("This month", r.month));
        out.add("\u00a78Today, week and month count games the mod saved. Full lists: battle.aspir3.net/leaderboard");
        return out;
    }

    private static String cell(String text, boolean best) {
        return (best ? "\u00a7a" : "\u00a7f") + text;
    }

    /** One row: higher wins unless lowerIsBetter. A missing number never wins. */
    private static void row(List<String> out, String label, Double me, Double them, String meText, String themText, boolean lowerIsBetter) {
        boolean meBest = me != null && (them == null || (lowerIsBetter ? me < them : me > them));
        boolean themBest = them != null && (me == null || (lowerIsBetter ? them < me : them > me));
        out.add("\u00a77" + label + ": " + cell(me == null ? "\u2013" : meText, meBest) + " \u00a78vs " + cell(them == null ? "\u2013" : themText, themBest));
    }

    public static List<String> compare(ApiClient.Compare c) {
        List<String> out = new ArrayList<String>();
        ApiClient.Side a = c.me, b = c.them;
        out.add(HEAD + "\u00a76" + a.name + " \u00a77vs \u00a76" + b.name);
        row(out, "Score", (double) a.score, (double) b.score, num(a.score), num(b.score), false);
        row(out, "Global rank", a.globalRank == null ? null : (double) a.globalRank, b.globalRank == null ? null : (double) b.globalRank,
            a.globalRank == null ? "" : "#" + num(a.globalRank), b.globalRank == null ? "" : "#" + num(b.globalRank), true);
        row(out, "Wins", (double) a.wins, (double) b.wins, num(a.wins), num(b.wins), false);
        row(out, "Games", (double) a.games, (double) b.games, num(a.games), num(b.games), false);
        row(out, "Win rate", a.winRate, b.winRate,
            a.winRate == null ? "" : String.format(Locale.US, "%.1f%%", a.winRate), b.winRate == null ? "" : String.format(Locale.US, "%.1f%%", b.winRate), false);
        row(out, "Aspiring Level", a.level == null ? null : (double) a.level, b.level == null ? null : (double) b.level,
            a.level == null ? "" : num(a.level), b.level == null ? "" : num(b.level), false);
        row(out, "Aspiring Points", a.ap == null ? null : (double) a.ap, b.ap == null ? null : (double) b.ap,
            a.ap == null ? "" : num(a.ap), b.ap == null ? "" : num(b.ap), false);
        out.add("\u00a78Green = ahead. Level and points only show for players who use Better Battle.");
        return out;
    }
}
