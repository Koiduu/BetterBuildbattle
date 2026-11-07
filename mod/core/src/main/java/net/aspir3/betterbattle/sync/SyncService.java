package net.aspir3.betterbattle.sync;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Drains the local outbox to the server. Runs on its own daemon thread, never the render thread.
 * If the server is down, nothing is lost: matches stay 'pending' in SQLite and go up later.
 */
public final class SyncService {
    private static final Logger LOG = Logger.getLogger("BetterBattle/Sync");
    private static final int BATCH = 50;
    private static final long MIN_BACKOFF_MS = 5_000L, MAX_BACKOFF_MS = 600_000L;

    public interface StatusSink {
        void offline(int pendingGames);   // show once: "Better Battle is offline — your games are saved locally."
        void online(int uploaded);
        void achievementConfirmed(String id);
        void achievementRevoked(String id);
    }

    private final LocalDatabase db;
    private final ApiClient api;
    private final StatusSink ui;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BetterBattle-Sync");
        t.setDaemon(true);
        return t;
    });
    private long backoffMs = 0;
    private long nextAttemptAt = 0;
    private boolean announcedOffline = false;

    public SyncService(LocalDatabase db, ApiClient api, StatusSink ui) {
        this.db = db;
        this.api = api;
        this.ui = ui;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 2, 60, TimeUnit.SECONDS);
    }

    /** Call when a game ends: flush soon, but still off-thread. */
    public void nudge() {
        exec.execute(this::tick);
    }

    private void tick() {
        if (System.currentTimeMillis() < nextAttemptAt) return;
        try {
            if ("1".equals(db.getMeta("server_requested_full_resync", "0"))) {
                int n = db.requeueEverything();
                db.setMeta("server_requested_full_resync", "0");
                LOG.info("Server asked for full history, re-queued " + n + " matches");
            }
            int uploaded = 0;
            List<MatchRecord> batch;
            while (!(batch = db.pendingMatches(BATCH)).isEmpty()) {
                List<String> nonces = new ArrayList<String>();
                for (MatchRecord m : batch) nonces.add(m.nonce);
                ApiClient.SyncResult res;
                try {
                    res = api.sync(batch, db.unlockedIds());
                } catch (Exception netErr) {
                    db.bumpAttempts(nonces);
                    throw netErr;
                }
                db.markSynced(res.accepted);
                for (ApiClient.Rejection r : res.rejected) db.markRejected(r.nonce, r.reason);
                for (String id : res.confirmed) { db.setAchievementState(id, "confirmed"); ui.achievementConfirmed(id); }
                for (String id : res.revoked) { db.setAchievementState(id, "revoked"); ui.achievementRevoked(id); }
                if (res.fullResyncRequested) db.setMeta("server_requested_full_resync", "1");
                uploaded += res.accepted.size();
                if (res.accepted.isEmpty() && res.rejected.isEmpty()) break; // server made no progress
            }
            db.setMeta("last_sync_at", Long.toString(System.currentTimeMillis()));
            if (announcedOffline || uploaded > 0) ui.online(uploaded);
            announcedOffline = false;
            backoffMs = 0;
        } catch (Exception e) {
            backoffMs = backoffMs == 0 ? MIN_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            if (!announcedOffline) {
                announcedOffline = true;
                try { ui.offline(db.countPending()); } catch (Exception ignored) { ui.offline(-1); }
            }
            LOG.fine("Sync failed, retry in " + backoffMs / 1000 + "s: " + e);
        }
    }

    public void shutdown() {
        exec.shutdown();
    }
}
