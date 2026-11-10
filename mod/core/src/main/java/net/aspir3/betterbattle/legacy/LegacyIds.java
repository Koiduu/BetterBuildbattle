package net.aspir3.betterbattle.legacy;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.8.9 identifies many blocks by id + metadata ("minecraft:wool" with meta 14 = red wool).
 * Achievement rules use modern ids, so the 1.8.9 build runs every block through here first.
 */
public final class LegacyIds {
    private LegacyIds() {}

    private static final String[] COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    private static final Map<String, String> RENAMED = new HashMap<String, String>();
    static {
        String[][] pairs = {
            {"grass", "grass_block"}, {"web", "cobweb"}, {"deadbush", "dead_bush"}, {"yellow_flower", "dandelion"},
            {"brick_block", "bricks"}, {"nether_brick", "nether_bricks"}, {"hardened_clay", "terracotta"},
            {"lit_pumpkin", "jack_o_lantern"}, {"melon_block", "melon"}, {"waterlily", "lily_pad"},
            {"quartz_ore", "nether_quartz_ore"}, {"noteblock", "note_block"}, {"golden_rail", "powered_rail"},
            {"snow_layer", "snow"}, {"reeds", "sugar_cane"}, {"fence", "oak_fence"}, {"fence_gate", "oak_fence_gate"},
            {"trapdoor", "oak_trapdoor"}, {"wooden_pressure_plate", "oak_pressure_plate"}, {"wooden_button", "oak_button"},
            {"stone_slab", "smooth_stone_slab"}, {"double_stone_slab", "smooth_stone"}, {"mob_spawner", "spawner"},
            {"lit_redstone_lamp", "redstone_lamp"}, {"unpowered_repeater", "repeater"}, {"powered_repeater", "repeater"},
            {"unpowered_comparator", "comparator"}, {"powered_comparator", "comparator"}, {"monster_egg", "infested_stone"},
            {"flowing_water", "water"}, {"flowing_lava", "lava"}, {"tallgrass", "short_grass"}, {"slime", "slime_block"},
            {"stone_stairs", "cobblestone_stairs"}, {"wooden_door", "oak_door"}, {"standing_sign", "oak_sign"},
            {"wall_sign", "oak_wall_sign"}, {"lit_furnace", "furnace"}, {"unlit_redstone_torch", "redstone_torch"},
        };
        for (String[] p : pairs) RENAMED.put(p[0], p[1]);
    }

    /** ("minecraft:wool", 14) → "minecraft:red_wool" */
    public static String modern(String id, int meta) {
        String b = id.startsWith("minecraft:") ? id.substring(10) : id;
        return "minecraft:" + convert(b, meta);
    }

    private static String convert(String b, int meta) {
        int color = meta & 15;
        if (b.equals("wool")) return COLORS[color] + "_wool";
        if (b.equals("carpet")) return COLORS[color] + "_carpet";
        if (b.equals("stained_hardened_clay")) return COLORS[color] + "_terracotta";
        if (b.equals("stained_glass")) return COLORS[color] + "_stained_glass";
        if (b.equals("stained_glass_pane")) return COLORS[color] + "_stained_glass_pane";
        if (b.equals("stone")) {
            String[] s = {"stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite"};
            return s[Math.min(meta, s.length - 1)];
        }
        if (b.equals("planks")) return WOODS[Math.min(meta, 5)] + "_planks";
        if (b.equals("log")) return WOODS[meta & 3] + "_log";
        if (b.equals("log2")) return WOODS[4 + (meta & 1)] + "_log";
        if (b.equals("leaves")) return WOODS[meta & 3] + "_leaves";
        if (b.equals("leaves2")) return WOODS[4 + (meta & 1)] + "_leaves";
        if (b.equals("sapling")) return WOODS[Math.min(meta & 7, 5)] + "_sapling";
        if (b.equals("wooden_slab")) return WOODS[Math.min(meta & 7, 5)] + "_slab";
        if (b.equals("sand")) return meta == 1 ? "red_sand" : "sand";
        if (b.equals("dirt")) return meta == 1 ? "coarse_dirt" : meta == 2 ? "podzol" : "dirt";
        if (b.equals("sandstone")) return meta == 1 ? "chiseled_sandstone" : meta == 2 ? "cut_sandstone" : "sandstone";
        if (b.equals("red_sandstone")) return meta == 1 ? "chiseled_red_sandstone" : meta == 2 ? "cut_red_sandstone" : "red_sandstone";
        if (b.equals("stonebrick")) {
            String[] s = {"stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks"};
            return s[Math.min(meta, 3)];
        }
        if (b.equals("quartz_block")) return meta == 1 ? "chiseled_quartz_block" : meta >= 2 ? "quartz_pillar" : "quartz_block";
        if (b.equals("prismarine")) return meta == 1 ? "prismarine_bricks" : meta == 2 ? "dark_prismarine" : "prismarine";
        if (b.equals("sponge")) return meta == 1 ? "wet_sponge" : "sponge";
        if (b.equals("cobblestone_wall")) return meta == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall";
        if (b.equals("red_flower")) {
            String[] s = {"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"};
            return s[Math.min(meta, s.length - 1)];
        }
        if (b.equals("double_plant")) {
            String[] s = {"sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony"};
            return s[Math.min(meta & 7, s.length - 1)];
        }
        String renamed = RENAMED.get(b);
        return renamed != null ? renamed : b;
    }
}
