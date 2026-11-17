import net.aspir3.betterbattle.achieve.AchievementRules;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Standalone tests, like Replay; run with the core, Gson and SQLite on the classpath. */
public final class AchievementRulesTest {
    private static int assertions;

    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions);
    }

    private static MatchRecord win(String mode) {
        MatchRecord m = new MatchRecord();
        m.mode = mode;
        m.placement = 1;
        m.playedAt = System.currentTimeMillis();
        return m;
    }

    public static void main(String[] args) throws Exception {
        for (int score : new int[] {200, 300, 350, 400}) {
            AchievementRules.Rule rule = new AchievementRules.HighScoreWin(score);
            MatchRecord m = win("teams");
            check(!rule.onMatch(m, null));
            m.score = score - 1.0;
            check(!rule.onMatch(m, null));
            m.score = (double) score;
            check(rule.onMatch(m, null));
            m.score++;
            check(rule.onMatch(m, null));
            m.placement = 2;
            check(!rule.onMatch(m, null));
            m.placement = 1;
            m.mode = "gtb";
            check(!rule.onMatch(m, null));
        }
        for (AchievementRules.Rule rule : Arrays.<AchievementRules.Rule>asList(
                new AchievementRules.SlabsOnly(), new AchievementRules.MenuBlocksOnly())) {
            MatchRecord m = win("solo");
            check(!rule.onMatch(m, null));
            m.countBlock(rule instanceof AchievementRules.SlabsOnly ? "minecraft:oak_slab" : "minecraft:crafting_table");
            check(rule.onMatch(m, null));
            m.mode = "speed_builders";
            check(!rule.onMatch(m, null));
            m.mode = "pro";
            m.placement = null;
            check(!rule.onMatch(m, null));
            m.placement = 1;
            m.countBlock("minecraft:stone");
            check(!rule.onMatch(m, null));
        }
        MatchRecord m = win("teams");
        AchievementRules.Rule noJump = new AchievementRules.TeamsNoJumping();
        check(!noJump.onMatch(m, null));
        m.countBlock("minecraft:stone");
        check(noJump.onMatch(m, null));
        m.jumped = true;
        check(!noJump.onMatch(m, null));
        m.jumped = false;
        m.mode = "solo";
        check(!noJump.onMatch(m, null));
        m.mode = "teams";
        m.placement = 2;
        check(!noJump.onMatch(m, null));

        GameStateMachine game = new GameStateMachine(new ChatParser(new InputStreamReader(
            AchievementRulesTest.class.getResourceAsStream("/betterbattle/patterns.json"))), new GameStateMachine.Listener() {
                public void onStateChanged(GameStateMachine.State state) {}
                public void onMatchFinished(MatchRecord match) { check(match.jumped); }
            }, "26.3", "Builder");
        game.onJumping();
        game.onSidebar("BUILD BATTLE", Arrays.asList("Mode: Teams"));
        game.onChat("Build something relevant to the theme: Castle");
        game.onJumping();
        game.leave();

        File dir = Files.createTempDirectory("achievement-rules-test").toFile();
        try {
            try (LocalDatabase db = new LocalDatabase(dir, "test")) {
                AchievementRules.Rule gtbStreak = new AchievementRules.GtbWinStreak();
                check(!gtbStreak.onMatch(win("gtb"), db));
                for (int i = 1; i <= 3; i++) {
                    m = win("gtb"); m.playedAt = i; db.insertMatch(m);
                    check(gtbStreak.onMatch(m, db) == (i == 3));
                }
                m = win("solo"); m.playedAt = 4; db.insertMatch(m);
                check(!gtbStreak.onMatch(m, db));
                check(!gtbStreak.onMatch(win("gtb"), db));
                m = win("gtb"); m.playedAt = 5; m.placement = 2; db.insertMatch(m);
                check(!gtbStreak.onMatch(m, db));
            }
            try (LocalDatabase db = new LocalDatabase(dir, "streak")) {
                for (int n : new int[] {25, 50}) check(!new AchievementRules.WinStreak(n, "test").onMatch(win("solo"), db));
                for (int i = 1; i <= 50; i++) {
                    m = win("solo"); m.playedAt = i; db.insertMatch(m);
                    for (int n : new int[] {25, 50}) check(new AchievementRules.WinStreak(n, "test").onMatch(m, db) == (i >= n));
                }
                m = win("solo"); m.playedAt = 51; m.placement = null; db.insertMatch(m);
                for (int n : new int[] {25, 50}) check(!new AchievementRules.WinStreak(n, "test").onMatch(win("solo"), db));
            }
            try (LocalDatabase db = new LocalDatabase(dir, "themes")) {
                for (String mode : new String[] {"solo", "teams", "pro"}) {
                    check(db.distinctThemesWon(mode) == 0);
                    for (int i = 1; i <= 50; i++) {
                        m = win(mode); m.theme = "Theme " + i; db.insertMatch(m);
                        for (int n : new int[] {10, 25, 50}) check(new AchievementRules.DistinctThemeWins(mode, n).onMatch(m, db) == (i >= n));
                    }
                    m = win(mode); m.theme = " THEME 1 "; db.insertMatch(m);
                    m = win(mode); m.theme = " "; db.insertMatch(m);
                    m = win(mode); db.insertMatch(m);
                    m = win(mode); m.theme = "Lost theme"; m.placement = 2; db.insertMatch(m);
                    check(db.distinctThemesWon(mode) == 50);
                    check(!new AchievementRules.DistinctThemeWins(mode, 10).onMatch(m, db));
                    m = win("gtb"); m.theme = "Theme 1";
                    check(!new AchievementRules.DistinctThemeWins(mode, 10).onMatch(m, db));
                    m = win(mode);
                    check(!new AchievementRules.DistinctThemeWins(mode, 10).onMatch(m, db));
                }
            }
        } finally { delete(dir); }
        Set<String> ids = new HashSet<String>();
        for (AchievementRules.Rule rule : AchievementRules.all()) {
            check(ids.add(rule.id()));
            check(Catalog.get(rule.id()) != null);
        }
        Set<String> modes = new HashSet<String>(Arrays.asList("solo", "teams", "pro", "gtb", "speed_builders"));
        for (Catalog.Entry entry : Catalog.list()) {
            check(entry.modes != null && !entry.modes.isEmpty() && modes.containsAll(entry.modes));
            check(new HashSet<String>(entry.modes).size() == entry.modes.size());
        }
        System.out.println("AchievementRulesTest: " + assertions + " assertions passed");
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) throw new AssertionError("cannot delete test file " + file);
    }
}
