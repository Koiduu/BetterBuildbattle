package net.aspir3.betterbattle.report;

import net.aspir3.betterbattle.parse.ChatParser;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The last few games as /bb report sends them: the game's own messages, the sidebar and what the mod
 * decided. Anything a person wrote is left out before it is even kept: player chat, private messages,
 * guild, party and friend messages, and friend lists. It lives in memory only and leaves the computer
 * only when the player runs /bb report.
 */
public final class ReportLog {
    private static final int MAX_LINES = 2500;

    /** "Label: value" lines that are the game talking, not a person. Everything else with ": " is chat. */
    private static final String[] GAME_LABELS = {
        "Your Score:", "Builder:", "Builders:", "Round:", "Confirmed vote:", "The Builder was:", "The theme was:",
        "Theme:", "Mode:", "Winner:", "Winners:", "Time:", "Players:", "Teams:",
    };
    /** Social channels, private messages, party and friend system lines. */
    private static final String[] SOCIAL_PREFIXES = {
        "Guild >", "Party >", "Friend >", "Officer >", "From ", "To ", "[SHOUT]", "Friends (Page",
        "You have joined", "The party", "Your party", "You'll be partying",
    };
    private static final Pattern SOCIAL = Pattern.compile(
        "(?i).*(\\bparty\\b|\\binvited\\b|\\bfriend request\\b|\\bis (?:in|currently) |\\bjoined the lobby\\b|\\bguild\\b).*");

    private final ArrayDeque<String> lines = new ArrayDeque<String>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private boolean lastLeftOut;

    /**
     * What /bb report keeps of a chat line: the line, or null to leave it out. Game events the parser knows
     * are kept (except nick changes), and so are system lines that don't look like anyone talking.
     */
    public static String keep(String line, ChatParser parser) {
        if (line == null || line.isEmpty()) return null;
        ChatParser.Event e = parser.parse(line);
        if (e != null) {
            if (e.type == ChatParser.Type.NICK_ON || e.type == ChatParser.Type.NICK_SET || e.type == ChatParser.Type.NICK_RESET) {
                return "(nick changed)";
            }
            if (e.type == ChatParser.Type.VOTE || e.type == ChatParser.Type.PODIUM) return line;
        }
        for (String p : SOCIAL_PREFIXES) if (line.startsWith(p)) return null;
        if (line.contains(": ")) {
            for (String label : GAME_LABELS) if (line.startsWith(label)) return line;
            return e != null ? line : null;   // "Name: message" is someone talking
        }
        if (e == null && SOCIAL.matcher(line).matches()) return null;
        return line;
    }

    public synchronized void chat(String line, ChatParser parser) {
        String kept = keep(line, parser);
        if (kept == null) {
            if (!lastLeftOut) add("chat left out");   // one marker per run keeps the timing readable
            lastLeftOut = true;
            return;
        }
        add("chat: " + kept);
    }

    public synchronized void sidebar(String title, List<String> lines) {
        StringBuilder sb = new StringBuilder("sidebar [").append(title).append("]: ");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(" | ");
            // Lobby sidebars can show your guild; keep the line, not the name
            String l = lines.get(i);
            sb.append(l.startsWith("Guild:") ? "Guild: (left out)" : l);
        }
        add(sb.toString());
    }

    /** The mod's own lines: state changes, a finished game's summary. */
    public synchronized void mod(String line) {
        add(line);
    }

    private void add(String line) {
        if (!line.equals("chat left out")) lastLeftOut = false;
        lines.addLast(time.format(new Date()) + "  " + line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
