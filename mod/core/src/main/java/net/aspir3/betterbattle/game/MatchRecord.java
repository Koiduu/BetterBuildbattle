package net.aspir3.betterbattle.game;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One finished game. Built by GameStateMachine, stored by LocalDatabase, sent by SyncService. */
public final class MatchRecord {
    public String nonce = UUID.randomUUID().toString();
    public long playedAt;
    public String mode;            // solo | teams | pro | gtb | speed_builders
    public String theme;
    public Integer placement;
    public Integer players;
    public Double score;
    public String grade;
    public int durationSec;
    public int blocksPlaced;
    public int blocksBroken;
    public static final int LEGENDARY_VOTE = 18;   // highest vote one player can give
    public final Map<String, Integer> palette = new LinkedHashMap<String, Integer>();
    public String spbRoundsJson;   // set by SpbTracker

    public boolean won() { return placement != null && placement == 1; }

    public void countBlock(String blockId) {
        Integer c = palette.get(blockId);
        palette.put(blockId, c == null ? 1 : c + 1);
        blocksPlaced++;
    }

    /** Speed Builders precision: blocks the build needs / blocks actually touched. 1.0 = no wasted moves. */
    public static double precision(int blocksNeeded, int placed, int broken) {
        int touched = placed + broken;
        return touched == 0 ? 0 : Math.min(1.0, blocksNeeded / (double) touched);
    }

    /** Players who could vote on your build: everyone except you, and your teammate in Teams. */
    public int voters() {
        if (players == null) return 0;
        return Math.max(0, players - ("teams".equals(mode) ? 2 : 1));
    }

    /** Best possible score: every voter gives Legendary (18). */
    public Integer maxScore() {
        int v = voters();
        return v == 0 ? null : v * LEGENDARY_VOTE;
    }

    /** Crowd score 0.0 – 1.0. Only for voted modes (solo, teams, pro). */
    public Double crowdScore() {
        Integer max = maxScore();
        if (score == null || max == null || "gtb".equals(mode) || "speed_builders".equals(mode)) return null;
        return Math.min(1.0, score / max);
    }

    public String paletteJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey().replace("\"", "")).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    public static MatchRecord fromRow(ResultSet r) throws SQLException {
        MatchRecord m = new MatchRecord();
        m.nonce = r.getString("nonce");
        m.playedAt = r.getLong("played_at");
        m.mode = r.getString("mode");
        m.theme = r.getString("theme");
        int pl = r.getInt("placement"); m.placement = r.wasNull() ? null : pl;
        int pc = r.getInt("players"); m.players = r.wasNull() ? null : pc;
        double sc = r.getDouble("score"); m.score = r.wasNull() ? null : sc;
        m.grade = r.getString("grade");
        m.durationSec = r.getInt("duration_sec");
        m.blocksPlaced = r.getInt("blocks_placed");
        m.blocksBroken = r.getInt("blocks_broken");
        m.spbRoundsJson = r.getString("spb_rounds");
        // palette stays as JSON text on the way out; SyncService sends it raw
        return m;
    }
}
