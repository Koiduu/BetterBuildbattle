package net.aspir3.betterbattle;

import net.aspir3.betterbattle.achieve.AchievementRules;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.game.SpbTracker;
import net.aspir3.betterbattle.pages.Pages;
import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.progress.Level;
import net.aspir3.betterbattle.shots.RoundShots;
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
    public static final String CREDIT = "Better Battle";

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
    /** The last games for /bb report, without anything people wrote. Kept in memory across games. */
    private final net.aspir3.betterbattle.report.ReportLog report = new net.aspir3.betterbattle.report.ReportLog();
    private boolean whatsNewDue, whatsNewAnnounced;
    private volatile ApiClient.Latest newer;      // a version newer than this one, once the server reports it
    private boolean newerAnnounced;
    private GameStateMachine.State lastState = GameStateMachine.State.IDLE;
    private long joinedAt;

    private BetterBattle(Platform platform, File configDir, String modVersion, String mcVersion) {
        this.platform = platform;
        this.dir = new File(configDir, MOD_ID);
        this.dir.mkdirs();
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
        this.config = Config.load(dir);
        // screenshots are only kept for 15 days
        try {
            RoundShots.prune(RoundShots.dir(dir), RoundShots.KEEP_DAYS, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
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
    public String modVersion() { return modVersion; }
    public File dir() { return dir; }

    private boolean nicked;
    private String nick;

    /** True while you're under a Hypixel /nick. */
    public boolean nicked() { return nicked; }

    private void setNick(boolean on, String name) {
        if (on == nicked && (name == null || name.equalsIgnoreCase(nick))) return;
        nicked = on;
        nick = on ? (name != null ? name : nick) : null;
        debug("nick: " + (on ? (nick == null ? "on" : nick) : "off"));
        if (game != null) game.setNick(nicked, nick);
    }

    /** The name the server shows for you in the tab list. A different one means you're nicked. */
    public void onOwnTabName(String shown) {
        if (shown == null || playerName == null || shown.isEmpty()) return;
        if (!shown.equalsIgnoreCase(playerName)) setNick(true, shown);
        else if (nicked) setNick(false, null);
    }

    /**
     * Text above the hotbar. Returns true when the "no word hint" challenge says to hide it:
     * in Guess the Build, the hint is the theme as underscores ("_ _ _ _ _") filling in letter by letter.
     */
    public boolean onActionBar(String text) {
        if (text == null) return false;
        String line = ChatParser.clean(text);
        if (line.startsWith("You are currently NICKED") || line.startsWith("You are currently nicked")) setNick(true, null);
        return config.challengeNoHints && game != null && "gtb".equals(game.mode()) && isWordHint(line);
    }

    static boolean isWordHint(String line) {
        if (line.matches("(?i)^the theme is\\b.*")) return true;
        int underscores = 0;
        for (int i = 0; i < line.length(); i++) if (line.charAt(i) == '_') underscores++;
        return underscores >= 2 && line.matches("^[\\p{L}\\d_ '\\-!?.,&()]+$");
    }

    private boolean rejoining;

    /**
     * The next disconnect is the head database's quick rejoin: keep the game, database and sync open
     * so the match stays one record. Ends at the next join, or {@link #endRejoin()} if that never comes.
     */
    public void holdGameForRejoin() { rejoining = game != null; }

    public void endRejoin() {
        if (!rejoining) return;
        rejoining = false;
        onLeave();
    }

    /** Called when the player joins a server. Only Hypixel unless anyServer is on. */
    public void onJoin(String address, UUID uuid, String name) {
        if (rejoining) {
            rejoining = false;
            String to = address == null ? "" : address.toLowerCase();
            if (name != null && name.equalsIgnoreCase(playerName) && (config.anyServer || to.contains("hypixel.net"))) {
                debug("rejoined " + address + ", carrying on with the same game");
                return;
            }
        }
        onLeave();
        String host = address == null ? "" : address.toLowerCase();
        if (!config.anyServer && !host.contains("hypixel.net")) return;
        try {
            playerName = name;
            db = new LocalDatabase(dir.getParentFile(), uuid.toString());
            HttpApiClient api = new HttpApiClient(config.apiBase, modVersion, mcVersion);
            sync = new SyncService(db, api, name, new SyncListener());
            game = new GameStateMachine(parser, new GameListener(), mcVersion, name);
            game.setSpbRecords(new LocalRecords());
            game.setNick(nicked, nick);
            joinedAt = System.currentTimeMillis();
            if (!modVersion.equals(config.lastSeenVersion)) {
                if (config.whatsNew) {
                    whatsNewDue = true;
                } else {
                    config.lastSeenVersion = modVersion;
                    config.save();
                }
            }
            sync.start();
            if (config.debug) openDebugLog();
            debug("joined " + address + " as " + name);
        } catch (Exception e) {
            game = null;
            System.err.println("[Better Battle] couldn't start: " + e);
        }
    }

    public void onLeave() {
        if (rejoining) return;
        // Hypixel moves you between servers all the time; keep a game that was cut short.
        if (game != null) game.leave();
        // Close the database only after the sync thread is done with it
        final LocalDatabase closing = db;
        Runnable close = new Runnable() {
            public void run() {
                if (closing != null) {
                    try { closing.close(); } catch (Exception ignored) { }
                }
            }
        };
        if (sync != null) sync.shutdown(close);
        else close.run();
        if (debugLog != null) debugLog.close();
        sync = null;
        db = null;
        game = null;
        debugLog = null;
        seenThisSession.clear();
        newerAnnounced = false;
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
            report.chat(line, parser);
            ChatParser.Event e = parser.parse(line);
            if (e != null && e.type == ChatParser.Type.NICK_ON) setNick(true, null);
            else if (e != null && e.type == ChatParser.Type.NICK_SET) setNick(true, e.text);
            else if (e != null && e.type == ChatParser.Type.NICK_RESET) setNick(false, null);
            game.onChat(line);
        }
    }

    /** Sidebar title and lines, top to bottom. Call it often; nothing happens unless something changed. */
    public void onSidebar(String title, List<String> lines) {
        if (game == null) return;
        announceUpdate();
        List<String> cleaned = new ArrayList<String>();
        for (String line : lines) cleaned.add(ChatParser.cleanSidebar(line));
        String cleanTitle = ChatParser.cleanSidebar(title);
        if (cleanTitle.equals(lastSidebarTitle) && cleaned.equals(lastSidebar)) return;
        if (!cleanTitle.equals(lastSidebarTitle)) debug("sidebar title: " + cleanTitle);
        // The clock line changes every second; leave it out of the log.
        boolean changed = !cleanTitle.equals(lastSidebarTitle) || !withoutClock(cleaned).equals(withoutClock(lastSidebar));
        if (changed) report.sidebar(cleanTitle, cleaned);
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

    // ---- pages and the timer

    /**
     * After an update, the first B press opens What's New instead of the menu (once).
     * Never pops up by itself, so it can't land on top of a game or the chat box.
     */
    public boolean takeWhatsNew() {
        if (!whatsNewDue) return false;
        whatsNewDue = false;
        config.lastSeenVersion = modVersion;
        config.save();
        return true;
    }

    /** A chat nudge a few seconds after joining, once per update. Called from onSidebar. */
    private void announceUpdate() {
        if (!whatsNewDue || whatsNewAnnounced || System.currentTimeMillis() - joinedAt < 5000) return;
        whatsNewAnnounced = true;
        platform.chat("§d§lBetter Battle §8» §7Updated to §f" + modVersion + "§7. Press §fB §7to see what's new.");
    }

    /** True if version a is newer than b. Compares the numbers ("0.10.0" > "0.9.2"); anything without numbers never is. */
    static boolean isNewer(String a, String b) {
        if (a == null || b == null || !a.matches("\\d+(\\.\\d+)*") || !b.matches("\\d+(\\.\\d+)*.*")) return false;
        String[] x = a.split("\\."), y = b.replaceAll("[^0-9.].*$", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long xi = i < x.length ? Long.parseLong(x[i]) : 0, yi = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }

    /** Where to get the mod. Always this site's own install page, never an address the server sent. */
    private String installUrl() {
        return config.website.replaceAll("/+$", "") + "/install";
    }

    /** The newer version's number when one has been released, otherwise null. */
    public String updateAvailable() {
        ApiClient.Latest n = newer;
        return n == null ? null : n.version;
    }

    /** What's New, with a line on top when a newer version is out. */
    public Pages.Page whatsNewPage() {
        Pages.Page page = Pages.whatsNew();
        ApiClient.Latest n = newer;
        if (n == null) return page;
        List<String> lines = new ArrayList<String>();
        lines.add("§a§lUpdate available: §f" + n.version + " §7(you have " + modVersion + ")");
        if (n.notes != null && !n.notes.trim().isEmpty()) lines.add("§7" + n.notes.trim().replace('§', ' '));
        lines.add("§7Download it at §b" + installUrl());
        lines.add("");
        lines.addAll(page.lines);
        return new Pages.Page(page.title, lines);
    }

    public Pages.Page lastGamePage() {
        List<String> lines = null;
        try {
            if (db != null) lines = new com.google.gson.Gson().fromJson(db.getMeta("last_recap", null),
                new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
        } catch (Exception ignored) {
        }
        if (lines == null || lines.isEmpty()) {
            lines = new ArrayList<String>();
            lines.add(db == null ? "§7Join Hypixel to see your games." : "§7Play a game and its recap shows up here.");
        }
        return new Pages.Page("Last Game", lines);
    }

    /** Your own numbers from the games on this PC. */
    public Pages.Page statsPage() {
        if (db == null) return new Pages.Page("My Stats", java.util.Arrays.asList("\u00a77Join Hypixel to see your stats."));
        try {
            java.util.Map<String, LocalDatabase.LocalStats> modes = new java.util.LinkedHashMap<String, LocalDatabase.LocalStats>();
            for (String m : new String[] {"solo", "teams", "pro", "gtb", "speed_builders"}) modes.put(m, db.stats(m));
            long[] lv = levelProgress();
            return Pages.stats(db.stats(null), modes, db.topThemes(5), lv[0], lv[1], lv[2]);
        } catch (Exception e) {
            debug("stats page failed: " + e);
            return new Pages.Page("My Stats", java.util.Arrays.asList("\u00a7cCouldn't read your games. Try again."));
        }
    }

    /** /bb rank */
    public void rank() {
        if (sync == null) {
            platform.chat("\u00a7d\u00a7lBetter Battle \u00a78\u00bb \u00a77Join Hypixel first, then run \u00a7b/bb rank\u00a77 again.");
            return;
        }
        sync.rank();
    }

    /** /bb compare name */
    public void compare(String name) {
        if (sync == null) {
            platform.chat("\u00a7d\u00a7lBetter Battle \u00a78\u00bb \u00a77Join Hypixel first, then run \u00a7b/bb compare\u00a77 again.");
            return;
        }
        sync.compare(name);
    }

    /** /bb help. headsAndBanners: the Fabric build has the Head Database and banners; Forge doesn't. */
    public List<String> helpLines(boolean headsAndBanners) {
        List<String> out = new ArrayList<String>();
        out.add("\u00a7d\u00a7lBetter Battle \u00a78\u00bb \u00a76Commands");
        out.add("\u00a7b/bb \u00a77Open the menu (or press B)");
        out.add("\u00a7b/bb stats \u00a77Your own numbers: games, wins, win rate by mode");
        out.add("\u00a7b/bb rank \u00a77Where you stand today, this week, this month and all time");
        out.add("\u00a7b/bb compare <name> \u00a77You next to another player");
        if (headsAndBanners) out.add("\u00a7b/bb shots \u00a77Your round screenshots, kept for 15 days");
        out.add("\u00a7b/bb achievements \u00a77Your achievements and goals");
        out.add("\u00a7b/bb recap \u00a77Your last game in full");
        out.add("\u00a7b/bb weekly \u00a77This week's challenges");
        out.add("\u00a7b/bb news \u00a77What's new in this version");
        if (headsAndBanners) {
            out.add("\u00a7b/bb heads [search] \u00a77The Head Database");
            out.add("\u00a7b/bb banners \u00a77Banner designs");
        }
        out.add("\u00a7b/bb palette [size] \u00a77A random block palette to build with");
        out.add("\u00a7b/bb settings \u00a77Switch features on and off, including Low-end mode");
        out.add("\u00a7b/bb sync \u00a77Upload your games now");
        out.add("\u00a7b/bb link <code> \u00a77Connect your website account");
        out.add("\u00a7b/bb report [what went wrong] \u00a77Send a bug report");
        out.add("\u00a7b/bb debug \u00a77Turn the debug log on or off");
        return out;
    }

    public Pages.Page weeklyPage() {
        String json = null, ends = null;
        try {
            if (db != null) {
                json = db.getMeta("weekly", null);
                ends = db.getMeta("week_ends_at", null);
            }
        } catch (Exception ignored) {
        }
        return Pages.weekly(Pages.weeklyFromJson(json), ends);
    }

    /** Lines for the Speed Builders timer, or an empty list when it shouldn't show. */
    public List<String> hudLines() {
        if (!config.spbTimer || game == null || game.spb() == null) return new ArrayList<String>();
        return game.spb().hudLines(System.currentTimeMillis(), config.spbShowRecord);
    }

    public void syncNow() {
        if (sync != null) sync.syncNow();
    }

    /**
     * /bb report [what went wrong]: sends the last games' game messages, sidebar and the mod's decisions.
     * Never chat: ReportLog leaves out anything a person wrote before it is kept.
     */
    public void sendReport(String note) {
        if (sync == null) {
            platform.chat("§d§lBetter Battle §8» §7Join Hypixel first, then run §b/bb report§7 again.");
            return;
        }
        if (report.isEmpty()) {
            platform.chat("§d§lBetter Battle §8» §7Nothing to send yet. Play a game, then run §b/bb report§7.");
            return;
        }
        platform.chat("§d§lBetter Battle §8» §7Sending a bug report: your last games' game messages and scoreboard. "
            + "§fNo chat or private messages§7 are included.");
        sync.report(note == null ? "" : note.trim(), report.text());
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
        // The mod's own lines go into /bb report too; chat and sidebar arrive there filtered, separately
        if (!line.startsWith("chat: ") && !line.startsWith("sidebar")) {
            report.mod(line.startsWith("nick: ") ? "nick: " + (nicked ? "on" : "off") : line);
        }
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
            GameStateMachine.State before = lastState;
            lastState = state;
            // Building time just ran out: take a picture of the build.
            if (before == GameStateMachine.State.BUILDING && state == GameStateMachine.State.VOTING && config.roundScreenshots) {
                final File file = new File(RoundShots.dir(dir), RoundShots.fileName(System.currentTimeMillis(), game.mode(), game.theme()));
                platform.runOnMainThread(new Runnable() {
                    public void run() {
                        file.getParentFile().mkdirs();
                        try {
                            platform.screenshot(file);
                        } catch (Throwable t) {
                            debug("round screenshot failed: " + t);
                        }
                    }
                });
            }
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
                long gained = Level.xpForMatch(m.mode, m.won(), m.grade, 0, 0, false);
                db.setMeta("xp", String.valueOf(xp + gained));
                List<String> unlocked = new ArrayList<String>();
                if (unlock("welcome", m)) unlocked.add(Catalog.get("welcome").name);
                for (AchievementRules.Rule rule : AchievementRules.all()) {
                    // A challenge only counts in the modes the catalog lists for it: a "stone blocks only" build
                    // is for the building modes, not a Speed Builders game
                    Catalog.Entry forMode = Catalog.get(rule.id());
                    if (forMode != null && forMode.modes != null && !forMode.modes.isEmpty() && !forMode.modes.contains(m.mode)) continue;
                    try {
                        if (!rule.onMatch(m, db)) continue;
                    } catch (Exception e) {
                        debug("rule " + rule.id() + " failed: " + e);
                        continue;
                    }
                    m.tags.add(rule.id());
                    if (unlock(rule.id(), m)) {
                        Catalog.Entry entry = Catalog.get(rule.id());
                        if (entry != null) unlocked.add(entry.name);
                    }
                }
                db.setTags(m.nonce, m.tags);
                db.setMeta("last_recap", new com.google.gson.Gson().toJson(Pages.recap(m, gained, unlocked).lines));
                if (config.recap && m.placement != null) {
                    for (String line : Pages.recapChat(m, gained, unlocked)) platform.chat(line);
                } else if (config.chatMessages && m.placement != null) {
                    String grade = m.grade == null ? "" : "  §7Grade §f" + m.grade;
                    platform.chat("§d§lBetter Battle §8» §7Game saved." + grade + "  §8(" + m.blocksPlaced + " blocks)");
                }
            } catch (Exception e) {
                System.err.println("[Better Battle] couldn't save game: " + e);
            }
            if (sync != null) sync.nudge();
        }

        /** True if this unlocked it just now. */
        private boolean unlock(String id, MatchRecord m) throws Exception {
            if (!db.unlockLocal(id, m.playedAt)) return false;
            config.unpin(id);
            final Catalog.Entry entry = Catalog.get(id);
            if (entry == null) return false;
            if (config.toasts) platform.showAchievement(entry);
            if (config.chatMessages) {
                platform.chat("§a>> §6Achievement Unlocked: §e" + entry.name + " §a<<  §7(+" + entry.points + " AP)");
            }
            return true;
        }
    }

    /** Bests from the local database, world records from the server's last list. */
    private final class LocalRecords implements SpbTracker.Records {
        public Long personalBest(String buildId) {
            try { return db == null ? null : db.personalBestMs(buildId); } catch (Exception e) { return null; }
        }

        public Long worldRecord(String buildId) {
            try { return db == null ? null : db.worldRecordMs(buildId); } catch (Exception e) { return null; }
        }

        public String worldRecordHolder(String buildId) {
            try { return db == null ? null : db.worldRecordHolder(buildId); } catch (Exception e) { return null; }
        }

        public void savePersonalBest(String buildId, String buildName, long timeMs, double precision) {
            try {
                if (db != null) db.savePersonalBest(buildId, buildName, timeMs, precision);
            } catch (Exception e) {
                debug("couldn't save best: " + e);
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

        public void lines(final List<String> lines) {
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    for (String line : lines) platform.chat(line);
                }
            });
        }

        public void offline(final int pending, final String reason) {
            if (!config.chatMessages) return;
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    platform.chat("§d§lBetter Battle §8» §7Couldn't upload your games: " + reason.replace('§', ' ')
                        + " They're saved and will upload later. Type §b/bb sync§7 to try again now.");
                }
            });
        }

        public void gamesSkipped(final int count) {
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    platform.chat("§d§lBetter Battle §8» §7" + (count == 1 ? "1 game" : count + " games")
                        + " couldn't be uploaded: the stats server says " + (count == 1 ? "it isn't" : "they aren't")
                        + " valid. Skipped so your other games can upload.");
                }
            });
        }

        private final List<Catalog.Entry> fromServer = new ArrayList<Catalog.Entry>();

        public void achievementConfirmed(String id) {
            config.unpin(id);
            // Unlocked on the server first, e.g. from Hypixel stats. Shown together after the sync.
            Catalog.Entry entry = Catalog.get(id);
            if (entry != null) fromServer.add(entry);
        }

        public void latestVersion(final ApiClient.Latest latest) {
            if (!isNewer(latest.version, modVersion)) return;
            newer = latest;
            if (!config.updateNotices || newerAnnounced) return;
            newerAnnounced = true;   // once per time you join, not on every check
            platform.runOnMainThread(new Runnable() {
                public void run() {
                    platform.chat("§d§lBetter Battle §8» §aVersion " + latest.version + " is out §7(you have " + modVersion
                        + "). Get it at §b" + installUrl());
                }
            });
        }

        public void bootstrapped(ApiClient.Bootstrap b) {
            LocalDatabase d = db;
            if (d == null) return;
            try {
                if (b.weeklyChallenges != null && !b.weeklyChallenges.isEmpty()) saveWeekly(d, b.weeklyChallenges, b.weekEndsAt);
                if (b.worldRecords != null) {
                    List<String[]> rows = new ArrayList<String[]>();
                    for (ApiClient.WorldRecord r : b.worldRecords) {
                        if (r.build != null) rows.add(new String[] {r.build, r.name, r.holder, Long.toString(r.timeMs)});
                    }
                    d.saveWorldRecords(rows);
                }
            } catch (Exception e) {
                debug("bootstrap: " + e);
            }
        }

        private void saveWeekly(LocalDatabase d, List<ApiClient.Weekly> weekly, String endsAt) throws Exception {
            d.setMeta("weekly", new com.google.gson.Gson().toJson(weekly));
            if (endsAt != null) d.setMeta("week_ends_at", endsAt);
        }

        public void synced(ApiClient.SyncResult result) {
            if (result.weekly != null && db != null) {
                try {
                    saveWeekly(db, result.weekly, result.weekEndsAt);
                } catch (Exception ignored) {
                }
            }
            if (config.weeklyAlerts && result.weekly != null && result.weeklyCompleted != null) {
                for (final String id : result.weeklyCompleted) {
                    for (final ApiClient.Weekly w : result.weekly) {
                        if (!w.id.equals(id)) continue;
                        platform.runOnMainThread(new Runnable() {
                            public void run() {
                                platform.chat("§d§lBetter Battle §8» §6Weekly challenge done: §f" + w.text + " §a(+" + w.xp + " XP)");
                            }
                        });
                    }
                }
            }
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
