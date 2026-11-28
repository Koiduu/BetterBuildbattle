package net.aspir3.betterbattle.sync;

import net.aspir3.betterbattle.game.MatchRecord;

import java.util.ArrayList;
import java.util.List;

/** The Better Battle server API. HttpApiClient is the real one. */
public interface ApiClient {
    String challenge() throws Exception;

    /** Returns a mod token once Mojang confirms the player joined with this serverId. */
    String verify(String playerName, String serverId) throws Exception;

    void setToken(String token);

    /** Ties this Minecraft account to the website account that made the code. */
    void link(String code) throws Exception;

    SyncResult sync(List<MatchRecord> matches, List<String> localAchievementIds, List<String> seenPlayers) throws Exception;

    /** /bb report: the player's note and the filtered log of their last games. Returns the report's id. */
    String report(String note, String log) throws Exception;

    /** The newest released mod for this Minecraft version, or null if the server has none. No login needed. */
    Latest latest() throws Exception;

    final class Latest {
        public String version, sha256, notes;
    }

    /** One place on a leaderboard. rank is null when the player isn't on it. */
    final class Standing {
        public Integer rank;
        public int of;
        public Double value;
    }

    final class PeriodStanding {
        public Standing score, wins, winRate;
    }

    /** /bb rank: where this player stands, all time and today / this week / this month. */
    final class Rank {
        public Integer globalRank;
        public int rankedPlayers;
        public Standing allTimeScore, allTimeLevel, allTimeWinRate;
        public PeriodStanding day, week, month;
    }

    /** One side of /bb compare. */
    final class Side {
        public String name;
        public long score, wins, games;
        public Integer globalRank;
        public int rankedPlayers;
        public Double winRate;
        public Integer level, ap;
    }

    final class Compare {
        public Side me, them;
    }

    /** Where this player stands on the leaderboards. Needs a newer server; older ones answer 404. */
    default Rank rank() throws Exception {
        throw new UnsupportedOperationException("rank");
    }

    /** This player next to another one. */
    default Compare compare(String name) throws Exception {
        throw new UnsupportedOperationException("compare");
    }

    /**
     * A temporary Discord voice channel for this player and the players they named. status is "none",
     * "waiting" (some named players haven't pressed Voice yet) or "ready" (url opens the channel).
     */
    final class Voice {
        public String status, scope, label, url, notice;
        public List<String> waitingFor = new ArrayList<String>();

        public boolean ready() { return "ready".equals(status); }
        public boolean waiting() { return "waiting".equals(status); }
    }

    /** Pressing Voice with these teammates or party members. Each of them has to press Voice too. Older servers answer 404. */
    default Voice voice(List<String> members) throws Exception {
        throw new UnsupportedOperationException("voice");
    }

    /** The voice group this player is in, if any. */
    default Voice voiceStatus() throws Exception {
        throw new UnsupportedOperationException("voice");
    }

    default void leaveVoice() throws Exception {
        throw new UnsupportedOperationException("voice");
    }

    // ---- LFG lobbies. Older servers answer 404; HttpApiClient implements these.

    /** One lobby as the browser shows it. */
    final class Lobby {
        public String id, host, name, tag, region, note, myRequest, createdAt, expiresAt;
        public int maxSize, players;
        public boolean locked, mine, nameHidden, closesSoon;
    }

    final class LobbyList {
        public List<Lobby> lobbies = new ArrayList<Lobby>();
        public String notice;
    }

    /** Someone asking to join the lobby you host. */
    final class LobbyRequest {
        public long id;
        public String name, status;
    }

    /** One of your own requests to join someone else's lobby. */
    final class Joining {
        public String lobbyId, host, name, tag, status;
        public boolean lobbyOpen;
    }

    /** Your place in line while every lobby slot is taken. offerUntil is set while a slot is held for you. */
    final class LobbyQueue {
        public Integer position;
        public String offerUntil;
    }

    final class LobbyPoll {
        public Lobby hosting;
        public List<LobbyRequest> requests = new ArrayList<LobbyRequest>();
        public List<Joining> joining = new ArrayList<Joining>();
        public LobbyQueue queue;
    }

    /** Starting a lobby either opens it or puts you in line. */
    final class LobbyStarted {
        public Lobby lobby;
        public LobbyQueue queued;
    }

    final class LobbySettings {
        public String name, tag, region = "any", note = "";
        public int maxSize;
    }

    /** The host's answer. invite is "/p invite name" after an accept, for the host to send themselves. */
    final class Decided {
        public String status, name, invite;
    }

    default LobbyList lfgList(String tag, String search) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default LobbyStarted lfgStart(LobbySettings settings, String password) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default LobbyStarted lfgClaim() throws Exception { throw new UnsupportedOperationException("lfg"); }
    default void lfgLeaveQueue() throws Exception { throw new UnsupportedOperationException("lfg"); }
    default void lfgClose(String lobbyId) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default Lobby lfgRename(String lobbyId, String name) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default void lfgReport(String lobbyId) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default void lfgJoin(String lobbyId, String password) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default void lfgLeave(String lobbyId) throws Exception { throw new UnsupportedOperationException("lfg"); }
    /** action: accept, decline, kick or block. */
    default Decided lfgDecide(long requestId, String action) throws Exception { throw new UnsupportedOperationException("lfg"); }
    default LobbyPoll lfgPoll() throws Exception { throw new UnsupportedOperationException("lfg"); }

    /** Teammate pings: says who your Teams teammate is and fetches the pings they sent. Older servers answer 404. */
    default net.aspir3.betterbattle.pings.TeammatePings.Poll pingPoll(String teammate) throws Exception {
        throw new UnsupportedOperationException("pings");
    }

    /** Sends one ping to the teammate. block is {x, y, z} for a block ping, otherwise null. */
    default void pingSend(String teammate, String kind, int[] block) throws Exception {
        throw new UnsupportedOperationException("pings");
    }

    /** The Teams game ended: the server forgets the teammate. */
    default void pingLeave() throws Exception {
    }

    /** World records, weekly challenges and the like. Fetched now and then, not every sync. */
    Bootstrap bootstrap() throws Exception;

    /** A weekly challenge and how far along this player is. */
    final class Weekly {
        public String id, text;
        public int xp, progress, target;
        public boolean done;
    }

    /** The fastest time anyone with Better Battle has on a Speed Builders build. */
    final class WorldRecord {
        public String build, name, holder;
        public long timeMs;
    }

    final class Bootstrap {
        public List<Weekly> weeklyChallenges = new ArrayList<Weekly>();
        public String weekEndsAt;
        public List<WorldRecord> worldRecords = new ArrayList<WorldRecord>();
        /** The player's ready custom heads. Null from servers that don't have them yet. */
        public List<net.aspir3.betterbattle.heads.MyHeads.Entry> customHeads;
    }

    /** The player's ready custom heads ("My heads"). */
    default List<net.aspir3.betterbattle.heads.MyHeads.Entry> myHeads() throws Exception {
        throw new UnsupportedOperationException("myHeads");
    }

    final class Rejection {
        public String nonce, reason;
    }

    final class SyncResult {
        public List<String> accepted = new ArrayList<String>();
        public List<Rejection> rejected = new ArrayList<Rejection>();
        public List<String> confirmed = new ArrayList<String>();
        public List<String> revoked = new ArrayList<String>();
        public boolean fullResyncRequested;
        public Integer level;
        public Long xp;
        public Integer globalRank;
        public Integer rankedPlayers;
        public List<Weekly> weekly;
        public String weekEndsAt;
        /** Weekly challenges this sync completed. */
        public List<String> weeklyCompleted = new ArrayList<String>();
    }

    /** Thrown when the server says the token is missing or expired. */
    final class Unauthorized extends Exception {
        public Unauthorized() { super("unauthorized"); }
    }
}
