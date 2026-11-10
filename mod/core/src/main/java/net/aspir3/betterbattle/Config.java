package net.aspir3.betterbattle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** config/betterbattle/config.json */
public final class Config {
    public String apiBase = "https://battle.aspir3.net/bb/v1";
    public String website = "https://battle.aspir3.net";
    public boolean toasts = true;
    public boolean chatMessages = true;
    /** Track games on any server, not just Hypixel (for testing). */
    public boolean anyServer = false;
    /** Write chat and scoreboard lines to debug.log while in a game. */
    public boolean debug = false;

    private transient File file;

    public static Config load(File dir) {
        File f = new File(dir, "config.json");
        Config c = null;
        if (f.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                c = new Gson().fromJson(r, Config.class);
            } catch (Exception ignored) {
                // a broken file just falls back to defaults
            }
        }
        if (c == null) c = new Config();
        c.file = f;
        c.save();
        return c;
    }

    public void save() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(this, w);
        } catch (Exception ignored) {
        }
    }
}
