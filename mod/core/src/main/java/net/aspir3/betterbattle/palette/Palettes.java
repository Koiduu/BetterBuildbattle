package net.aspir3.betterbattle.palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** /bb palette: a random set of blocks that go well together. */
public final class Palettes {
    private Palettes() {}

    private static volatile Palette lastRoll;
    private static volatile long lastRollAt;

    public static final class Palette {
        public final String name;
        public final List<String> blocks;
        Palette(String name, List<String> blocks) { this.name = name; this.blocks = blocks; }
    }

    public static synchronized Palette lastRoll() { return lastRoll; }
    public static synchronized long lastRollAt() { return lastRollAt; }

    // Blocks before "|" exist on both 26.3 and 1.8.9; the ones after are modern only.
    private static final String[][] THEMES = {
        {"Cottage", "oak_planks spruce_planks cobblestone stone_bricks oak_log bricks | stripped_spruce_log mud_bricks"},
        {"Desert", "sandstone cut_sandstone chiseled_sandstone terracotta orange_terracotta red_sand | smooth_sandstone packed_mud"},
        {"Nether", "nether_bricks netherrack soul_sand quartz_block glowstone nether_brick_fence | blackstone red_nether_bricks crimson_planks"},
        {"Icy", "packed_ice snow_block white_wool light_blue_stained_glass quartz_block diorite | blue_ice calcite"},
        {"Ocean", "prismarine prismarine_bricks dark_prismarine sea_lantern light_blue_wool cyan_terracotta | tube_coral_block"},
        {"Autumn", "dark_oak_planks spruce_planks orange_wool red_wool brown_wool yellow_terracotta | stripped_dark_oak_log"},
        {"Candy", "pink_wool magenta_wool white_wool light_blue_wool lime_wool yellow_wool | pink_concrete"},
        {"Castle", "stone_bricks mossy_stone_bricks cracked_stone_bricks andesite cobblestone dark_oak_planks | deepslate_bricks tuff_bricks"},
        {"Forest", "oak_log oak_leaves podzol grass_block dirt mossy_cobblestone | moss_block mud"},
        {"Futuristic", "quartz_block white_stained_glass light_gray_wool cyan_terracotta sea_lantern iron_block | white_concrete smooth_quartz"},
        {"Spooky", "dark_oak_planks gray_wool black_wool soul_sand cobweb jack_o_lantern | deepslate_tiles polished_blackstone"},
        {"Royal", "gold_block purple_wool red_wool quartz_pillar dark_oak_planks chiseled_quartz_block | purple_concrete"},
    };

    public static Palette roll(int size, boolean legacy, Random random) {
        String[] theme = THEMES[random.nextInt(THEMES.length)];
        String[] parts = theme[1].split("\\|");
        List<String> pool = new ArrayList<String>(Arrays.asList(parts[0].trim().split("\\s+")));
        if (!legacy && parts.length > 1) pool.addAll(Arrays.asList(parts[1].trim().split("\\s+")));
        Collections.shuffle(pool, random);
        int n = Math.max(3, Math.min(size, pool.size()));
        Palette result = new Palette(theme[0], new ArrayList<String>(pool.subList(0, n)));
        synchronized (Palettes.class) {
            lastRollAt = System.currentTimeMillis();
            lastRoll = result;
        }
        return result;
    }
}
