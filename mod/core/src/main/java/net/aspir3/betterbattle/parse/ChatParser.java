package net.aspir3.betterbattle.parse;

import com.google.gson.Gson;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel's wording lives in patterns.json, not in code, so a reworded message
 * is a one-line fix. Every line is trimmed and colour-stripped before matching.
 */
public final class ChatParser {
    public enum Type {
        THEME, VOTING_START, BUILDER, VOTE, SCORE, PLACEMENT, GAME_END, GTB_CORRECT_GUESS,
        // Speed Builders (from a real game, Nov 2026)
        SPB_PERFECT, SPB_ACCURACY, SPB_ELIMINATED,
        /** "+120 tokens! (2nd Place)" */
        TOKENS_PLACEMENT,
        /** "1st - 7": the next chat line is that player's name. */
        PODIUM
    }

    public static final class Event {
        public final Type type;
        public final String text;
        /** Second capture group, if the pattern has one (e.g. the time in a perfect-build line). */
        public final String text2;

        Event(Type type, String text, String text2) {
            this.type = type;
            this.text = text;
            this.text2 = text2;
        }
    }

    private static final class Spec {
        int version;
        String title;
        List<String> titles;
        Map<String, String> modes;
        Map<String, String> sidebar;
        Map<String, String> chat;
    }

    private static final class Rule {
        final Type type;
        final Pattern pattern;

        Rule(Type type, Pattern pattern) {
            this.type = type;
            this.pattern = pattern;
        }
    }

    private final int version;
    private final List<String> titles = new ArrayList<String>();
    private final Map<String, Pattern> sidebarExtra = new java.util.HashMap<String, Pattern>();
    private final Map<String, String> modes;
    private final Pattern sidebarMode, sidebarPlayers, sidebarTime;
    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d{2})\\s*$");
    private final List<Rule> rules = new ArrayList<Rule>();

    public ChatParser(Reader json) {
        Spec s = new Gson().fromJson(json, Spec.class);
        version = s.version;
        if (s.title != null) titles.add(s.title.toUpperCase(Locale.ROOT));
        if (s.titles != null) for (String t : s.titles) if (!titles.contains(t.toUpperCase(Locale.ROOT))) titles.add(t.toUpperCase(Locale.ROOT));
        for (Map.Entry<String, String> e : s.sidebar.entrySet()) {
            if (!e.getKey().startsWith("_")) sidebarExtra.put(e.getKey(), Pattern.compile(e.getValue()));
        }
        modes = s.modes;
        sidebarMode = Pattern.compile(s.sidebar.get("MODE"));
        sidebarPlayers = Pattern.compile(s.sidebar.get("PLAYERS"));
        sidebarTime = Pattern.compile(s.sidebar.get("TIME"));
        for (Map.Entry<String, String> e : s.chat.entrySet()) {
            if (e.getKey().startsWith("_")) continue;   // comments
            Type type;
            try {
                type = Type.valueOf(e.getKey());
            } catch (IllegalArgumentException unknown) {
                continue;   // a newer patterns file than this mod version knows about
            }
            rules.add(new Rule(type, Pattern.compile(e.getValue())));
        }
    }

    public int version() { return version; }

    /** Removes colour codes (including the odd ones Hypixel pads sidebar lines with) and outer spaces. */
    public static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }

    public boolean isBuildBattle(String sidebarTitle) {
        String t = clean(sidebarTitle).toUpperCase(Locale.ROOT);
        for (String title : titles) if (t.contains(title)) return true;
        return false;
    }

    /** "Mode: Solo" → "solo". Null if the line isn't a mode line or the mode is unknown. */
    public String mode(String line) {
        Matcher m = sidebarMode.matcher(line);
        return m.find() ? modes.get(m.group(1).trim().toLowerCase(Locale.ROOT)) : null;
    }

    public Integer players(String line) {
        Matcher m = sidebarPlayers.matcher(line);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** True for the build countdown line, which only shows while building. */
    public boolean isBuildTimer(String line) {
        return sidebarTime.matcher(line).find();
    }

    /**
     * A named sidebar pattern from patterns.json (e.g. "SPB_THEME"): its first group, the whole line
     * if it has no groups, or null if the line doesn't match or this patterns file doesn't have it.
     */
    public String sidebar(String key, String line) {
        Pattern p = sidebarExtra.get(key);
        if (p == null) return null;
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        return m.groupCount() >= 1 ? m.group(1).trim() : line;
    }

    public Integer sidebarInt(String key, String line) {
        String v = sidebar(key, line);
        try {
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A "mm:ss" sidebar pattern with two groups, as seconds. */
    public Integer sidebarClock(String key, String line) {
        Pattern p = sidebarExtra.get(key);
        if (p == null) return null;
        Matcher m = p.matcher(line);
        if (!m.find() || m.groupCount() < 2) return null;
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    /** Seconds left on the sidebar's build clock ("Time: 0:42" → 42), or null for any other line. */
    public Integer secondsLeft(String line) {
        if (!isBuildTimer(line)) return null;
        Matcher m = CLOCK.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : null;
    }

    public Event parse(String line) {
        for (Rule r : rules) {
            Matcher m = r.pattern.matcher(line);
            if (m.find()) return new Event(r.type, group(m, 1), group(m, 2));
        }
        return null;
    }

    private static String group(Matcher m, int i) {
        if (m.groupCount() < i || m.group(i) == null) return null;
        return m.group(i).trim();
    }
}
