import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.game.MatchRecord;
import net.aspir3.betterbattle.parse.ChatParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * End-of-game results from real Hypixel games (Nov 2026 debug logs), cut down to the game's own lines.
 *  - Teams: Solo and Pro show "Players: N" in game, but Teams only shows "Teams: N" (the lobby's
 *    "Players: 32/32" isn't read). 14th of 15 teams must still get a player count, crowd score and grade.
 *  - Pro: a podium finish prints no "Your Score:" line; the score is on the podium ("3rd - 28").
 *  - Teams podium: names come as "A, B", and tied teams each get their own line under one place.
 *  - Guess the Build: the sidebar title is "GUESS THE BUILD" with no "Mode:" line, nothing announces the
 *    start, and the final sidebar ranks every player. Koidu played nicked as Cool_Ghost (2nd); reaxz was 8th.
 */
public class GameResultTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static GameStateMachine machine(final List<MatchRecord> done) {
        return machine(done, "Koidu");
    }

    private static GameStateMachine machine(final List<MatchRecord> done, String playerName) {
        ChatParser parser = new ChatParser(new InputStreamReader(
            GameResultTest.class.getResourceAsStream("/betterbattle/patterns.json"), StandardCharsets.UTF_8));
        return new GameStateMachine(parser, new GameStateMachine.Listener() {
            public void onStateChanged(GameStateMachine.State state) {}
            public void onMatchFinished(MatchRecord match) { done.add(match); }
        }, "26.3", playerName);
    }

    public static void main(String[] args) {
        teamsBelowPodium();
        proPodium();
        teamsPodiumTie();
        guessTheBuild("Koidu", 2, 18.0, 1);   // nicked as Cool_Ghost, found from the guess reward
        guessTheBuild("reaxz", 8, 7.0, 0);    // below the podium: only the final sidebar has the place
        System.out.println("GameResultTest: " + checks + " assertions passed");
    }

    private static void teamsBelowPodium() {
        final List<MatchRecord> done = new ArrayList<MatchRecord>();
        GameStateMachine game = machine(done);

        String bb = "BUILD BATTLE";
        game.onSidebar(bb, Arrays.asList("11/20/26  m105G", "", "Players: 32/32", "", "Starting in 10s", "", "Mode: Teams"));
        game.onSidebar("", new ArrayList<String>());   // the sidebar blanks between lobby and game
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
    }

    private static void proPodium() {
        final List<MatchRecord> done = new ArrayList<MatchRecord>();
        GameStateMachine game = machine(done);
        String bb = "BUILD BATTLE";
        game.onSidebar(bb, Arrays.asList("Players: 4/16", "", "Starting in 00:10", "", "Mode: Pro"));
        game.onChat("Build something relevant to the theme: Gothic");
        // Solo and Pro show the count in game as "Players: 4" (the lobby's "4/16" isn't read)
        game.onSidebar(bb, Arrays.asList("Time: 04:59", "", "Theme:", "Gothic", "", "Players: 4", "", "Mode: Pro"));
        game.onChat("Vote for your competitors' plots!");
        for (String line : new String[] {"Builder: hellothere", "Confirmed vote: MEH", "+16 tokens! (Vote)",
                "The Builder was: MyrhiMairhym", "Builder: dream_two", "+16 tokens! (Fair Voting Bonus)", "+80 tokens! (3rd Place)",
                "Build Battle", "Winners", "1st - 43", "dream_two", "2nd - 36", "MyrhiMairhym", "3rd - 28", "Koidu",
                "Top 3 Challenge Completed!", "+3,700 Hypixel Experience"}) {
            game.onChat(line);
        }
        game.onChat("Reward Summary");

        check(done.size() == 1, "one Pro game recorded, got " + done.size());
        MatchRecord m = done.get(0);
        check("pro".equals(m.mode), "mode pro, got " + m.mode);
        check(m.placement != null && m.placement == 3, "3rd place, got " + m.placement);
        check(m.score != null && m.score == 28.0, "score 28 from the podium, got " + m.score);
        check(m.players != null && m.players == 4, "4 players, got " + m.players);
    }

    private static void teamsPodiumTie() {
        final List<MatchRecord> done = new ArrayList<MatchRecord>();
        GameStateMachine game = machine(done);
        String bb = "BUILD BATTLE";
        game.onSidebar(bb, Arrays.asList("Time: 07:00", "", "Theme:", "Pond", "", "Teams: 15", "", "Mode: Teams"));
        game.onChat("Build something relevant to the theme: Pond");
        game.onChat("Vote for your competitors' plots!");
        // Koidu's team is the second of two teams tied for 3rd
        for (String line : new String[] {"Build Battle Teams", "Winners", "1st - 204", "Blockerize, BedWans", "2nd - 199",
                "Tingyar, FalseFox", "3rd - 144", "Linuxz07, iWillohs", "Koidu, Aquell1"}) {
            game.onChat(line);
        }
        game.onChat("Reward Summary");

        check(done.size() == 1, "one Teams podium game recorded, got " + done.size());
        MatchRecord m = done.get(0);
        check(m.placement != null && m.placement == 3, "3rd place from the second tied team line, got " + m.placement);
        check(m.score != null && m.score == 144.0, "team score 144 from the podium, got " + m.score);
        check("A".equals(m.grade) || "S".equals(m.grade), "3rd of 15 teams grades well, got " + m.grade);
    }

    private static void guessTheBuild(String player, int place, double points, int correct) {
        final List<MatchRecord> done = new ArrayList<MatchRecord>();
        GameStateMachine game = machine(done, player);
        String gtb = "GUESS THE BUILD";
        game.onSidebar(gtb, Arrays.asList("11/20/26  m85AE", "Builder:", "unzf", "", "Starts In: 00:10", "", "Theme:", "???", "www.hypixel.net"));
        game.onSidebar(gtb, Arrays.asList("11/20/26  m85AE", "Builder:", "unzf", "", "Time: 02:00", "", "Theme:", "???", "www.hypixel.net"));
        game.onChat("Round: 1/8");
        game.onChat("The theme is 9 characters long");
        game.onChat("Cool_Ghost correctly guessed the theme!");
        if (player.equals("Koidu")) game.onChat("+40 tokens! (Correct Guess)");   // only the guesser gets this
        game.onChat("+3 points");
        game.onChat("mooofies correctly guessed the theme!");
        game.onChat("The theme was: Surfboard!");
        game.onChat("Builder: reaxz");
        game.onSidebar(gtb, Arrays.asList("Builder:", "reaxz", "", "Cool_Ghost: 3", "unzf: 3", "mooofies: 2", "", "Time: 02:00", "", "Theme:", "???"));
        game.onChat("Round: 2/8");
        for (String line : new String[] {"The theme was: Astronaut!", "+120 tokens! (2nd Place)", "Build Battle", "Winners",
                "1st - 19", "mooofies", "2nd - 18", "Cool_Ghost", "3rd - 12", "xSuzuya", "Want to play again? Click here!"}) {
            game.onChat(line);
        }
        game.onSidebar(gtb, Arrays.asList("11/20/26  m85AE", "", "1. mooofies: 19", "2. Cool_Ghost: 18", "3. xSuzuya: 12", "4. seirreb: 10",
            "5. _PuffiGHG: 9", "6. Baumschnaps: 8", "6. unzf: 8", "8. reaxz: 7", "9.", "10.", "", "www.hypixel.net"));
        game.onChat("Reward Summary");

        check(done.size() == 1, player + ": one Guess the Build game recorded, got " + done.size());
        MatchRecord m = done.get(0);
        check("gtb".equals(m.mode), player + ": mode gtb, got " + m.mode);
        check(m.placement != null && m.placement == place, player + ": place " + place + ", got " + m.placement);
        check(m.score != null && m.score == points, player + ": " + points + " points, got " + m.score);
        check(m.players != null && m.players == 8, player + ": 8 players from the final sidebar, got " + m.players);
        check(m.gtbCorrect == correct, player + ": " + correct + " correct guesses, got " + m.gtbCorrect);
    }
}
