package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.repository.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/** D012 durable intent and mutex operations. The caller owns publication state policy and JSON. */
public final class StockStDailyPublicationStore {
    @FunctionalInterface
    public interface Decoder<T> {
        T decode(String intentJson, String state, long revision) throws Exception;
    }

    private final Path path;

    public StockStDailyPublicationStore(Path path) throws SQLException {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.path)) throw new IllegalArgumentException("Existing D012 run ledger required");
        try (var db = open(); var statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS stk_st_daily_publication_mutex(singleton INTEGER PRIMARY KEY CHECK(singleton=1),run_id TEXT,publication_id TEXT)");
            statement.execute("INSERT OR IGNORE INTO stk_st_daily_publication_mutex(singleton,run_id,publication_id) VALUES(1,NULL,NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS stk_st_daily_publications(run_id TEXT PRIMARY KEY REFERENCES sync_runs(id),publication_id TEXT NOT NULL UNIQUE,intent_json TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,updated_at TEXT NOT NULL)");
        }
    }

    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000");
        }
        return db;
    }

    public <T> Optional<T> findForRun(String runId, Decoder<T> decoder) throws Exception {
        try (var db = open(); var query = db.prepareStatement(
                "SELECT intent_json,state,revision FROM stk_st_daily_publications WHERE run_id=?")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                T entry = decoder.decode(rows.getString(1), rows.getString(2), rows.getLong(3));
                if (rows.next()) throw new IllegalStateException("Multiple D012 publication intents for one run");
                return Optional.of(entry);
            }
        }
    }

    public void requireNoPendingPublication() throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (rows.next() && rows.getString(1) != null)
                    throw new IllegalStateException("D012 table publication is unresolved for run " + rows.getString(1));
            }
        }
    }

    /** Serialize only after acquiring the mutex, within the same transaction as the intent insert. */
    public void reserve(String runId, String publicationId, Callable<String> intentJson) throws Exception {
        try (var db = open()) {
            db.setAutoCommit(false);
            try (var update = db.prepareStatement("UPDATE stk_st_daily_publication_mutex SET run_id=?,publication_id=? WHERE singleton=1 AND run_id IS NULL")) {
                update.setString(1, runId); update.setString(2, publicationId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Another D012 publication holds the table lock");
            }
            try (var insert = db.prepareStatement("INSERT INTO stk_st_daily_publications(run_id,publication_id,intent_json,state,revision,updated_at) VALUES(?,?,?,'PREPARED',0,?)")) {
                insert.setString(1, runId); insert.setString(2, publicationId);
                insert.setString(3, intentJson.call());
                insert.setString(4, Instant.now().toString()); insert.executeUpdate();
            }
            db.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot reserve durable D012 publication slot", failure);
        }
    }

    public void advance(String runId, String priorState, long priorRevision, String nextState) throws SQLException {
        try (var db = open(); var update = db.prepareStatement("UPDATE stk_st_daily_publications SET state=?,revision=revision+1,updated_at=? WHERE run_id=? AND state=? AND revision=?")) {
            update.setString(1, nextState); update.setString(2, Instant.now().toString());
            update.setString(3, runId); update.setString(4, priorState); update.setLong(5, priorRevision);
            if (update.executeUpdate() != 1) throw new IllegalStateException("D012 publication phase changed concurrently");
        }
    }

    public void requireMutex(String runId, String publicationId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id,publication_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !runId.equals(rows.getString(1)) || !publicationId.equals(rows.getString(2)))
                    throw new IllegalStateException("D012 publication lock ownership differs from journal");
            }
        }
    }

    public void release(String runId, String publicationId) throws SQLException {
        try (var db = open(); var update = db.prepareStatement("UPDATE stk_st_daily_publication_mutex SET run_id=NULL,publication_id=NULL WHERE singleton=1 AND run_id=? AND publication_id=?")) {
            update.setString(1, runId); update.setString(2, publicationId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("D012 publication lock ownership changed before release");
        }
    }

    public void releaseIfOwned(String runId, String publicationId) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id,publication_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("D012 publication mutex row disappeared");
                String owner = rows.getString(1), id = rows.getString(2);
                if (owner == null && id == null) return;
                if (!runId.equals(owner) || !publicationId.equals(id))
                    throw new IllegalStateException("D012 verified publication mutex belongs to a different run");
            }
        }
        release(runId, publicationId);
    }
}
