import net.aspir3.betterbattle.heads.BannerLibrary;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The bundled banner catalog loads, every design fits a survival banner, and search/filters work. */
public final class BannerLibraryTest {
    private static int assertions;

    private static void check(boolean condition, String what) {
        assertions++;
        if (!condition) throw new AssertionError(what);
    }

    private static boolean has(List<BannerLibrary.Design> list, String name) {
        for (BannerLibrary.Design d : list) if (d.name.equals(name)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("bb-banners").toFile();
        BannerLibrary modern = new BannerLibrary(dir, false);
        BannerLibrary legacy = new BannerLibrary(dir, true);

        List<BannerLibrary.Design> all = modern.catalog();
        check(all.size() >= 300, "hundreds of designs, got " + all.size());
        check(modern.catalogVersion() >= 1, "catalog has a version");

        Set<String> cats = new HashSet<String>();
        for (BannerLibrary.Category c : modern.categories()) cats.add(c.id);
        for (String id : new String[] {"flags", "letters", "numbers", "symbols", "patterns", "colors"}) {
            check(cats.contains(id), "category " + id);
            check(!modern.search(id, "").isEmpty(), "category " + id + " has designs");
        }

        Set<String> names = new HashSet<String>();
        int modernOnly = 0;
        for (BannerLibrary.Design d : all) {
            check(names.add(d.name.toLowerCase()), "unique name " + d.name);
            check(d.layers.size() <= BannerLibrary.MAX_LAYERS, "at most 6 layers: " + d.name);
            check(cats.contains(d.category), "known category: " + d.name);
            if (d.modernOnly()) modernOnly++;
        }
        check(modernOnly > 0, "some designs use newer patterns");

        // 1.8.9 leaves out every design with a pattern it doesn't have, and nothing else
        check(legacy.catalog().size() == all.size() - modernOnly, "1.8.9 hides exactly the modern-only designs");
        for (BannerLibrary.Design d : legacy.catalog()) {
            for (String[] l : d.layers) check(!BannerLibrary.MODERN_ONLY.contains(l[0]), "1.8.9 shows " + d.name);
        }
        check(has(modern.search("all", "piglin"), "Piglin"), "modern shows Piglin");
        check(!has(legacy.search("all", "piglin"), "Piglin"), "1.8.9 hides Piglin");

        // Search: every word, any case, name or tags; scoped to a category
        check(has(modern.search("all", "germany"), "Germany"), "search by name");
        check(has(modern.search("all", "DEUTSCHLAND"), "Germany"), "search by tag, any case");
        check(has(modern.search("flags", "europe nordic"), "Norway"), "two words");
        check(!has(modern.search("letters", "germany"), "Germany"), "category filter");
        check(has(modern.search("letters", "letter a"), "Letter A"), "letters");
        check(modern.search("all", "zzzz no such banner").isEmpty(), "no match");
        int letters = 0;
        for (BannerLibrary.Design d : modern.search("letters", "")) if (d.name.startsWith("Letter ")) letters++;
        check(letters >= 26, "A to Z");

        // Saved designs come first in All and on their own under Saved, and survive a restart
        BannerLibrary.Design mine = new BannerLibrary.Design("My team flag", "red", "stripe_top", "white");
        modern.save(mine);
        check(modern.search("all", "").get(0).name.equals("My team flag"), "saved first in All");
        check(modern.search("saved", "").size() == 1, "saved tab");
        check(new BannerLibrary(dir, false).saved().size() == 1, "saved survives a restart");

        // A newer catalog from the server replaces the bundled one; older or broken ones don't
        String newer = "{\"version\": 999, \"categories\": [{\"id\": \"flags\", \"label\": \"Flags\"}], \"designs\": ["
            + "{\"name\": \"Test flag\", \"category\": \"flags\", \"base\": \"blue\", \"layers\": [[\"cross\", \"white\"]]},"
            + "{\"name\": \"Too tall\", \"category\": \"flags\", \"base\": \"blue\", \"layers\": [[\"cross\", \"white\"],"
            + "[\"cross\", \"white\"],[\"cross\", \"white\"],[\"cross\", \"white\"],[\"cross\", \"white\"],[\"cross\", \"white\"],[\"cross\", \"white\"]]},"
            + "{\"name\": \"Bad pattern\", \"category\": \"flags\", \"base\": \"blue\", \"layers\": [[\"nope\", \"white\"]]}]}";
        check(!modern.install("not json"), "broken catalog refused");
        check(!modern.install(newer.replace("999", "0")), "older catalog refused");
        check(modern.install(newer), "newer catalog installed");
        check(modern.catalog().size() == 1 && modern.catalog().get(0).name.equals("Test flag"), "invalid designs dropped");
        check(new BannerLibrary(dir, false).catalogVersion() == 999, "installed catalog kept");

        System.out.println("BannerLibraryTest: " + assertions + " assertions passed (" + all.size() + " designs, "
            + modernOnly + " modern-only)");
    }
}
