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
    /** Save the votes you cast, for your end-of-year Build Battle Wrapped. */
    public boolean shareVotes = true;
    /** Set once the player has been told about vote saving. */
    public boolean votesNoticeShown = false;
    /** Write chat and scoreboard lines to debug.log while in a game. */
    public boolean debug = false;

    // ---- Build Battle update features. All of them can be switched off to play normally.
    /** Speed Builders stopwatch with your best time next to it. */
    public boolean spbTimer = true;
    /** Also show the Better Battle world record on the stopwatch. */
    public boolean spbShowRecord = true;
    /** A short summary in chat after each game. */
    public boolean recap = true;
    /** Open the What's New page once after the mod updates. */
    public boolean whatsNew = true;
    /** Say in chat when you finish a weekly challenge. */
    public boolean weeklyAlerts = true;
    /** The version whose What's New page was last shown. */
    public String lastSeenVersion = "";

    /** One on/off setting as the Settings screen shows it. */
    public static final class Toggle {
        public final String label, help;
        private final java.lang.reflect.Field field;

        Toggle(String fieldName, String label, String help) {
            try {
                this.field = Config.class.getField(fieldName);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
            this.label = label;
            this.help = help;
        }

        public boolean get(Config c) {
            try {
                return field.getBoolean(c);
            } catch (IllegalAccessException e) {
                return false;
            }
        }

        public void flip(Config c) {
            try {
                field.setBoolean(c, !field.getBoolean(c));
            } catch (IllegalAccessException ignored) {
            }
            c.save();
        }
    }

    /** Everything the Settings screen lets you switch, in order. */
    public static final java.util.List<Toggle> TOGGLES = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
        new Toggle("spbTimer", "Speed Builders timer", "A stopwatch with your best time while you build"),
        new Toggle("spbShowRecord", "Show world record", "Put the Better Battle record on the timer too"),
        new Toggle("recap", "Game recap in chat", "A short summary after each game"),
        new Toggle("toasts", "Achievement pop-ups", "The box in the corner when you unlock one"),
        new Toggle("chatMessages", "Chat messages", "Achievement and sync messages in chat"),
        new Toggle("weeklyAlerts", "Weekly challenge alerts", "Tell me when I finish a weekly challenge"),
        new Toggle("whatsNew", "What's New after updates", "Show the patch notes once after the mod updates"),
        new Toggle("shareVotes", "Save my votes", "Keep the votes you cast for Build Battle Wrapped")));

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
