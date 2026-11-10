package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Which 1.8.9 item stands for which achievement. */
final class Icons {
    private Icons() {}

    private static final Map<String, ItemStack> BY_ID = new HashMap<String, ItemStack>();
    private static final Map<String, ItemStack> BY_TRACK = new HashMap<String, ItemStack>();

    static {
        BY_ID.put("all_wood", new ItemStack(Blocks.log));
        BY_ID.put("stone_age", new ItemStack(Blocks.stonebrick));
        BY_ID.put("rainbow", new ItemStack(Blocks.wool, 1, 14));
        BY_ID.put("monochrome", new ItemStack(Blocks.wool));
        BY_ID.put("minimalist", new ItemStack(Items.flower_pot));
        BY_ID.put("one_block_wonder", new ItemStack(Blocks.diamond_block));
        BY_ID.put("nether_native", new ItemStack(Blocks.netherrack));
        BY_ID.put("hotbar_hero", new ItemStack(Blocks.chest));
        BY_ID.put("block_hoarder", new ItemStack(Blocks.bookshelf));
        BY_ID.put("old_school", new ItemStack(Blocks.cobblestone));
        BY_ID.put("steam_engine", new ItemStack(Items.lava_bucket));
        BY_ID.put("tab_building", new ItemStack(Blocks.brick_block));
        BY_ID.put("tab_colored", new ItemStack(Blocks.wool, 1, 9));
        BY_ID.put("tab_natural", new ItemStack(Blocks.grass));
        BY_ID.put("tab_functional", new ItemStack(Blocks.crafting_table));
        BY_ID.put("tab_redstone", new ItemStack(Items.redstone));
        BY_ID.put("sky_high", new ItemStack(Blocks.ladder));
        BY_ID.put("grounded", new ItemStack(Items.iron_boots));
        BY_ID.put("floor_is_lava", new ItemStack(Items.magma_cream));
        BY_ID.put("factory_settings", new ItemStack(Blocks.stained_hardened_clay));
        BY_ID.put("indecisive", new ItemStack(Items.painting));
        BY_ID.put("mode_marathon", new ItemStack(Items.clock));
        BY_ID.put("hat_trick", new ItemStack(Items.golden_helmet));
        BY_ID.put("unstoppable", new ItemStack(Items.blaze_powder));
        BY_ID.put("night_owl", new ItemStack(Items.bed));
        BY_ID.put("century_day", new ItemStack(Items.experience_bottle));
        BY_ID.put("comeback", new ItemStack(Items.golden_apple));
        BY_ID.put("perfect_crowd", new ItemStack(Blocks.jukebox));
        BY_ID.put("ss_streak", new ItemStack(Items.nether_star));
        BY_ID.put("underdog", new ItemStack(Items.bone));
        BY_ID.put("spb_sweep", new ItemStack(Items.feather));
        BY_ID.put("spb_wr_close", new ItemStack(Items.ghast_tear));
        BY_ID.put("spb_wr", new ItemStack(Blocks.dragon_egg));
        BY_ID.put("spb_no_break", new ItemStack(Items.shears));
        BY_ID.put("gtb_first_guess", new ItemStack(Items.sugar));
        BY_ID.put("gtb_all_rounds", new ItemStack(Items.ender_eye));
        BY_ID.put("welcome", new ItemStack(Blocks.sapling));
        BY_ID.put("random_palette_win", new ItemStack(Items.painting));
        BY_ID.put("pinned", new ItemStack(Items.item_frame));
        BY_ID.put("offline_saver", new ItemStack(Items.compass));

        BY_TRACK.put("bb_wins", new ItemStack(Items.gold_ingot));
        BY_TRACK.put("bb_score", new ItemStack(Items.emerald));
        BY_TRACK.put("perfect_builds", new ItemStack(Items.diamond));
        BY_TRACK.put("correct_guesses", new ItemStack(Items.name_tag));
        BY_TRACK.put("pro_wins", new ItemStack(Items.diamond_pickaxe));
        BY_TRACK.put("teams_wins", new ItemStack(Items.lead));
        BY_TRACK.put("solo_wins", new ItemStack(Items.iron_ingot));
        BY_TRACK.put("spb_wins", new ItemStack(Items.clock));
        BY_TRACK.put("themes_built", new ItemStack(Items.book));
        BY_TRACK.put("ss_grades", new ItemStack(Items.nether_star));
        BY_TRACK.put("crowd_90", new ItemStack(Items.golden_apple));
        BY_TRACK.put("precision_100", new ItemStack(Items.arrow));
        BY_TRACK.put("pbs_set", new ItemStack(Items.compass));
        BY_TRACK.put("days_played", new ItemStack(Blocks.double_plant));
    }

    static ItemStack of(Catalog.Entry e) {
        ItemStack s = BY_ID.get(e.id);
        if (s == null && e.track != null) s = BY_TRACK.get(e.track);
        if (s == null) {
            if ("speed_builders".equals(e.category)) s = new ItemStack(Items.clock);
            else if ("gtb".equals(e.category)) s = new ItemStack(Items.name_tag);
            else if ("better_battle".equals(e.category)) s = new ItemStack(Items.nether_star);
            else if ("challenge".equals(e.category)) s = new ItemStack(Blocks.brick_block);
            else s = new ItemStack(Items.gold_ingot);
        }
        return s;
    }
}
