package net.aspir3.betterbattle.lfg;

import net.aspir3.betterbattle.sync.ApiClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** What both loaders' lobby screens share: tags, regions, sizes, search and the wording of server errors. */
public final class Lfg {
    private Lfg() {}

    public static final String NOTICE = "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.";

    public static final List<String> TAGS = Arrays.asList("teams", "solo", "gtb", "spb", "pro", "other");
    public static final List<String> REGIONS = Arrays.asList("any", "na", "eu", "as", "oc", "sa");
    public static final int NAME_MAX = 32, NOTE_MAX = 60, PASSWORD_MAX = 32;

    public static String tagLabel(String tag) {
        if (tag == null) return "All";
        if ("teams".equals(tag)) return "Teams";
        if ("solo".equals(tag)) return "Solo";
        if ("gtb".equals(tag)) return "GTB";
        if ("spb".equals(tag)) return "SPB";
        if ("pro".equals(tag)) return "Pro";
        return "Other";
    }

    /** The chip colour for a tag, as 0xRRGGBB. Matches the Discord posts. */
    public static int tagColour(String tag) {
        if ("teams".equals(tag)) return 0x55FF55;
        if ("solo".equals(tag)) return 0x55FFFF;
        if ("gtb".equals(tag)) return 0xFFAA00;
        if ("spb".equals(tag)) return 0xFF5555;
        if ("pro".equals(tag)) return 0xAA00AA;
        return 0xAAAAAA;
    }

    /** The most players one Hypixel game of each kind takes. The server checks this too. */
    public static int maxSize(String tag) {
        if ("solo".equals(tag) || "pro".equals(tag)) return 12;
        if ("gtb".equals(tag)) return 10;
        if ("spb".equals(tag)) return 8;
        return 16;
    }

    public static String regionLabel(String region) {
        if ("na".equals(region)) return "NA";
        if ("eu".equals(region)) return "EU";
        if ("as".equals(region)) return "Asia";
        if ("oc".equals(region)) return "Oceania";
        if ("sa".equals(region)) return "S. America";
        return "Any region";
    }

    /** The next item of a list after current, wrapping round. */
    public static <T> T next(List<T> list, T current) {
        int i = list.indexOf(current);
        return list.get((i + 1) % list.size());
    }

    /** The browser's search box: every word must appear in the name, host or tag. */
    public static boolean matches(ApiClient.Lobby l, String search) {
        if (search == null || search.trim().isEmpty()) return true;
        String hay = (l.name + " " + l.host + " " + l.tag + " " + tagLabel(l.tag)).toLowerCase(Locale.ROOT);
        for (String w : search.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!hay.contains(w)) return false;
        return true;
    }

    public static List<ApiClient.Lobby> filter(List<ApiClient.Lobby> all, String tag, String search) {
        List<ApiClient.Lobby> out = new ArrayList<ApiClient.Lobby>();
        if (all != null) for (ApiClient.Lobby l : all) {
            if ((tag == null || tag.equals(l.tag)) && matches(l, search)) out.add(l);
        }
        return out;
    }

    /** The server's error code from an exception message such as {@code HTTP 409: {"error":"lobby_full"}}. */
    public static String errorCode(Exception e) {
        String m = String.valueOf(e.getMessage());
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([a-z_]+)\"").matcher(m);
        return x.find() ? x.group(1) : null;
    }

    /** Words a player can act on for a server error code, or null for codes this doesn't know. */
    public static String explain(String code) {
        if (code == null) return null;
        if (code.equals("not_linked")) return "Link your website account first: get a code on the website's account page, then run /bb link <code>.";
        if (code.equals("no_minecraft")) return "Log in with the mod first.";
        if (code.equals("rate_limited")) return "Slow down a little and try again in a minute.";
        if (code.equals("lobby_full")) return "That lobby is full.";
        if (code.equals("lobby_closed") || code.equals("not_found")) return "That lobby has closed.";
        if (code.equals("wrong_password")) return "Wrong password.";
        if (code.equals("declined")) return "The host turned your request down.";
        if (code.equals("already_requested")) return "You already asked to join. Wait for the host.";
        if (code.equals("already_in")) return "You're already in that lobby.";
        if (code.equals("own_lobby")) return "That's your own lobby.";
        if (code.equals("too_many_requests")) return "You're waiting on 3 lobbies already. Take one back first.";
        if (code.equals("host_banned")) return "You can't host lobbies right now.";
        if (code.equals("no_offer")) return "That free slot has passed on. Start the lobby again to get back in line.";
        if (code.equals("inappropriate") || code.endsWith("_inappropriate")) return "Please pick a different name.";
        if (code.endsWith("_too_short")) return "That's too short.";
        if (code.endsWith("_too_long")) return "That's too long.";
        if (code.endsWith("_repeated_characters")) return "Too many of the same letter in a row.";
        if (code.endsWith("_no_links")) return "No links or Discord invites, please.";
        if (code.endsWith("_bad_characters")) return "Colour codes and special characters can't be used.";
        if (code.startsWith("cant_")) return "That can't be done any more. The list may be out of date.";
        return null;
    }

    /** Hours left until an ISO time, rounded up. */
    public static long hoursLeft(String iso, long now) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long ms = f.parse(iso).getTime() - now;
            return Math.max(0, (ms + 3_599_999L) / 3_600_000L);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Seconds left until an ISO time, or -1 when it can't be read. */
    public static long secondsLeft(String iso, long now) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return Math.max(0, (f.parse(iso).getTime() - now) / 1000L);
        } catch (Exception e) {
            return -1;
        }
    }
}
