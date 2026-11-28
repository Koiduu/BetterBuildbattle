package net.aspir3.betterbattle.lfg;

import net.aspir3.betterbattle.sync.ApiClient;
import net.aspir3.betterbattle.sync.SyncService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LFG lobbies on the player's side: the lobby list, the lobby you host, your requests and your place in line.
 * Polls the server every few seconds, but only while there is something to watch (hosting, waiting on a
 * request or in line). Never sends anything to Hypixel: after an accept the host gets "/p invite name"
 * in their chat box and presses Enter themselves.
 */
public final class LfgService {
    /** Reaches the logged-in API on the sync thread. */
    public interface Backend {
        /** False while not on Hypixel. */
        boolean connected();

        void call(SyncService.ApiCall call, SyncService.Failure failure);
    }

    /** What the game shows. Always called on the main thread. */
    public interface Ui {
        /** A pop-up in the corner. Only called when lobby notifications are on. */
        void toast(String title, String line);

        void chat(String message);

        /** A chat line followed by clickable buttons: each is {label, command to run}. */
        void chatButtons(String message, String[][] buttons);

        /** Opens the chat box with this text typed in, for the player to send (or not) themselves. */
        void fillChat(String text);

        /** Lobby state changed; open screens redraw. */
        void changed();
    }

    public interface Notifications {
        boolean on();
    }

    static final String PREFIX = "§d§lBetter Battle §8» §7";
    private static final long POLL_SECONDS = 5;
    /** After you start, join or claim, keep polling at least this long even if the server says nothing yet. */
    private static final long BOOST_MS = 60_000L;

    private final Backend backend;
    private final Executor main;
    private final Ui ui;
    private final Notifications notifications;
    private final ScheduledExecutorService ticker;

    private volatile List<ApiClient.Lobby> lobbies = Collections.emptyList();
    private volatile String notice = Lfg.NOTICE;
    private volatile boolean loading;
    private volatile ApiClient.Lobby hosting;
    private volatile List<ApiClient.LobbyRequest> requests = Collections.emptyList();
    private volatile List<ApiClient.Joining> joining = Collections.emptyList();
    private volatile ApiClient.LobbyQueue queue;
    private volatile String status = "";
    private volatile long boostUntil;
    private volatile boolean polling;

    // What the player has already been told about. Touched on the sync thread only.
    private final Set<Long> toldRequests = new HashSet<Long>();
    private final Map<String, String> toldJoin = new HashMap<String, String>();
    private final Set<String> warnedLobbies = new HashSet<String>();
    private String toldOffer;
    private String hostingId;
    private boolean closingMyself;

    public LfgService(Backend backend, Executor main, Ui ui, Notifications notifications) {
        this(backend, main, ui, notifications, true);
    }

    /** schedule=false for tests: poll() is then called by hand. */
    public LfgService(Backend backend, Executor main, Ui ui, Notifications notifications, boolean schedule) {
        this.backend = backend;
        this.main = main;
        this.ui = ui;
        this.notifications = notifications;
        if (schedule) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BetterBattle-LFG");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        } else {
            ticker = null;
        }
    }

    // ---- What screens read

    public List<ApiClient.Lobby> lobbies() { return lobbies; }
    public String notice() { return notice == null ? Lfg.NOTICE : notice; }
    public boolean loading() { return loading; }
    public ApiClient.Lobby hosting() { return hosting; }
    public List<ApiClient.LobbyRequest> requests() { return requests; }
    public List<ApiClient.Joining> joining() { return joining; }
    public ApiClient.LobbyQueue queue() { return queue; }
    /** The last thing that happened, for the screens' status line. */
    public String status() { return status; }
    public boolean connected() { return backend.connected(); }

    /** My request to this lobby, as the server last said: pending, accepted, declined, kicked, or null. */
    public String myRequest(String lobbyId) {
        for (ApiClient.Joining j : joining) if (j.lobbyId.equals(lobbyId)) return j.status;
        for (ApiClient.Lobby l : lobbies) if (l.id.equals(lobbyId)) return l.myRequest;
        return null;
    }

    public long pendingRequests() {
        long n = 0;
        for (ApiClient.LobbyRequest r : requests) if ("pending".equals(r.status)) n++;
        return n;
    }

    // ---- Actions. All of them run on the sync thread and report back through status() and the Ui.

    public void refresh() {
        if (!ready()) return;
        loading = true;
        changed();
        run(api -> {
            ApiClient.LobbyList list = api.lfgList(null, null);
            lobbies = list.lobbies == null ? Collections.<ApiClient.Lobby>emptyList() : list.lobbies;
            if (list.notice != null) notice = list.notice;
            loading = false;
            changed();
        }, "Couldn't load lobbies");
    }

    public void start(final ApiClient.LobbySettings settings, final String password) {
        if (!ready()) return;
        setStatus("Starting your lobby...");
        boost();
        run(api -> {
            ApiClient.LobbyStarted s = api.lfgStart(settings, password);
            if (s.lobby != null) {
                opened(s.lobby);
            } else if (s.queued != null) {
                queue = s.queued;
                say("All lobbies are full, you're §f#" + s.queued.position + "§7 in line. We'll tell you when a slot opens.");
            }
            pollNow(api);
        }, "Couldn't start the lobby");
    }

    public void claim() {
        if (!ready()) return;
        boost();
        run(api -> {
            ApiClient.LobbyStarted s = api.lfgClaim();
            if (s.lobby != null) opened(s.lobby);
            pollNow(api);
        }, "Couldn't claim the slot");
    }

    public void leaveQueue() {
        if (!ready()) return;
        run(api -> {
            api.lfgLeaveQueue();
            queue = null;
            toldOffer = null;
            setStatus("You left the line.");
        }, "Couldn't leave the line");
    }

    public void close() {
        final ApiClient.Lobby l = hosting;
        if (l == null || !ready()) return;
        run(api -> {
            closingMyself = true;
            api.lfgClose(l.id);
            hosting = null;
            hostingId = null;
            requests = Collections.emptyList();
            setStatus("Lobby closed.");
            refreshQuietly(api);
        }, "Couldn't close the lobby");
    }

    public void rename(final String name) {
        final ApiClient.Lobby l = hosting;
        if (l == null || !ready()) return;
        run(api -> {
            ApiClient.Lobby renamed = api.lfgRename(l.id, name);
            if (renamed != null) hosting = renamed;
            setStatus("Renamed to §f" + name + "§7.");
        }, "Couldn't rename it");
    }

    public void report(final String lobbyId) {
        if (!ready()) return;
        run(api -> {
            api.lfgReport(lobbyId);
            setStatus("Thanks, the name was reported. Admins will take a look.");
        }, "Couldn't report it");
    }

    public void join(final String lobbyId, final String password) {
        if (!ready()) return;
        boost();
        setStatus("Asking to join...");
        run(api -> {
            api.lfgJoin(lobbyId, password);
            toldJoin.put(lobbyId, "pending");
            setStatus("Request sent. You'll hear when the host answers.");
            pollNow(api);
            refreshQuietly(api);
        }, "Couldn't ask to join");
    }

    public void leave(final String lobbyId) {
        if (!ready()) return;
        run(api -> {
            api.lfgLeave(lobbyId);
            toldJoin.remove(lobbyId);
            setStatus("Done.");
            pollNow(api);
            refreshQuietly(api);
        }, "Couldn't do that");
    }

    /** accept, decline, kick or block. Accept puts "/p invite name" in the host's chat box. */
    public void decide(final long requestId, final String action) {
        if (!ready()) return;
        run(api -> {
            final ApiClient.Decided d = api.lfgDecide(requestId, action);
            if (d.invite != null && d.invite.matches("^/p invite [A-Za-z0-9_]{1,16}$")) {
                main.execute(() -> {
                    ui.chat(PREFIX + "Accepted §f" + d.name + "§7. Press §fEnter§7 to send the party invite.");
                    ui.fillChat(d.invite);
                });
            } else {
                String what = "declined".equals(d.status) ? "Declined " : "kicked".equals(d.status) ? "Kicked " : "blocked".equals(d.status) ? "Blocked " : "Updated ";
                setStatus(what + "§f" + d.name + "§7.");
            }
            pollNow(api);
        }, "Couldn't do that");
    }

    /** Leaving Hypixel: close the lobby you host and step out of the line. Queued before the sync stops. */
    public void onLeave() {
        final ApiClient.Lobby l = hosting;
        final ApiClient.LobbyQueue q = queue;
        if (l != null) backend.call(api -> api.lfgClose(l.id), null);
        if (q != null) backend.call(ApiClient::lfgLeaveQueue, null);
        hosting = null;
        hostingId = null;
        queue = null;
        requests = Collections.emptyList();
        joining = Collections.emptyList();
        toldRequests.clear();
        toldJoin.clear();
        toldOffer = null;
        changed();
    }

    public void shutdown() {
        if (ticker != null) ticker.shutdownNow();
    }

    // ---- Polling

    private void tick() {
        try {
            if (!polling && backend.connected() && wantsPoll()) {
                polling = true;
                backend.call(api -> {
                    try {
                        pollNow(api);
                    } finally {
                        polling = false;
                    }
                }, e -> polling = false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** True while there is something to keep polling for. */
    public boolean wantsPoll() {
        if (hosting != null || queue != null || System.currentTimeMillis() < boostUntil) return true;
        for (ApiClient.Joining j : joining) {
            if (j.lobbyOpen && ("pending".equals(j.status) || "accepted".equals(j.status))) return true;
        }
        return false;
    }

    /** Asks the server for the latest and tells the player what changed. Runs on the sync thread. */
    void pollNow(ApiClient api) throws Exception {
        ApiClient.LobbyPoll p = api.lfgPoll();
        if (p == null) return;
        applyPoll(p);
    }

    /** Takes in what the server said and tells the player what changed. Public for tests. */
    public void applyPoll(ApiClient.LobbyPoll p) {
        final boolean toasts = notifications.on();

        // The lobby you host
        if (p.hosting == null && hostingId != null && !closingMyself) {
            tell("Your lobby closed.", "Your lobby closed (it timed out or you were away too long).", null);
        }
        closingMyself = false;
        hosting = p.hosting;
        hostingId = p.hosting == null ? null : p.hosting.id;
        if (p.hosting != null && p.hosting.closesSoon && warnedLobbies.add(p.hosting.id)) {
            long h = Lfg.hoursLeft(p.hosting.expiresAt, System.currentTimeMillis());
            String line = "Your lobby closes in " + (h < 0 ? "a few" : String.valueOf(h)) + (h == 1 ? " hour" : " hours");
            tell(line, line + ". Start a new one after that if you still need players.", null);
        }

        // People asking to join it
        requests = p.requests == null ? Collections.<ApiClient.LobbyRequest>emptyList() : p.requests;
        for (final ApiClient.LobbyRequest r : requests) {
            if (!"pending".equals(r.status) || !toldRequests.add(r.id)) continue;
            final String name = r.name;
            main.execute(() -> {
                if (toasts) ui.toast("Lobby request", name + " wants to join");
                ui.chatButtons(PREFIX + "§f" + name + "§7 wants to join your lobby.", new String[][] {
                    {"§a[Accept]", "/bb lfg accept " + r.id},
                    {"§c[Decline]", "/bb lfg decline " + r.id},
                    {"§7[Lobby]", "/bb lfg mine"},
                });
            });
        }

        // Your own requests
        joining = p.joining == null ? Collections.<ApiClient.Joining>emptyList() : p.joining;
        for (ApiClient.Joining j : joining) {
            String before = toldJoin.get(j.lobbyId);
            String now = j.lobbyOpen ? j.status : "closed";
            if (now.equals(before)) continue;
            toldJoin.put(j.lobbyId, now);
            if (before == null && !"accepted".equals(now)) continue;   // nothing new to say about an old request
            if ("accepted".equals(now)) {
                tell("Accepted by " + j.host, "Accepted by §f" + j.host + "§7, watch for the party invite.", null);
            } else if ("declined".equals(now)) {
                tell("Request declined", "§f" + j.host + "§7 turned your request down.", null);
            } else if ("kicked".equals(now)) {
                tell("Removed from lobby", "§f" + j.host + "§7 removed you from their lobby.", null);
            } else if ("closed".equals(now) && ("pending".equals(before) || "accepted".equals(before))) {
                tell("Lobby closed", "§f" + j.host + "§7's lobby closed.", null);
            }
        }

        // Your place in line
        queue = p.queue;
        if (p.queue != null && p.queue.offerUntil != null && !p.queue.offerUntil.equals(toldOffer)) {
            toldOffer = p.queue.offerUntil;
            long s = Lfg.secondsLeft(p.queue.offerUntil, System.currentTimeMillis());
            tell("A lobby slot is free", "A lobby slot is free! Claim it within " + (s < 0 ? "a minute" : s + " seconds") + ".",
                new String[][] {{"§a[Claim]", "/bb lfg claim"}});
        }
        changed();
    }

    // ---- Helpers

    private void opened(ApiClient.Lobby l) {
        hosting = l;
        hostingId = l.id;
        queue = null;
        toldOffer = null;
        say("Your lobby §f" + l.name + "§7 is open. You'll get a pop-up when someone asks to join.");
    }

    private boolean ready() {
        if (backend.connected()) return true;
        setStatus("Join Hypixel first.");
        main.execute(() -> ui.chat(PREFIX + "Join Hypixel first, then open the lobbies again."));
        return false;
    }

    private void boost() {
        boostUntil = System.currentTimeMillis() + BOOST_MS;
    }

    private void refreshQuietly(ApiClient api) {
        try {
            ApiClient.LobbyList list = api.lfgList(null, null);
            if (list.lobbies != null) lobbies = list.lobbies;
        } catch (Exception ignored) {
        }
    }

    private void run(final SyncService.ApiCall call, final String failedWhat) {
        backend.call(call, e -> {
            loading = false;
            setStatus("§c" + failedWhat + ": §7" + reason(e));
        });
    }

    static String reason(Exception e) {
        if (e instanceof UnsupportedOperationException) return "this stats server doesn't have lobbies yet.";
        String known = Lfg.explain(Lfg.errorCode(e));
        return known != null ? known : SyncService.explain(e);
    }

    private void setStatus(String s) {
        status = s;
        changed();
    }

    /** A status line and a chat line. */
    private void say(final String s) {
        status = s;
        main.execute(() -> {
            ui.chat(PREFIX + s);
            ui.changed();
        });
    }

    /** A toast (when notifications are on), a chat line, and the status line. */
    private void tell(final String toast, final String chatLine, final String[][] buttons) {
        status = chatLine;
        final boolean toasts = notifications.on();
        main.execute(() -> {
            if (toasts) ui.toast("Lobbies", toast);
            if (buttons == null) ui.chat(PREFIX + chatLine);
            else ui.chatButtons(PREFIX + chatLine, buttons);
            ui.changed();
        });
    }

    private void changed() {
        main.execute(ui::changed);
    }

    /** For the lobby list: lobbies sorted so your own comes first (the server already does this). */
    public List<ApiClient.Lobby> shown(String tag, String search) {
        return new ArrayList<ApiClient.Lobby>(Lfg.filter(lobbies, tag, search));
    }
}
