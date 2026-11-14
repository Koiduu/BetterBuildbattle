import net.aspir3.betterbattle.game.*;
import net.aspir3.betterbattle.parse.ChatParser;
import java.io.*; import java.nio.file.*; import java.util.*;
/**
 * Feeds a real debug.log back through the state machine, to check pattern changes.
 * Compile next to the core classes and run: java Replay path/to/debug.log ""
 */
public class Replay {
  public static void main(String[] a) throws Exception {
    ChatParser p = new ChatParser(new FileReader("resources/betterbattle/patterns.json"));
    final List<MatchRecord> done = new ArrayList<>();
    GameStateMachine g = new GameStateMachine(p, new GameStateMachine.Listener() {
      public void onStateChanged(GameStateMachine.State s) { System.out.println("  state -> " + s); }
      public void onMatchFinished(MatchRecord m) { done.add(m); }
    }, "26.3", "Koidu");
    String title = a[1];
    for (String raw : Files.readAllLines(Paths.get(a[0]))) {
      String line = raw.length() > 14 && raw.charAt(2) == ':' ? raw.substring(14) : raw;
      if (line.startsWith("---- ")) { g.leave(); title = ""; }
      else if (line.startsWith("sidebar title: ")) { title = line.substring(15); g.onSidebar(ChatParser.clean(title), new ArrayList<String>()); }
      else if (line.startsWith("sidebar title:")) { title = ""; g.onSidebar("", new ArrayList<String>()); }
      else if (line.startsWith("sidebar: ")) { List<String> ls = new ArrayList<>(); for (String x : line.substring(9).split(" \\| ")) ls.add(ChatParser.clean(x)); g.onSidebar(ChatParser.clean(title), ls); }
      else { String c = ChatParser.clean(line.startsWith("chat: ") ? line.substring(6) : line); if (!c.isEmpty()) g.onChat(c); }
    }
    for (MatchRecord m : done) {
      System.out.println("MATCH mode=" + m.mode + " theme=" + m.theme + " place=" + m.placement + "/" + m.players + " score=" + m.score
        + " crowd=" + m.crowdScore() + " grade=" + m.grade + " dur=" + m.durationSec);
      for (MatchRecord.Vote v : m.votes) System.out.println("   vote " + v.round + " " + v.builder + " -> " + v.vote);
    }
    System.out.println(done.size() + " match(es)");
  }
}
