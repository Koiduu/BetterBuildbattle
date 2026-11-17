package net.aspir3.betterbattle.achieve;

import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loader-independent filters, visibility, progress and recommendation snapshot. */
public final class AchievementBrowser {
    public enum Show { ALL, RECOMMENDED, UNLOCKED, LOCKED, PINNED }
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList("all", "solo", "teams", "pro", "gtb", "speed_builders"));
    public final Map<String, Long> unlocked = new HashMap<String, Long>();
    public final Map<String, Long> counts = new HashMap<String, Long>();
    public final Map<String, Long> totals = new HashMap<String, Long>();
    private final String player;
    private final long day;

    public AchievementBrowser(String player, long day) { this.player = player; this.day = day; }

    public static AchievementBrowser load(LocalDatabase db, Config config, String player) throws SQLException {
        AchievementBrowser view = new AchievementBrowser(player, LocalDate.now().toEpochDay());
        if (db != null) {
            view.unlocked.putAll(db.achievementTimes());
            view.counts.putAll(db.modeMatchCounts());
            view.totals.putAll(db.achievementTotals());
            config.removeUnlockedPins(view.unlocked.keySet());
        }
        return view;
    }

    public static boolean inMode(Catalog.Entry entry, String mode) {
        return mode == null || "all".equals(mode) || entry.modes == null || entry.modes.contains(mode);
    }

    public boolean hidden(Catalog.Entry e) { return e.secret && !unlocked.containsKey(e.id); }
    public String name(Catalog.Entry e) { return hidden(e) ? "Secret achievement" : e.name; }
    public String description(Catalog.Entry e) { return hidden(e) ? "Keep playing to find out." : e.description; }

    public String progress(Catalog.Entry e) {
        if (hidden(e) || e.threshold == null) return "";
        Long total = totals.get(e.track);
        if (total != null && total >= e.threshold && !unlocked.containsKey(e.id)) return "Complete, unlocks on next sync";
        return total == null ? "Goal: " + String.format("%,d", e.threshold)
            : String.format("%,d / %,d", Math.max(0, Math.min(total, e.threshold)), e.threshold);
    }

    public List<Catalog.Entry> recommended(Config config, String mode) {
        return Recommender.pick(Catalog.list(), unlocked.keySet(), config.pinnedAchievements(), counts, totals, day, player, mode);
    }

    public List<Catalog.Entry> entries(Show show, String mode, Config config) {
        List<Catalog.Entry> recommendations = recommended(config, mode);
        if (show == Show.RECOMMENDED) return recommendations;
        List<Catalog.Entry> result = new ArrayList<Catalog.Entry>();
        Set<String> pins = new HashSet<String>(config.pinnedAchievements());
        for (Catalog.Entry e : Catalog.list()) {
            if (!inMode(e, mode)) continue;
            boolean done = unlocked.containsKey(e.id);
            if (show == Show.UNLOCKED && !done || show == Show.LOCKED && done || show == Show.PINNED && !pins.contains(e.id)) continue;
            result.add(e);
        }
        Collections.sort(result, new Comparator<Catalog.Entry>() {
            public int compare(Catalog.Entry a, Catalog.Entry b) {
                Long at = unlocked.get(a.id), bt = unlocked.get(b.id);
                if (at == null && bt != null) return 1;
                if (at != null && bt == null) return -1;
                return at != null ? Long.compare(bt, at) : 0;
            }
        });
        if (show == Show.ALL) {
            result.removeAll(recommendations);
            result.addAll(0, recommendations);
        }
        return result;
    }

    public boolean togglePin(Catalog.Entry e, Config config) {
        if (config.pinnedAchievements().contains(e.id)) { config.unpin(e.id); return true; }
        return !hidden(e) && !unlocked.containsKey(e.id) && config.pin(e.id);
    }

    public static String modeName(String mode) {
        if ("gtb".equals(mode)) return "GTB";
        if ("speed_builders".equals(mode)) return "Speed Builders";
        return Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
    }

    public static String modes(Catalog.Entry e) {
        List<String> modes = e.modes == null ? MODES.subList(1, MODES.size()) : e.modes;
        StringBuilder text = new StringBuilder();
        for (String mode : modes) { if (text.length() > 0) text.append(" · "); text.append(modeName(mode)); }
        return text.toString();
    }
}
