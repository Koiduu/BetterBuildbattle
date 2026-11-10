package net.aspir3.betterbattle.achieve;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The achievement list shipped in the jar (generated from api/db/seeds/achievements.yaml). */
public final class Catalog {
    public static final class Entry {
        public String id, name, description, category, track;
        public int points;
        public boolean secret;
        public Integer threshold;
    }

    private static final class File {
        int version;
        List<Entry> achievements;
    }

    private static Map<String, Entry> byId;
    private static int version;

    public static synchronized Map<String, Entry> all() {
        if (byId == null) {
            byId = new LinkedHashMap<String, Entry>();
            Reader r = new InputStreamReader(Catalog.class.getResourceAsStream("/betterbattle/achievements.json"), StandardCharsets.UTF_8);
            try {
                File f = new Gson().fromJson(r, File.class);
                version = f.version;
                for (Entry e : f.achievements) byId.put(e.id, e);
            } finally {
                try { r.close(); } catch (Exception ignored) { }
            }
        }
        return Collections.unmodifiableMap(byId);
    }

    public static Entry get(String id) {
        return all().get(id);
    }

    public static List<Entry> list() {
        return new ArrayList<Entry>(all().values());
    }

    public static int version() {
        all();
        return version;
    }
}
