package net.aspir3.betterbattle.achieve;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.game.SpbTracker;
import net.aspir3.betterbattle.palette.Palettes;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
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
            new AllWood(), new Monochrome(), new Minimalist(), new OneBlockType(), new NetherOnly(),
            new StoneOnly(), new Rainbow(), new HotbarOnly(), new HundredBlockTypes(),
            new LegacyBlocksOnly(), new WaterAndLava(), new ModeMarathon(),
            new WinStreak(3, "hat_trick"), new WinStreak(10, "unstoppable"), new SsStreak5(),
            new Comeback(), new NightOwl(), new HundredInADay(), new PerfectCrowd(), new FullLobbyWin(),
            new SpbNoBreak(), new SpbSweep(), new WithinHalfSecondOfWr(), new SetWr(),
            new SpbGrounded(), new GoodEnough(), new AssemblyLine(), new PhotoFinish(), new FromTheBottom(), new Speedrunner(),
            new GtbFastGuess(), new GtbAllRounds(), new PaletteRollWin(), new OfflineGames(),
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

    public static final class Monochrome implements Rule {
        private static final String[] COLOURS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black", "gray"
        };
        private static final Set<String> TYPES = new HashSet<String>(Arrays.asList(
            "wool", "carpet", "concrete", "concrete_powder", "terracotta", "glazed_terracotta",
            "stained_glass", "stained_glass_pane"));

        public String id() { return "monochrome"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won() || m.palette.isEmpty()) return false;
            String colour = null;
            for (String id : m.palette.keySet()) {
                String block = bare(id);
                String blockColour = null;
                for (String candidate : COLOURS) {
                    if (block.startsWith(candidate + "_")) {
                        blockColour = candidate;
                        break;
                    }
                }
                if (blockColour == null || !TYPES.contains(block.substring(blockColour.length() + 1))) return false;
                if (colour == null) colour = blockColour;
                else if (!colour.equals(blockColour)) return false;
            }
            return true;
        }
    }

    public static final class Minimalist implements Rule {
        public String id() { return "minimalist"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return "solo".equals(m.mode) && m.won() && m.blocksPlaced > 0 && m.blocksPlaced < 100;
        }
    }

    public static final class OneBlockType implements Rule {
        public String id() { return "one_block_wonder"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return m.won() && m.palette.size() == 1;
        }
    }

    public static final class NetherOnly extends OnlyBlocks {
        private static final Set<String> BLOCKS = new HashSet<String>(Arrays.asList(
            "netherrack", "soul_sand", "soul_soil", "glowstone", "magma_block", "nether_quartz_ore",
            "nether_gold_ore", "ancient_debris", "basalt", "polished_basalt", "blackstone",
            "gilded_blackstone", "chiseled_polished_blackstone", "crying_obsidian", "obsidian", "shroomlight",
            "nether_wart_block", "warped_wart_block", "crimson_nylium", "warped_nylium", "bone_block",
            "gold_block", "chain", "soul_lantern", "soul_torch", "lava",
            "nether_bricks", "cracked_nether_bricks", "chiseled_nether_bricks", "nether_brick_fence",
            "nether_brick_stairs", "nether_brick_slab", "nether_brick_wall", "red_nether_bricks",
            "red_nether_brick_stairs", "red_nether_brick_slab", "red_nether_brick_wall",
            "weeping_vines", "twisting_vines", "nether_sprouts", "nether_wart"));

        public String id() { return "nether_native"; }

        boolean allowed(String b) {
            return BLOCKS.contains(b) || b.startsWith("polished_blackstone")
                || b.startsWith("crimson_") || b.startsWith("warped_");
        }
    }

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
            return votedMode && m.won() && m.floorChanges == 0 && m.backdropChanges == 0;
        }
    }

    public static final class BackgroundSwitches implements Rule {
        public String id() { return "indecisive"; }
        public boolean onMatch(MatchRecord m, LocalDatabase db) { return m.backdropChanges >= 5; }
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

    public static final class WinStreak implements Rule {
        private final int n;
        private final String id;

        public WinStreak(int n, String id) {
            this.n = n;
            this.id = id;
        }

        public String id() { return id; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!m.won()) return false;
            List<MatchRecord> recent = db.recentMatches(n);
            if (recent.size() != n) return false;
            for (MatchRecord match : recent) if (!match.won()) return false;
            return true;
        }
    }

    public static final class SsStreak5 implements Rule {
        public String id() { return "ss_streak"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            List<MatchRecord> recent = db.recentMatches(5);
            if (recent.size() != 5) return false;
            for (MatchRecord match : recent) if (!"SS".equals(match.grade)) return false;
            return true;
        }
    }

    public static final class Comeback implements Rule {
        public String id() { return "comeback"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!m.won()) return false;
            List<MatchRecord> recent = db.recentMatches(2);
            if (recent.size() != 2) return false;
            MatchRecord previous = recent.get(1);
            return previous.placement != null && previous.players != null
                && previous.placement.equals(previous.players);
        }
    }

    public static final class NightOwl implements Rule {
        public String id() { return "night_owl"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won()) return false;
            Calendar time = Calendar.getInstance();
            time.setTimeInMillis(m.playedAt);
            int hour = time.get(Calendar.HOUR_OF_DAY);
            return hour == 2 || hour == 3 || hour == 4;
        }
    }

    public static final class HundredInADay implements Rule {
        public String id() { return "century_day"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            Calendar midnight = Calendar.getInstance();
            midnight.setTimeInMillis(m.playedAt);
            midnight.set(Calendar.HOUR_OF_DAY, 0);
            midnight.set(Calendar.MINUTE, 0);
            midnight.set(Calendar.SECOND, 0);
            midnight.set(Calendar.MILLISECOND, 0);
            return db.countMatchesSince(midnight.getTimeInMillis()) >= 100;
        }
    }

    public static final class PerfectCrowd implements Rule {
        public String id() { return "perfect_crowd"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            Double crowdScore = m.crowdScore();
            return crowdScore != null && crowdScore >= 1.0;
        }
    }

    public static final class FullLobbyWin implements Rule {
        public String id() { return "underdog"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return "solo".equals(m.mode) && m.won() && m.players != null && m.players >= 16;
        }
    }

    public static final class SpbNoBreak implements Rule {
        public String id() { return "spb_no_break"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return "speed_builders".equals(m.mode) && m.won() && m.blocksPlaced > 0 && m.blocksBroken == 0;
        }
    }

    private static boolean spbWin(MatchRecord m) {
        return "speed_builders".equals(m.mode) && m.won() && !m.spbRounds.isEmpty();
    }

    public static final class SpbSweep implements Rule {
        public String id() { return "spb_sweep"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!spbWin(m)) return false;
            for (SpbTracker.Round r : m.spbRounds) if (!r.perfect) return false;
            return true;
        }
    }

    public static final class WithinHalfSecondOfWr implements Rule {
        public String id() { return "spb_wr_close"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!"speed_builders".equals(m.mode)) return false;
            for (SpbTracker.Round r : m.spbRounds) {
                if (!r.perfect || r.timeMs == null) continue;
                Long wr = db.worldRecordMs(r.build);
                if (wr != null && r.timeMs - wr <= 500L) return true;
            }
            return false;
        }
    }

    public static final class SetWr implements Rule {
        public String id() { return "spb_wr"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!"speed_builders".equals(m.mode)) return false;
            for (SpbTracker.Round r : m.spbRounds) {
                if (!r.perfect || r.timeMs == null) continue;
                Long wr = db.worldRecordMs(r.build);
                if (wr != null && r.timeMs < wr) return true;
            }
            return false;
        }
    }

    /** Win a Speed Builders game without flying. */
    public static final class SpbGrounded implements Rule {
        public String id() { return "spb_grounded"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return spbWin(m) && !m.flew && m.blocksPlaced > 0;
        }
    }

    /** Win without a perfect build in every round. */
    public static final class GoodEnough implements Rule {
        public String id() { return "spb_good_enough"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!spbWin(m)) return false;
            for (SpbTracker.Round r : m.spbRounds) if (!r.perfect) return true;
            return false;
        }
    }

    /** Win placing one block type at a time: once you move on from a block, you can't go back to it that round. */
    public static final class AssemblyLine implements Rule {
        public String id() { return "spb_assembly_line"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!spbWin(m)) return false;
            for (SpbTracker.Round r : m.spbRounds) if (!r.oneTypeAtATime) return false;
            return true;
        }
    }

    /** A perfect build with under a second left on the round clock. */
    public static final class PhotoFinish implements Rule {
        public String id() { return "spb_photo_finish"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!"speed_builders".equals(m.mode)) return false;
            for (SpbTracker.Round r : m.spbRounds) {
                if (r.perfect && r.remainingMs != null && r.remainingMs <= 1000) return true;
            }
            return false;
        }
    }

    /** Win after the slowest perfect build of round 1 (at least two others got one first). */
    public static final class FromTheBottom implements Rule {
        public String id() { return "spb_from_the_bottom"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!spbWin(m)) return false;
            SpbTracker.Round first = m.spbRounds.get(0);
            return first.perfect && first.othersPerfect >= 2 && first.othersPerfectBefore == first.othersPerfect;
        }
    }

    /** Win with a perfect build in under 15 seconds in every round. */
    public static final class Speedrunner implements Rule {
        public static final int LIMIT_MS = 15000;

        public String id() { return "spb_speedrunner"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!spbWin(m) || m.spbRounds.size() < 3) return false;
            for (SpbTracker.Round r : m.spbRounds) {
                if (!r.perfect || r.timeMs == null || r.timeMs >= LIMIT_MS) return false;
            }
            return true;
        }
    }

    public static final class GtbFastGuess implements Rule {
        public String id() { return "gtb_first_guess"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return "gtb".equals(m.mode) && m.gtbFastestGuessMs != null && m.gtbFastestGuessMs <= 3000;
        }
    }

    public static final class GtbAllRounds implements Rule {
        public String id() { return "gtb_all_rounds"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            return "gtb".equals(m.mode) && m.gtbRounds >= 2 && m.gtbCorrect >= m.gtbRounds - 1;
        }
    }

    public static final class PaletteRollWin implements Rule {
        private static final long ROLL_WINDOW_MS = 2L * 60L * 60L * 1000L;

        public String id() { return "random_palette_win"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            Palettes.Palette roll = Palettes.lastRoll();
            long rolledAt = Palettes.lastRollAt();
            long age = m.playedAt - rolledAt;
            if (!m.won() || m.palette.isEmpty() || roll == null || rolledAt == 0
                || age < 0 || age > ROLL_WINDOW_MS) return false;
            for (String block : m.palette.keySet()) if (!roll.blocks.contains(bare(block))) return false;
            return true;
        }
    }

    public static final class OfflineGames implements Rule {
        public String id() { return "offline_saver"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            return Long.parseLong(db.getMeta("offline_games", "0")) >= 10;
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
