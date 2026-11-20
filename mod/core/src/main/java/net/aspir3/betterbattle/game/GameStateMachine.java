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
    /** Teams mode: teams left in the game. Places rank teams, and the in-game sidebar shows no player count. */
    private Integer teams;
    private int voteRound = -1;
    private String roundBuilder;
    private SpbTracker spb;
    private Integer podiumPlace;
    /** Guess the Build: who the last "X correctly guessed the theme!" was about, for one line. */
    private String lastGuesser;
    /** The points shown next to podiumPlace. A podium finish in Solo or Pro prints no "Your Score:" line. */
    private Double podiumScore;
    /** A podium name line: one player ("Koidu") or a team ("Koidu, Teammate"). */
    private static final java.util.regex.Pattern PODIUM_NAMES =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,16}(?:, [A-Za-z0-9_]{1,16})*$");
    private SpbTracker.Records spbRecords;

    public GameStateMachine(ChatParser parser, Listener listener, String mcVersion, String playerName) {
        this.parser = parser;
        this.listener = listener;
        this.mcVersion = mcVersion;
        this.playerName = playerName;
    }

    public State state() { return state; }

    /** Where Speed Builders bests and records come from. Set by the core once the database is open. */
    public void setSpbRecords(SpbTracker.Records records) { this.spbRecords = records; }

    /** The running Speed Builders game, or null. */
    public SpbTracker spb() { return spb; }

    /** "solo", "gtb", "speed_builders"... from the sidebar, or null. */
    public String mode() { return mode; }

    private boolean nicked;
    private String nick;

    /** You're under a Hypixel /nick (nick is null if we don't know it yet). */
    public void setNick(boolean nicked, String nick) {
        this.nicked = nicked;
        if (nick != null) this.nick = nick;
        if (!nicked) this.nick = null;
        if (spb != null) spb.setNick(this.nicked, this.nick);
    }

    /** The nick you're playing under, once known. */
    public String nick() {
        if (nick == null && spb != null) nick = spb.nickSeen();
        return nick;
    }

    private boolean speedBuilders() {
        return "speed_builders".equals(mode);
    }

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
        String titleMode = parser.modeForTitle(title);
        if (titleMode != null) mode = titleMode;   // Guess the Build names itself only in the title

        boolean timer = false;
        String theme = null;
        String builder = null;
        Integer spbRound = null, spbTimeLeft = null, spbPlayersLeft = null;
        String spbTheme = null, spbDifficulty = null;
        boolean spbGameOver = false;
        List<String[]> standings = new java.util.ArrayList<String[]>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String m = parser.mode(line);
            if (m != null) mode = m;
            Integer p = parser.players(line);
            // The lobby keeps shrinking as people leave; the count when voting starts is what matters.
            if (p != null && state != State.VOTING) players = p;
            Integer teamCount = parser.sidebarInt("TEAMS", line);
            if (teamCount != null && state != State.VOTING) teams = teamCount;
            if (parser.isBuildTimer(line)) timer = true;
            // Speed Builders shows the round, the build ("Theme") and the build clock in the sidebar
            Integer r = parser.sidebarInt("SPB_ROUND", line);
            if (r != null) spbRound = r;
            String t = parser.sidebar("SPB_THEME", line);
            if (t != null) spbTheme = t;
            String d = parser.sidebar("SPB_DIFFICULTY", line);
            if (d != null) spbDifficulty = d;
            Integer left = parser.sidebarClock("SPB_TIME_LEFT", line);
            if (left != null) spbTimeLeft = left;
            Integer alive = parser.sidebarInt("SPB_PLAYERS_LEFT", line);
            if (alive != null) spbPlayersLeft = alive;
            if (parser.sidebar("SPB_GAME_OVER", line) != null) spbGameOver = true;
            String[] standing = parser.sidebarGroups("GTB_STANDING", line);   // "2. Cool_Ghost: 18"
            if (standing != null) standings.add(standing);
            if (i + 1 < lines.size()) {
                String next = lines.get(i + 1);
                if (line.equals("Theme:") && !next.isEmpty() && !next.equals("Vote Now!") && !next.equals("???")) theme = next;
                if (line.equals("Builder:") && !next.isEmpty()) builder = next;
                if (line.equals("Winner:") && current != null && current.placement == null
                    && next.toLowerCase(Locale.ROOT).endsWith(playerName.toLowerCase(Locale.ROOT))) {
                    current.placement = 1;
                }
            }
        }
        // Backup for the chat announcement, e.g. if the mod was turned on mid-game. Guess the Build has no
        // announcement at all: a round's clock ("Time: 02:00") is the start, and its theme is hidden ("???").
        boolean gtb = "gtb".equals(mode);
        if (state == State.LOBBY && timer && (theme != null || gtb) && !speedBuilders()) {
            startBuilding(gtb ? null : theme);
        }
        if (!standings.isEmpty()) onGtbStandings(standings);
        if (builder != null) startGtbRound(builder);
        if (spbRound != null && spbPlayersLeft != null) {
            onSpeedBuildersSidebar(spbRound, spbTheme, spbDifficulty, spbTimeLeft, spbPlayersLeft, spbGameOver);
        }
    }

    /** One read of the in-game Speed Builders sidebar. Only that sidebar has "Round:" and "Players Left:". */
    private void onSpeedBuildersSidebar(int round, String theme, String difficulty, Integer timeLeft, int playersLeft, boolean gameOver) {
        long now = System.currentTimeMillis();
        if (state == State.LOBBY && !gameOver) {
            mode = "speed_builders";   // also covers the mod being switched on mid-game
            startBuilding(null);
        }
        if (spb == null || current == null) return;
        // The lobby count is "8/8", so the players in the game are whoever is left when it starts.
        if (players == null || playersLeft > players) players = playersLeft;
        if (theme != null) {
            spb.onRound(round, theme, difficulty, playersLeft, now);
            if (current.theme == null) current.theme = theme;   // the game is filed under its first build
        }
        if (timeLeft != null) spb.onBuildClock(timeLeft, now);
        if (gameOver) finish();
    }

    /**
     * The final Guess the Build sidebar ranks everyone ("1. mooofies: 19", "2. Cool_Ghost: 18", ...), so it
     * gives the place and points of every player, not only the podium, and the number of players.
     */
    private void onGtbStandings(List<String[]> standings) {
        if (current == null || !"gtb".equals(current.mode)) return;
        if (players == null || standings.size() > players) players = standings.size();
        String nick = nick();
        for (String[] s : standings) {
            String name = s[1];
            if (!name.equalsIgnoreCase(playerName) && (nick == null || !name.equalsIgnoreCase(nick))) continue;
            if (current.placement == null) current.placement = Integer.valueOf(s[0]);
            if (current.score == null) current.score = Double.valueOf(s[2]);
        }
    }

    // ---- chat

    /** One chat line, cleaned. Multi-line messages are split by the caller. */
    public void onChat(String line) {
        if (state == State.IDLE) return;
        String guesser = lastGuesser;   // only the line right before this one counts
        lastGuesser = null;
        // The end-of-game podium is a place and its points ("3rd - 28"), then one line per player or team on
        // it: "Koidu", or "Koidu, Teammate" in Teams. Tied teams get a line each, so stay on this place
        // until a line that isn't names.
        if (podiumPlace != null) {
            if (PODIUM_NAMES.matcher(line).matches()) {
                if (current != null && current.placement == null && namesInclude(line)) {
                    current.placement = podiumPlace;
                    if (current.score == null) current.score = podiumScore;
                }
            } else {
                podiumPlace = null;
                podiumScore = null;
            }
        }
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
            case TOKENS_PLACEMENT:
                if (current != null && spb != null) current.placement = Integer.valueOf(e.text);
                break;
            case PODIUM:
                podiumPlace = Integer.valueOf(e.text);
                podiumScore = parseScore(e.text2);
                break;
            case SPB_ELIMINATED:
                if (spb != null) {
                    Integer place = spb.onEliminated();
                    if (current.placement == null) current.placement = place;
                }
                break;
            case SPB_PERFECT:
                if (spb != null) spb.onPerfect(e.text, e.text2, System.currentTimeMillis());
                break;
            case SPB_ACCURACY:
                if (spb != null) spb.onAccuracy(e.text);
                break;
            case GTB_GUESSED:
                lastGuesser = e.text;
                break;
            case GTB_CORRECT_GUESS:
                // The reward comes straight after your own "X correctly guessed the theme!". If X isn't your
                // name, X is the nick you're playing under.
                if (guesser != null && !guesser.equalsIgnoreCase(playerName) && nick() == null) {
                    nicked = true;
                    nick = guesser;
                }
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
        spb = "speed_builders".equals(current.mode) ? new SpbTracker(playerName, spbRecords) : null;
        if (spb != null) spb.setNick(nicked, nick);
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
        if (spb != null) spb.onPlace(blockId);
        if (creativeTab != null) current.blockTabs.put(blockId, creativeTab);
        if (plot != null) plot.onPlace(y);
    }

    public void onOwnBlockBroken() {
        if (!building()) return;
        current.blocksBroken++;
        if (spb != null) spb.onBreak();
    }

    /** Called every tick the server allows the player to fly, whether or not they do. */
    public void onCanFly() {
        if (building()) current.couldFly = true;
    }

    /** Called every tick the player is flying. */
    public void onFlying() {
        if (building()) current.flew = true;
    }

    public void onJumping() {
        if (building()) current.jumped = true;
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
        boolean teamsMode = "teams".equals(done.mode);
        // Solo and Pro show "Players: N" in game; Teams shows only "Teams: N". Two builders a team; a lone
        // player's team counts as two, so the crowd score errs low.
        if (teamsMode && teams != null && (done.players == null || done.players < teams)) done.players = teams * 2;
        // In Teams the place is among teams ("14th" of 15 teams), so grade against the team count.
        Integer field = teamsMode && teams != null ? teams : done.players;
        done.grade = Grades.of(done.placement, field, done.crowdScore());
        if (spb != null) {
            done.spbRounds.addAll(spb.finish());
            done.spbRoundsJson = SpbTracker.toJson(done.spbRounds);
        }
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
        spb = null;
        podiumPlace = null;
        podiumScore = null;
        lastGuesser = null;
        gtbLastBuilder = null;
        gtbRoundStartedAt = 0;
        gtbCorrectInRound = false;
        plot = null;
        players = null;
        teams = null;
        setState(State.IDLE);
    }

    private boolean namesInclude(String line) {
        String nick = nick();
        for (String name : line.split(", ")) {
            if (name.equalsIgnoreCase(playerName) || (nick != null && name.equalsIgnoreCase(nick))) return true;
        }
        return false;
    }

    private static Double parseScore(String text) {
        if (text == null) return null;
        try {
            return Double.valueOf(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
