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
