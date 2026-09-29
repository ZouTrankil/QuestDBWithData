package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Local SQLite transactional authority. JSON is an immutable payload, never a second status authority. */
public final class SyncRunLedger {
    public enum Kind { RUN, ATTEMPT, SLICE }
    public record Entry(String id, Kind kind, String runId, String parentId, SyncRunState state,
                        long revision, String payloadJson, String updatedAt) {}
    public record Run(String id, String parentRunId, String jobId, int jobVersion, String logicalDate,
                      String targetId, String frozenJson) {}
    private final Path path;
    private final boolean readOnly;
    private static final ObjectMapper JSON = new ObjectMapper();

    public SyncRunLedger(Path path) throws IOException, SQLException {
        this(path, false);
    }
    public static SyncRunLedger openReadOnly(Path path) throws IOException, SQLException {
        return new SyncRunLedger(path, true);
    }
    private SyncRunLedger(Path path, boolean readOnly) throws IOException, SQLException {
        this.path = path.toAbsolutePath().normalize();
        this.readOnly = readOnly;
        if (readOnly) {
            if (!Files.isRegularFile(this.path)) throw new IOException("Ledger does not exist");
            return;
        }
        Files.createDirectories(this.path.getParent());
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            c.setAutoCommit(false);
            s.execute("CREATE TABLE IF NOT EXISTS ledger_meta(version INTEGER NOT NULL)");
            s.execute("INSERT INTO ledger_meta SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM ledger_meta)");
            try (var r = s.executeQuery("SELECT version FROM ledger_meta")) {
                if (!r.next() || r.getInt(1) != 1 || r.next()) throw new SQLException("Unsupported ledger schema");
            }
            s.execute("CREATE TABLE IF NOT EXISTS sync_runs(id TEXT PRIMARY KEY, parent_run_id TEXT REFERENCES sync_runs(id), "
                    + "job_id TEXT NOT NULL, job_version INTEGER NOT NULL, logical_date TEXT NOT NULL, "
                    + "target_id TEXT NOT NULL, frozen_json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sync_entries(id TEXT PRIMARY KEY, kind TEXT NOT NULL, "
                    + "run_id TEXT NOT NULL REFERENCES sync_runs(id), parent_id TEXT REFERENCES sync_entries(id), "
                    + "state TEXT NOT NULL, revision INTEGER NOT NULL, payload_json TEXT NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sync_events(entry_id TEXT NOT NULL REFERENCES sync_entries(id), "
                    + "revision INTEGER NOT NULL, state TEXT NOT NULL, payload_json TEXT NOT NULL, updated_at TEXT NOT NULL, "
                    + "PRIMARY KEY(entry_id,revision))");
            s.execute("CREATE INDEX IF NOT EXISTS sync_entries_run ON sync_entries(run_id,kind,id)");
            s.execute("CREATE INDEX IF NOT EXISTS sync_entries_parent ON sync_entries(parent_id,state)");
            c.commit();
        }
    }
    private Connection connect() throws SQLException {
        var c = DriverManager.getConnection(readOnly
                ? "jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro"
                : "jdbc:sqlite:" + path);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
            if (readOnly) s.execute("PRAGMA query_only=ON");
        } catch (SQLException e) { c.close(); throw e; }
        return c;
    }
    public void createRun(String id, String parentRunId, String targetId,
                          com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest request)
            throws SQLException, IOException {
        Objects.requireNonNull(request);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("definition", request.definition()); snapshot.put("mode", request.mode());
        snapshot.put("parameters", request.parameters()); snapshot.put("from", request.from());
        snapshot.put("to", request.to()); snapshot.put("logicalDate", request.logicalDate());
        String frozen = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writeValueAsString(snapshot);
        createRun(new Run(id, parentRunId, request.definition().jobId(), request.definition().version(),
                request.logicalDate().toString(), targetId, frozen));
    }
    public void createRun(Run run) throws SQLException {
        id(run.id()); id(run.jobId()); id(run.targetId());
        if (run.jobVersion() < 1) throw new IllegalArgumentException("Positive job version required");
        LocalDate.parse(run.logicalDate()); json(run.frozenJson());
        if (run.parentRunId() != null) id(run.parentRunId());
        transaction(c -> {
            try (var s = c.prepareStatement("INSERT INTO sync_runs VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1, run.id()); s.setString(2, run.parentRunId()); s.setString(3, run.jobId());
                s.setInt(4, run.jobVersion()); s.setString(5, run.logicalDate()); s.setString(6, run.targetId());
                s.setString(7, run.frozenJson()); s.executeUpdate();
            }
            insertEntry(c, run.id(), Kind.RUN, run.id(), null);
        });
    }
    public void createChild(String id, Kind kind, String runId, String parentId) throws SQLException {
        id(id); id(runId); id(parentId);
        if (kind == null || kind == Kind.RUN) throw new IllegalArgumentException("Use createRun for a run");
        transaction(c -> {
            var parent = require(c, parentId);
            if (!parent.runId().equals(runId) || (kind == Kind.ATTEMPT ? parent.kind() != Kind.RUN : parent.kind() != Kind.ATTEMPT))
                throw new IllegalArgumentException("Invalid run/attempt/slice parent");
            if (parent.state().terminal() || parent.state() == SyncRunState.IN_DOUBT)
                throw new IllegalArgumentException("Cannot append work to terminal or uncertain parent");
            insertEntry(c, id, kind, runId, parentId);
        });
    }
    private void insertEntry(Connection c, String id, Kind kind, String runId, String parent) throws SQLException {
        String now = Instant.now().toString();
        try (var s = c.prepareStatement("INSERT INTO sync_entries VALUES(?,?,?,?,?,0,'{}',?)")) {
            s.setString(1, id); s.setString(2, kind.name()); s.setString(3, runId); s.setString(4, parent);
            s.setString(5, SyncRunState.PENDING.name()); s.setString(6, now); s.executeUpdate();
        }
        appendEvent(c, id, 0, SyncRunState.PENDING, "{}", now);
    }
    /** Compare-and-set update and immutable event append commit together. */
    public void transition(String id, long expectedRevision, SyncRunState next, String payloadJson) throws SQLException {
        id(id); json(payloadJson);
        transaction(c -> {
            var previous = require(c, id);
            if (previous.revision() != expectedRevision) throw new IllegalStateException("Stale ledger revision");
            previous.state().requireTransition(next);
            requireCompletionEvidence(previous.state(), next, payloadJson);
            if (next == SyncRunState.VERIFIED || next == SyncRunState.VERIFIED_EMPTY) {
                try (var s = c.prepareStatement("SELECT count(*) FROM sync_entries WHERE parent_id=? "
                        + "AND (state NOT IN ('VERIFIED','VERIFIED_EMPTY') OR (?='VERIFIED_EMPTY' AND state='VERIFIED'))")) {
                    s.setString(1, id); s.setString(2, next.name());
                    try (var r = s.executeQuery()) {
                        if (r.next() && r.getLong(1) != 0) throw new IllegalStateException("Child work is not verified consistently");
                    }
                }
            }
            String now = Instant.now().toString();
            try (var s = c.prepareStatement("UPDATE sync_entries SET state=?, revision=revision+1, payload_json=?, updated_at=? WHERE id=? AND revision=?")) {
                s.setString(1, next.name()); s.setString(2, payloadJson); s.setString(3, now);
                s.setString(4, id); s.setLong(5, expectedRevision);
                if (s.executeUpdate() != 1) throw new IllegalStateException("Concurrent ledger update");
            }
            appendEvent(c, id, expectedRevision + 1, next, payloadJson, now);
        });
    }
    private void appendEvent(Connection c, String id, long revision, SyncRunState state, String json, String now) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO sync_events VALUES(?,?,?,?,?)")) {
            s.setString(1, id); s.setLong(2, revision); s.setString(3, state.name()); s.setString(4, json);
            s.setString(5, now); s.executeUpdate();
        }
    }
    public Entry get(String id) throws SQLException { id(id); try (var c = connect()) { return require(c, id); } }
    public List<Entry> entries(String runId, String afterId, int limit) throws SQLException {
        id(runId); if (afterId != null) id(afterId); pageSize(limit);
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT * FROM sync_entries WHERE run_id=? AND id>? ORDER BY id LIMIT ?")) {
            s.setString(1, runId); s.setString(2, afterId == null ? "" : afterId); s.setInt(3, limit);
            var entries = new ArrayList<Entry>();
            try (var r = s.executeQuery()) { while (r.next()) entries.add(entry(r)); }
            return List.copyOf(entries);
        }
    }
    public record Event(String entryId, long revision, SyncRunState state, String payloadJson, String updatedAt) {}
    public List<Event> events(String id, long afterRevision, int limit) throws SQLException {
        id(id); pageSize(limit);
        if (afterRevision < -1) throw new IllegalArgumentException("Invalid event cursor");
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT * FROM sync_events WHERE entry_id=? AND revision>? ORDER BY revision LIMIT ?")) {
            s.setString(1, id); s.setLong(2, afterRevision); s.setInt(3, limit);
            var events = new ArrayList<Event>();
            try (var r = s.executeQuery()) {
                while (r.next()) events.add(new Event(id, r.getLong("revision"), SyncRunState.valueOf(r.getString("state")),
                        r.getString("payload_json"), r.getString("updated_at")));
            }
            return List.copyOf(events);
        }
    }
    private static void pageSize(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Ledger page limit must be 1..1000");
    }
    public Run getRun(String id) throws SQLException {
        id(id);
        try (var c = connect(); var s = c.prepareStatement("SELECT * FROM sync_runs WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown run");
                return new Run(r.getString("id"), r.getString("parent_run_id"), r.getString("job_id"),
                        r.getInt("job_version"), r.getString("logical_date"), r.getString("target_id"), r.getString("frozen_json"));
            }
        }
    }
    private Entry require(Connection c, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM sync_entries WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown ledger entry");
                return entry(r);
            }
        }
    }
    private static Entry entry(ResultSet r) throws SQLException {
        return new Entry(r.getString("id"), Kind.valueOf(r.getString("kind")), r.getString("run_id"),
                r.getString("parent_id"), SyncRunState.valueOf(r.getString("state")), r.getLong("revision"),
                r.getString("payload_json"), r.getString("updated_at"));
    }
    @FunctionalInterface private interface Work { void run(Connection connection) throws SQLException; }
    private void transaction(Work work) throws SQLException {
        if (readOnly) throw new IllegalStateException("Read-only ledger");
        try (var c = connect()) {
            c.setAutoCommit(false);
            try { work.run(c); c.commit(); }
            catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    private static void id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid ledger ID");
    }
    private static void json(String text) {
        if (text == null || text.length() > 1048576) throw new IllegalArgumentException("Bounded JSON required");
        try {
            var node = JSON.readTree(text);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        }
        catch (IOException e) { throw new IllegalArgumentException("Invalid ledger JSON"); }
    }
    private static void requireCompletionEvidence(SyncRunState previous, SyncRunState next, String payload) {
        try {
            var root = JSON.readTree(payload);
            if (next == SyncRunState.VERIFIED) {
                var proof = root.path("verification");
                long expected = count(proof.path("expectedRows"));
                if (expected < 1 || !proof.path("passed").isBoolean() || !proof.path("passed").booleanValue()
                        || count(proof.path("matchedRows")) != expected
                        || count(proof.path("actualRows")) != expected
                        || count(proof.path("mismatchedRows")) != 0
                        || count(proof.path("duplicateKeys")) != 0
                        || count(proof.path("missingKeys")) != 0
                        || proof.path("readbackEvidence").asText("").isBlank()
                        || proof.path("sourceFingerprint").asText("").isBlank()
                        || (previous == SyncRunState.IN_DOUBT && !proof.path("writerStopped").asBoolean(false)))
                    throw new IllegalArgumentException("Complete readback evidence required for verification");
            }
            if (next == SyncRunState.VERIFIED_EMPTY && (!root.path("sourceComplete").isBoolean()
                    || !root.path("sourceComplete").booleanValue()
                    || count(root.path("returnedRows")) != 0 || count(root.path("submittedRows")) != 0
                    || root.path("responseEvidence").asText("").isBlank()))
                throw new IllegalArgumentException("Complete empty source evidence required");
        } catch (IOException e) { throw new IllegalArgumentException("Invalid ledger evidence"); }
    }
    private static long count(com.fasterxml.jackson.databind.JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : -1;
    }
}
