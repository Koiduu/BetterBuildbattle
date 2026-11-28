package net.aspir3.betterbattle.heads;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Banner Database: a catalog of ready-made designs (flags, letters, symbols, patterns, icons) in
 * betterbattle/banners.json, plus the ones you save, kept in heads/banners.json.
 * A newer catalog can be dropped in as heads/banner_catalog.json (see {@link #install}).
 */
public final class BannerLibrary {
    public static final String[] COLORS = {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
        "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"};
    public static final String[] PATTERNS = {"stripe_bottom", "stripe_top", "stripe_left", "stripe_right", "stripe_center",
        "stripe_middle", "stripe_downright", "stripe_downleft", "small_stripes", "cross", "straight_cross",
        "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top", "diagonal_left", "diagonal_up_right",
        "diagonal_up_left", "diagonal_right", "circle", "rhombus", "half_vertical", "half_horizontal",
        "half_vertical_right", "half_horizontal_bottom", "square_bottom_left", "square_bottom_right", "square_top_left",
        "square_top_right", "border", "curly_border", "gradient", "gradient_up", "bricks", "globe", "creeper", "skull",
        "flower", "mojang", "piglin", "flow", "guster"};
    /** Patterns added after Minecraft 1.8.9: designs using them are hidden in the 1.8.9 build. */
    public static final Set<String> MODERN_ONLY = Collections.unmodifiableSet(new HashSet<String>(
        Arrays.asList("globe", "piglin", "flow", "guster")));
    /** Survival banners stop at 6 layers; Hypixel may not take more. */
    public static final int MAX_LAYERS = 6;
    /** Category ids that aren't in the catalog: everything, and your own designs. */
    public static final String ALL = "all", SAVED = "saved";

    private static final String RESOURCE = "/betterbattle/banners.json";
    private static final Set<String> COLOR_SET = new HashSet<String>(Arrays.asList(COLORS));
    private static final Set<String> PATTERN_SET = new HashSet<String>(Arrays.asList(PATTERNS));

    public static final class Category {
        public String id, label;

        public Category() { }

        public Category(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final class Design {
        public String name;
        public String base = "white";
        public List<String[]> layers = new ArrayList<String[]>();
        /** Catalog only: the category id and extra words the search matches. */
        public String category, tags;
        private transient String haystack;

        public Design() { }

        public Design(String name, String base, String... layers) {
            this.name = name;
            this.base = base;
            for (int i = 0; i + 1 < layers.length; i += 2) this.layers.add(new String[]{layers[i], layers[i + 1]});
        }

        public Design copy() {
            Design d = new Design();
            d.name = name;
            d.base = base;
            for (String[] l : layers) d.layers.add(l.clone());
            return d;
        }

        /** Uses a pattern Minecraft 1.8.9 doesn't have. */
        public boolean modernOnly() {
            for (String[] l : layers) if (MODERN_ONLY.contains(l[0])) return true;
            return false;
        }

        /** Every word of the query appears in the name, tags or category. */
        boolean matches(String[] words) {
            if (words.length == 0) return true;
            if (haystack == null) {
                haystack = ((name == null ? "" : name) + " " + (tags == null ? "" : tags) + " "
                    + (category == null ? "" : category)).toLowerCase(Locale.ROOT);
            }
            for (String w : words) if (!haystack.contains(w)) return false;
            return true;
        }
    }

    /** The shape of banners.json. */
    static final class Catalog {
        int version;
        List<Category> categories = new ArrayList<Category>();
        List<Design> designs = new ArrayList<Design>();
    }

    private final File file, catalogFile;
    private final boolean legacy;
    private final List<Design> saved = new ArrayList<Design>();
    private Catalog catalog;

    /** @param legacy true on Minecraft 1.8.9: designs with newer patterns are left out. */
    public BannerLibrary(File modDir, boolean legacy) {
        File heads = new File(modDir, "heads");
        this.file = new File(heads, "banners.json");
        this.catalogFile = new File(heads, "banner_catalog.json");
        this.legacy = legacy;
        load();
        catalog = loadCatalog();
    }

    public BannerLibrary(File modDir) {
        this(modDir, false);
    }

    public List<Category> categories() { return catalog.categories; }
    public List<Design> catalog() { return catalog.designs; }
    public int catalogVersion() { return catalog.version; }
    public List<Design> saved() { return saved; }

    /**
     * Designs in a category ({@link #ALL}, {@link #SAVED} or a catalog id) whose name, tags or category
     * hold every word of the query. "All" lists your saved designs first.
     */
    public List<Design> search(String category, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Design> out = new ArrayList<Design>();
        boolean all = category == null || ALL.equals(category);
        if (all || SAVED.equals(category)) {
            for (Design d : saved) if (usable(d) && d.matches(words)) out.add(d);
        }
        if (!SAVED.equals(category)) {
            for (Design d : catalog.designs) {
                if ((all || category.equals(d.category)) && d.matches(words)) out.add(d);
            }
        }
        return out;
    }

    private boolean usable(Design d) {
        return !legacy || !d.modernOnly();
    }

    public void save(Design d) {
        Design c = d.copy();
        if (c.name == null || c.name.trim().isEmpty()) c.name = "Banner " + (saved.size() + 1);
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).name.equalsIgnoreCase(c.name)) { saved.set(i, c); write(); return; }
        }
        saved.add(0, c);
        write();
    }

    public void remove(Design d) {
        if (saved.remove(d)) write();
    }

    /**
     * Takes a newer catalog (the same JSON as banners.json, e.g. from the server) and keeps it for next time.
     * Returns false, changing nothing, when it doesn't parse, holds no usable designs or isn't newer.
     */
    public boolean install(String json) {
        Catalog c = parse(new StringReader(json));
        if (c == null || c.designs.isEmpty() || c.version <= catalog.version) return false;
        catalogFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(catalogFile), StandardCharsets.UTF_8)) {
            w.write(json);
        } catch (Exception e) {
            return false;
        }
        catalog = c;
        return true;
    }

    /** The bundled catalog, or the installed one when that's newer. */
    private Catalog loadCatalog() {
        Catalog bundled = null;
        InputStream in = BannerLibrary.class.getResourceAsStream(RESOURCE);
        if (in != null) {
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                bundled = parse(r);
            } catch (Exception ignored) { }
        }
        Catalog installed = null;
        if (catalogFile.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(catalogFile), StandardCharsets.UTF_8)) {
                installed = parse(r);
            } catch (Exception ignored) { }
        }
        if (installed != null && !installed.designs.isEmpty() && (bundled == null || installed.version > bundled.version)) {
            return installed;
        }
        return bundled != null ? bundled : new Catalog();
    }

    /** Reads a catalog, dropping designs this version can't show. Null when it isn't one. */
    Catalog parse(Reader in) {
        Catalog c;
        try {
            c = new Gson().fromJson(in, Catalog.class);
        } catch (Exception e) {
            return null;
        }
        if (c == null) return null;
        Set<String> ids = new HashSet<String>();
        List<Category> cats = new ArrayList<Category>();
        if (c.categories != null) {
            for (Category k : c.categories) {
                if (k != null && k.id != null && k.label != null && !ALL.equals(k.id) && !SAVED.equals(k.id) && ids.add(k.id)) cats.add(k);
            }
        }
        List<Design> designs = new ArrayList<Design>();
        if (c.designs != null) {
            for (Design d : c.designs) {
                if (valid(d) && ids.contains(d.category) && usable(d)) designs.add(d);
            }
        }
        // A category with nothing left in it (e.g. all modern-only on 1.8.9) isn't shown
        List<Category> used = new ArrayList<Category>();
        for (Category k : cats) {
            for (Design d : designs) if (k.id.equals(d.category)) { used.add(k); break; }
        }
        c.categories = Collections.unmodifiableList(used);
        c.designs = Collections.unmodifiableList(designs);
        return c;
    }

    static boolean valid(Design d) {
        if (d == null || d.name == null || d.name.trim().isEmpty() || !COLOR_SET.contains(d.base) || d.layers == null) return false;
        if (d.layers.size() > MAX_LAYERS) return false;
        for (String[] l : d.layers) {
            if (l == null || l.length != 2 || !PATTERN_SET.contains(l[0]) || !COLOR_SET.contains(l[1])) return false;
        }
        return true;
    }

    private void load() {
        if (!file.isFile()) return;
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<Design> list = new Gson().fromJson(in, new TypeToken<List<Design>>() { }.getType());
            if (list != null) for (Design d : list) if (d != null && d.base != null && d.layers != null) saved.add(d);
        } catch (Exception ignored) { }
    }

    private void write() {
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new Gson().toJson(saved, w);
        } catch (Exception ignored) { }
    }
}
