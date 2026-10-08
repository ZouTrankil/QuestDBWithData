package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.repository.*;

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

/** SQLite persistence for the D011 physical target lineage. */
public final class StockSuspendTargetTransitionStore {
    public record Intent(String publicationId, String runId, String logicalTargetId,
                         String previousPhysicalId, String nextPhysicalId, String previousDirectory, String nextDirectory,
                         String fromDay, String toDay, String beforeFingerprint, String afterFingerprint) {}
    public record Entry(Intent intent, String state, String createdAt) {}
    public record Pending(String publicationRunId, String transitionRunId) {}
    private final Path path;

    public StockSuspendTargetTransitionStore(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public void initialize() throws SQLException {
        try (var db = open(); var statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS stk_suspend_target_transitions (publication_id TEXT PRIMARY KEY,run_id TEXT NOT NULL UNIQUE,"
                    + "logical_target_id TEXT NOT NULL,previous_physical_id TEXT NOT NULL,next_physical_id TEXT NOT NULL,previous_directory TEXT NOT NULL,"
                    + "next_directory TEXT NOT NULL,from_day TEXT NOT NULL,to_day TEXT NOT NULL,before_fingerprint TEXT NOT NULL,after_fingerprint TEXT NOT NULL,"
                    + "state TEXT NOT NULL CHECK(state IN ('PENDING','VERIFIED')),created_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS stk_suspend_transitions_logical ON stk_suspend_target_transitions(logical_target_id,created_at)");
        }
    }

    /** Missing ledgers and absent publication tables are never created by the guard. */
    public Pending pendingIfPresent() throws SQLException {
        if (!Files.isRegularFile(path)) return new Pending(null, null);
        try (var db = open(); var statement = db.createStatement()) {
            var names = new java.util.HashSet<String>();
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rows.next()) names.add(rows.getString(1));
            }
            if (names.contains("reference_publications")) {
                String run = firstPendingPublication(db);
                if (run != null) return new Pending(run, null);
            }
            if (names.contains("stk_suspend_target_transitions")) {
                try (var query = db.prepareStatement("SELECT run_id FROM stk_suspend_target_transitions WHERE state<>'VERIFIED' LIMIT 1");
                     var rows = query.executeQuery()) {
                    if (rows.next()) return new Pending(null, rows.getString(1));
                }
            }
            return new Pending(null, null);
        }
    }

    public String firstUnverifiedPublication() throws SQLException {
        try (var db = open()) { return firstPendingPublication(db); }
    }

    public Optional<Entry> forRun(String runId, String logicalTargetId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM stk_suspend_target_transitions WHERE run_id=? AND logical_target_id=?")) {
            query.setString(1, runId); query.setString(2, logicalTargetId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Entry result = entry(rows);
                if (rows.next()) throw new IllegalStateException("Duplicate stk_suspend physical transition for run");
                return Optional.of(result);
            }
        }
    }

    public Optional<Entry> forPublication(String publicationId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM stk_suspend_target_transitions WHERE publication_id=?")) {
            query.setString(1, publicationId);
            try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(entry(rows)) : Optional.empty(); }
        }
    }

    public List<Entry> lineage(String logicalTargetId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT * FROM stk_suspend_target_transitions WHERE logical_target_id=? ORDER BY created_at,rowid")) {
            query.setString(1, logicalTargetId);
            var result = new ArrayList<Entry>();
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(entry(rows)); }
            return List.copyOf(result);
        }
    }

    public void insertPending(Intent intent) throws SQLException {
        try (var db = open(); var insert = db.prepareStatement("INSERT INTO stk_suspend_target_transitions "
                + "(publication_id,run_id,logical_target_id,previous_physical_id,next_physical_id,previous_directory,next_directory,from_day,to_day,before_fingerprint,after_fingerprint,state,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)")) {
            insert.setString(1, intent.publicationId()); insert.setString(2, intent.runId()); insert.setString(3, intent.logicalTargetId());
            insert.setString(4, intent.previousPhysicalId()); insert.setString(5, intent.nextPhysicalId());
            insert.setString(6, intent.previousDirectory()); insert.setString(7, intent.nextDirectory());
            insert.setString(8, intent.fromDay()); insert.setString(9, intent.toDay());
            insert.setString(10, intent.beforeFingerprint()); insert.setString(11, intent.afterFingerprint());
            insert.setString(12, Instant.now().toString()); insert.executeUpdate();
        }
    }

    public void markVerified(String runId) throws SQLException {
        try (var db = open(); var update = db.prepareStatement("UPDATE stk_suspend_target_transitions SET state='VERIFIED' WHERE run_id=? AND state IN ('PENDING','VERIFIED')")) {
            update.setString(1, runId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("stk_suspend transition journal is absent/ambiguous");
        }
    }

    private static String firstPendingPublication(Connection db) throws SQLException {
        try (var query = db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset='stk_suspend' AND state<>'VERIFIED' LIMIT 1");
             var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(new Intent(rows.getString("publication_id"), rows.getString("run_id"), rows.getString("logical_target_id"),
                rows.getString("previous_physical_id"), rows.getString("next_physical_id"), rows.getString("previous_directory"), rows.getString("next_directory"),
                rows.getString("from_day"), rows.getString("to_day"), rows.getString("before_fingerprint"), rows.getString("after_fingerprint")),
                rows.getString("state"), rows.getString("created_at"));
    }

    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString());
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException failure) { db.close(); throw failure; }
        return db;
    }
}
