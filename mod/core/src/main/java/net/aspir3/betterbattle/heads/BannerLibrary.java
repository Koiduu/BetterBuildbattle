package net.aspir3.betterbattle.heads;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Banner designs: a built-in set plus the ones you save, kept in heads/banners.json. */
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
    /** Survival banners stop at 6 layers; Hypixel may not take more. */
    public static final int MAX_LAYERS = 6;

    public static final class Design {
        public String name;
        public String base = "white";
        public List<String[]> layers = new ArrayList<String[]>();

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
    }

    public static final List<Design> PRESETS = Arrays.asList(
        new Design("Japan", "white", "circle", "red"),
        new Design("France", "white", "stripe_left", "blue", "stripe_right", "red"),
        new Design("Italy", "white", "stripe_left", "green", "stripe_right", "red"),
        new Design("Ireland", "white", "stripe_left", "green", "stripe_right", "orange"),
        new Design("Belgium", "yellow", "stripe_left", "black", "stripe_right", "red"),
        new Design("Germany", "red", "stripe_top", "black", "stripe_bottom", "yellow"),
        new Design("Netherlands", "white", "stripe_top", "red", "stripe_bottom", "blue"),
        new Design("Russia", "white", "stripe_middle", "blue", "stripe_bottom", "red"),
        new Design("Poland", "white", "half_horizontal_bottom", "red"),
        new Design("Ukraine", "blue", "half_horizontal_bottom", "yellow"),
        new Design("Indonesia", "red", "half_horizontal_bottom", "white"),
        new Design("Sweden", "blue", "straight_cross", "yellow"),
        new Design("England", "white", "straight_cross", "red"),
        new Design("Scotland", "blue", "cross", "white"),
        new Design("Switzerland", "red", "straight_cross", "white", "stripe_top", "red", "stripe_bottom", "red", "border", "red"),
        new Design("Creeper", "lime", "creeper", "black"),
        new Design("Pirate", "black", "skull", "white", "border", "black"),
        new Design("Flower", "white", "flower", "red"),
        new Design("Globe", "light_blue", "globe", "green"),
        new Design("Brick wall", "red", "bricks", "light_gray"),
        new Design("Sunset", "orange", "gradient", "purple"),
        new Design("Ocean", "blue", "gradient_up", "cyan"),
        new Design("Checker", "white", "square_top_left", "black", "square_bottom_right", "black"),
        new Design("Diamond", "black", "rhombus", "light_blue"),
        new Design("Window", "light_gray", "straight_cross", "brown", "border", "brown"),
        new Design("Stripes", "white", "small_stripes", "red"),
        new Design("Zigzag", "yellow", "triangles_bottom", "black", "triangles_top", "black"),
        new Design("Arrow", "white", "triangle_top", "black", "stripe_center", "black"),
        new Design("Piglin", "black", "piglin", "orange"),
        new Design("Mojang", "white", "mojang", "red"));

    private final File file;
    private final List<Design> saved = new ArrayList<Design>();

    public BannerLibrary(File modDir) {
        this.file = new File(new File(modDir, "heads"), "banners.json");
        load();
    }

    public List<Design> saved() { return saved; }

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
