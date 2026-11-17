import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.achieve.AchievementBrowser;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.achieve.Recommender;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AchievementBrowserTest {
    private static int assertions;
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions);
    }

    private static Catalog.Entry entry(String id, int ap, String mode) {
        Catalog.Entry e = new Catalog.Entry();
        e.id = id; e.name = id; e.description = id; e.points = ap; e.modes = Arrays.asList(mode);
        return e;
    }

    private static List<String> ids(List<Catalog.Entry> entries) {
        List<String> ids = new ArrayList<String>();
        for (Catalog.Entry e : entries) ids.add(e.id);
        return ids;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Long> counts = new HashMap<String, Long>(), totals = new HashMap<String, Long>();
        List<String> none = Collections.emptyList();
        check(Recommender.pick(Collections.<Catalog.Entry>emptyList(), none, none, counts, totals, 1, "player", "all").isEmpty());
        Catalog.Entry solo = entry("solo", 10, "solo"), gtb = entry("gtb", 10, "gtb"), secret = entry("secret", 1, "solo");
        secret.secret = true;
        counts.put("gtb", 90L); counts.put("solo", 10L);
        List<Catalog.Entry> pool = Arrays.asList(solo, gtb, secret);
        check(ids(Recommender.pick(pool, none, none, counts, totals, 1, "player", "all")).equals(Arrays.asList("gtb", "solo")));
        check(ids(Recommender.pick(pool, Arrays.asList("gtb"), Arrays.asList("solo"), counts, totals, 1, "player", "all")).isEmpty());
        check(ids(Recommender.pick(pool, none, none, counts, totals, 1, "player", "solo")).equals(Arrays.asList("solo")));
        Catalog.Entry low = entry("low", 5, "solo"), high = entry("high", 50, "solo");
        check(Recommender.pick(Arrays.asList(high, low), none, none, counts, totals, 1, "player", "all").get(0) == low);
        Catalog.Entry tier1 = entry("track_t1", 40, "solo"), tier2 = entry("track_t2", 5, "solo");
        tier1.track = tier2.track = "track"; tier1.threshold = 100; tier2.threshold = 200;
        check(ids(Recommender.pick(Arrays.asList(tier2, tier1), none, none, counts, totals, 1, "player", "all")).equals(Arrays.asList("track_t1")));
        check(ids(Recommender.pick(Arrays.asList(tier2, tier1), Arrays.asList("track_t1"), none, counts, totals, 1, "player", "all")).equals(Arrays.asList("track_t2")));
        check(Recommender.pick(Arrays.asList(tier1, tier2), none, Arrays.asList("track_t1"), counts, totals, 1, "player", "all").isEmpty());
        totals.put("track", 99L);
        check(Recommender.pick(Arrays.asList(tier1, solo), none, none, counts, totals, 1, "player", "all").get(0) == tier1);
        List<Catalog.Entry> many = new ArrayList<Catalog.Entry>();
        for (int i = 0; i < 20; i++) many.add(entry("goal_" + i, 10, "solo"));
        List<String> first = ids(Recommender.pick(many, none, none, counts, totals, 23, "player", "all"));
        check(first.size() == 5);
        Collections.reverse(many);
        check(first.equals(ids(Recommender.pick(many, none, none, counts, totals, 23, "player", "all"))));
        check(!first.equals(ids(Recommender.pick(many, none, none, counts, totals, 24, "player", "all"))));
        check(!first.equals(ids(Recommender.pick(many, none, none, counts, totals, 23, "other player", "all"))));

        File dir = Files.createTempDirectory("achievement-browser-test").toFile();
        try {
            Config config = Config.load(dir);
            check(config.pinnedAchievements().isEmpty());
            check(!config.pin("not_in_catalog"));
            check(!config.pin("one_block_wonder"));
            check(config.pin("all_wood"));
            check(!config.pin("all_wood"));
            check(config.pin("monochrome"));
            check(config.pin("slabs_only"));
            check(!config.pin("score_win_200"));
            check(Config.load(dir).pinnedAchievements().equals(config.pinnedAchievements()));
            config.pinnedAchievements().clear();
            check(config.pinnedAchievements().size() == 3);
            config.removeUnlockedPins(Arrays.asList("monochrome"));
            check(!Config.load(dir).pinnedAchievements().contains("monochrome"));
            config.unpin("all_wood"); config.unpin("slabs_only");
            AchievementBrowser view = new AchievementBrowser("player", 23);
            check(view.hidden(Catalog.get("one_block_wonder")));
            check(view.name(Catalog.get("one_block_wonder")).equals("Secret achievement"));
            check(!view.description(Catalog.get("one_block_wonder")).contains("block type"));
            check(!view.togglePin(Catalog.get("one_block_wonder"), config));
            check(view.togglePin(Catalog.get("all_wood"), config));
            check(view.entries(AchievementBrowser.Show.PINNED, "gtb", config).isEmpty());
            check(ids(view.entries(AchievementBrowser.Show.PINNED, "solo", config)).equals(Arrays.asList("all_wood")));
            check(view.togglePin(Catalog.get("all_wood"), config));
            view.unlocked.put("all_wood", 1L);
            check(!view.togglePin(Catalog.get("all_wood"), config));
            check(view.entries(AchievementBrowser.Show.UNLOCKED, "solo", config).size() == 1);
            check(!ids(view.entries(AchievementBrowser.Show.LOCKED, "solo", config)).contains("all_wood"));
            check(view.progress(Catalog.get("bb_wins_t1")).equals("Goal: 10"));
            view.totals.put("themes_built", 7L);
            check(view.progress(Catalog.get("themes_built_t1")).equals("7 / 25"));
            view.totals.put("themes_built", 25L);
            check(view.progress(Catalog.get("themes_built_t1")).equals("Complete, unlocks on next sync"));
            view.totals.put("themes_built", 30L);
            check(view.progress(Catalog.get("themes_built_t1")).equals("Complete, unlocks on next sync"));
            view.unlocked.put("themes_built_t1", 1L);
            check(view.progress(Catalog.get("themes_built_t1")).equals("25 / 25"));
            view.totals.put("themes_built", -1L);
            check(view.progress(Catalog.get("themes_built_t1")).equals("0 / 25"));
            check(AchievementBrowser.modes(Catalog.get("monochrome")).equals("Solo · Teams · Pro"));
            check(ids(view.entries(AchievementBrowser.Show.ALL, "gtb", config)).subList(0, 5)
                .equals(ids(view.recommended(config, "gtb"))));
            for (Catalog.Entry e : view.entries(AchievementBrowser.Show.RECOMMENDED, "gtb", config)) check(AchievementBrowser.inMode(e, "gtb"));
            try (LocalDatabase db = new LocalDatabase(dir, "test")) {
                check(db.modeMatchCounts().isEmpty());
                check(db.achievementTotals().get("days_played") == 0);
                MatchRecord m = new MatchRecord();
                m.mode = "teams"; m.theme = "Castle"; m.grade = "SS"; m.placement = 1;
                m.players = 10; m.score = 144.0; m.playedAt = 1000;
                db.insertMatch(m);
                check(db.modeMatchCounts().get("teams") == 1);
                Map<String, Long> local = db.achievementTotals();
                check(local.get("themes_built") == 1 && local.get("ss_grades") == 1 && local.get("crowd_90") == 1 && local.get("days_played") == 1);
                check(!local.containsKey("bb_wins"));
                config.pin("all_wood"); db.unlockLocal("all_wood", 1000);
                AchievementBrowser loaded = AchievementBrowser.load(db, config, "player");
                check(loaded.unlocked.containsKey("all_wood"));
                check(config.pinnedAchievements().isEmpty());
                db.close();
                boolean failed = false;
                try { AchievementBrowser.load(db, config, "player"); } catch (java.sql.SQLException expected) { failed = true; }
                check(failed);
            }
        } finally { delete(dir); }
        System.out.println("AchievementBrowserTest: " + assertions + " assertions passed");
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) throw new AssertionError("cannot delete " + file);
    }
}
