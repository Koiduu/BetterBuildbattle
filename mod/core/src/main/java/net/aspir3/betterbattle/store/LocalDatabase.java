package net.aspir3.betterbattle.store;

import net.aspir3.betterbattle.game.MatchRecord;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod's own database. Source of truth for everything the player earns.
 * One SQLite file per Minecraft account: config/betterbattle/<uuid>.db
 *
 * Java 8 compatible so the same core runs in both the Fabric 26.3 and Forge 1.8.9 builds.
 * Requires org.xerial:sqlite-jdbc shaded into each jar.
 */
public final class LocalDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final Connection conn;

    public LocalDatabase(File configDir, String mcUuid) throws SQLException {
        File dir = new File(configDir, "betterbattle");
        if (!dir.exists() && !dir.mkdirs()) throw new SQLException("cannot create " + dir);
        conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, mcUuid + ".db").getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");      // crash-safe if the game dies mid-write
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            int v = Integer.parseInt(getMeta("schema_version", "0"));
            if (v < 1) {
                s.execute("CREATE TABLE matches (" +
                        " nonce TEXT PRIMARY KEY, played_at INTEGER NOT NULL, mode TEXT NOT NULL," +
                        " theme TEXT, placement INTEGER, players INTEGER, score REAL, grade TEXT," +
                        " duration_sec INTEGER, blocks_placed INTEGER, blocks_broken INTEGER, max_score REAL, palette TEXT, spb_rounds TEXT," +
                        " sync_state TEXT NOT NULL DEFAULT 'pending'," +   // pending | synced | rejected
                        " synced_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0, reject_reason TEXT)");
                s.execute("CREATE INDEX matches_sync_idx ON matches (sync_state, played_at)");
                s.execute("CREATE INDEX matches_time_idx ON matches (played_at)");
                s.execute("CREATE TABLE achievements_catalog (" +
                        " id TEXT PRIMARY KEY, name TEXT, description TEXT, category TEXT, track TEXT," +
                        " tier INTEGER, threshold INTEGER, points INTEGER, secret INTEGER)");
                s.execute("CREATE TABLE achievements (" +
                        " id TEXT PRIMARY KEY, unlocked_at INTEGER NOT NULL," +
                        " state TEXT NOT NULL DEFAULT 'local')");          // local | confirmed | revoked
                s.execute("CREATE TABLE personal_bests (" +
                        " build_id TEXT PRIMARY KEY, time_ms INTEGER NOT NULL, accuracy REAL," +
                        " placements BLOB, set_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)");
                s.execute("CREATE TABLE wr_ghosts (" +
                        " build_id TEXT PRIMARY KEY, holder TEXT, time_ms INTEGER, placements BLOB, fetched_at INTEGER)");
                s.execute("CREATE TABLE events (" +            // rolling log for time-window achievements
                        " at INTEGER NOT NULL, kind TEXT NOT NULL, mode TEXT)");
                s.execute("CREATE INDEX events_at_idx ON events (at)");
                setMeta("schema_version", "1");
            }
            // future: if (v < 2) { ... ALTER TABLE ... ; setMeta("schema_version","2"); }
        }
    }

    // ---------------- matches ----------------

    /** Write first, sync later. Returns false if this nonce was already stored. */
    public synchronized boolean insertMatch(MatchRecord m) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT OR IGNORE INTO matches (nonce, played_at, mode, theme, placement, players, score, grade," +
                " duration_sec, blocks_placed, blocks_broken, max_score, palette, spb_rounds) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, m.nonce);
            p.setLong(2, m.playedAt);
            p.setString(3, m.mode);
            p.setString(4, m.theme);
            setIntOrNull(p, 5, m.placement);
            setIntOrNull(p, 6, m.players);
            if (m.score == null) p.setNull(7, Types.REAL); else p.setDouble(7, m.score);
            p.setString(8, m.grade);
            p.setInt(9, m.durationSec);
            p.setInt(10, m.blocksPlaced);
            p.setInt(11, m.blocksBroken);
            if (m.maxScore == null) p.setNull(12, Types.REAL); else p.setDouble(12, m.maxScore);
            p.setString(13, m.paletteJson());
            p.setString(14, m.spbRoundsJson);
            boolean inserted = p.executeUpdate() == 1;
            if (inserted && m.placement != null && m.placement == 1) {
                try (PreparedStatement e = conn.prepareStatement("INSERT INTO events (at, kind, mode) VALUES (?, 'win', ?)")) {
                    e.setLong(1, m.playedAt);
                    e.setString(2, m.mode);
                    e.executeUpdate();
                }
            }
            conn.commit();
            return inserted;
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized List<MatchRecord> pendingMatches(int limit) throws SQLException {
        List<MatchRecord> out = new ArrayList<MatchRecord>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT * FROM matches WHERE sync_state = 'pending' ORDER BY played_at LIMIT ?")) {
            p.setInt(1, limit);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(MatchRecord.fromRow(r));
            }
        }
        return out;
    }

    public synchronized void markSynced(List<String> nonces) throws SQLException {
        updateState(nonces, "synced", null);
    }

    public synchronized void markRejected(String nonce, String reason) throws SQLException {
        updateState(java.util.Collections.singletonList(nonce), "rejected", reason);
    }

    public synchronized void bumpAttempts(List<String> nonces) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("UPDATE matches SET attempts = attempts + 1 WHERE nonce = ?")) {
            for (String n : nonces) { p.setString(1, n); p.addBatch(); }
            p.executeBatch();
        }
    }

    /** Disaster recovery: server lost its data → re-queue the whole history for upload. */
    public synchronized int requeueEverything() throws SQLException {
        try (Statement s = conn.createStatement()) {
            return s.executeUpdate("UPDATE matches SET sync_state = 'pending' WHERE sync_state = 'synced'");
        }
    }

    public synchronized int countPending() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM matches WHERE sync_state = 'pending'")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    // ---------------- stats (always recomputed from matches, never synced) ----------------

    public synchronized LocalStats stats(String modeOrNull) throws SQLException {
        String where = modeOrNull == null ? "" : " WHERE mode = ?";
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT count(*), sum(placement = 1), coalesce(sum(score),0), coalesce(sum(duration_sec),0)," +
                " sum(grade = 'SS'), sum(grade = 'S'), sum(grade = 'A') FROM matches" + where)) {
            if (modeOrNull != null) p.setString(1, modeOrNull);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new LocalStats(r.getInt(1), r.getInt(2), r.getDouble(3), r.getLong(4),
                        r.getInt(5), r.getInt(6), r.getInt(7));
            }
        }
    }

    /** Distinct modes with a win since the given time (Mode Marathon). */
    public synchronized List<String> modesWonSince(long sinceMillis) throws SQLException {
        List<String> out = new ArrayList<String>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT DISTINCT mode FROM events WHERE kind = 'win' AND at >= ?")) {
            p.setLong(1, sinceMillis);
            try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(r.getString(1)); }
        }
        return out;
    }

    // ---------------- achievements ----------------

    public synchronized boolean unlockLocal(String id, long at) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT OR IGNORE INTO achievements (id, unlocked_at, state) VALUES (?, ?, 'local')")) {
            p.setString(1, id);
            p.setLong(2, at);
            return p.executeUpdate() == 1;
        }
    }

    public synchronized void setAchievementState(String id, String state) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("UPDATE achievements SET state = ? WHERE id = ?")) {
            p.setString(1, state);
            p.setString(2, id);
            p.executeUpdate();
        }
    }

    public synchronized List<String> unlockedIds() throws SQLException {
        List<String> out = new ArrayList<String>();
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT id FROM achievements WHERE state != 'revoked'")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    // ---------------- meta ----------------

    public synchronized String getMeta(String key, String def) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : def; }
        }
    }

    public synchronized void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            p.setString(1, key);
            p.setString(2, value);
            p.executeUpdate();
        }
    }

    Connection connection() { return conn; } // for Exporter

    private void updateState(List<String> nonces, String state, String reason) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "UPDATE matches SET sync_state = ?, synced_at = ?, reject_reason = ? WHERE nonce = ?")) {
            long now = System.currentTimeMillis();
            for (String n : nonces) {
                p.setString(1, state); p.setLong(2, now); p.setString(3, reason); p.setString(4, n);
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    private static void setIntOrNull(PreparedStatement p, int i, Integer v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v);
    }

    @Override public void close() throws SQLException { conn.close(); }

    public static final class LocalStats {
        public final int games, wins, ss, s, a;
        public final double totalScore;
        public final long playtimeSec;
        LocalStats(int games, int wins, double totalScore, long playtimeSec, int ss, int s, int a) {
            this.games = games; this.wins = wins; this.totalScore = totalScore;
            this.playtimeSec = playtimeSec; this.ss = ss; this.s = s; this.a = a;
        }
    }
}
