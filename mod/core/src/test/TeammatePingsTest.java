import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.pings.TeammatePings;
import net.aspir3.betterbattle.pings.TeammatePings.Kind;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Teammate pings:
 *  - only in Teams while building, with a teammate the sidebar names; never in Guess the Build or Solo
 *  - the teammate is read from the sidebar ("Teammate:" then the name, or "None")
 *  - nothing is sent until the server says the teammate named you back, and cooldowns hold
 *  - only pings from the current teammate are shown
 */
public final class TeammatePingsTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static final class FakeServer implements TeammatePings.Transport {
        boolean ready;
        final List<TeammatePings.Incoming> waiting = new ArrayList<TeammatePings.Incoming>();
        final List<String> sent = new ArrayList<String>();
        int polls, leaves;

        public TeammatePings.Poll poll(String teammate) {
            polls++;
            TeammatePings.Poll p = new TeammatePings.Poll();
            p.ready = ready;
            p.pings.addAll(waiting);
            waiting.clear();
            return p;
        }

        public synchronized void send(String teammate, String kind, int[] block) {
            sent.add(teammate + ":" + kind + (block == null ? "" : "@" + block[0] + "," + block[1] + "," + block[2]));
        }

        public void leave() { leaves++; }
    }

    static final class Shown implements TeammatePings.Listener {
        final List<String> received = new ArrayList<String>();
        public void received(String from, Kind kind, int[] block) { received.add(from + ":" + kind.id); }
        public void sent(String to, Kind kind) {}
        public void notice(String message) {}
    }

    public static void main(String[] args) throws Exception {
        onlyInTeams();
        teammateFromSidebar();
        sendingAndCooldowns();
        System.out.println("TeammatePingsTest: " + checks + " assertions passed");
    }

    private static void onlyInTeams() {
        check(TeammatePings.allowed(true, "teams", "BUILDING", "Mate"), "Teams while building");
        check(!TeammatePings.allowed(true, "gtb", "BUILDING", "Mate"), "never in Guess the Build");
        check(!TeammatePings.allowed(true, "solo", "BUILDING", "Mate"), "not in Solo");
        check(!TeammatePings.allowed(true, "pro", "BUILDING", "Mate"), "not in Pro");
        check(!TeammatePings.allowed(true, "speed_builders", "BUILDING", "Mate"), "not in Speed Builders");
        check(!TeammatePings.allowed(true, "teams", "VOTING", "Mate"), "not while voting");
        check(!TeammatePings.allowed(true, "teams", "LOBBY", "Mate"), "not in the lobby");
        check(!TeammatePings.allowed(true, "teams", "BUILDING", null), "not without a teammate");
        check(!TeammatePings.allowed(false, "teams", "BUILDING", "Mate"), "not when switched off");
    }

    private static GameStateMachine machine() {
        ChatParser parser = new ChatParser(new InputStreamReader(
            TeammatePingsTest.class.getResourceAsStream("/betterbattle/patterns.json"), StandardCharsets.UTF_8));
        return new GameStateMachine(parser, new GameStateMachine.Listener() {
            public void onStateChanged(GameStateMachine.State state) {}
            public void onMatchFinished(MatchRecord match) {}
        }, "26.3", "Koidu");
    }

    private static void teammateFromSidebar() {
        GameStateMachine game = machine();
        String bb = "BUILD BATTLE";
        game.onSidebar(bb, Arrays.asList("Time: 07:00", "", "Theme:", "Volcano", "", "Teams: 17", "", "Teammate:", "Mate", "", "Mode: Teams"));
        check("Mate".equals(game.teammate()), "teammate read from the sidebar");
        check(game.state() == GameStateMachine.State.BUILDING, "building");
        check(TeammatePings.allowed(true, game.mode(), game.state().name(), game.teammate()), "pings allowed in this game");
        game.onSidebar(bb, Arrays.asList("Time: 06:59", "", "Theme:", "Volcano", "", "Teams: 17", "", "Teammate:", "None", "", "Mode: Teams"));
        check(game.teammate() == null, "\"None\" is no teammate");
        game.leave();
        check(game.teammate() == null, "forgotten after the game");

        GameStateMachine gtb = machine();
        gtb.onSidebar("GUESS THE BUILD", Arrays.asList("Builder:", "unzf", "", "Time: 02:00", "", "Teammate:", "Mate", "", "Theme:", "???"));
        check(gtb.teammate() == null, "no teammate outside Teams, even if a sidebar names one");
        check(!TeammatePings.allowed(true, gtb.mode(), gtb.state().name(), gtb.teammate()), "no pings in Guess the Build");
    }

    private static void sendingAndCooldowns() throws Exception {
        FakeServer server = new FakeServer();
        Shown shown = new Shown();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        TeammatePings pings = new TeammatePings(server, shown, exec);

        check(pings.send(Kind.HEAD, null, 0) != null, "nothing goes outside a Teams game");
        pings.update(true, "teams", "BUILDING", "Mate");
        pings.tick(0);
        check(server.polls == 1, "polls once active");
        check(pings.send(Kind.HEAD, null, 100) != null, "nothing goes before the teammate named you back");

        server.ready = true;
        pings.tick(1000);
        check(server.polls == 1, "waits between polls");
        pings.tick(2100);
        check(server.polls == 2, "polls again");
        check(pings.send(Kind.HEAD, null, 3000) == null, "sent once ready");
        check(pings.send(Kind.BODY, null, 3500) != null, "global cooldown");
        check(pings.send(Kind.BODY, null, 4100) == null, "another callout after the global cooldown");
        check(pings.send(Kind.HEAD, null, 5200) != null, "the same callout waits longer");
        check(pings.send(Kind.HEAD, null, 7100) == null, "and goes after its cooldown");
        check(pings.send(Kind.BLOCK, null, 9000) != null, "a block ping needs a block");
        check(pings.send(Kind.BLOCK, new int[] {1, 64, -2}, 9000) == null, "block ping sent");

        TeammatePings.Incoming fromMate = new TeammatePings.Incoming();
        fromMate.from = "mate";
        fromMate.kind = "help";
        TeammatePings.Incoming fromOld = new TeammatePings.Incoming();
        fromOld.from = "OldMate";
        fromOld.kind = "done";
        TeammatePings.Incoming bad = new TeammatePings.Incoming();
        bad.from = "Mate";
        bad.kind = "block";   // no coordinates
        server.waiting.addAll(Arrays.asList(fromMate, fromOld, bad));
        pings.tick(10000);
        check(shown.received.equals(Arrays.asList("mate:help")), "only the current teammate's pings show: " + shown.received);

        pings.update(true, "gtb", "BUILDING", "Mate");
        check(!pings.active(), "off once the mode isn't Teams");
        pings.tick(11000);
        check(server.leaves == 1, "tells the server once pings stop");
        pings.tick(12000);
        check(server.leaves == 1, "only once");

        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
        check(server.sent.equals(Arrays.asList("Mate:head", "Mate:body", "Mate:head", "Mate:block@1,64,-2")), "sent: " + server.sent);
    }
}
