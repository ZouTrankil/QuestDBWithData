package com.zoutrankil.data.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite persistence for the D023 physical target lineage. */
public final class DcIndexTargetTransitionStore {
    public record Intent(String publicationId, String runId, String logicalTargetId,
                         String previousPhysicalId, String nextPhysicalId, String fromDay, String toDay) {}
    public record Entry(Intent intent, String state, String createdAt) {}
    public record Pending(String publicationRunId, String transitionRunId) {}
    private final Path path;

    public DcIndexTargetTransitionStore(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public void initialize() throws SQLException {
        try (var db = open(); var statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS dc_index_target_transitions (publication_id TEXT PRIMARY KEY,run_id TEXT NOT NULL UNIQUE,logical_target_id TEXT NOT NULL,previous_physical_id TEXT NOT NULL,next_physical_id TEXT NOT NULL,from_day TEXT NOT NULL,to_day TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PENDING','VERIFIED')),created_at TEXT NOT NULL)");
        }
    }

    /** Inspect existing tables only; callers choose whether schema initialization is appropriate. */
    public Pending pendingIfPresent() throws SQLException {
        if (!Files.isRegularFile(path)) return new Pending(null, null);
        try (var db = open()) {
            if (tableExists(db, "reference_publications")) {
                String run = firstPending(db, true, null);
                if (run != null) return new Pending(run, null);
            }
            return new Pending(null, tableExists(db, "dc_index_target_transitions") ? firstPending(db, false, null) : null);
        }
    }

    public String firstUnverifiedPublication(String exceptRun) throws SQLException {
        try (var db = open()) { return firstPending(db, true, exceptRun); }
    }

    public String firstUnverifiedTransition(String exceptRun) throws SQLException {
        try (var db = open()) { return firstPending(db, false, exceptRun); }
    }

    public Optional<Entry> forRun(String runId, String logicalTargetId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM dc_index_target_transitions WHERE run_id=? AND logical_target_id=?")) {
            query.setString(1, runId); query.setString(2, logicalTargetId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Entry result = entry(rows);
                if (rows.next()) throw new IllegalStateException("Duplicate D023 transition");
                return Optional.of(result);
            }
        }
    }

    public Optional<Entry> forPublication(String publicationId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM dc_index_target_transitions WHERE publication_id=?")) {
            query.setString(1, publicationId);
            try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(entry(rows)) : Optional.empty(); }
        }
    }

    public List<Entry> lineage(String logicalTargetId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM dc_index_target_transitions WHERE logical_target_id=? ORDER BY created_at,rowid")) {
            query.setString(1, logicalTargetId);
            var result = new ArrayList<Entry>();
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(entry(rows)); }
            return List.copyOf(result);
        }
    }

    public void insertPending(Intent intent) throws SQLException {
        try (var db = open(); var insert = db.prepareStatement("INSERT INTO dc_index_target_transitions(publication_id,run_id,logical_target_id,previous_physical_id,next_physical_id,from_day,to_day,state,created_at) VALUES(?,?,?,?,?,?,?,'PENDING',?)")) {
            insert.setString(1, intent.publicationId()); insert.setString(2, intent.runId()); insert.setString(3, intent.logicalTargetId());
            insert.setString(4, intent.previousPhysicalId()); insert.setString(5, intent.nextPhysicalId());
            insert.setString(6, intent.fromDay()); insert.setString(7, intent.toDay()); insert.setString(8, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    public void markVerified(String runId) throws SQLException {
        try (var db = open(); var update = db.prepareStatement("UPDATE dc_index_target_transitions SET state='VERIFIED' WHERE run_id=? AND state IN ('PENDING','VERIFIED')")) {
            update.setString(1, runId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("D023 transition journal missing/ambiguous");
        }
    }

    private static String firstPending(Connection db, boolean publication, String exceptRun) throws SQLException {
        String sql = publication
                ? "SELECT run_id FROM reference_publications WHERE dataset='dc_index' AND state<>'VERIFIED'"
                : "SELECT run_id FROM dc_index_target_transitions WHERE state<>'VERIFIED'";
        if (exceptRun != null) sql += " AND run_id<>?";
        try (var query = db.prepareStatement(sql + " LIMIT 1")) {
            if (exceptRun != null) query.setString(1, exceptRun);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(new Intent(rows.getString("publication_id"), rows.getString("run_id"), rows.getString("logical_target_id"),
                rows.getString("previous_physical_id"), rows.getString("next_physical_id"), rows.getString("from_day"), rows.getString("to_day")),
                rows.getString("state"), rows.getString("created_at"));
    }

    private static boolean tableExists(Connection db, String table) throws SQLException {
        try (var query = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString());
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException failure) { db.close(); throw failure; }
        return db;
    }
}
