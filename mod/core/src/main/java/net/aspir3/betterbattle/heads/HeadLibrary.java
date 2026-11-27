package net.aspir3.betterbattle.heads;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The head database: about 90,000 decorative heads from minecraft-heads.com, fetched one category
 * at a time and kept in config/betterbattle/heads for a week. Favourites and recents live next to them.
 */
public final class HeadLibrary {
    public static final String CREDIT = "Heads from minecraft-heads.com";
    private static final String SOURCE = "https://minecraft-heads.com/scripts/api.php?tags=true&cat=";
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final Pattern HASH = Pattern.compile("textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,64})");

    public static final class Category {
        public final String id, label;

        Category(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final List<Category> CATEGORIES = Collections.unmodifiableList(java.util.Arrays.asList(
        new Category("alphabet", "Letters"), new Category("animals", "Animals"), new Category("blocks", "Blocks"),
        new Category("decoration", "Decoration"), new Category("food-drinks", "Food"), new Category("humans", "People"),
        new Category("humanoid", "Characters"), new Category("miscellaneous", "Misc"), new Category("monsters", "Monsters"),
        new Category("plants", "Plants")));

    /** One head. hash is the skin's id on textures.minecraft.net. */
    public static final class Head {
        public String name, hash, tags, category;

        public Head() {}

        public Head(String name, String hash, String tags, String category) {
            this.name = name;
            this.hash = hash;
            this.tags = tags;
            this.category = category;
        }

        public String textureUrl() {
            return "http://textures.minecraft.net/texture/" + hash;
        }

        /** The base64 "textures" property a head item needs to show this skin. */
        public String textureProperty() {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + textureUrl() + "\"}}}";
            return java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Saved {
        List<Head> favourites = new ArrayList<Head>();
        List<Head> recent = new ArrayList<Head>();
    }

    private final File dir;
    private final Map<String, List<Head>> loaded = new LinkedHashMap<String, List<Head>>();
    private final Map<String, String> failed = new LinkedHashMap<String, String>();
    private final java.util.Set<String> loading = new java.util.HashSet<String>();
    private Saved saved = new Saved();

    public HeadLibrary(File modDir) {
        this.dir = new File(modDir, "heads");
        this.dir.mkdirs();
        File f = new File(dir, "saved.json");
        if (f.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Saved s = new Gson().fromJson(r, Saved.class);
                if (s != null) saved = s;
            } catch (Exception ignored) {
            }
        }
        if (saved.favourites == null) saved.favourites = new ArrayList<Head>();
        if (saved.recent == null) saved.recent = new ArrayList<Head>();
        MyHeads.init(modDir);
    }

    /** Pulls the texture hash out of a base64 "textures" property or a texture URL. Null if there isn't one. */
    public static String hashOf(String propertyOrUrl) {
        if (propertyOrUrl == null) return null;
        String text = propertyOrUrl;
        if (!text.contains("textures.minecraft.net")) {
            try {
                text = new String(java.util.Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        Matcher m = HASH.matcher(text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    // ---- categories

    public synchronized boolean isLoaded(String category) {
        return loaded.containsKey(category);
    }

    public synchronized boolean isLoading(String category) {
        return loading.contains(category);
    }

    public synchronized String error(String category) {
        return failed.get(category);
    }

    public synchronized int loadedCount() {
        int n = 0;
        for (List<Head> l : loaded.values()) n += l.size();
        return n;
    }

    /** Starts loading a category in the background (from the cache if it's fresh). */
    public synchronized void load(final String category) {
        if (loaded.containsKey(category) || loading.contains(category)) return;
        loading.add(category);
        failed.remove(category);
        Thread t = new Thread(new Runnable() {
            public void run() {
                List<Head> heads = null;
                String err = null;
                try {
                    heads = readCategory(category);
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    System.err.println("[Better Battle] heads " + category + ": " + err);
                }
                synchronized (HeadLibrary.this) {
                    loading.remove(category);
                    if (heads != null) loaded.put(category, heads);
                    else failed.put(category, err);
                }
            }
        }, "Better Battle heads " + category);
        t.setDaemon(true);
        t.start();
    }

    public void loadAll() {
        for (Category c : CATEGORIES) load(c.id);
    }

    private List<Head> readCategory(String category) throws Exception {
        File cache = new File(dir, category + ".json");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS) {
            List<Head> fromCache = readCache(cache);
            if (fromCache != null && !fromCache.isEmpty()) return fromCache;
        }
        List<Head> heads;
        try {
            heads = download(category);
        } catch (Exception e) {
            // Offline: an old cache is better than nothing
            List<Head> stale = cache.isFile() ? readCache(cache) : null;
            if (stale != null && !stale.isEmpty()) return stale;
            throw e;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(cache), StandardCharsets.UTF_8)) {
            new Gson().toJson(heads, w);
        }
        return heads;
    }

    private static List<Head> readCache(File cache) {
        try (Reader r = new InputStreamReader(new FileInputStream(cache), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, new TypeToken<List<Head>>() {}.getType());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Head> download(String category) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SOURCE + category).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "BetterBattle-mod (+https://battle.aspir3.net)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        List<Head> out = new ArrayList<Head>();
        try (InputStream in = c.getInputStream()) {
            JsonArray arr = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String hash = hashOf(o.has("value") ? o.get("value").getAsString() : null);
                if (hash == null || !o.has("name")) continue;
                String tags = o.has("tags") && !o.get("tags").isJsonNull() ? o.get("tags").getAsString() : "";
                out.add(new Head(o.get("name").getAsString(), hash, tags, category));
            }
        }
        return out;
    }

    // ---- browsing

    /** Heads in a category ("all" for every loaded one), filtered by words in the name or tags. */
    public synchronized List<Head> search(String category, String query) {
        List<Head> source = new ArrayList<Head>();
        if ("favourites".equals(category)) source.addAll(saved.favourites);
        else if ("recent".equals(category)) source.addAll(saved.recent);
        else if (MyHeads.CATEGORY.equals(category)) source.addAll(MyHeads.list());
        else if ("all".equals(category)) {
            source.addAll(MyHeads.list());
            for (List<Head> l : loaded.values()) source.addAll(l);
        }
        else if (loaded.containsKey(category)) source.addAll(loaded.get(category));
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return source;
        String[] words = q.split("\\s+");
        List<Head> starts = new ArrayList<Head>(), rest = new ArrayList<Head>();
        for (Head h : source) {
            String name = h.name.toLowerCase(Locale.ROOT);
            String hay = name + " " + (h.tags == null ? "" : h.tags.toLowerCase(Locale.ROOT));
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (!all) continue;
            if (name.startsWith(words[0])) starts.add(h); else rest.add(h);
        }
        starts.addAll(rest);
        return starts;
    }

    public synchronized boolean isFavourite(Head h) {
        for (Head f : saved.favourites) if (f.hash.equals(h.hash)) return true;
        return false;
    }

    public synchronized void toggleFavourite(Head h) {
        if (!removeHash(saved.favourites, h.hash)) saved.favourites.add(0, h);
        save();
    }

    public synchronized void addRecent(Head h) {
        removeHash(saved.recent, h.hash);
        saved.recent.add(0, h);
        while (saved.recent.size() > 45) saved.recent.remove(saved.recent.size() - 1);
        save();
    }

    private static boolean removeHash(List<Head> list, String hash) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).hash.equals(hash)) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private void save() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "saved.json")), StandardCharsets.UTF_8)) {
            new Gson().toJson(saved, w);
        } catch (Exception ignored) {
        }
    }
}
