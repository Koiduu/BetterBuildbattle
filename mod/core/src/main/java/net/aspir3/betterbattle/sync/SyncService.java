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
        void offline(int pendingGames);
        void synced(ApiClient.SyncResult result);
        void achievementConfirmed(String id);
        void achievementRevoked(String id);
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
    private boolean offline;

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

    public void addSeenPlayers(List<String> uuids) {
        seenPlayers.addAll(uuids);
    }

    private void ensureToken() throws Exception {
        String token = db.getMeta("token", null);
        if (token == null) {
            String serverId = api.challenge();
            listener.joinServer(serverId);
            token = api.verify(playerName, serverId);
            db.setMeta("token", token);
        }
        api.setToken(token);
    }

    private synchronized void tick(boolean force) {
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
                ApiClient.SyncResult res;
                try {
                    res = api.sync(batch, db.unlockedIds(), seen);
                } catch (ApiClient.Unauthorized e) {
                    db.setMeta("token", null);
                    throw e;
                }
                synchronized (seenPlayers) { seenPlayers.subList(0, seen.size()).clear(); }
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
            } while (batch.size() == BATCH && ++rounds < 20);
            db.setMeta("last_sync_at", Long.toString(System.currentTimeMillis()));
            offline = false;
            backoffMs = 0;
            nextAttemptAt = 0;
        } catch (Exception e) {
            backoffMs = backoffMs == 0 ? MIN_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            if (!offline) {
                offline = true;
                int pending = -1;
                try { pending = db.countPending(); } catch (Exception ignored) { }
                listener.offline(pending);
            }
        }
    }

    public void shutdown() {
        exec.shutdown();
    }
}
