package net.aspir3.betterbattle.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Follows one Speed Builders game round by round: which build it was, how long you took,
 * whether it was perfect, what you placed and broke, and how you compared with the lobby.
 *
 * It only reads chat, the sidebar clock and your own clicks. It never shows where blocks go,
 * so the timer it feeds is just a stopwatch with your best time next to it.
 */
public final class SpbTracker {
    /** Your best and the Better Battle world record for a build, and where a new best is saved. */
    public interface Records {
        Long personalBest(String buildId);
        Long worldRecord(String buildId);
        String worldRecordHolder(String buildId);
        void savePersonalBest(String buildId, String buildName, long timeMs, double precision);
    }

    /** One round. Serialised as-is into the match's spb_rounds JSON. */
    public static final class Round {
        public String build;               // build id, e.g. "pagoda"
        public String name;                // as Hypixel shows it, e.g. "Pagoda"
        public Integer timeMs;             // your perfect time; null if you didn't get a perfect build
        public Double accuracy;            // 1.0 for perfect, else what Hypixel reported, null if unknown
        public Double precision;           // needed / touched blocks, for perfect rounds
        public int placed, broken;
        public boolean perfect;
        public Integer remainingMs;        // round clock left when you finished
        public int othersPerfect;          // other players with a perfect build this round
        public int othersPerfectBefore;    // ...of whom finished before you
        public boolean oneTypeAtATime = true;
        public Long pbBeforeMs;            // your best on this build before this round
        public boolean newPb;

        transient Long wrMs;
        transient String wrHolder;

        transient long startedAt;
        transient String currentType;
        transient Set<String> finishedTypes = new HashSet<String>();
    }

    private final String playerName;
    private final List<Round> rounds = new ArrayList<Round>();
    private Records records;
    private Round current;
    private Integer secondsLeft;
    private long secondsLeftAt;
    private long finishedAt;

    public SpbTracker(String playerName, Records records) {
        this.playerName = playerName;
        this.records = records;
    }

    /** "Pagoda Tower" → "pagoda_tower". The server uses the same rule. */
    public static String buildId(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "unknown" : (id.length() > 48 ? id.substring(0, 48) : id);
    }

    // ---- events

    /** A new round's build was announced. Closes the round before it. */
    public void onBuild(String name, long now) {
        closeRound();
        Round r = new Round();
        r.name = name;
        r.build = buildId(name);
        r.startedAt = now;
        if (records != null) {
            r.pbBeforeMs = records.personalBest(r.build);
            r.wrMs = records.worldRecord(r.build);
            r.wrHolder = records.worldRecordHolder(r.build);
        }
        current = r;
        finishedAt = 0;
    }

    /** The "go" signal, if Hypixel sends one after showing the build. Restarts the clock. */
    public void onStart(long now) {
        if (current != null && current.placed == 0 && current.broken == 0 && !current.perfect) current.startedAt = now;
    }

    /** Someone got a perfect build. name is null for "You ...". seconds is null if the line had no time. */
    public void onPerfect(String name, String seconds, long now) {
        if (current == null) return;
        boolean self = name == null || name.isEmpty() || name.equalsIgnoreCase(playerName);
        if (!self) {
            current.othersPerfect++;
            return;
        }
        if (current.perfect) return;
        current.perfect = true;
        current.accuracy = 1.0;
        current.othersPerfectBefore = current.othersPerfect;
        long ms = now - current.startedAt;
        if (seconds != null) {
            try {
                ms = Math.round(Double.parseDouble(seconds) * 1000.0);
            } catch (NumberFormatException ignored) {
            }
        }
        current.timeMs = (int) Math.max(1, Math.min(Integer.MAX_VALUE, ms));
        if (secondsLeft != null) {
            current.remainingMs = (int) Math.max(0, secondsLeft * 1000L - (now - secondsLeftAt));
        }
        int touched = current.placed + current.broken;
        current.precision = touched == 0 ? 1.0 : Math.max(0, current.placed - current.broken) / (double) touched;
        finishedAt = now;
        if (current.pbBeforeMs == null || current.timeMs < current.pbBeforeMs) {
            current.newPb = true;
            if (records != null) records.savePersonalBest(current.build, current.name, current.timeMs, current.precision);
        }
    }

    /** Hypixel's accuracy for a build that wasn't perfect, as a percentage. */
    public void onAccuracy(String percent) {
        if (current == null || current.perfect || percent == null) return;
        try {
            current.accuracy = Math.max(0, Math.min(1, Integer.parseInt(percent) / 100.0));
        } catch (NumberFormatException ignored) {
        }
    }

    public void onPlace(String blockId) {
        if (current == null || current.perfect) return;
        current.placed++;
        if (blockId.equals(current.currentType)) return;
        if (current.finishedTypes.contains(blockId)) current.oneTypeAtATime = false;
        if (current.currentType != null) current.finishedTypes.add(current.currentType);
        current.currentType = blockId;
    }

    public void onBreak() {
        if (current != null && !current.perfect) current.broken++;
    }

    /** The sidebar clock, read every half second. */
    public void onSecondsLeft(int seconds, long now) {
        if (secondsLeft == null || seconds != secondsLeft) {
            secondsLeft = seconds;
            secondsLeftAt = now;
        }
    }

    /** Ends the last round and returns them all. */
    public List<Round> finish() {
        closeRound();
        return Collections.unmodifiableList(rounds);
    }

    private void closeRound() {
        if (current != null && (current.placed > 0 || current.perfect)) rounds.add(current);
        current = null;
        secondsLeft = null;
    }

    // ---- the timer overlay

    /** Lines for the on-screen timer, with § colours. Empty when there's nothing to show. */
    public List<String> hudLines(long now, boolean showWorldRecord) {
        List<String> out = new ArrayList<String>();
        Round r = current;
        if (r == null) return out;
        if (r.perfect && now - finishedAt > 6000) return out;   // fade out a few seconds after finishing
        long ms = r.perfect ? r.timeMs : now - r.startedAt;
        out.add("§6" + r.name);
        out.add((r.perfect ? "§a" : "§f") + seconds(ms) + (r.perfect && r.newPb ? " §d§lNEW BEST" : ""));
        if (r.pbBeforeMs != null) out.add("§7Best §f" + seconds(r.pbBeforeMs) + "  " + delta(ms - r.pbBeforeMs, r.perfect));
        if (showWorldRecord && r.wrMs != null) {
            out.add("§7Record §f" + seconds(r.wrMs) + (r.wrHolder == null ? "" : " §8" + r.wrHolder) + "  " + delta(ms - r.wrMs, r.perfect));
        }
        return out;
    }

    /** While still building, only show how far behind you are once you are behind. */
    private static String delta(long diff, boolean finished) {
        if (!finished && diff < 0) return "";
        return (diff <= 0 ? "§a-" : "§c+") + seconds(Math.abs(diff));
    }

    public static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    // ---- JSON

    public static String toJson(List<Round> rounds) {
        return rounds.isEmpty() ? null : new Gson().toJson(rounds);
    }

    public static List<Round> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            List<Round> list = new Gson().fromJson(json, new TypeToken<List<Round>>() {}.getType());
            if (list == null) return Collections.emptyList();
            List<Round> out = new ArrayList<Round>();
            for (Round r : list) if (r != null && r.build != null) out.add(r);
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
