package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.repository.QuestDbWriteChecks;
import com.zoutrankil.questdbwithdata.repository.StockStDailyStorage;
import com.zoutrankil.questdbwithdata.repository.StockStDailyStaging;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** D012-specific durable, serialized two-rename publication for bounded authoritative ST windows. */
public final class StockStDailyPublication {
    public enum State { PREPARED, OLD_MOVED, PUBLISHED, VERIFIED, IN_DOUBT, RESUMING }
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Intent(String id, String runId, String logicalTarget, String frozenPhysicalTarget,
                         String target, String backup, String stage, StockStDailyStorage.Identity beforeIdentity,
                         StockStDailyStorage.Content before, StockStDailyStorage.Identity stageIdentity,
                         StockStDailyStorage.Content after, StockStDailyStorage.Content preservedOutside,
                         StockStDailyStorage.Content authoritativeWindow, LocalDate windowFrom, LocalDate windowTo,
                         int sourceRows, String sourceFingerprint, String stageReceipt, String stageReceiptFingerprint) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(runId); Objects.requireNonNull(logicalTarget);
            Objects.requireNonNull(frozenPhysicalTarget); Objects.requireNonNull(beforeIdentity);
            Objects.requireNonNull(before); Objects.requireNonNull(stageIdentity); Objects.requireNonNull(after);
            Objects.requireNonNull(preservedOutside); Objects.requireNonNull(authoritativeWindow);
            Objects.requireNonNull(windowFrom); Objects.requireNonNull(windowTo); Objects.requireNonNull(sourceFingerprint);
            Objects.requireNonNull(stageReceipt); Objects.requireNonNull(stageReceiptFingerprint);
            DatasetDefinition.identifier(target); DatasetDefinition.identifier(backup); DatasetDefinition.identifier(stage);
            if (Set.of(target, backup, stage).size() != 3 || windowFrom.isAfter(windowTo) || sourceRows < 0
                    || !sourceFingerprint.matches("[0-9a-f]{64}") || !stageReceiptFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete bounded D012 publication intent required");
        }
    }
    public record Entry(Intent intent, State state, long revision) {}
    public record Result(Entry entry, Layout layout, StockStDailyStorage.Snapshot target,
                         StockStDailyStorage.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        private final String publicationId;
        Uncertain(String runId, String publicationId, Exception cause) {
            super("D012 staged table publication needs reconciliation: " + runId, cause);
            this.runId = runId; this.publicationId = publicationId;
        }
        public String runId() { return runId; }
        public String publicationId() { return publicationId; }
    }

    private final JdbcTemplate jdbc;
    private final Path ledgerPath;

    public StockStDailyPublication(JdbcTemplate jdbc, Path ledgerPath) throws Exception {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(120);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        initialize();
    }

    /** Reserve the sole non-atomic publication slot before recording intent or renaming any QuestDB table. */
    public Result publish(String runId, String logicalTarget, String frozenPhysicalTarget, String target,
                          StockStDailyStaging.Prepared prepared, StockStDailyStaging.Verified stage,
                          BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(cancelled); DatasetDefinition.identifier(target);
        verifyRunBinding(runId, logicalTarget, frozenPhysicalTarget, target);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D012 publication cancelled before intent");
        var before = new StockStDailyStorage(jdbc, target).snapshot();
        var staged = new StockStDailyStorage(jdbc, stage.table()).snapshot();
        if (!before.equals(prepared.before())
                || !StockStDailyStorage.targetId(jdbc, target, before.identity()).equals(frozenPhysicalTarget)
                || !samePhysical(staged.identity(), stage.snapshot().identity())
                || !StockStDailyStorage.sameContent(staged.content(), stage.snapshot().content()))
            throw new IllegalStateException("D012 target or staged snapshot changed before publication");
        Path receipt = Path.of(stage.receipt()).toAbsolutePath().normalize();
        Path runEvidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
        Path realEvidence = runEvidence.toRealPath(), realReceipt = receipt.toRealPath();
        if (!realReceipt.startsWith(realEvidence) || Files.size(realReceipt) > 32L * 1024 * 1024)
            throw new IllegalStateException("D012 stage receipt is outside its bounded run evidence root");
        String receiptFingerprint = sha256(Files.readAllBytes(realReceipt));
        String backup = StockStDailyJobService.ISOLATED_TABLE_PREFIX + "backup_"
                + UUID.randomUUID().toString().replace("-", "");
        DatasetDefinition.identifier(backup);
        String id = "stk-st-publication-" + UUID.randomUUID();
        var intent = new Intent(id, runId, logicalTarget, frozenPhysicalTarget, target, backup, stage.table(),
                before.identity(), before.content(), staged.identity(), staged.content(), stage.outside(), stage.window(),
                prepared.from(), prepared.to(), stage.sourceRows(), stage.sourceFingerprint(), realReceipt.toString(), receiptFingerprint);
        Entry entry = reserve(intent);
        try {
            check(cancelled);
            rename(target, backup);
            entry = advance(entry, State.OLD_MOVED);
            check(cancelled);
            rename(stage.table(), target);
            entry = advance(entry, State.PUBLISHED);
            var verified = verifyPublished(entry);
            entry = advance(entry, State.VERIFIED);
            release(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        } catch (Exception failure) {
            retainUncertain(entry, failure);
            throw new Uncertain(runId, id, failure);
        }
    }

    /** Recover only the exact journal-owned original/stage/published layouts after the writer has stopped. */
    public Result finish(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped D012 writer proof required before publication recovery");
        Entry entry = forRun(runId);
        if (entry.state() == State.VERIFIED) {
            var verified = verifyPublished(entry);
            releaseIfOwned(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        }
        requireMutex(entry);
        Layout layout = inspect(entry);
        if (layout == Layout.CONFLICT) throw new IllegalStateException("D012 publication tables differ from exact journal identities/content");
        if (entry.state() != State.IN_DOUBT) entry = advance(entry, State.IN_DOUBT);
        entry = advance(entry, State.RESUMING);
        try {
            if (layout == Layout.ORIGINAL) rename(entry.intent().target(), entry.intent().backup());
            if (layout != Layout.PUBLISHED) rename(entry.intent().stage(), entry.intent().target());
            entry = advance(entry, State.PUBLISHED);
            var verified = verifyPublished(entry);
            entry = advance(entry, State.VERIFIED);
            release(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        } catch (Exception failure) {
            retainUncertain(entry, failure);
            throw new Uncertain(runId, entry.intent().id(), failure);
        }
    }

    public Entry forRun(String runId) throws Exception {
        try (var db = open(); var query = db.prepareStatement(
                "SELECT intent_json,state,revision FROM stk_st_daily_publications WHERE run_id=?")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("No D012 publication intent for run " + runId);
                Entry entry = new Entry(JobDefinitionJson.mapper().readValue(rows.getString(1), Intent.class),
                        State.valueOf(rows.getString(2)), rows.getLong(3));
                if (rows.next()) throw new IllegalStateException("Multiple D012 publication intents for one run");
                return entry;
            }
        }
    }

    public Optional<Entry> findForRun(String runId) throws Exception {
        try (var db = open(); var query = db.prepareStatement(
                "SELECT intent_json,state,revision FROM stk_st_daily_publications WHERE run_id=?")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Entry entry = new Entry(JobDefinitionJson.mapper().readValue(rows.getString(1), Intent.class),
                        State.valueOf(rows.getString(2)), rows.getLong(3));
                if (rows.next()) throw new IllegalStateException("Multiple D012 publication intents for one run");
                return Optional.of(entry);
            }
        }
    }

    public void requireNoPendingPublication() throws Exception {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (rows.next() && rows.getString(1) != null)
                    throw new IllegalStateException("D012 table publication is unresolved for run " + rows.getString(1));
            }
        }
    }

    private Result verifyPublished(Entry entry) throws Exception {
        if (inspect(entry) != Layout.PUBLISHED) throw new IllegalStateException("D012 published target differs from durable stage intent");
        var target = new StockStDailyStorage(jdbc, entry.intent().target()).snapshot();
        var backup = new StockStDailyStorage(jdbc, entry.intent().backup()).snapshot();
        return new Result(entry, Layout.PUBLISHED, target, backup);
    }

    private Layout inspect(Entry entry) throws Exception {
        Intent i = entry.intent();
        var target = snapshotIfPresent(i.target()); var backup = snapshotIfPresent(i.backup()); var stage = snapshotIfPresent(i.stage());
        if (matches(target, i.beforeIdentity(), i.before()) && backup == null && matches(stage, i.stageIdentity(), i.after()))
            return Layout.ORIGINAL;
        if (target == null && matches(backup, i.beforeIdentity(), i.before()) && matches(stage, i.stageIdentity(), i.after()))
            return Layout.OLD_MOVED;
        if (matches(target, i.stageIdentity(), i.after()) && matches(backup, i.beforeIdentity(), i.before()) && stage == null)
            return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }

    private Entry reserve(Intent intent) throws Exception {
        try (var db = open()) {
            db.setAutoCommit(false);
            try (var update = db.prepareStatement("UPDATE stk_st_daily_publication_mutex SET run_id=?,publication_id=? WHERE singleton=1 AND run_id IS NULL")) {
                update.setString(1, intent.runId()); update.setString(2, intent.id());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Another D012 publication holds the table lock");
            }
            try (var insert = db.prepareStatement("INSERT INTO stk_st_daily_publications(run_id,publication_id,intent_json,state,revision,updated_at) VALUES(?,?,?,'PREPARED',0,?)")) {
                insert.setString(1, intent.runId()); insert.setString(2, intent.id());
                insert.setString(3, JobDefinitionJson.mapper().writeValueAsString(intent));
                insert.setString(4, Instant.now().toString()); insert.executeUpdate();
            }
            db.commit();
            return new Entry(intent, State.PREPARED, 0);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot reserve durable D012 publication slot", failure);
        }
    }

    private Entry advance(Entry prior, State next) throws Exception {
        boolean valid = next == State.IN_DOUBT && prior.state() != State.VERIFIED
                || prior.state() == State.PREPARED && next == State.OLD_MOVED
                || prior.state() == State.OLD_MOVED && next == State.PUBLISHED
                || prior.state() == State.PUBLISHED && next == State.VERIFIED
                || prior.state() == State.IN_DOUBT && next == State.RESUMING
                || prior.state() == State.RESUMING && next == State.PUBLISHED;
        if (!valid) throw new IllegalArgumentException("Invalid D012 publication transition");
        try (var db = open(); var update = db.prepareStatement("UPDATE stk_st_daily_publications SET state=?,revision=revision+1,updated_at=? WHERE run_id=? AND state=? AND revision=?")) {
            update.setString(1, next.name()); update.setString(2, Instant.now().toString());
            update.setString(3, prior.intent().runId()); update.setString(4, prior.state().name()); update.setLong(5, prior.revision());
            if (update.executeUpdate() != 1) throw new IllegalStateException("D012 publication phase changed concurrently");
        }
        return new Entry(prior.intent(), next, prior.revision() + 1);
    }

    private void verifyRunBinding(String runId, String logicalTarget, String physicalTarget, String target) throws Exception {
        var run = com.zoutrankil.questdbwithdata.repository.SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        if (!"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1 || !logicalTarget.equals(run.targetId())
                || !logicalTarget.equals(frozen.path("parameters").path("targetId").asText())
                || !physicalTarget.equals(frozen.path("parameters").path("physicalTargetId").asText())
                || !StockStDailySyncJobOwner.DEFINITION.jobId().equals(frozen.path("definition").path("jobId").asText())
                || !target.startsWith(StockStDailyJobService.ISOLATED_TABLE_PREFIX))
            throw new IllegalStateException("D012 publication differs from frozen logical/physical target binding");
    }

    private void initialize() throws Exception {
        if (!Files.isRegularFile(ledgerPath)) throw new IllegalArgumentException("Existing D012 run ledger required");
        try (var db = open(); var statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS stk_st_daily_publication_mutex(singleton INTEGER PRIMARY KEY CHECK(singleton=1),run_id TEXT,publication_id TEXT)");
            statement.execute("INSERT OR IGNORE INTO stk_st_daily_publication_mutex(singleton,run_id,publication_id) VALUES(1,NULL,NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS stk_st_daily_publications(run_id TEXT PRIMARY KEY REFERENCES sync_runs(id),publication_id TEXT NOT NULL UNIQUE,intent_json TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,updated_at TEXT NOT NULL)");
        }
    }

    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + ledgerPath);
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000");
        }
        return db;
    }

    private void requireMutex(Entry entry) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id,publication_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !entry.intent().runId().equals(rows.getString(1))
                        || !entry.intent().id().equals(rows.getString(2)))
                    throw new IllegalStateException("D012 publication lock ownership differs from journal");
            }
        }
    }

    private void release(Entry entry) throws SQLException {
        try (var db = open(); var update = db.prepareStatement("UPDATE stk_st_daily_publication_mutex SET run_id=NULL,publication_id=NULL WHERE singleton=1 AND run_id=? AND publication_id=?")) {
            update.setString(1, entry.intent().runId()); update.setString(2, entry.intent().id());
            if (update.executeUpdate() != 1) throw new IllegalStateException("D012 publication lock ownership changed before release");
        }
    }

    private void releaseIfOwned(Entry entry) throws SQLException {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id,publication_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("D012 publication mutex row disappeared");
                String owner = rows.getString(1), id = rows.getString(2);
                if (owner == null && id == null) return;
                if (!entry.intent().runId().equals(owner) || !entry.intent().id().equals(id))
                    throw new IllegalStateException("D012 verified publication mutex belongs to a different run");
            }
        }
        release(entry);
    }

    private void retainUncertain(Entry entry, Exception original) {
        try {
            Entry latest = forRun(entry.intent().runId());
            if (latest.state() != State.IN_DOUBT && latest.state() != State.VERIFIED)
                advance(latest, State.IN_DOUBT);
            requireMutex(latest);
        } catch (Exception failure) { original.addSuppressed(failure); }
    }

    private StockStDailyStorage.Snapshot snapshotIfPresent(String table) throws Exception {
        var tables = jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table);
        if (tables.isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("D012 renamed table WAL did not settle; publication remains locked");
            Thread.sleep(50);
        }
        return new StockStDailyStorage(jdbc, table).snapshot();
    }

    private static boolean matches(StockStDailyStorage.Snapshot snapshot, StockStDailyStorage.Identity identity,
                                  StockStDailyStorage.Content content) {
        return snapshot != null && samePhysical(snapshot.identity(), identity)
                && StockStDailyStorage.sameContent(snapshot.content(), content);
    }
    private static boolean samePhysical(StockStDailyStorage.Identity left, StockStDailyStorage.Identity right) {
        return left.id() == right.id() && left.directory().equals(right.directory());
    }
    private void rename(String from, String to) {
        DatasetDefinition.identifier(from); DatasetDefinition.identifier(to);
        jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\"");
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D012 stage publication cancelled");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
