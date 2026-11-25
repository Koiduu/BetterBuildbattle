package net.aspir3.betterbattle.shots;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Round screenshots: one picture of your build when the building time runs out, saved to
 * config/betterbattle/screenshots and deleted after KEEP_DAYS days. Only your own screen is ever saved.
 */
public final class RoundShots {
    private RoundShots() {}

    public static final int KEEP_DAYS = 15;
    private static final String STAMP = "yyyyMMdd-HHmmss";

    public static File dir(File modDir) {
        return new File(modDir, "screenshots");
    }

    /** yyyyMMdd-HHmmss_mode_theme.png, with anything odd in the theme turned into a dash. */
    public static String fileName(long at, String mode, String theme) {
        String t = theme == null ? "" : theme.trim().replaceAll("[^A-Za-z0-9]+", "-");
        if (t.length() > 40) t = t.substring(0, 40);
        t = t.replaceAll("^-+|-+$", "");
        return new SimpleDateFormat(STAMP, Locale.US).format(new Date(at)) + "_" + (mode == null ? "game" : mode)
            + (t.isEmpty() ? "" : "_" + t) + ".png";
    }

    /** Newest first. */
    public static List<File> list(File dir) {
        File[] files = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (files == null) return out;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".png")) out.add(f);
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        return out;
    }

    /** Deletes screenshots older than the given number of days. Returns how many it removed. */
    public static int prune(File dir, int days, long now) {
        long cutoff = now - days * 24L * 3600L * 1000L;
        int removed = 0;
        for (File f : list(dir)) {
            // the name says when it was taken; fall back to the file's own time if it doesn't parse
            long taken = f.lastModified();
            try {
                taken = new SimpleDateFormat(STAMP, Locale.US).parse(f.getName().substring(0, STAMP.length())).getTime();
            } catch (Exception ignored) {
            }
            if (taken < cutoff && f.delete()) removed++;
        }
        return removed;
    }

    private static String modeLabel(String id) {
        if ("solo".equals(id)) return "Solo";
        if ("teams".equals(id)) return "Teams";
        if ("pro".equals(id)) return "Pro";
        if ("gtb".equals(id)) return "Guess the Build";
        if ("speed_builders".equals(id)) return "Speed Builders";
        return id;
    }

    /** "25 Nov 12:34 · Solo · Pagoda" for a saved file. */
    public static String label(File f) {
        String n = f.getName();
        try {
            String[] parts = n.substring(0, n.length() - 4).split("_", 3);
            Date when = new SimpleDateFormat(STAMP, Locale.US).parse(parts[0]);
            StringBuilder sb = new StringBuilder(new SimpleDateFormat("d MMM HH:mm", Locale.US).format(when));
            if (parts.length > 1) sb.append(" · ").append(modeLabel(parts[1]));
            if (parts.length > 2) sb.append(" · ").append(parts[2].replace('-', ' '));
            return sb.toString();
        } catch (Exception e) {
            return n;
        }
    }

    public static List<String> names(List<File> files) {
        List<String> out = new ArrayList<String>();
        for (File f : files) out.add(f.getName());
        return Arrays.asList(out.toArray(new String[0]));
    }
}
