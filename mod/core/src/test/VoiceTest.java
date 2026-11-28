import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.store.LocalDatabase;
import net.aspir3.betterbattle.sync.ApiClient;
import net.aspir3.betterbattle.sync.SyncService;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Voice in the mod:
 *  - "/bb voice Koidu, Aspir3" reads as two names; anything that isn't a Minecraft name is refused
 *  - each refusal from the server becomes a message the player can act on, with the page that fixes it
 *  - pressing Voice sends the names and tells the player once the channel is ready
 */
public class VoiceTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static class FakeServer implements ApiClient {
        List<String> asked;
        boolean left;
        public String challenge() { return "server-id"; }
        public String verify(String playerName, String serverId) { return "token"; }
        public void setToken(String token) {}
        public void link(String code) {}
        public Latest latest() { return null; }
        public String report(String note, String log) { return "1"; }
        public Bootstrap bootstrap() { return null; }
        public SyncResult sync(List<MatchRecord> m, List<String> a, List<String> s) { return new SyncResult(); }

        public Voice voice(List<String> members) {
            asked = members;
            Voice v = new Voice();
            v.status = "ready";
            v.label = "Teams · Aspir3 + Koidu";
            v.url = "https://discord.com/channels/1/2";
            return v;
        }

        public void leaveVoice() { left = true; }
    }

    static final class Recorder implements SyncService.Listener {
        final CountDownLatch heard = new CountDownLatch(1);
        ApiClient.Voice voice;
        boolean changed;
        String problem, link;
        public void joinServer(String serverId) {}
        public void offline(int pendingGames, String reason) {}
        public void gamesSkipped(int count) {}
        public void manualResult(boolean ok, String message) {}
        public void lines(List<String> lines) {}
        public void synced(ApiClient.SyncResult result) {}
        public void achievementConfirmed(String id) {}
        public void achievementRevoked(String id) {}
        public void latestVersion(ApiClient.Latest latest) {}
        public void bootstrapped(ApiClient.Bootstrap b) {}
        public void voice(ApiClient.Voice v, boolean changed) { voice = v; this.changed = changed; heard.countDown(); }
        public void voiceProblem(String message, String link) { problem = message; this.link = link; heard.countDown(); }
    }

    public static void main(String[] args) throws Exception {
        check(BetterBattle.voiceNames("Koidu, Aspir3").equals(Arrays.asList("Koidu", "Aspir3")), "comma and space separated");
        check(BetterBattle.voiceNames("  Koidu   ").equals(Arrays.asList("Koidu")), "spaces trimmed");
        check(BetterBattle.voiceNames("").isEmpty(), "nothing typed");
        check(BetterBattle.voiceNames("not-a-name") == null, "dash refused");
        check(BetterBattle.voiceNames("way_too_long_name_x") == null, "17 letters refused");

        String[] p = SyncService.voiceProblem(new Exception("HTTP 403: {\"error\":\"voice_off\"}"));
        check(p[0].contains("Allow voice chat") && "/account".equals(p[1]), "voice turned off sends you to the account page");
        p = SyncService.voiceProblem(new Exception("HTTP 403: {\"error\":\"discord_required\"}"));
        check(p[0].contains("Discord") && "/account".equals(p[1]), "no Discord sends you to the account page");
        p = SyncService.voiceProblem(new Exception("HTTP 403: {\"error\":\"not_in_server\",\"invite\":\"https://discord.gg/abc\"}"));
        check("https://discord.gg/abc".equals(p[1]), "not in the server gives the invite, got " + p[1]);
        p = SyncService.voiceProblem(new Exception("HTTP 403: {\"error\":\"not_in_server\",\"invite\":null}"));
        check(p[1] == null, "no invite configured, no link");
        p = SyncService.voiceProblem(new Exception("HTTP 400: {\"error\":\"bad_members\"}"));
        check(p[0].contains("1 to 7"), "bad names explained");

        File dir = Files.createTempDirectory("voice-test").toFile();
        try (LocalDatabase db = new LocalDatabase(dir, "voice")) {
            FakeServer server = new FakeServer();
            Recorder heard = new Recorder();
            SyncService sync = new SyncService(db, server, "Koidu", heard);
            sync.voice(new ArrayList<String>(Arrays.asList("Aspir3")));
            check(heard.heard.await(10, TimeUnit.SECONDS), "an answer came back");
            check(heard.problem == null, "no problem, got " + heard.problem);
            check(server.asked.equals(Arrays.asList("Aspir3")), "named players sent");
            check(heard.voice.ready() && heard.changed, "told the player it's ready");
            check(sync.voiceState() == heard.voice, "the Voice screen sees the same state");
            sync.shutdown();
        }
        System.out.println("VoiceTest: " + checks + " checks passed");
    }
}
