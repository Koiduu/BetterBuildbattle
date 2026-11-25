import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;
import net.aspir3.betterbattle.sync.ApiClient;
import net.aspir3.betterbattle.sync.SyncService;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Uploads against a fake server:
 *  - one invalid game is set aside and the others still upload (it used to block every retry forever)
 *  - when the server turns down even an upload with no games, nothing is set aside
 *  - a server that doesn't answer gives a reason the player can act on
 */
public class SyncServiceTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    /** Turns down any upload containing a game themed "BAD", or every upload when refuseAll is set. */
    static class FakeServer implements ApiClient {
        boolean refuseAll, down;
        final List<String> uploaded = new ArrayList<String>();

        public String challenge() { return "server-id"; }
        public String verify(String playerName, String serverId) { return "token"; }
        public void setToken(String token) {}
        public void link(String code) {}
        public Latest latest() { return null; }
        public String report(String note, String log) { return "1"; }
        public Bootstrap bootstrap() { return null; }

        public SyncResult sync(List<MatchRecord> matches, List<String> localAchievementIds, List<String> seenPlayers) throws Exception {
            if (down) throw new java.net.ConnectException("Connection refused");
            if (refuseAll) throw new Exception("HTTP 400: {\"error\":\"bad_request\"}");
            for (MatchRecord m : matches) {
                if ("BAD".equals(m.theme)) throw new Exception("HTTP 400: {\"error\":\"bad_request\",\"issues\":[]}");
            }
            SyncResult r = new SyncResult();
            for (MatchRecord m : matches) {
                r.accepted.add(m.nonce);
                uploaded.add(m.theme);
            }
            return r;
        }
    }

    static final class Recorder implements SyncService.Listener {
        String offlineReason;
        int skipped;
        public void joinServer(String serverId) {}
        public void offline(int pendingGames, String reason) { offlineReason = reason; }
        public void gamesSkipped(int count) { skipped += count; }
        public void manualResult(boolean ok, String message) {}
        public void lines(List<String> lines) {}
        public void synced(ApiClient.SyncResult result) {}
        public void achievementConfirmed(String id) {}
        public void achievementRevoked(String id) {}
        public void latestVersion(ApiClient.Latest latest) {}
        public void bootstrapped(ApiClient.Bootstrap b) {}
    }

    private static MatchRecord game(String theme, long at) {
        MatchRecord m = new MatchRecord();
        m.mode = "solo";
        m.theme = theme;
        m.playedAt = at;
        m.placement = 2;
        m.players = 8;
        return m;
    }

    private static void tick(SyncService sync) throws Exception {
        Method tick = SyncService.class.getDeclaredMethod("tick", boolean.class);
        tick.setAccessible(true);
        tick.invoke(sync, false);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("sync-service-test").toFile();

        // One invalid game among three: the other two upload, the bad one is set aside, no "offline" message.
        try (LocalDatabase db = new LocalDatabase(dir, "one-bad")) {
            db.insertMatch(game("Castle", 1000));
            db.insertMatch(game("BAD", 2000));
            db.insertMatch(game("Pond", 3000));
            FakeServer server = new FakeServer();
            Recorder heard = new Recorder();
            SyncService sync = new SyncService(db, server, "Koidu", heard);
            tick(sync);
            check(server.uploaded.contains("Castle") && server.uploaded.contains("Pond"), "good games uploaded, got " + server.uploaded);
            check(!server.uploaded.contains("BAD"), "bad game not uploaded");
            check(db.countPending() == 0, "nothing left pending, got " + db.countPending());
            check(heard.skipped == 1, "told the player 1 game was skipped, got " + heard.skipped);
            check(heard.offlineReason == null, "no offline message, got " + heard.offlineReason);
            check(!sync.isOffline(), "not marked offline");
            sync.shutdown();
        }

        // The server turns everything down, even an upload with no games: keep all games, explain, don't skip.
        try (LocalDatabase db = new LocalDatabase(dir, "refuse-all")) {
            db.insertMatch(game("Castle", 1000));
            db.insertMatch(game("Pond", 2000));
            FakeServer server = new FakeServer();
            server.refuseAll = true;
            Recorder heard = new Recorder();
            SyncService sync = new SyncService(db, server, "Koidu", heard);
            tick(sync);
            check(db.countPending() == 2, "both games kept for later, got " + db.countPending());
            check(heard.skipped == 0, "nothing skipped, got " + heard.skipped);
            check(heard.offlineReason != null && heard.offlineReason.contains("turned the upload down"),
                "reason names the refusal, got " + heard.offlineReason);
            sync.shutdown();
        }

        // The server doesn't answer: the reason says so.
        try (LocalDatabase db = new LocalDatabase(dir, "down")) {
            db.insertMatch(game("Castle", 1000));
            FakeServer server = new FakeServer();
            server.down = true;
            Recorder heard = new Recorder();
            SyncService sync = new SyncService(db, server, "Koidu", heard);
            tick(sync);
            check(db.countPending() == 1, "game kept for later");
            check(heard.offlineReason != null && heard.offlineReason.contains("isn't answering"),
                "reason says the server isn't answering, got " + heard.offlineReason);
            sync.shutdown();
        }

        // Hypixel moves you to the lobby seconds after a game: the upload the game queued is still running when
        // the mod leaves the server. The database must close only after it, with no "offline" message.
        try (LocalDatabase db = new LocalDatabase(dir, "server-switch")) {
            db.insertMatch(game("Castle", 1000));
            final java.util.concurrent.CountDownLatch inUpload = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
            final int[] uploads = {0};
            FakeServer slow = new FakeServer() {
                @Override
                public SyncResult sync(List<MatchRecord> matches, List<String> ids, List<String> seen) throws Exception {
                    uploads[0]++;
                    inUpload.countDown();
                    release.await();
                    return super.sync(matches, ids, seen);
                }
            };
            Recorder heard = new Recorder();
            SyncService sync = new SyncService(db, slow, "Koidu", heard);
            sync.nudge();                                   // the upload a finished game queues
            check(inUpload.await(5, java.util.concurrent.TimeUnit.SECONDS), "upload started");
            sync.nudge();                                   // another one queued behind it
            final boolean[] dbOpenWhenClosing = {false};
            sync.shutdown(new Runnable() {                  // joining the lobby
                public void run() {
                    try { db.countPending(); dbOpenWhenClosing[0] = true; } catch (Exception e) { dbOpenWhenClosing[0] = false; }
                    closed.countDown();
                }
            });
            release.countDown();
            check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS), "the close step ran");
            check(dbOpenWhenClosing[0], "the database was still usable until the close step");
            check(uploads[0] == 1, "the queued upload was skipped once stopped, got " + uploads[0] + " uploads");
            check(heard.offlineReason == null, "no offline message from the switch, got " + heard.offlineReason);
            check(db.countPending() == 0, "the running upload still finished and marked the game synced");
        }

        System.out.println("SyncServiceTest: " + checks + " assertions passed");
    }
}
