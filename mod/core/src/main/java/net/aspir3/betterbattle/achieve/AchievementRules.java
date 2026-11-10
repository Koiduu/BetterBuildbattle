package net.aspir3.betterbattle.achieve;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One class per challenge in achievements.yaml. Every unlock is saved locally first, then confirmed on sync. */
public final class AchievementRules {
    private AchievementRules() {}

    public interface Rule {
        String id();
        boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception;
    }

    /** All rules the mod checks after each game. */
    public static List<Rule> all() {
        List<Rule> rules = new ArrayList<Rule>(Arrays.<Rule>asList(
            new AllWood(), new StoneOnly(), new Rainbow(), new HotbarOnly(), new HundredBlockTypes(),
            new LegacyBlocksOnly(), new LatestReleaseOnly(), new WaterAndLava(), new ModeMarathon(),
            new HeightLimit(), new NoFlying(), new LavaFloor(), new DefaultPlot(), new BackgroundSwitches()));
        for (String tab : new String[] {"building", "colored", "natural", "functional", "redstone"}) {
            rules.add(new CreativeTabOnly(tab));
        }
        return rules;
    }

    static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** Base for "win using only blocks that match X" challenges. */
    abstract static class OnlyBlocks implements Rule {
        abstract boolean allowed(String block);

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won() || m.palette.isEmpty()) return false;
            for (String block : m.palette.keySet()) {
                if (!allowed(bare(block))) return false;
            }
            return true;
        }
    }

    // ---- Palette challenges

    public static final class AllWood extends OnlyBlocks {
        private static final String[] SUFFIXES = {
            "_planks", "_log", "_wood", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor",
            "_button", "_pressure_plate", "_sign", "_stem", "_hyphae"
        };
        private static final Set<String> WOODS = new HashSet<String>(Arrays.asList(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak",
            "bamboo", "crimson", "warped"));

        public String id() { return "all_wood"; }

        boolean allowed(String b) {
            if (b.startsWith("stripped_")) b = b.substring(9);
            for (String suffix : SUFFIXES) {
                if (b.endsWith(suffix) && WOODS.contains(b.substring(0, b.length() - suffix.length()))) return true;
            }
            return false;
        }
    }

    public static final class StoneOnly extends OnlyBlocks {
        private static final String[] STONE = {
            "stone", "cobblestone", "andesite", "diorite", "granite", "deepslate", "blackstone", "tuff",
            "basalt", "calcite", "dripstone"
        };
        private static final String[] NOT_STONE = {"redstone", "glowstone", "sandstone", "lodestone"};

        public String id() { return "stone_age"; }

        boolean allowed(String b) {
            for (String no : NOT_STONE) if (b.contains(no)) return false;
            for (String yes : STONE) if (b.contains(yes)) return true;
            return false;
        }
    }

    public static final class Rainbow implements Rule {
        // Each rainbow colour and the block colour prefixes that count for it
        private static final String[][] COLOURS = {
            {"red_"}, {"orange_"}, {"yellow_"}, {"green_", "lime_"}, {"blue_", "light_blue_"}, {"purple_", "magenta_"}
        };

        public String id() { return "rainbow"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won()) return false;
            for (String[] colour : COLOURS) {
                boolean found = false;
                for (String block : m.palette.keySet()) {
                    for (String prefix : colour) if (bare(block).startsWith(prefix)) found = true;
                }
                if (!found) return false;
            }
            return true;
        }
    }

    public static final class HotbarOnly implements Rule {
        public String id() { return "hotbar_hero"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return m.won() && !m.palette.isEmpty() && m.palette.size() <= 9;
        }
    }

    public static final class HundredBlockTypes implements Rule {
        public String id() { return "block_hoarder"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return m.palette.size() >= 100;
        }
    }

    /** Only counts on 26.3, since every block on a 1.8.9 client is a 1.8.9 block. */
    public static final class LegacyBlocksOnly extends OnlyBlocks {
        public String id() { return "old_school"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if ("1.8.9".equals(m.mcVersion) || BlockLists.LEGACY_1_8_9.isEmpty()) return false;
            return super.onMatch(m, db);
        }

        boolean allowed(String b) { return BlockLists.LEGACY_1_8_9.contains(b); }
    }

    public static final class LatestReleaseOnly extends OnlyBlocks {
        public String id() { return "fresh_off_the_press"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (BlockLists.LATEST_RELEASE.isEmpty()) return false;
            return super.onMatch(m, db);
        }

        boolean allowed(String b) { return BlockLists.LATEST_RELEASE.contains(b); }
    }

    /** Bucket placements are reported by the adapter as minecraft:water / minecraft:lava. */
    public static final class WaterAndLava implements Rule {
        public String id() { return "steam_engine"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            boolean water = false, lava = false;
            for (String block : m.palette.keySet()) {
                String b = bare(block);
                if (b.equals("water")) water = true;
                if (b.equals("lava")) lava = true;
            }
            return m.won() && water && lava;
        }
    }

    public static final class CreativeTabOnly implements Rule {
        private final String tab;

        public CreativeTabOnly(String tab) { this.tab = tab; }

        public String id() { return "tab_" + tab; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won() || m.palette.isEmpty()) return false;
            for (String block : m.palette.keySet()) {
                // A block with no known tab doesn't count either way, but at least one must be known.
                String t = m.blockTabs.get(block);
                if (t != null && !t.equals(tab)) return false;
            }
            return m.blockTabs.containsValue(tab);
        }
    }

    // ---- Plot and movement challenges

    public static final class HeightLimit implements Rule {
        public String id() { return "sky_high"; }
        public boolean onMatch(MatchRecord m, LocalDatabase db) { return m.won() && m.reachedHeightLimit; }
    }

    public static final class NoFlying implements Rule {
        public String id() { return "grounded"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            // Speed Builders and Guess the Build have no free flight to give up
            boolean votedMode = "solo".equals(m.mode) || "teams".equals(m.mode) || "pro".equals(m.mode);
            return votedMode && m.won() && !m.flew && m.blocksPlaced > 0;
        }
    }

    public static final class LavaFloor implements Rule {
        public String id() { return "floor_is_lava"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return m.won() && m.floor != null && bare(m.floor).equals("lava");
        }
    }

    public static final class DefaultPlot implements Rule {
        public String id() { return "factory_settings"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            boolean votedMode = "solo".equals(m.mode) || "teams".equals(m.mode) || "pro".equals(m.mode);
            return votedMode && m.won() && m.floorChanges == 0 && m.backgroundChanges == 0;
        }
    }

    public static final class BackgroundSwitches implements Rule {
        public String id() { return "indecisive"; }
        public boolean onMatch(MatchRecord m, LocalDatabase db) { return m.backgroundChanges >= 5; }
    }

    // ---- Time and streak challenges

    public static final class ModeMarathon implements Rule {
        private static final List<String> REQUIRED = Arrays.asList("solo", "teams", "pro", "gtb", "speed_builders");

        public String id() { return "mode_marathon"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!m.won()) return false;
            return db.modesWonSince(m.playedAt - 60L * 60L * 1000L).containsAll(REQUIRED);
        }
    }

    /** Tiered track (wins, perfect builds, guesses). Also runs at startup from totals. */
    public static final class Tiered {
        public final String trackId;
        public final int[] thresholds;

        public Tiered(String trackId, int... thresholds) {
            this.trackId = trackId;
            this.thresholds = thresholds;
        }

        public int tiersReached(long total) {
            int n = 0;
            for (int t : thresholds) if (total >= t) n++;
            return n;
        }
    }
}
