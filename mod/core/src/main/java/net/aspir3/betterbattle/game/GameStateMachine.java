package net.aspir3.betterbattle.game;

import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.parse.ChatParser.Event;

import java.util.List;
import java.util.Locale;

/**
 * Follows one Build Battle game from the sidebar and chat:
 *
 *   IDLE → LOBBY (Build Battle sidebar is showing)
 *        → BUILDING (theme announced, build timer running)
 *        → VOTING (one "Builder: name" chat line per plot)
 *        → IDLE (results read, match handed to the listener)
 *
 * It only ever reads. Nothing here sends input to the game.
 */
public final class GameStateMachine {
    public enum State { IDLE, LOBBY, BUILDING, VOTING }

    public interface Listener {
        void onStateChanged(State state);
        void onMatchFinished(MatchRecord m);
    }

    private final ChatParser parser;
    private final Listener listener;
    private final String mcVersion;
    private final String playerName;

    private State state = State.IDLE;
    private MatchRecord current;
    private PlotTracker plot;
    private long startedAt;
    private String mode;
    private Integer players;
    private int voteRound = -1;
    private String roundBuilder;

    public GameStateMachine(ChatParser parser, Listener listener, String mcVersion, String playerName) {
        this.parser = parser;
        this.listener = listener;
        this.mcVersion = mcVersion;
        this.playerName = playerName;
    }

    public State state() { return state; }

    private void setState(State next) {
        if (next == state) return;
        state = next;
        listener.onStateChanged(next);
    }

    private String gtbLastBuilder;
    private long gtbRoundStartedAt;
    private boolean gtbCorrectInRound;

    private boolean building() {
        return state == State.BUILDING && current != null;
    }

    // ---- sidebar

    /** The whole sidebar, top to bottom, already cleaned. Called whenever it changes. */
    public void onSidebar(String title, List<String> lines) {
        if (!parser.isBuildBattle(title)) {
            leave();
            return;
        }
        if (state == State.IDLE) setState(State.LOBBY);

        boolean timer = false;
        String theme = null;
        String builder = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String m = parser.mode(line);
            if (m != null) mode = m;
            Integer p = parser.players(line);
            // The lobby keeps shrinking as people leave; the count when voting starts is what matters.
            if (p != null && state != State.VOTING) players = p;
            if (parser.isBuildTimer(line)) timer = true;
            if (i + 1 < lines.size()) {
                String next = lines.get(i + 1);
                if (line.equals("Theme:") && !next.isEmpty() && !next.equals("Vote Now!")) theme = next;
                if (line.equals("Builder:") && !next.isEmpty()) builder = next;
                if (line.equals("Winner:") && current != null && current.placement == null
                    && next.toLowerCase(Locale.ROOT).endsWith(playerName.toLowerCase(Locale.ROOT))) {
                    current.placement = 1;
                }
            }
        }
        // Backup for the chat announcement, e.g. if the mod was turned on mid-game.
        if (state == State.LOBBY && timer && theme != null) startBuilding(theme);
        if (builder != null) startGtbRound(builder);
    }

    // ---- chat

    /** One chat line, cleaned. Multi-line messages are split by the caller. */
    public void onChat(String line) {
        if (state == State.IDLE) return;
        Event e = parser.parse(line);
        if (e == null) return;
        switch (e.type) {
            case THEME:
                if (state == State.LOBBY) startBuilding(e.text);
                break;
            case VOTING_START:
                if (state == State.BUILDING) setState(State.VOTING);
                break;
            case BUILDER:
                if (state == State.BUILDING) setState(State.VOTING);
                if (state == State.VOTING) {
                    startGtbRound(e.text);
                    voteRound++;
                    roundBuilder = e.text;
                }
                break;
            case VOTE:
                if (state == State.VOTING) recordVote(e.text);
                break;
            case SCORE:
                if (current != null) current.score = Double.valueOf(e.text);
                break;
            case PLACEMENT:
                if (current != null) current.placement = Integer.valueOf(e.text);
                break;
            case GAME_END:
                finish();
                break;
            case GTB_CORRECT_GUESS:
                if (current != null && "gtb".equals(current.mode)
                    && gtbRoundStartedAt > 0 && !gtbCorrectInRound) {
                    int guessMs = (int) (System.currentTimeMillis() - gtbRoundStartedAt);
                    current.gtbCorrect++;
                    if (current.gtbFastestGuessMs == null || guessMs < current.gtbFastestGuessMs) {
                        current.gtbFastestGuessMs = guessMs;
                    }
                    gtbCorrectInRound = true;
                }
                break;
            default:
                break;
        }
    }

    private void startGtbRound(String builder) {
        if (current == null || !"gtb".equals(current.mode)
            || (state != State.BUILDING && state != State.VOTING)
            || builder == null || builder.isEmpty() || builder.equals(gtbLastBuilder)) return;
        gtbLastBuilder = builder;
        gtbRoundStartedAt = System.currentTimeMillis();
        gtbCorrectInRound = false;
        current.gtbRounds++;
    }

    private void startBuilding(String theme) {
        current = new MatchRecord();
        current.mode = mode == null ? "solo" : mode;
        current.mcVersion = mcVersion;
        current.theme = theme;
        startedAt = System.currentTimeMillis();
        voteRound = -1;
        roundBuilder = null;
        gtbLastBuilder = null;
        gtbRoundStartedAt = 0;
        gtbCorrectInRound = false;
        plot = null;
        setState(State.BUILDING);
    }

    /** "MEH", "SUPER POOP"... Hypixel confirms each vote in chat; the last one for a plot counts. */
    private void recordVote(String shown) {
        String vote = shown.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!MatchRecord.VOTES.contains(vote) || roundBuilder == null) return;
        MatchRecord.Vote last = current.votes.isEmpty() ? null : current.votes.get(current.votes.size() - 1);
        if (last != null && last.round == voteRound) {
            last.vote = vote;
            last.at = System.currentTimeMillis();
            return;
        }
        MatchRecord.Vote v = new MatchRecord.Vote();
        v.round = voteRound;
        v.vote = vote;
        v.builder = roundBuilder;
        v.at = System.currentTimeMillis();
        current.votes.add(v);
    }

    // ---- the plot

    /**
     * The adapter calls this once building starts: the block under the player is the floor,
     * and the white terracotta around it marks out the plot.
     */
    public void onPlotFound(int minX, int minZ, int maxX, int maxZ, int floorY) {
        if (building()) plot = new PlotTracker(minX, minZ, maxX, maxZ, floorY);
    }

    /** Every block change the server sends. */
    public void onBlockUpdate(int x, int y, int z, String blockId) {
        if (building() && plot != null) plot.onBlockUpdate(x, y, z, blockId, System.currentTimeMillis());
    }

    /**
     * The player's own placement. Buckets count too: the adapter reports them as
     * "minecraft:water" / "minecraft:lava". creativeTab may be null if unknown.
     */
    public void onOwnBlockPlaced(String blockId, String creativeTab, int y) {
        if (!building()) return;
        current.countBlock(blockId);
        if (creativeTab != null) current.blockTabs.put(blockId, creativeTab);
        if (plot != null) plot.onPlace(y);
    }

    public void onOwnBlockBroken() {
        if (building()) current.blocksBroken++;
    }

    /** Called every tick the player is flying. */
    public void onFlying() {
        if (building()) current.flew = true;
    }

    // ---- endings

    /** The player left the game (sidebar gone, server switch, disconnect). Keeps what was played. */
    public void leave() {
        if (state == State.BUILDING || state == State.VOTING) finish();
        else reset();
    }

    private void finish() {
        if (current == null) {
            reset();
            return;
        }
        MatchRecord done = current;
        done.playedAt = System.currentTimeMillis();
        done.durationSec = (int) ((done.playedAt - startedAt) / 1000L);
        done.players = players;
        done.grade = Grades.of(done.placement, done.players, done.crowdScore());
        if (plot != null) {
            done.floor = plot.floor();
            done.floorChanges = plot.floorChanges();
            done.backdropChanges = plot.backdropChanges();
            done.reachedHeightLimit = plot.reachedTop();
        }
        reset();
        listener.onMatchFinished(done);
    }

    private void reset() {
        current = null;
        gtbLastBuilder = null;
        gtbRoundStartedAt = 0;
        gtbCorrectInRound = false;
        plot = null;
        players = null;
        setState(State.IDLE);
    }
}
