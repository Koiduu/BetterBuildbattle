package net.aspir3.betterbattle.pages;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.game.SpbTracker;
import net.aspir3.betterbattle.sync.ApiClient;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The text pages in the B menu (What's New, Last Game, Weekly Challenges), built here once so
 * both Minecraft versions show exactly the same thing. Lines use § colours; the screen wraps them.
 */
public final class Pages {
    private Pages() {}

    public static final class Page {
        public final String title;
        public final List<String> lines;

        public Page(String title, List<String> lines) {
            this.title = title;
            this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
        }
    }

    /** Measures text in the game's font. */
    public interface Width {
        int of(String text);
    }

    /** Word-wraps one line to maxWidth, carrying its colour codes onto the next line. */
    public static List<String> wrap(String line, int maxWidth, Width width) {
        List<String> out = new ArrayList<String>();
        if (width.of(line) <= maxWidth) {
            out.add(line);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        String carry = "";
        for (String word : line.split(" ")) {
            String candidate = cur.length() == 0 ? carry + word : cur + " " + word;
            if (cur.length() > 0 && width.of(candidate) > maxWidth) {
                out.add(cur.toString());
                carry = "  " + lastCodes(cur.toString());
                cur = new StringBuilder(carry + word);
            } else {
                cur = new StringBuilder(candidate);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** The colour (and any formats after it) in effect at the end of a line. */
    static String lastCodes(String s) {
        String colour = "", formats = "";
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '§') continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r') {
                colour = c == 'r' ? "" : "§" + c;
                formats = "";
            } else if (c >= 'k' && c <= 'o') {
                formats += "§" + c;
            }
        }
        return colour + formats;
    }

    // ---- What's New

    private static final class Changelog {
        List<Update> updates;
    }

    private static final class Update {
        String version, date, title, summary;
        List<Section> sections;
    }

    private static final class Section {
        String heading;
        List<String> items;
    }

    private static List<Update> updates() {
        Reader r = new InputStreamReader(Pages.class.getResourceAsStream("/betterbattle/changelog.json"), StandardCharsets.UTF_8);
        try {
            Changelog c = new Gson().fromJson(r, Changelog.class);
            return c == null || c.updates == null ? Collections.<Update>emptyList() : c.updates;
        } catch (Exception e) {
            return Collections.emptyList();
        } finally {
            try { r.close(); } catch (Exception ignored) { }
        }
    }

    public static Page whatsNew() {
        List<String> out = new ArrayList<String>();
        List<Update> all = updates();
        for (int i = 0; i < all.size() && i < 3; i++) {
            Update u = all.get(i);
            if (i > 0) {
                out.add("");
                out.add("§8――――――――――――――――――――");
                out.add("");
            }
            out.add("§d§l" + u.title + "  §8v" + u.version + " · " + u.date);
            if (u.summary != null) out.add("§7" + u.summary);
            if (u.sections == null) continue;
            for (Section s : u.sections) {
                out.add("");
                out.add("§6" + s.heading);
                for (String item : s.items) out.add("§8• §f" + item);
            }
        }
        if (out.isEmpty()) out.add("§7No patch notes in this version.");
        out.add("");
        out.add("§8Switch any of this off in Settings. Full notes: battle.aspir3.net/updates");
        return new Page("What's New", out);
    }

    // ---- Last Game

    public static String modeName(String mode) {
        if ("solo".equals(mode)) return "Solo";
        if ("teams".equals(mode)) return "Teams";
        if ("pro".equals(mode)) return "Pro";
        if ("gtb".equals(mode)) return "Guess The Build";
        if ("speed_builders".equals(mode)) return "Speed Builders";
        return mode == null ? "Build Battle" : mode;
    }

    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    /** The few lines that go to chat right after a game. */
    public static List<String> recapChat(MatchRecord m, long xp, List<String> unlocked) {
        List<String> out = new ArrayList<String>();
        String what = m.theme == null ? "" : " §8· §f" + m.theme;
        out.add("§d§lBetter Battle §8» §7" + modeName(m.mode) + what);

        List<String> parts = new ArrayList<String>();
        if (m.placement != null) {
            parts.add((m.won() ? "§6§l" : "§f") + ordinal(m.placement) + (m.players == null ? "" : " §7of " + m.players));
        }
        if (m.grade != null) parts.add("§7Grade §f" + m.grade);
        Double crowd = m.crowdScore();
        if (crowd != null) parts.add("§7Crowd §f" + percent(crowd));
        if ("gtb".equals(m.mode) && m.gtbRounds > 0) parts.add("§7Guessed §f" + m.gtbCorrect + "/" + m.gtbRounds);
        if (xp > 0) parts.add("§a+" + xp + " XP");
        if (!parts.isEmpty()) out.add("  " + join(parts));

        if (!m.spbRounds.isEmpty()) out.add("  " + join(spbSummary(m.spbRounds)));
        if (!unlocked.isEmpty()) out.add("  §6Unlocked §e" + String.join("§7, §e", unlocked));
        out.add("  §8Press B, then Last Game, for the full breakdown");
        return out;
    }

    private static List<String> spbSummary(List<SpbTracker.Round> rounds) {
        int perfect = 0;
        Integer best = null;
        boolean newPb = false;
        double precision = 0;
        int withPrecision = 0;
        for (SpbTracker.Round r : rounds) {
            if (!r.perfect) continue;
            perfect++;
            if (r.timeMs != null && (best == null || r.timeMs < best)) best = r.timeMs;
            if (r.newPb) newPb = true;
            if (r.precision != null) {
                precision += r.precision;
                withPrecision++;
            }
        }
        List<String> parts = new ArrayList<String>();
        parts.add("§7Perfect §f" + perfect + "/" + rounds.size());
        if (best != null) parts.add("§7Fastest §f" + SpbTracker.seconds(best));
        if (withPrecision > 0) parts.add("§7Precision §f" + percent(precision / withPrecision));
        if (newPb) parts.add("§d§lNew best!");
        return parts;
    }

    /** The full Last Game page. */
    public static Page recap(MatchRecord m, long xp, List<String> unlocked) {
        List<String> out = new ArrayList<String>();
        out.add("§d§l" + modeName(m.mode) + (m.theme == null ? "" : " §8· §f" + m.theme));
        out.add("§8" + new SimpleDateFormat("EEE d MMM, HH:mm", Locale.ROOT).format(new Date(m.playedAt))
            + " · " + (m.durationSec / 60) + " min " + (m.durationSec % 60) + " s");
        out.add("");
        if (m.placement != null) out.add("§7Placed  " + (m.won() ? "§6§l" : "§f") + ordinal(m.placement) + (m.players == null ? "" : " §7of §f" + m.players));
        if (m.grade != null) out.add("§7Grade  §f" + m.grade);
        Double crowd = m.crowdScore();
        if (crowd != null) out.add("§7Crowd score  §f" + percent(crowd) + "  §8(" + Math.round(m.score) + " of " + m.maxScore() + ")");
        if ("gtb".equals(m.mode) && m.gtbRounds > 0) out.add("§7Correct guesses  §f" + m.gtbCorrect + " of " + m.gtbRounds);
        out.add("§7Blocks  §f" + m.blocksPlaced + " placed, " + m.blocksBroken + " broken, " + m.palette.size() + " types");
        if (xp > 0) out.add("§7XP  §a+" + xp);

        if (!m.spbRounds.isEmpty()) {
            out.add("");
            out.add("§6Rounds");
            int n = 1;
            for (SpbTracker.Round r : m.spbRounds) {
                StringBuilder line = new StringBuilder("§8" + n++ + ". §f" + r.name + "  ");
                if (r.perfect && r.timeMs != null) {
                    line.append("§a").append(SpbTracker.seconds(r.timeMs));
                    if (r.newPb) line.append(" §d§lBEST");
                    else if (r.pbBeforeMs != null) line.append(" §8(best ").append(SpbTracker.seconds(r.pbBeforeMs)).append(")");
                } else {
                    line.append("§c").append(r.accuracy == null ? "not perfect" : percent(r.accuracy) + " accurate");
                }
                if (r.precision != null) line.append("  §7precision §f").append(percent(r.precision));
                out.add(line.toString());
            }
            out.add("  " + join(spbSummary(m.spbRounds)));
        }

        out.add("");
        if (unlocked.isEmpty()) {
            out.add("§8No new achievements this game.");
        } else {
            out.add("§6Unlocked");
            for (String name : unlocked) out.add("§8• §e" + name);
        }
        return new Page("Last Game", out);
    }

    // ---- Weekly challenges

    public static Page weekly(List<ApiClient.Weekly> list, String weekEndsAt) {
        List<String> out = new ArrayList<String>();
        if (list == null || list.isEmpty()) {
            out.add("§7No weekly challenges yet.");
            out.add("§8They arrive with your first sync of the week.");
            return new Page("Weekly Challenges", out);
        }
        String left = timeUntil(weekEndsAt);
        if (left != null) out.add("§7New challenges in §f" + left);
        out.add("");
        for (ApiClient.Weekly w : list) {
            int target = Math.max(1, w.target);
            int progress = Math.min(w.progress, target);
            out.add((w.done ? "§a✔ " : "§8○ ") + (w.done ? "§a" : "§f") + w.text + "  §6+" + w.xp + " XP");
            out.add("   " + bar(progress, target) + " §7" + progress + " / " + target);
            out.add("");
        }
        out.add("§8Weekly challenges give XP toward your Aspiring Level. They reset every Monday at 00:00 UTC.");
        return new Page("Weekly Challenges", out);
    }

    public static List<ApiClient.Weekly> weeklyFromJson(String json) {
        if (json == null) return Collections.emptyList();
        try {
            List<ApiClient.Weekly> list = new Gson().fromJson(json, new TypeToken<List<ApiClient.Weekly>>() {}.getType());
            return list == null ? Collections.<ApiClient.Weekly>emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String bar(int progress, int target) {
        int cells = 10;
        int full = (int) Math.round(progress * (double) cells / target);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < cells; i++) {
            if (i == full) sb.append("§8");
            sb.append('■');
        }
        return sb.toString();
    }

    /** "2d 5h", "3h 12m", or null if unknown. */
    public static String timeUntil(String iso) {
        if (iso == null) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ms = f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso).getTime() - System.currentTimeMillis();
            if (ms <= 0) return "a moment";
            long min = ms / 60000;
            if (min >= 1440) return (min / 1440) + "d " + (min % 1440 / 60) + "h";
            if (min >= 60) return (min / 60) + "h " + (min % 60) + "m";
            return Math.max(1, min) + "m";
        } catch (Exception e) {
            return null;
        }
    }

    private static String join(List<String> parts) {
        return String.join("  §8|  ", parts);
    }
}
