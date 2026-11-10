package net.aspir3.betterbattle.achieve;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Block id lists bundled in the jar under /betterbattle/blocks/. One id per line, # for comments. */
public final class BlockLists {
    private BlockLists() {}

    public static final Set<String> LEGACY_1_8_9 = load("legacy_1_8_9.txt");
    public static final Set<String> LATEST_RELEASE = load("latest_release.txt");

    static Set<String> load(String name) {
        Set<String> out = new HashSet<String>();
        InputStream in = BlockLists.class.getResourceAsStream("/betterbattle/blocks/" + name);
        if (in == null) return Collections.emptySet();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(strip(line));
            }
        } catch (Exception e) {
            return Collections.emptySet();
        }
        return out;
    }

    static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
