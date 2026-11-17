import net.aspir3.betterbattle.achieve.AchievementRules;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.legacy.LegacyIds;

public final class LegacyIdsTest {
    private static int assertions;

    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions);
    }

    private static void slab(String legacy, int meta, String modern) {
        String mapped = LegacyIds.modern(legacy, meta);
        check(mapped.equals("minecraft:" + modern));
        MatchRecord match = new MatchRecord();
        match.mode = "teams";
        match.placement = 1;
        match.countBlock(mapped);
        check(new AchievementRules.SlabsOnly().onMatch(match, null));
        match.countBlock("minecraft:stone");
        check(!new AchievementRules.SlabsOnly().onMatch(match, null));
    }

    public static void main(String[] args) {
        String[] stones = {"smooth_stone", "sandstone", "petrified_oak", "cobblestone", "brick",
            "stone_brick", "nether_brick", "quartz"};
        for (String legacy : new String[] {"stone_slab", "double_stone_slab"}) {
            for (int meta = 0; meta < 16; meta++) slab("minecraft:" + legacy, meta, stones[meta & 7] + "_slab");
        }
        for (String legacy : new String[] {"stone_slab2", "double_stone_slab2"}) {
            slab(legacy, 0, "red_sandstone_slab");
            slab(legacy, 8, "red_sandstone_slab");
        }
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
        for (String legacy : new String[] {"wooden_slab", "double_wooden_slab"}) {
            for (int meta = 0; meta < 6; meta++) {
                slab(legacy, meta, woods[meta] + "_slab");
                slab(legacy, meta | 8, woods[meta] + "_slab");
            }
        }
        check(LegacyIds.modern("minecraft:stone", 0).equals("minecraft:stone"));
        check(LegacyIds.modern("minecraft:red_sandstone", 0).equals("minecraft:red_sandstone"));
        check(LegacyIds.modern("minecraft:smooth_stone", 0).equals("minecraft:smooth_stone"));
        check(LegacyIds.modern("minecraft:unknown_block", 0).equals("minecraft:unknown_block"));
        System.out.println("LegacyIdsTest: " + assertions + " assertions passed");
    }
}
