import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.parse.ChatParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A real Teams game (Nov 2026 debug log), cut down to the game's own lines. The sidebar blanks as the
 * game starts, which drops the lobby's "Players: 32/32"; from then on it only says "Teams: N".
 * The player finished 14th of 15 teams and must still get a player count, a crowd score and a grade.
 */
public class TeamsResultTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        ChatParser parser = new ChatParser(new InputStreamReader(
            TeamsResultTest.class.getResourceAsStream("/betterbattle/patterns.json"), StandardCharsets.UTF_8));
        final List<MatchRecord> done = new ArrayList<MatchRecord>();
        GameStateMachine game = new GameStateMachine(parser, new GameStateMachine.Listener() {
            public void onStateChanged(GameStateMachine.State state) {}
            public void onMatchFinished(MatchRecord match) { done.add(match); }
        }, "26.3", "Koidu");

        String bb = "BUILD BATTLE";
        game.onSidebar(bb, Arrays.asList("11/20/26  m105G", "", "Players: 32/32", "", "Starting in 10s", "", "Mode: Teams"));
        game.onSidebar("", new ArrayList<String>());   // the blank sidebar between lobby and game
        game.onSidebar(bb, Arrays.asList("Vote Time: 00:15", "", "Theme:", "Vote Now!", "", "Teams: 17", "", "Teammate:", "None", "", "Mode: Teams"));
        game.onChat("Build something relevant to the theme: Pond");
        game.onSidebar(bb, Arrays.asList("Time: 07:00", "", "Theme:", "Pond", "", "Teams: 16", "", "Teammate:", "None", "", "Mode: Teams"));
        game.onSidebar(bb, Arrays.asList("Time: 05:49", "", "Theme:", "Pond", "", "Teams: 15", "", "Teammate:", "None", "", "Mode: Teams"));
        game.onChat("Vote for your competitors' plots!");
        game.onSidebar(bb, Arrays.asList("Theme:", "Pond", "", "Mode: Teams", "", "Teammate:", "None"));
        game.onChat("Builders: Tingyar, FalseFox");
        game.onChat("Confirmed vote: OK");
        for (String line : new String[] {"Build Battle Teams", "Winners", "1st - 204", "Blockerize, BedWans", "2nd - 199",
                "Tingyar, FalseFox", "3rd - 144", "Linuxz07, iWillohs", "Aquell1, W1zShard", "Your Score: 95", "(14th Place)"}) {
            game.onChat(line);
        }
        game.onSidebar(bb, Arrays.asList("Winners:", "Blockerize", "BedWans", "", "Theme:", "Pond", "", "Teams: 14", "", "Mode: Teams"));
        game.onChat("Reward Summary");

        check(done.size() == 1, "one game recorded, got " + done.size());
        MatchRecord m = done.get(0);
        check("teams".equals(m.mode), "mode teams, got " + m.mode);
        check("Pond".equals(m.theme), "theme Pond, got " + m.theme);
        check(m.placement != null && m.placement == 14, "14th place, got " + m.placement);
        check(m.score != null && m.score == 95.0, "score 95, got " + m.score);
        // 15 teams when voting started (the post-game "Teams: 14" must not count)
        check(m.players != null && m.players == 30, "30 players from 15 teams, got " + m.players);
        check(m.crowdScore() != null, "crowd score worked out");
        check("C".equals(m.grade), "14th of 15 teams grades C, got " + m.grade);

        System.out.println("TeamsResultTest: " + checks + " assertions passed");
    }
}
