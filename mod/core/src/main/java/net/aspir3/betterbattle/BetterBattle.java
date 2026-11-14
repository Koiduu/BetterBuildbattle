package net.aspir3.betterbattle;

import net.aspir3.betterbattle.achieve.AchievementRules;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.progress.Level;
import net.aspir3.betterbattle.store.LocalDatabase;
import net.aspir3.betterbattle.sync.ApiClient;
import net.aspir3.betterbattle.sync.HttpApiClient;
import net.aspir3.betterbattle.sync.SyncService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything version-independent, in one place. Each loader adapter feeds it game events
 * and implements Platform for the few things only the game can do.
 */
public final class BetterBattle {
    public static final String MOD_ID = "betterbattle";
    public static final String CREDIT = "Better Battle, created by Koidu";

    private static BetterBattle instance;

    private final Platform platform;
    private final File dir;
    private final String modVersion;
    private final String mcVersion;
    private final Config config;
    private final ChatParser parser;

    private LocalDatabase db;
    private SyncService sync;
    private GameStateMachine game;
    private String playerName;
    private final Set<UUID> seenThisSession = new HashSet<UUID>();
    private String lastSidebarTitle = "";
    private List<String> lastSidebar = new ArrayList<String>();
    private PrintWriter debugLog;

    private BetterBattle(Platform platform, File configDir, String modVersion, String mcVersion) {
        this.platform = platform;
        this.dir = new File(configDir, MOD_ID);
        this.dir.mkdirs();
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
        this.config = Config.load(dir);
        this.parser = new ChatParser(new InputStreamReader(
            BetterBattle.class.getResourceAsStream("/betterbattle/patterns.json"), StandardCharsets.UTF_8));
    }

    public static BetterBattle init(Platform platform, File configDir, String modVersion, String mcVersion) {
        instance = new BetterBattle(platform, configDir, modVersion, mcVersion);
        return instance;
    }

    public static BetterBattle get() {
        return instance;
    }

    public Config config() { return config; }
    public String mcVersion() { return mcVersion; }
    public boolean active() { return game != null; }
    public GameStateMachine game() { return game; }
    public LocalDatabase db() { return db; }

    /** Called when the player joins a server. Only Hypixel unless anyServer is on. */
    public void onJoin(String address, UUID uuid, String name) {
        onLeave();
        String host = address == null ? "" : address.toLowerCase();
        if (!config.anyServer && !host.contains("hypixel.net")) return;
        try {
            playerName = name;
            db = new LocalDatabase(dir.getParentFile(), uuid.toString());
            HttpApiClient api = new HttpApiClient(config.apiBase, modVersion, mcVersion);
            sync = new SyncService(db, api, name, new SyncListener());
            game = new GameStateMachine(parser, new GameListener(), mcVersion, name);
            sync.start();
            if (config.debug) openDebugLog();
            debug("joined " + address + " as " + name);
        } catch (Exception e) {
            game = null;
            System.err.println("[Better Battle] couldn't start: " + e);
        }
    }

    public void onLeave() {
        // Hypixel moves you between servers all the time; keep a game that was cut short.
        if (game != null) game.leave();
        if (sync != null) sync.shutdown();
        if (db != null) {
            try { db.close(); } catch (Exception ignored) { }
        }
        if (debugLog != null) debugLog.close();
        sync = null;
        db = null;
        game = null;
        debugLog = null;
        seenThisSession.clear();
        lastSidebarTitle = "";
        lastSidebar = new ArrayList<String>();
    }

    // ---- game events from the adapter

    /** A chat message as the game shows it. Hypixel sends some as several lines in one message. */
    public void onChat(String message) {
        if (game == null || message == null) return;
        for (String raw : message.split("\n")) {
            String line = ChatParser.clean(raw);
            if (line.isEmpty()) continue;
            debug("chat: " + line);
            game.onChat(line);
        }
    }

    /** Sidebar title and lines, top to bottom. Call it often; nothing happens unless something changed. */
    public void onSidebar(String title, List<String> lines) {
        if (game == null) return;
        List<String> cleaned = new ArrayList<String>();
        for (String line : lines) cleaned.add(ChatParser.clean(line));
        String cleanTitle = ChatParser.clean(title);
        if (cleanTitle.equals(lastSidebarTitle) && cleaned.equals(lastSidebar)) return;
        if (!cleanTitle.equals(lastSidebarTitle)) debug("sidebar title: " + cleanTitle);
        // The clock line changes every second; leave it out of the log.
        if (config.debug && !withoutClock(cleaned).equals(withoutClock(lastSidebar))) debug("sidebar: " + String.join(" | ", cleaned));
        lastSidebarTitle = cleanTitle;
        lastSidebar = cleaned;
        game.onSidebar(cleanTitle, cleaned);
    }

    private static List<String> withoutClock(List<String> lines) {
        List<String> out = new ArrayList<String>();
        for (String line : lines) {
            if (!line.matches("(Time|Vote Time): \\d+:\\d+|Starting in \\d+s?")) out.add(line);
        }
        return out;
    }

    public void onTabList(Collection<UUID> uuids) {
        if (sync == null) return;
        List<String> fresh = new ArrayList<String>();
        for (UUID u : uuids) if (seenThisSession.add(u)) fresh.add(u.toString());
        if (!fresh.isEmpty()) sync.addSeenPlayers(fresh);
    }

    public void syncNow() {
        if (sync != null) sync.syncNow();
    }

    /** /bb link CODE, with the code from the website's account page. */
    public void link(String code) {
        if (sync != null) sync.link(code.trim().toUpperCase());
    }

    public void setDebug(boolean on) {
        config.debug = on;
        config.save();
        if (on && game != null) openDebugLog();
        if (!on && debugLog != null) {
            debugLog.close();
            debugLog = null;
        }
    }

    public File debugFile() {
        return new File(dir, "debug.log");
    }

    public void debug(String line) {
        if (debugLog == null) return;
        debugLog.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "  " + line);
        debugLog.flush();
    }

    private void openDebugLog() {
        try {
            debugLog = new PrintWriter(new OutputStreamWriter(new FileOutputStream(debugFile(), true), StandardCharsets.UTF_8));
            debug("---- Better Battle " + modVersion + " on " + mcVersion);
        } catch (Exception ignored) {
        }
    }

    /** Level, XP into level and XP needed, from the last sync (or counted locally while offline). */
    public long[] levelProgress() {
        long xp = 0;
        try {
            if (db != null) xp = Long.parseLong(db.getMeta("xp", "0"));
        } catch (Exception ignored) {
        }
        int level = Level.levelForXp(xp);
        long base = Level.totalXpForLevel(level);
        return new long[] {level, xp - base, Level.totalXpForLevel(level + 1) - base};
    }

    // ---- internals

    private final class GameListener implements GameStateMachine.Listener {
        public void onStateChanged(GameStateMachine.State state) {
            debug("state: " + state);
        }

        public void onMatchFinished(MatchRecord m) {
            debug("votes: " + m.votes.size());
            debug("finished: mode=" + m.mode + " theme=" + m.theme + " place=" + m.placement + "/" + m.players
                + " score=" + m.score + " blocks=" + m.blocksPlaced + " floor=" + m.floor);
            if (!config.shareVotes) m.votes.clear();
            if (!m.votes.isEmpty() && !config.votesNoticeShown) {
                config.votesNoticeShown = true;
                config.save();
                platform.chat("\u00a7d\u00a7lBetter Battle \u00a78\u00bb \u00a77Your votes are saved for your end-of-year "
                    + "\u00a7dBuild Battle Wrapped\u00a77. Don't want that? Set \u00a7fshareVotes \u00a77to \u00a7ffalse \u00a77in "
                    + "config/betterbattle/config.json.");
            }
            try {
                if (!db.insertMatch(m)) return;
                if (sync != null && sync.isOffline()) {
                    long offlineGames = Long.parseLong(db.getMeta("offline_games", "0"));
                    db.setMeta("offline_games", Long.toString(offlineGames + 1));
                }
                // Count XP locally so the level bar moves even while offline; the server's number replaces it on sync.
                long xp = Long.parseLong(db.getMeta("xp", "0"));
                db.setMeta("xp", String.valueOf(xp + Level.xpForMatch(m.mode, m.won(), m.grade, 0, 0, false)));
                unlock("welcome", m);
                for (AchievementRules.Rule rule : AchievementRules.all()) {
                    if (rule.onMatch(m, db)) unlock(rule.id(), m);
                }
            } catch (Exception e) {
                System.err.println("[Better Battle] couldn't save game: " + e);
            }
            if (config.chatMessages && m.placement != null) {
                String grade = m.grade == null ? "" : "  §7Grade §f" + m.grade;
                platform.chat("§d§lBetter Battle §8» §7Game saved." + grade + "  §8(" + m.blocksPlaced + " blocks)");
            }
            if (sync != null) sync.nudge();
        }

        private void unlock(String id, MatchRecord m) throws Exception {
            if (!db.unlockLocal(id, m.playedAt)) return;
            final Catalog.Entry entry = Catalog.get(id);
            if (entry == null) return;
            if (config.toasts) platform.showAchievement(entry);
            if (config.chatMessages) {
                platform.chat("§a>> §6Achievement Unlocked: §e" + entry.name + " §a<<  §7(+" + entry.points + " AP)");
            }
        }
    }

    private final class SyncListener implements SyncService.Listener {
        public void joinServer(String serverId) throws Exception {
            platform.joinServer(serverId);
        }

        public void manualResult(final boolean ok, final String message) {
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    platform.chat("\u00a7d\u00a7lBetter Battle \u00a78\u00bb " + (ok ? "\u00a7a" : "\u00a7c") + message);
                }
            });
        }

        public void offline(final int pending) {
            if (!config.chatMessages) return;
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    platform.chat("§d§lBetter Battle §8» §7Can't reach the stats server right now. "
                        + "Your games are saved and will upload later.");
                }
            });
        }

        private final List<Catalog.Entry> fromServer = new ArrayList<Catalog.Entry>();

        public void achievementConfirmed(String id) {
            // Unlocked on the server first, e.g. from Hypixel stats. Shown together after the sync.
            Catalog.Entry entry = Catalog.get(id);
            if (entry != null) fromServer.add(entry);
        }

        public void synced(ApiClient.SyncResult result) {
            final List<Catalog.Entry> unlocked = new ArrayList<Catalog.Entry>(fromServer);
            fromServer.clear();
            if (unlocked.isEmpty()) return;
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    if (unlocked.size() <= 3) {
                        for (Catalog.Entry e : unlocked) {
                            if (config.toasts) platform.showAchievement(e);
                        }
                        return;
                    }
                    int ap = 0;
                    for (Catalog.Entry e : unlocked) ap += e.points;
                    platform.chat("§d§lBetter Battle §8» §6" + unlocked.size() + " achievements unlocked §7from your Hypixel stats §8(+"
                        + ap + " AP)§7. Press §fB §7to see them.");
                }
            });
        }

        public void achievementRevoked(String id) {
        }
    }
}
