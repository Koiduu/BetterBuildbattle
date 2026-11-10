package net.aspir3.betterbattle.game;

import net.aspir3.betterbattle.parse.ChatParser;
import net.aspir3.betterbattle.parse.ChatParser.Event;

/**
 * IDLE → LOBBY → BUILDING → VOTING → RESULTS → IDLE
 *
 * Fed by the loader adapters (fabric-26.3 / forge-1.8.9) with three things only:
 * chat lines, scoreboard titles, and the player's OWN block placements.
 * It never sends input to the game.
 */
public final class GameStateMachine {
    public enum State { IDLE, LOBBY, BUILDING, VOTING, RESULTS }

    public interface Listener { void onMatchFinished(MatchRecord m); }

    private final ChatParser parser;
    private final Listener listener;
    private State state = State.IDLE;
    private MatchRecord current;
    private long startedAt;

    private final String mcVersion;

    public GameStateMachine(ChatParser parser, Listener listener, String mcVersion) {
        this.parser = parser;
        this.listener = listener;
        this.mcVersion = mcVersion;
    }

    public State state() { return state; }

    /** Called with the scoreboard sidebar title whenever it changes. */
    public void onScoreboardTitle(String plainTitle) {
        String mode = parser.modeFromScoreboard(plainTitle);
        if (mode == null) {
            if (state != State.IDLE && state != State.RESULTS) reset(); // left the game
            return;
        }
        if (state == State.IDLE) {
            current = new MatchRecord();
            current.mode = mode;
            current.mcVersion = mcVersion;
            state = State.LOBBY;
        }
    }

    /** Called with every chat line, colour codes already stripped. */
    public void onChat(String plain) {
        if (state == State.IDLE) return;
        Event e = parser.parse(plain);
        if (e == null) return;
        switch (e.type) {
            case GAME_START:
                startedAt = System.currentTimeMillis();
                current.players = e.intValue;
                state = State.BUILDING;
                break;
            case THEME:
                current.theme = e.text;
                break;
            case VOTING_START:
                if (state == State.BUILDING) state = State.VOTING;
                break;
            case PLACEMENT:
                current.placement = e.intValue;
                break;
            case SCORE:
                current.score = e.doubleValue;
                break;
            case GAME_END:
                finish();
                break;
            default:
                break;
        }
    }

    private boolean building() {
        return state == State.BUILDING && current != null;
    }

    private PlotTracker plot;

    /**
     * The adapter calls this once building starts: the block under the player is the floor,
     * and the white terracotta around it marks out the plot.
     */
    public void onPlotFound(int minX, int minZ, int maxX, int maxZ, int floorY) {
        if (building()) plot = new PlotTracker(minX, minZ, maxX, maxZ, floorY);
    }

    /** Every block change the server sends (single updates and chunk section updates). */
    public void onBlockUpdate(int x, int y, int z, String blockId) {
        if (building() && plot != null) plot.onBlockUpdate(x, y, z, blockId, System.currentTimeMillis());
    }

    /**
     * Player's own placement only. Bucket placements count too: the adapter reports them
     * as "minecraft:water" / "minecraft:lava". creativeTab may be null if unknown.
     */
    public void onOwnBlockPlaced(String blockId, String creativeTab, int y) {
        if (!building()) return;
        current.countBlock(blockId);
        if (creativeTab != null) current.blockTabs.put(blockId, creativeTab);
        if (plot != null) plot.onPlace(y);
    }

    /** Called every tick the player is flying. */
    public void onFlying() {
        if (building()) current.flew = true;
    }



    private final java.util.Set<java.util.UUID> seenPlayers = new java.util.HashSet<java.util.UUID>();

    /** Adapter calls this with the tab list UUIDs once per game. Sent with the next sync for ranking discovery. */
    public void onLobbyRoster(java.util.Collection<java.util.UUID> uuids) {
        synchronized (seenPlayers) { seenPlayers.addAll(uuids); }
    }

    /** SyncService drains this each sync. Not stored in the local DB: it's only useful to the server. */
    public java.util.List<java.util.UUID> drainSeenPlayers() {
        synchronized (seenPlayers) {
            java.util.List<java.util.UUID> out = new java.util.ArrayList<java.util.UUID>(seenPlayers);
            seenPlayers.clear();
            return out;
        }
    }

    /** Player's own block break (Speed Builders precision). */
    public void onOwnBlockBroken() {
        if (state == State.BUILDING && current != null) current.blocksBroken++;
    }

    private void finish() {
        if (current == null) return;
        state = State.RESULTS;
        current.playedAt = System.currentTimeMillis();
        current.durationSec = (int) ((current.playedAt - startedAt) / 1000L);
        current.grade = Grades.of(current.placement, current.players, current.score);
        if (plot != null) {
            current.floor = plot.floor();
            current.floorChanges = plot.floorChanges();
            current.backdropChanges = plot.backdropChanges();
            current.reachedHeightLimit = plot.reachedTop();
        }
        MatchRecord done = current;
        reset();
        listener.onMatchFinished(done);
    }

    private void reset() {
        current = null;
        plot = null;
        state = State.IDLE;
    }
}
