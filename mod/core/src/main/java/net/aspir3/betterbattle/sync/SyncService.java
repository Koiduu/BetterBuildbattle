package net.aspir3.betterbattle.sync;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Sends saved games to the server on its own thread. If the server can't be reached the games
 * simply stay in the local database marked 'pending' and go up next time.
 */
public final class SyncService {
    private static final int BATCH = 50;
    private static final long MIN_BACKOFF_MS = 5000L, MAX_BACKOFF_MS = 600000L;

    public interface Listener {
        /** The game asks Mojang's session server to confirm this player; runs off the main thread. */
        void joinServer(String serverId) throws Exception;
        /** An upload failed; the games stay saved. reason is explain()'s wording, for the player. */
        void offline(int pendingGames, String reason);
        /** The server called these games invalid on their own, so they were set aside instead of retried forever. */
        void gamesSkipped(int count);
        /** Result of something the player asked for by hand (/bb sync, /bb link). */
        void manualResult(boolean ok, String message);
        void synced(ApiClient.SyncResult result);
        void achievementConfirmed(String id);
        void achievementRevoked(String id);
        /** The server's newest mod version (checked now and then; it may be the one already installed). */
        void latestVersion(ApiClient.Latest latest);
        /** New weekly challenges or world records arrived. */
        void bootstrapped(ApiClient.Bootstrap b);
    }

    private static final long BOOTSTRAP_EVERY_MS = 30L * 60L * 1000L;
    private long lastBootstrapAt;
    private static final long VERSION_CHECK_EVERY_MS = 6L * 60L * 60L * 1000L;
    private long lastVersionCheckAt;

    /** Asks for the newest version. Needs no login, and never gets in the way of a sync. */
    private void checkVersion() {
        if (System.currentTimeMillis() - lastVersionCheckAt < VERSION_CHECK_EVERY_MS) return;
        lastVersionCheckAt = System.currentTimeMillis();
        try {
            ApiClient.Latest latest = api.latest();
            if (latest != null && latest.version != null) listener.latestVersion(latest);
        } catch (Exception ignored) {
        }
    }

    private final LocalDatabase db;
    private final ApiClient api;
    private final String playerName;
    private final Listener listener;
    private final List<String> seenPlayers = Collections.synchronizedList(new ArrayList<String>());
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BetterBattle-Sync");
            t.setDaemon(true);
            return t;
        }
    });

    private long backoffMs;
    private long nextAttemptAt;
    private volatile boolean offline;

    public SyncService(LocalDatabase db, ApiClient api, String playerName, Listener listener) {
        this.db = db;
        this.api = api;
        this.playerName = playerName;
        this.listener = listener;
    }

    public void start() {
        exec.scheduleWithFixedDelay(new Runnable() {
            public void run() { tick(false); }
        }, 3, 60, TimeUnit.SECONDS);
    }

    /** Sync soon, e.g. right after a game ends. */
    public void nudge() {
        exec.execute(new Runnable() {
            public void run() { tick(false); }
        });
    }

    /** Sync now, ignoring any backoff (the "Sync now" button). */
    public void syncNow() {
        exec.execute(new Runnable() {
            public void run() { tick(true); }
        });
    }

    /** /bb report: one upload, whenever the player asks. */
    public void report(final String note, final String log) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    ensureToken();
                    String id = api.report(note, log);
                    listener.manualResult(true, "Report sent" + (id == null ? "" : " (#" + id + ")") + ". Thanks, we'll take a look.");
                } catch (ApiClient.Unauthorized e) {
                    try { db.setMeta("token", null); } catch (Exception ignored) { }
                    listener.manualResult(false, "Couldn't send the report: login expired. Run /bb report again.");
                } catch (Exception e) {
                    listener.manualResult(false, "Couldn't send the report: " + explain(e));
                }
            }
        });
    }

    public void link(final String code) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    ensureToken();
                    api.link(code);
                    listener.manualResult(true, "Linked to your website account.");
                } catch (Exception e) {
                    listener.manualResult(false, "Couldn't link: " + explain(e));
                }
            }
        });
    }

    /** Turns an exception into something a player can act on. */
    static String explain(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.UnknownHostException) return "can't find the stats server (" + m + "). Check apiBase in the config.";
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException) return "the stats server isn't answering. It may be offline.";
        if (e instanceof javax.net.ssl.SSLException) return "secure connection failed (" + m + ").";
        if (e instanceof ApiClient.Unauthorized) return "login expired. Try again.";
        if (m.contains("bad_code")) return "that code is wrong or has expired.";
        if (m.contains("already_claimed") || m.contains("already_linked")) return "this Minecraft account is already linked.";
        if (m.startsWith("mojang: ")) return "Mojang's login check didn't go through. Their servers may be busy.";
        if (m.contains("not_verified")) return "Mojang couldn't confirm your account. Restart the game and try again.";
        if (m.contains("HTTP 5") || m.contains("HTTP 1033") || m.contains("HTTP 530")) return "the stats server is having trouble (" + m.split(":")[0] + ").";
        if (m.startsWith("HTTP 400")) return "the stats server turned the upload down. The mod or the server may need updating.";
        if (m.startsWith("HTTP ")) return "the stats server answered " + m.split(":")[0] + ".";
        return shorten(m, 120);
    }

    public void addSeenPlayers(List<String> uuids) {
        seenPlayers.addAll(uuids);
    }

    public boolean isOffline() { return offline; }

    private void ensureToken() throws Exception {
        String token = db.getMeta("token", null);
        if (token == null) {
            String serverId = api.challenge();
            try {
                listener.joinServer(serverId);
            } catch (Exception e) {
                throw new Exception("mojang: " + e.getMessage(), e);
            }
            token = api.verify(playerName, serverId);
            db.setMeta("token", token);
        }
        api.setToken(token);
    }

    private synchronized void tick(boolean force) {
        if (stopped) return;   // left the server: an upload queued as the game ended mustn't run on a closed database
        checkVersion();
        if (!force && System.currentTimeMillis() < nextAttemptAt) return;
        try {
            ensureToken();
            List<MatchRecord> batch;
            int rounds = 0;
            do {
                batch = db.pendingMatches(BATCH);
                List<String> seen;
                synchronized (seenPlayers) {
                    seen = new ArrayList<String>(seenPlayers.subList(0, Math.min(2000, seenPlayers.size())));
                }
                try {
                    apply(api.sync(batch, db.unlockedIds(), seen));
                } catch (ApiClient.Unauthorized e) {
                    db.setMeta("token", null);
                    throw e;
                } catch (Exception e) {
                    if (!isBadRequest(e) || batch.isEmpty()) throw e;
                    uploadOneByOne(batch, seen);   // one invalid game shouldn't hold back the rest
                }
                synchronized (seenPlayers) { seenPlayers.subList(0, seen.size()).clear(); }
            } while (batch.size() == BATCH && ++rounds < 20);
            db.setMeta("last_sync_at", Long.toString(System.currentTimeMillis()));
            if (System.currentTimeMillis() - lastBootstrapAt > BOOTSTRAP_EVERY_MS) {
                // Not worth failing the sync over: records and weekly challenges can wait.
                try {
                    ApiClient.Bootstrap b = api.bootstrap();
                    lastBootstrapAt = System.currentTimeMillis();
                    if (b != null) listener.bootstrapped(b);
                } catch (ApiClient.Unauthorized e) {
                    throw e;
                } catch (Exception ignored) {
                }
            }
            offline = false;
            backoffMs = 0;
            nextAttemptAt = 0;
            if (force) listener.manualResult(true, "Synced.");
        } catch (Exception e) {
            backoffMs = backoffMs == 0 ? MIN_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            if (stopped) return;   // the server switch itself caused this; the next session uploads the games
            if (force) {
                listener.manualResult(false, "Couldn't sync: " + explain(e));
            } else if (!offline) {
                offline = true;
                int pending = -1;
                try { pending = db.countPending(); } catch (Exception ignored) { }
                listener.offline(pending, explain(e));
            }
        }
    }

    private void apply(ApiClient.SyncResult res) throws Exception {
        db.markSynced(res.accepted);
        for (ApiClient.Rejection r : res.rejected) db.markRejected(r.nonce, r.reason);
        for (String id : res.confirmed) {
            if (db.unlockLocal(id, System.currentTimeMillis())) listener.achievementConfirmed(id);
            db.setAchievementState(id, "confirmed");
        }
        for (String id : res.revoked) {
            db.setAchievementState(id, "revoked");
            listener.achievementRevoked(id);
        }
        if (res.level != null) db.setMeta("level", String.valueOf(res.level));
        if (res.xp != null) db.setMeta("xp", String.valueOf(res.xp));
        if (res.globalRank != null) db.setMeta("rank", String.valueOf(res.globalRank));
        if (res.rankedPlayers != null) db.setMeta("ranked_players", String.valueOf(res.rankedPlayers));
        listener.synced(res);
    }

    /** The server answers 400 when what we sent doesn't pass its checks. */
    static boolean isBadRequest(Exception e) {
        return String.valueOf(e.getMessage()).startsWith("HTTP 400");
    }

    /**
     * The server turned down a batch. First send it no games: if that is turned down too, the problem isn't
     * the games (say the server predates this mod version) and nothing is set aside. Otherwise send the games
     * one at a time and set aside only those turned down on their own, so they stop blocking the queue.
     */
    private void uploadOneByOne(List<MatchRecord> batch, List<String> seen) throws Exception {
        apply(api.sync(new ArrayList<MatchRecord>(), db.unlockedIds(), seen));
        int skipped = 0;
        for (MatchRecord m : batch) {
            try {
                apply(api.sync(java.util.Collections.singletonList(m), db.unlockedIds(), new ArrayList<String>()));
            } catch (Exception e) {
                if (!isBadRequest(e)) throw e;
                db.markRejected(m.nonce, "invalid: " + shorten(String.valueOf(e.getMessage()), 200));
                skipped++;
            }
        }
        if (skipped > 0) listener.gamesSkipped(skipped);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private volatile boolean stopped;

    public void shutdown() {
        shutdown(null);
    }

    /**
     * Stops syncing. Uploads already queued are skipped, one already running finishes, and then
     * {@code afterwards} runs on the sync thread: the place to close the database, so it is never closed
     * under an upload. (Closing it straight away made every game end with "database connection closed",
     * because Hypixel moves you to the lobby seconds after the upload the game queued.)
     */
    public void shutdown(Runnable afterwards) {
        stopped = true;
        if (afterwards != null) {
            try {
                exec.execute(afterwards);
            } catch (java.util.concurrent.RejectedExecutionException alreadyStopped) {
                afterwards.run();
            }
        }
        exec.shutdown();
    }
}
