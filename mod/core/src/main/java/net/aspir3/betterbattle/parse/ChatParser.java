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
    public enum Type { THEME, VOTING_START, BUILDER, VOTE, SCORE, PLACEMENT, GAME_END, GTB_CORRECT_GUESS }

    public static final class Event {
        public final Type type;
        public final String text;

        Event(Type type, String text) {
            this.type = type;
            this.text = text;
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
            rules.add(new Rule(Type.valueOf(e.getKey()), Pattern.compile(e.getValue())));
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

    public Event parse(String line) {
        for (Rule r : rules) {
            Matcher m = r.pattern.matcher(line);
            if (m.find()) return new Event(r.type, m.groupCount() >= 1 ? m.group(1).trim() : null);
        }
        return null;
    }
}
