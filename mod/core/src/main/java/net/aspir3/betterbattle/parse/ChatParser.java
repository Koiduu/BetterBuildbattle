package net.aspir3.betterbattle.parse;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex table loaded from patterns.json. The bundled copy ships in the jar;
 * /mod/bootstrap can hand down a newer one so wording changes on Hypixel
 * never need a mod release. Gson is available on both 26.3 and 1.8.9.
 */
public final class ChatParser {
    public enum Type { GAME_START, THEME, VOTING_START, PLACEMENT, SCORE, GAME_END }

    public static final class Event {
        public final Type type;
        public final String text;
        public final Integer intValue;
        public final Double doubleValue;
        Event(Type t, String text, Integer i, Double d) { type = t; this.text = text; intValue = i; doubleValue = d; }
    }

    private static final class Spec {
        int version;
        @SerializedName("scoreboard_modes") Map<String, String> scoreboardModes;
        Map<String, String> chat;
    }

    private static final class Rule {
        final Type type; final Pattern pattern;
        Rule(Type t, Pattern p) { type = t; pattern = p; }
    }

    private final int version;
    private final List<Rule> rules = new ArrayList<Rule>();
    private final List<Map.Entry<Pattern, String>> modes = new ArrayList<Map.Entry<Pattern, String>>();

    public ChatParser(Reader json) {
        Spec s = new Gson().fromJson(json, Spec.class);
        version = s.version;
        for (Map.Entry<String, String> e : s.scoreboardModes.entrySet()) {
            modes.add(new java.util.AbstractMap.SimpleEntry<Pattern, String>(
                    Pattern.compile(e.getKey(), Pattern.CASE_INSENSITIVE), e.getValue()));
        }
        for (Map.Entry<String, String> e : s.chat.entrySet()) {
            rules.add(new Rule(Type.valueOf(e.getKey()), Pattern.compile(e.getValue())));
        }
    }

    public int version() { return version; }

    public String modeFromScoreboard(String title) {
        for (Map.Entry<Pattern, String> m : modes) if (m.getKey().matcher(title).find()) return m.getValue();
        return null;
    }

    public Event parse(String line) {
        for (Rule r : rules) {
            Matcher m = r.pattern.matcher(line);
            if (!m.find()) continue;
            String g = m.groupCount() >= 1 ? m.group(1) : null;
            switch (r.type) {
                case THEME: return new Event(r.type, g == null ? null : g.trim(), null, null);
                case GAME_START:
                case PLACEMENT: return new Event(r.type, null, g == null ? null : Integer.valueOf(g), null);
                case SCORE: return new Event(r.type, null, null, g == null ? null : Double.valueOf(g));
                default: return new Event(r.type, null, null, null);
            }
        }
        return null;
    }
}
