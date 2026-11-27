package net.aspir3.betterbattle.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guess the Build: when each other player guessed the word right, measured the same way by every mod user in
 * the game, so the server can compare what several of them saw. Per guess: time since the round's builder
 * started, how many hint letters were showing, time since the last one appeared, the word's length and (once
 * the round ends) the word. Nothing anyone typed is kept: only Hypixel's own "X correctly guessed the theme!".
 *
 * It only ever reads. Nothing here sends input to the game.
 */
public final class GtbGuessTracker {
    /** One correct guess, ready to upload. */
    public static final class Guess {
        public String gameKey;
        public int round;
        public String name;
        public String builder;
        public long msSinceStart;
        /** Null when no letter was showing yet. */
        public Long msSinceReveal;
        public int revealed;
        public Integer wordLength;
        public String word;
    }

    private String server, date, firstBuilder;
    private int round;
    private String builder;
    private long roundStartedAt;
    private Integer wordLength;
    private int revealed;
    private long lastRevealAt;
    private final Set<String> guessedThisRound = new HashSet<String>();
    private final List<Guess> thisRound = new ArrayList<Guess>();
    private final List<Guess> ready = new ArrayList<Guess>();

    /** A new game starts. date and server are the last ones the sidebar showed (may be null). */
    public void newGame(String date, String server) {
        flushRound();
        this.date = date;
        this.server = server;
        firstBuilder = null;
        builder = null;
        round = 0;
        roundNumberSeen = false;
        roundStartedAt = 0;
        guessedThisRound.clear();
    }

    /** That guess was the player's own (Hypixel's reward line came right after it): drop it. */
    public void forget(String name) {
        for (int i = thisRound.size() - 1; i >= 0; i--) {
            if (thisRound.get(i).name.equalsIgnoreCase(name)) thisRound.remove(i);
        }
    }

    /** "11/20/26  m85AE" at the top of the sidebar: the date and Hypixel's server id. */
    public void onServer(String date, String server) {
        this.date = date;
        this.server = server;
    }

    /** A new builder started: a new round. */
    public void onRoundStart(String builder, long now) {
        flushRound();
        if (firstBuilder == null) firstBuilder = builder;
        this.builder = builder;
        roundStartedAt = now;
        wordLength = null;
        revealed = 0;
        lastRevealAt = 0;
        guessedThisRound.clear();
        if (round == 0) round = 1;
        else if (!roundNumberSeen) round++;
        roundNumberSeen = false;
    }

    private boolean roundNumberSeen;

    /** "Round: N/M" usually comes just after the builder changes; trust it over counting. */
    public void onRoundLine(int number) {
        if (number <= 0) return;
        round = number;
        roundNumberSeen = true;
        for (Guess g : thisRound) g.round = number;
    }

    /** "The theme is 9 characters long". */
    public void onWordLength(int length) {
        if (length > 0) wordLength = length;
    }

    /**
     * The word hint above the hotbar ("The theme is _ _ r _ _", or just the underscores). Letters showing are
     * counted; when that number goes up, a letter just appeared.
     */
    public void onHint(String line, long now) {
        if (roundStartedAt == 0 || line == null) return;
        int[] counts = hintCounts(line);
        if (counts == null) return;
        int shown = counts[0];
        if (wordLength == null && counts[0] + counts[1] > 0) wordLength = counts[0] + counts[1];
        if (shown > revealed) {
            revealed = shown;
            lastRevealAt = now;
        }
    }

    /** {letters showing, letters hidden} in a hint line, or null if it isn't one. */
    public static int[] hintCounts(String line) {
        boolean prefixed = line.matches("(?i)^the theme is\\b.*");
        if (prefixed && line.matches("(?i).*\\d+ characters? long.*")) return null;   // the chat line, not a hint
        String hint = line.replaceFirst("(?i)^the theme is\\s*:?\\s*", "");
        int shown = 0, hidden = 0;
        for (int i = 0; i < hint.length(); i++) {
            char c = hint.charAt(i);
            if (c == '_') hidden++;
            else if (Character.isLetterOrDigit(c)) shown++;
        }
        // A hint has blanks, or (with the prefix) is the whole word once every letter is out
        if (hidden == 0 && !prefixed) return null;
        if (hidden + shown == 0) return null;
        return new int[] {shown, hidden};
    }

    /** "Name correctly guessed the theme!". Self is the player's own name or nick: never recorded. */
    public void onGuessed(String name, boolean self, long now) {
        if (roundStartedAt == 0 || name == null || self) return;
        if (name.equalsIgnoreCase(builder)) return;
        if (!guessedThisRound.add(name.toLowerCase(Locale.ROOT))) return;
        Guess g = new Guess();
        g.gameKey = gameKey();
        g.round = round;
        g.name = name;
        g.builder = builder;
        g.msSinceStart = Math.max(0, now - roundStartedAt);
        g.msSinceReveal = revealed > 0 && lastRevealAt > 0 ? Long.valueOf(Math.max(0, now - lastRevealAt)) : null;
        g.revealed = revealed;
        g.wordLength = wordLength;
        thisRound.add(g);
    }

    /** "The theme was: Surfboard!": the round is over. */
    public void onWordRevealed(String word) {
        String w = word == null ? null : word.trim();
        if (w != null && w.endsWith("!")) w = w.substring(0, w.length() - 1).trim();
        for (Guess g : thisRound) {
            g.word = w;
            if (w != null && !w.isEmpty()) g.wordLength = w.replace(" ", "").length();
        }
        flushRound();
        roundStartedAt = 0;   // no guesses count until the next builder starts
    }

    /** The game ended or was left. */
    public void onGameEnd() {
        flushRound();
        roundStartedAt = 0;
    }

    /** Guesses from finished rounds, once each. */
    public List<Guess> take() {
        List<Guess> out = new ArrayList<Guess>(ready);
        ready.clear();
        return out;
    }

    private void flushRound() {
        ready.addAll(thisRound);
        thisRound.clear();
    }

    /** The same for everyone in the game: server, date and the first builder. */
    public String gameKey() {
        String key = (server == null ? "?" : server) + "|" + (date == null ? "?" : date) + "|" + (firstBuilder == null ? "?" : firstBuilder);
        return key.replaceAll("[^A-Za-z0-9_:/|.\\-]", "_");
    }
}
