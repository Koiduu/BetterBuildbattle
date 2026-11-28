import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.lfg.Lfg;
import net.aspir3.betterbattle.lfg.LfgService;
import net.aspir3.betterbattle.sync.ApiClient;
import net.aspir3.betterbattle.sync.SyncService;

import java.util.ArrayList;
import java.util.List;

/**
 * LFG lobbies on the player's side, against a fake server:
 *  - a join request pops up once, with Accept / Decline buttons that run /bb lfg commands
 *  - accepting puts "/p invite name" in the chat box and never sends it
 *  - the joiner hears "Accepted, watch for the party invite", and about declines and closed lobbies once
 *  - a free slot in line and the 25-hour warning are announced once
 *  - lobby notifications off: no pop-ups, chat lines still come
 *  - search and tag filtering, server error wording
 */
public class LfgServiceTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static final class FakeApi implements ApiClient {
        String decided;
        public String challenge() { return "s"; }
        public String verify(String playerName, String serverId) { return "t"; }
        public void setToken(String token) {}
        public void link(String code) {}
        public Latest latest() { return null; }
        public String report(String note, String log) { return "1"; }
        public Bootstrap bootstrap() { return null; }
        public SyncResult sync(List<MatchRecord> m, List<String> a, List<String> s) { return new SyncResult(); }

        public Decided lfgDecide(long requestId, String action) {
            decided = requestId + ":" + action;
            Decided d = new Decided();
            d.status = "accept".equals(action) ? "accepted" : "declined";
            d.name = "Joe";
            d.invite = "accept".equals(action) ? "/p invite Joe" : null;
            return d;
        }

        public LobbyPoll lfgPoll() { return new LobbyPoll(); }
    }

    static final class Screen implements LfgService.Ui {
        final List<String> toasts = new ArrayList<String>(), chat = new ArrayList<String>(), filled = new ArrayList<String>();
        final List<String[][]> buttons = new ArrayList<String[][]>();
        public void toast(String title, String line) { toasts.add(line); }
        public void chat(String message) { chat.add(message); }
        public void chatButtons(String message, String[][] b) { chat.add(message); buttons.add(b); }
        public void fillChat(String text) { filled.add(text); }
        public void changed() {}
    }

    static String allChat(Screen s) {
        return String.join("\n", s.chat);
    }

    public static void main(String[] args) throws Exception {
        final FakeApi api = new FakeApi();
        final boolean[] notify = {true};
        Screen ui = new Screen();
        LfgService lfg = new LfgService(new LfgService.Backend() {
            public boolean connected() { return true; }
            public void call(SyncService.ApiCall call, SyncService.Failure failure) {
                try { call.run(api); } catch (Exception e) { if (failure != null) failure.failed(e); }
            }
        }, Runnable::run, ui, () -> notify[0], false);

        // A request comes in for the lobby I host
        ApiClient.LobbyPoll p = new ApiClient.LobbyPoll();
        p.hosting = lobby("L1", "Chill GTB", "gtb");
        ApiClient.LobbyRequest r = new ApiClient.LobbyRequest();
        r.id = 7;
        r.name = "Joe";
        r.status = "pending";
        p.requests.add(r);
        lfg.applyPoll(p);
        lfg.applyPoll(p);
        check(ui.toasts.size() == 1, "one pop-up per request, got " + ui.toasts);
        check(ui.buttons.size() == 1 && ui.buttons.get(0)[0][1].equals("/bb lfg accept 7")
            && ui.buttons.get(0)[1][1].equals("/bb lfg decline 7"), "accept/decline buttons run /bb lfg");
        check(lfg.pendingRequests() == 1, "one pending request");

        // Accepting fills the chat box; nothing is sent
        lfg.decide(7, "accept");
        check("7:accept".equals(api.decided), "decision reaches the server");
        check(ui.filled.size() == 1 && ui.filled.get(0).equals("/p invite Joe"), "invite typed in, not sent: " + ui.filled);

        // My own request gets accepted, then the lobby closes
        Screen ui2 = new Screen();
        LfgService joiner = new LfgService(new LfgService.Backend() {
            public boolean connected() { return true; }
            public void call(SyncService.ApiCall call, SyncService.Failure failure) {
                try { call.run(api); } catch (Exception e) { if (failure != null) failure.failed(e); }
            }
        }, Runnable::run, ui2, () -> notify[0], false);
        joiner.applyPoll(poll(joining("L2", "Host", "pending", true)));
        check(ui2.chat.isEmpty(), "an old pending request says nothing");
        joiner.applyPoll(poll(joining("L2", "Host", "accepted", true)));
        check(allChat(ui2).contains("Accepted by §fHost§7, watch for the party invite"), "accepted line: " + ui2.chat);
        joiner.applyPoll(poll(joining("L2", "Host", "accepted", true)));
        check(ui2.chat.size() == 1, "said once");
        joiner.applyPoll(poll(joining("L2", "Host", "accepted", false)));
        check(allChat(ui2).contains("lobby closed"), "lobby closed line");
        check(!joiner.wantsPoll(), "nothing left to watch");

        // A declined request, with notifications off: chat but no pop-up
        notify[0] = false;
        int toastsBefore = ui2.toasts.size();
        joiner.applyPoll(poll(joining("L3", "Ann", "pending", true)));
        joiner.applyPoll(poll(joining("L3", "Ann", "declined", true)));
        check(allChat(ui2).contains("turned your request down"), "declined line");
        check(ui2.toasts.size() == toastsBefore, "no pop-ups with notifications off");
        notify[0] = true;

        // A free slot while in line, announced once
        ApiClient.LobbyPoll q = new ApiClient.LobbyPoll();
        q.queue = new ApiClient.LobbyQueue();
        q.queue.position = 1;
        q.queue.offerUntil = "2099-01-01T00:00:00.000Z";
        joiner.applyPoll(q);
        joiner.applyPoll(q);
        int claims = 0;
        for (String[][] b : ui2.buttons) if (b[0][1].equals("/bb lfg claim")) claims++;
        check(claims == 1, "one claim offer");

        // 25-hour warning, once
        ApiClient.LobbyPoll old = new ApiClient.LobbyPoll();
        old.hosting = lobby("L9", "Long one", "teams");
        old.hosting.closesSoon = true;
        old.hosting.expiresAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") {{
            setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }}.format(new java.util.Date(System.currentTimeMillis() + 5 * 3_600_000L - 60_000L));
        lfg.applyPoll(old);
        lfg.applyPoll(old);
        int warnings = 0;
        for (String c : ui.chat) if (c.contains("Your lobby closes in 5 hours")) warnings++;
        check(warnings == 1, "warned once at 25 hours: " + ui.chat);

        // The host's lobby disappears without them closing it
        lfg.applyPoll(new ApiClient.LobbyPoll());
        check(allChat(ui).contains("Your lobby closed"), "told the lobby closed");

        // Search and tags
        List<ApiClient.Lobby> all = new ArrayList<ApiClient.Lobby>();
        all.add(lobby("a", "Chill GTB", "gtb"));
        all.add(lobby("b", "Sweaty teams", "teams"));
        check(Lfg.filter(all, null, "chill").size() == 1, "search by name");
        check(Lfg.filter(all, null, "host").size() == 2, "search by host");
        check(Lfg.filter(all, "teams", null).size() == 1, "filter by tag");
        check(Lfg.filter(all, null, "spb").isEmpty(), "search by tag label");
        check(Lfg.maxSize("spb") == 8 && Lfg.maxSize("teams") == 16, "sizes per tag");
        check(Lfg.next(Lfg.TAGS, "other").equals("teams"), "tag cycle wraps");

        // Server errors in words
        check(Lfg.explain(Lfg.errorCode(new Exception("HTTP 403: {\"error\":\"not_linked\"}"))).contains("/bb link"), "not linked");
        check(Lfg.explain(Lfg.errorCode(new Exception("HTTP 400: {\"error\":\"name_no_links\"}"))).contains("No links"), "no links");

        // The toggle is in Settings
        boolean found = false;
        for (Config.Toggle t : Config.TOGGLES) if (t.label.equals("Lobby notifications")) found = true;
        check(found, "Lobby notifications toggle");

        System.out.println("LfgServiceTest: " + checks + " checks passed");
    }

    static ApiClient.Lobby lobby(String id, String name, String tag) {
        ApiClient.Lobby l = new ApiClient.Lobby();
        l.id = id;
        l.name = name;
        l.tag = tag;
        l.host = "Host";
        l.maxSize = 4;
        l.players = 1;
        return l;
    }

    static ApiClient.Joining joining(String lobbyId, String host, String status, boolean open) {
        ApiClient.Joining j = new ApiClient.Joining();
        j.lobbyId = lobbyId;
        j.host = host;
        j.status = status;
        j.lobbyOpen = open;
        return j;
    }

    static ApiClient.LobbyPoll poll(ApiClient.Joining j) {
        ApiClient.LobbyPoll p = new ApiClient.LobbyPoll();
        p.joining.add(j);
        return p;
    }
}
