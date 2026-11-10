package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Which item stands for which achievement. */
final class Icons {
    private Icons() {}

    private static final Map<String, Item> BY_ID = new HashMap<>();
    private static final Map<String, Item> BY_TRACK = new HashMap<>();
    private static final Map<String, Item> BY_CATEGORY = new HashMap<>();

    static {
        BY_ID.put("all_wood", Items.OAK_LOG);
        BY_ID.put("stone_age", Items.STONE_BRICKS);
        BY_ID.put("rainbow", Items.WOOL.pick(DyeColor.RED));
        BY_ID.put("monochrome", Items.WOOL.pick(DyeColor.WHITE));
        BY_ID.put("minimalist", Items.FLOWER_POT);
        BY_ID.put("one_block_wonder", Items.DIAMOND_BLOCK);
        BY_ID.put("nether_native", Items.NETHERRACK);
        BY_ID.put("hotbar_hero", Items.CHEST);
        BY_ID.put("block_hoarder", Items.BARREL);
        BY_ID.put("old_school", Items.COBBLESTONE);
        BY_ID.put("steam_engine", Items.LAVA_BUCKET);
        BY_ID.put("tab_building", Items.BRICKS);
        BY_ID.put("tab_colored", Items.WOOL.pick(DyeColor.CYAN));
        BY_ID.put("tab_natural", Items.GRASS_BLOCK);
        BY_ID.put("tab_functional", Items.CRAFTING_TABLE);
        BY_ID.put("tab_redstone", Items.REDSTONE);
        BY_ID.put("sky_high", Items.LADDER);
        BY_ID.put("grounded", Items.IRON_BOOTS);
        BY_ID.put("floor_is_lava", Items.MAGMA_BLOCK);
        BY_ID.put("factory_settings", Items.DYED_TERRACOTTA.pick(DyeColor.WHITE));
        BY_ID.put("indecisive", Items.PAINTING);
        BY_ID.put("mode_marathon", Items.CLOCK);
        BY_ID.put("hat_trick", Items.GOLDEN_HELMET);
        BY_ID.put("unstoppable", Items.BLAZE_POWDER);
        BY_ID.put("night_owl", Items.BED.pick(DyeColor.BLACK));
        BY_ID.put("century_day", Items.EXPERIENCE_BOTTLE);
        BY_ID.put("comeback", Items.TOTEM_OF_UNDYING);
        BY_ID.put("perfect_crowd", Items.JUKEBOX);
        BY_ID.put("ss_streak", Items.NETHER_STAR);
        BY_ID.put("underdog", Items.BONE);
        BY_ID.put("spb_sweep", Items.BRUSH);
        BY_ID.put("spb_wr_close", Items.PHANTOM_MEMBRANE);
        BY_ID.put("spb_wr", Items.DRAGON_EGG);
        BY_ID.put("spb_no_break", Items.SHEARS);
        BY_ID.put("gtb_first_guess", Items.SUGAR);
        BY_ID.put("gtb_all_rounds", Items.ENDER_EYE);
        BY_ID.put("welcome", Items.OAK_SAPLING);
        BY_ID.put("random_palette_win", Items.PAINTING);
        BY_ID.put("pinned", Items.ITEM_FRAME);
        BY_ID.put("offline_saver", Items.COMPASS);

        BY_TRACK.put("bb_wins", Items.GOLD_INGOT);
        BY_TRACK.put("bb_score", Items.EMERALD);
        BY_TRACK.put("perfect_builds", Items.DIAMOND);
        BY_TRACK.put("correct_guesses", Items.NAME_TAG);
        BY_TRACK.put("pro_wins", Items.DIAMOND_PICKAXE);
        BY_TRACK.put("teams_wins", Items.LEAD);
        BY_TRACK.put("solo_wins", Items.IRON_INGOT);
        BY_TRACK.put("spb_wins", Items.CLOCK);
        BY_TRACK.put("themes_built", Items.BOOK);
        BY_TRACK.put("ss_grades", Items.NETHER_STAR);
        BY_TRACK.put("crowd_90", Items.GOLDEN_APPLE);
        BY_TRACK.put("precision_100", Items.SPYGLASS);
        BY_TRACK.put("pbs_set", Items.RECOVERY_COMPASS);
        BY_TRACK.put("days_played", Items.SUNFLOWER);

        BY_CATEGORY.put("challenge", Items.BRICKS);
        BY_CATEGORY.put("speed_builders", Items.CLOCK);
        BY_CATEGORY.put("gtb", Items.NAME_TAG);
        BY_CATEGORY.put("better_battle", Items.NETHER_STAR);
    }

    static ItemStack of(Catalog.Entry e) {
        Item item = BY_ID.get(e.id);
        if (item == null && e.track != null) item = BY_TRACK.get(e.track);
        if (item == null) item = BY_CATEGORY.getOrDefault(e.category, Items.GOLD_INGOT);
        return new ItemStack(item);
    }
}
