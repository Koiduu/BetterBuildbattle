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
        // Speed Builders. Wording not yet confirmed from a real game: check with /bb debug.
        SPB_BUILD, SPB_START, SPB_PERFECT, SPB_ACCURACY
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
    private final String title;
    private final Map<String, String> modes;
    private final Pattern sidebarMode, sidebarPlayers, sidebarTime;
    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d{2})\\s*$");
    private final List<Rule> rules = new ArrayList<Rule>();

    public ChatParser(Reader json) {
        Spec s = new Gson().fromJson(json, Spec.class);
        version = s.version;
        title = s.title.toUpperCase(Locale.ROOT);
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
        return clean(sidebarTitle).toUpperCase(Locale.ROOT).contains(title);
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
