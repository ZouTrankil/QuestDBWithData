package com.zoutrankil.questdbwithdata.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** SQLite-backed cross-process exclusion for one dataset and an inclusive business interval. */
public final class DatasetIntervalLock {
    public record Scope(String datasetId, LocalDate from, LocalDate to) {
        public Scope {
            if (datasetId == null || !datasetId.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Valid dataset ID required");
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (to.isBefore(from)) throw new IllegalArgumentException("Reversed conflict interval");
        }
        public static Scope allDates(String datasetId) {
            return new Scope(datasetId, LocalDate.MIN, LocalDate.MAX);
        }
    }
    public record Lease(String id, String runId, Scope scope, boolean inDoubt) {}
    private final Path path;

    public DatasetIntervalLock(Path ledgerPath) {
        path = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Existing run ledger required");
        try (var db = open(); var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sync_interval_locks (
                      id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES sync_runs(id),
                      dataset_id TEXT NOT NULL, from_day INTEGER NOT NULL, to_day INTEGER NOT NULL,
                      in_doubt INTEGER NOT NULL CHECK(in_doubt IN (0,1)), acquired_at TEXT NOT NULL)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS sync_interval_locks_dataset ON sync_interval_locks(dataset_id,from_day,to_day)");
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize interval locks", failure); }
    }

    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException failure) { db.close(); throw failure; }
        return db;
    }

    /** Returns false on overlap. The lookup and insert share one immediate write transaction. */
    public Lease acquire(String runId, Scope scope) {
        Objects.requireNonNull(scope);
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Valid run ID required");
        try (var db = open()) {
            begin(db);
            try {
                try (var query = db.prepareStatement("""
                        SELECT id FROM sync_interval_locks WHERE dataset_id=?
                        AND from_day<=? AND to_day>=? LIMIT 1
                        """)) {
                    query.setString(1, scope.datasetId());
                    query.setLong(2, scope.to().toEpochDay());
                    query.setLong(3, scope.from().toEpochDay());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) { commit(db); return null; }
                    }
                }
                var lease = new Lease(UUID.randomUUID().toString(), runId, scope, false);
                try (var insert = db.prepareStatement("INSERT INTO sync_interval_locks VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, lease.id()); insert.setString(2, runId);
                    insert.setString(3, scope.datasetId());
                    insert.setLong(4, scope.from().toEpochDay());
                    insert.setLong(5, scope.to().toEpochDay());
                    insert.setInt(6, 0); insert.setString(7, Instant.now().toString());
                    insert.executeUpdate();
                }
                commit(db);
                return lease;
            } catch (Exception failure) { rollback(db); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot acquire interval lock", failure); }
    }

    public Lease findOwned(String runId,Scope scope) {
        Objects.requireNonNull(runId);Objects.requireNonNull(scope);
        try(var db=open();var query=db.prepareStatement("SELECT id,in_doubt FROM sync_interval_locks WHERE run_id=? AND dataset_id=? AND from_day=? AND to_day=?")) {
            query.setString(1,runId);query.setString(2,scope.datasetId());query.setLong(3,scope.from().toEpochDay());query.setLong(4,scope.to().toEpochDay());
            try(var rows=query.executeQuery()) {
                if(!rows.next()) return null;
                var lease=new Lease(rows.getString(1),runId,scope,rows.getInt(2)!=0);
                if(rows.next()) throw new IllegalStateException("Multiple owned leases for the same scope");
                return lease;
            }
        } catch(SQLException failure) { throw new IllegalStateException("Cannot read owned interval lock",failure); }
    }
    /** Unknown transport outcome keeps the lock until explicit reconciliation. */
    public void retainInDoubt(Lease lease) {
        change(lease, false, false);
    }
    public void releaseVerified(Lease lease) {
        change(lease, true, false);
    }
    public void releaseAfterReconciliation(Lease lease, boolean writerStopped, boolean exactReadback) {
        if (!writerStopped || !exactReadback)
            throw new IllegalStateException("Uncertain writer requires stop and exact readback proof");
        change(lease, true, true);
    }

    private void change(Lease lease, boolean delete, boolean requireInDoubt) {
        Objects.requireNonNull(lease);
        try (var db = open()) {
            begin(db);
            try {
                String sql = delete
                        ? "DELETE FROM sync_interval_locks WHERE id=? AND run_id=? AND in_doubt=?"
                        : "UPDATE sync_interval_locks SET in_doubt=1 WHERE id=? AND run_id=? AND in_doubt=?";
                try (var update = db.prepareStatement(sql)) {
                    update.setString(1, lease.id()); update.setString(2, lease.runId());
                    update.setInt(3, requireInDoubt ? 1 : 0);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Lease ownership or state changed");
                }
                commit(db);
            } catch (Exception failure) { rollback(db); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot update interval lock", failure); }
    }

    private static void begin(Connection db) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }
    private static void commit(Connection db) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute("COMMIT"); }
    }
    private static void rollback(Connection db) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute("ROLLBACK"); }
    }
}
