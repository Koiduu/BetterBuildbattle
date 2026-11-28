package net.aspir3.betterbattle.heads;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's own custom heads, painted at battle.aspir3.net/heads and approved there. The sync fills
 * this in (with the bootstrap, and when the "My heads" section opens); a copy in config/betterbattle/heads
 * keeps them while offline. Shared by the Fabric Head Database and the Forge "My heads" screen.
 */
public final class MyHeads {
    public static final String CATEGORY = "mine";
    public static final String PAINT_URL = "battle.aspir3.net/heads";
    public static final String NOTICE = "Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.";

    /** One head from the server: its id, the name the player gave it and its texture hash. */
    public static final class Entry {
        public long id;
        public String name, hash;
    }

    private static File file;
    private static List<HeadLibrary.Head> heads = new ArrayList<HeadLibrary.Head>();
    private static volatile int version;
    private static volatile boolean synced;

    private MyHeads() {}

    /** Reads the saved copy. Safe to call more than once. */
    public static synchronized void init(File modDir) {
        File dir = new File(modDir, "heads");
        dir.mkdirs();
        File f = new File(dir, "mine.json");
        if (f.equals(file)) return;
        file = f;
        if (!f.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            List<Entry> saved = new Gson().fromJson(r, new TypeToken<List<Entry>>() {}.getType());
            if (saved != null) heads = toHeads(saved);
            version++;
        } catch (Exception ignored) {
        }
    }

    /** What the server says the player has now. A null list (an older server) changes nothing. */
    public static synchronized void set(List<Entry> fromServer) {
        if (fromServer == null) return;
        heads = toHeads(fromServer);
        synced = true;
        version++;
        if (file == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new Gson().toJson(fromServer, w);
        } catch (Exception ignored) {
        }
    }

    private static List<HeadLibrary.Head> toHeads(List<Entry> entries) {
        List<HeadLibrary.Head> out = new ArrayList<HeadLibrary.Head>();
        for (Entry e : entries) {
            if (e == null || e.hash == null || !e.hash.matches("[0-9a-fA-F]{32,64}")) continue;
            String name = e.name == null || e.name.trim().isEmpty() ? "Custom head" : e.name.trim();
            out.add(new HeadLibrary.Head(name, e.hash.toLowerCase(java.util.Locale.ROOT), "my heads custom", CATEGORY));
        }
        return out;
    }

    public static synchronized List<HeadLibrary.Head> list() {
        return Collections.unmodifiableList(new ArrayList<HeadLibrary.Head>(heads));
    }

    /** Goes up whenever the list changes, so screens know to redraw. */
    public static int version() { return version; }

    /** True once this session has heard from the server (so an empty list really means none yet). */
    public static boolean synced() { return synced; }
}
