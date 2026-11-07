package net.aspir3.betterbattle.sync;

import net.aspir3.betterbattle.game.MatchRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP wrapper around https://api.aspir3.net/bb/v1.
 * Implemented per loader with whatever HTTP stack is handy
 * (java.net.HttpURLConnection works on both Java 8 and 21+).
 */
public interface ApiClient {
    SyncResult sync(List<MatchRecord> matches, List<String> localAchievementIds) throws Exception;

    final class Rejection {
        public String nonce, reason;
    }

    final class SyncResult {
        public List<String> accepted = new ArrayList<String>();
        public List<Rejection> rejected = new ArrayList<Rejection>();
        public List<String> confirmed = new ArrayList<String>();
        public List<String> revoked = new ArrayList<String>();
        public boolean fullResyncRequested;
        public Double rating;
        public Integer globalRank;
    }
}
