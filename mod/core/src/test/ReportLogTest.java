import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.report.ReportLog;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * /bb report must never carry what people wrote. Lines below copy Hypixel's formats (names and messages
 * made up): every "talking" line must be left out, and the game lines bug-fixing needs must stay.
 */
public class ReportLogTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        ChatParser parser = new ChatParser(new InputStreamReader(
            ReportLogTest.class.getResourceAsStream("/betterbattle/patterns.json"), StandardCharsets.UTF_8));

        for (String line : new String[] {
            "★ Grandmaster [MVP++] Steve: my secret base is at 100 64 200",       // lobby/game chat
            "☺ Rookie Alex: lol",
            "[SHOUT] [VIP] Alex: hello everyone",
            "From [MVP++] Friend123: here is my discord tag",                   // private messages
            "To [MVP++] Friend123: ok thanks",
            "Guild > [MVP++] Steve [GM]: meeting at 8",                         // guild, party, officer
            "Party > [MVP+] Alex: warp please",
            "Officer > Steve: ban him",
            "Friend > Alex joined.",
            "Friends (Page 1 of 3) >>",                                         // friends list
            "Alex is currently Away",
            "Steve is in a Bed Wars Game",
            "[MVP++] Friend123 has invited you to join their party!",
            "You have joined [MVP++] Friend123's party!",
            "Guild > Steve left.",
            "π Master [MVP++] Steve: Confirmed vote: LEGENDARY",                // chat that looks like a game line
            "Guild > [MVP++] Steve [AS]: Your Score: 999",
        }) {
            check(ReportLog.keep(line, parser) == null, "must be left out: " + line);
        }

        for (String line : new String[] {
            "Build something relevant to the theme: Pond",
            "Vote for your competitors' plots!",
            "Builder: Koidu",
            "Builders: Tingyar, FalseFox",
            "Confirmed vote: LEGENDARY",
            "+16 tokens! (Vote)",
            "Your Score: 95",
            "(14th Place)",
            "1st - 204",
            "Blockerize, BedWans",
            "Round: 2/8",
            "The theme was: Surfboard!",
            "Cool_Ghost correctly guessed the theme!",
            "+40 tokens! (Correct Guess)",
            "Reward Summary",
            "Koidu has joined (16/32)!",
        }) {
            check(line.equals(ReportLog.keep(line, parser)), "must be kept: " + line);
        }

        check("(nick changed)".equals(ReportLog.keep("You are now nicked as Secret_Name!", parser)), "the nick itself is left out");

        // Runs of left-out chat show up once, so the timing still reads right
        ReportLog log = new ReportLog();
        for (String line : Arrays.asList("Builder: Koidu", "☺ Rookie Alex: a", "☺ Rookie Alex: b", "Guild > x: c", "Your Score: 95")) {
            log.chat(line, parser);
        }
        String text = log.text();
        check(!text.contains("Alex") && !text.contains("Guild"), "nothing of the chat in the text: " + text);
        check(text.split("chat left out", -1).length - 1 == 1, "one marker for the run: " + text);
        check(text.contains("Builder: Koidu") && text.contains("Your Score: 95"), "game lines in the text");

        System.out.println("ReportLogTest: " + checks + " assertions passed");
    }
}
