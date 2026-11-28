package net.aspir3.betterbattle.sync;

import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.heads.MyHeads;
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
        /** Several chat lines in answer to /bb rank or /bb compare. */
        void lines(List<String> lines);
        void synced(ApiClient.SyncResult result);
        void achievementConfirmed(String id);
        void achievementRevoked(String id);
        /** The server's newest mod version (checked now and then; it may be the one already installed). */
        void latestVersion(ApiClient.Latest latest);
        /** New weekly challenges or world records arrived. */
        void bootstrapped(ApiClient.Bootstrap b);
        /**
         * Where this player's voice group stands. changed: the player pressed something, or the group just
         * became ready or closed, so it is worth a chat line. A quiet refresh (the Voice screen opening) is false.
         */
        default void voice(ApiClient.Voice v, boolean changed) {
        }
        /** Voice was turned down. link is a page that fixes it (the account page, the Discord invite), or null. */
        default void voiceProblem(String message, String link) {
        }
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

    private volatile long lastHeadsAt;

    /** Fetches the player's custom heads now (the "My heads" section just opened). At most once a minute. */
    public void refreshMyHeads() {
        if (System.currentTimeMillis() - lastHeadsAt < 60000L) return;
        lastHeadsAt = System.currentTimeMillis();
        exec.execute(new Runnable() {
            public void run() {
                if (stopped) return;
                try {
                    ensureToken();
                    MyHeads.set(api.myHeads());
                } catch (ApiClient.Unauthorized e) {
                    try { db.setMeta("token", null); } catch (Exception ignored) { }
                } catch (Exception ignored) {
                    // offline or an older server: the saved copy stays
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

    /** Something to do with the logged-in API on the sync thread (LFG lobbies use this). */
    public interface ApiCall {
        void run(ApiClient api) throws Exception;
    }

    /** Where an ApiCall that failed reports why. */
    public interface Failure {
        void failed(Exception e);
    }

    /**
     * Runs call on the sync thread once logged in. A login that expired is renewed once and the call retried.
     * Runs even after shutdown() has been called for calls already queued, so a lobby can still be closed on leaving.
     */
    public void call(final ApiCall call, final Failure failure) {
        try {
            exec.execute(new Runnable() {
                public void run() {
                    try {
                        try {
                            ensureToken();
                            call.run(api);
                        } catch (ApiClient.Unauthorized e) {
                            db.setMeta("token", null);
                            ensureToken();
                            call.run(api);
                        }
                    } catch (Exception e) {
                        if (failure != null) failure.failed(e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (failure != null) failure.failed(new Exception("not connected"));
        }
    }

    /** /bb rank: asks the server where this player stands and shows it in chat. */
    public void rank() {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    ensureToken();
                    listener.lines(net.aspir3.betterbattle.pages.RankText.rank(api.rank()));
                } catch (UnsupportedOperationException e) {
                    listener.manualResult(false, "This stats server can't show ranks yet.");
                } catch (Exception e) {
                    listener.manualResult(false, "Couldn't get your rank: " + explain(e));
                }
            }
        });
    }

    /** /bb compare: this player next to another one. */
    public void compare(final String name) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    ensureToken();
                    listener.lines(net.aspir3.betterbattle.pages.RankText.compare(api.compare(name)));
                } catch (UnsupportedOperationException e) {
                    listener.manualResult(false, "This stats server can't compare players yet.");
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("not_found") && !m.contains("you_not_found")) {
                        listener.manualResult(false, "I haven't seen " + name + " play Build Battle.");
                    } else {
                        listener.manualResult(false, "Couldn't compare: " + explain(e));
                    }
                }
            }
        });
    }

    /** How often the mod asks whether the others have pressed Voice, and for how long (the server gives up at 10 minutes). */
    static final long VOICE_POLL_MS = 5000L, VOICE_POLL_FOR_MS = 10L * 60L * 1000L;
    private java.util.concurrent.ScheduledFuture<?> voicePoll;
    private volatile ApiClient.Voice voice;

    /** The voice group as last heard from the server, or null before the first answer. */
    public ApiClient.Voice voiceState() { return voice; }

    /** Pressing Voice with these players. */
    public void voice(final List<String> members) {
        exec.execute(new Runnable() {
            public void run() {
                voiceCall(new VoiceCall() {
                    public ApiClient.Voice call() throws Exception { return api.voice(members); }
                }, true);
            }
        });
    }

    /** What the Voice screen shows when it opens; no chat line. */
    public void refreshVoice() {
        exec.execute(new Runnable() {
            public void run() {
                voiceCall(new VoiceCall() {
                    public ApiClient.Voice call() throws Exception { return api.voiceStatus(); }
                }, false);
            }
        });
    }

    public void leaveVoice() {
        exec.execute(new Runnable() {
            public void run() {
                voiceCall(new VoiceCall() {
                    public ApiClient.Voice call() throws Exception {
                        api.leaveVoice();
                        ApiClient.Voice none = new ApiClient.Voice();
                        none.status = "none";
                        return none;
                    }
                }, true);
            }
        });
    }

    private interface VoiceCall {
        ApiClient.Voice call() throws Exception;
    }

    /** Runs on the sync thread. */
    private void voiceCall(VoiceCall call, boolean changed) {
        if (stopped) return;
        try {
            ensureToken();
            ApiClient.Voice v;
            try {
                v = call.call();
            } catch (ApiClient.Unauthorized e) {
                db.setMeta("token", null);   // the token ran out: log in again once
                ensureToken();
                v = call.call();
            }
            voice = v;
            listener.voice(v, changed);
            if (v.waiting()) pollVoice(System.currentTimeMillis() + VOICE_POLL_FOR_MS);
            else stopVoicePoll();
        } catch (UnsupportedOperationException e) {
            listener.voiceProblem("This stats server doesn't have voice chat yet.", null);
        } catch (Exception e) {
            String[] problem = voiceProblem(e);
            listener.voiceProblem(problem[0], problem[1]);
        }
    }

    /** While the others haven't pressed Voice yet, ask every few seconds and say so once the channel is ready. */
    private void pollVoice(final long until) {
        if (voicePoll != null && !voicePoll.isDone()) return;
        voicePoll = exec.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                if (stopped || System.currentTimeMillis() > until) {
                    stopVoicePoll();
                    return;
                }
                try {
                    ensureToken();
                    ApiClient.Voice v = api.voiceStatus();
                    ApiClient.Voice before = voice;
                    voice = v;
                    if (!v.waiting()) {
                        stopVoicePoll();
                        listener.voice(v, true);   // ready, or the group closed
                    } else if (before == null || before.waitingFor == null || !before.waitingFor.equals(v.waitingFor)) {
                        listener.voice(v, false);  // someone else pressed Voice: the screen shows who is left
                    }
                } catch (Exception ignored) {
                    // a blip: try again next time round
                }
            }
        }, VOICE_POLL_MS, VOICE_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopVoicePoll() {
        if (voicePoll != null) voicePoll.cancel(false);
        voicePoll = null;
    }

    /** What to tell the player when the server turns voice down: the message, and a page that fixes it or null. */
    public static String[] voiceProblem(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("voice_off")) return new String[] {
            "Voice chat is off for your account. Turn on \"Allow voice chat\" on your account page on the website first.", "/account"};
        if (m.contains("discord_required")) return new String[] {
            "Connect your Discord account on your account page on the website first.", "/account"};
        if (m.contains("not_in_server")) {
            String invite = null;
            int at = m.indexOf("\"invite\":\"");
            if (at >= 0) {
                int end = m.indexOf('"', at + 10);
                if (end > at + 10) invite = m.substring(at + 10, end);
            }
            return new String[] {"Join the Better Battle Discord server first, then press Voice again.", invite};
        }
        if (m.contains("no_minecraft")) return new String[] {
            "Link this Minecraft account to your website account first: run /bb link with the code from the account page.", "/account"};
        if (m.contains("bad_members")) return new String[] {
            "Name 1 to 7 other players by their Minecraft names, like /bb voice Teammate.", null};
        if (m.contains("voice_unavailable")) return new String[] {"Voice chat isn't switched on for the stats server right now.", null};
        if (m.contains("HTTP 429")) return new String[] {"Slow down a little, then try again.", null};
        return new String[] {"Couldn't reach voice: " + explain(e), null};
    }

    /** Turns an exception into something a player can act on. */
    public static String explain(Exception e) {
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
                    if (b != null) MyHeads.set(b.customHeads);
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
