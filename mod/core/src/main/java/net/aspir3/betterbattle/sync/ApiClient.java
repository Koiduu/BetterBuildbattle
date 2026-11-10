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

    SyncResult sync(List<MatchRecord> matches, List<String> localAchievementIds, List<String> seenPlayers) throws Exception;

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
    }

    /** Thrown when the server says the token is missing or expired. */
    final class Unauthorized extends Exception {
        public Unauthorized() { super("unauthorized"); }
    }
}
