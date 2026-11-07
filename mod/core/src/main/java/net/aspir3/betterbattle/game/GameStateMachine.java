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

    public GameStateMachine(ChatParser parser, Listener listener) {
        this.parser = parser;
        this.listener = listener;
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

    /** Player's own placement only. Used for All-Wood and SPB timing. */
    public void onOwnBlockPlaced(String blockId) {
        if (state == State.BUILDING && current != null) current.countBlock(blockId);
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
        MatchRecord done = current;
        reset();
        listener.onMatchFinished(done);
    }

    private void reset() {
        current = null;
        state = State.IDLE;
    }
}
