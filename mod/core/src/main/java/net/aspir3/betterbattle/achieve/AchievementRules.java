package net.aspir3.betterbattle.achieve;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rule interface + the first concrete rules. Every unlock is written to the local DB first. */
public final class AchievementRules {
    private AchievementRules() {}

    public interface Rule {
        String id();
        /** Return true if this match unlocks the achievement. */
        boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception;
    }

    /** Win a game using only wood-type blocks. */
    public static final class AllWood implements Rule {
        private static final String[] WOOD_SUFFIXES = {
            "_planks", "_log", "_wood", "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor",
            "_button", "_pressure_plate", "_sign", "_stem", "_hyphae"
        };
        private static final Set<String> WOOD_PREFIXES = new HashSet<String>(Arrays.asList(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak",
            "bamboo", "crimson", "warped", "stripped_oak", "stripped_spruce", "stripped_birch",
            "stripped_jungle", "stripped_acacia", "stripped_dark_oak", "stripped_mangrove", "stripped_cherry",
            "stripped_pale_oak", "stripped_crimson", "stripped_warped"
        ));
        // 1.8.9 ids (planks/log/log2/wooden_slab/…) are mapped to modern names by the forge-1.8.9 adapter.

        public String id() { return "all_wood"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) {
            if (!m.won() || m.palette.isEmpty()) return false;
            for (String block : m.palette.keySet()) if (!isWood(block)) return false;
            return true;
        }

        static boolean isWood(String id) {
            String b = id.startsWith("minecraft:") ? id.substring(10) : id;
            for (String suf : WOOD_SUFFIXES) {
                if (b.endsWith(suf)) {
                    String material = b.substring(0, b.length() - suf.length());
                    if (WOOD_PREFIXES.contains(material)) return true;
                }
            }
            return false;
        }
    }

    /** Win in every Build Battle mode within one hour. */
    public static final class ModeMarathon implements Rule {
        private static final List<String> REQUIRED = Arrays.asList("solo", "teams", "pro", "gtb", "speed_builders");

        public String id() { return "mode_marathon"; }

        public boolean onMatch(MatchRecord m, LocalDatabase db) throws Exception {
            if (!m.won()) return false;
            List<String> won = db.modesWonSince(m.playedAt - 60L * 60L * 1000L);
            return won.containsAll(REQUIRED);
        }
    }

    /** Tiered track (wins, perfect builds, guesses). Also runs retroactively on startup from totals. */
    public static final class Tiered {
        public final String trackId;
        public final int[] thresholds;   // e.g. {10, 100, 500, 1000, 5000}

        public Tiered(String trackId, int... thresholds) {
            this.trackId = trackId;
            this.thresholds = thresholds;
        }

        /** Returns how many tiers the total reaches; caller unlocks "<track>_t1".."<track>_tN". */
        public int tiersReached(long total) {
            int n = 0;
            for (int t : thresholds) if (total >= t) n++;
            return n;
        }
    }
}
