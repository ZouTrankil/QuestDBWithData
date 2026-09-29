package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** D007 registered owner: bounded planning, checkpoint validation, manual run and run-history integration. */
@Service
public final class DailyJobService implements SyncJobOwner {
    static final String ISOLATED_TABLE_PREFIX = "java_d007_daily_";
    public record Plan(FrozenRequest request, String targetId, LocalDate verifiedThrough,
                       int checkedCheckpointSessions) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            if (checkedCheckpointSessions < 0) throw new IllegalArgumentException("Negative checkpoint session count");
        }
    }

    private final TusharePageService pages;
    private final ExchangeCalendarReadRepository calendars;
    private final StockDetailInfoReadRepository stockDetails;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public DailyJobService(TusharePageService pages, ExchangeCalendarReadRepository calendars,
            StockDetailInfoReadRepository stockDetails, JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties questProperties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.daily-table:daily}") String table) {
        this(pages, calendars, stockDetails, jdbc, questdb, questProperties, Path.of(ledgerPath), table);
    }

    public DailyJobService(TusharePageService pages, ExchangeCalendarReadRepository calendars,
            StockDetailInfoReadRepository stockDetails, JdbcTemplate jdbc, QuestDB questdb,
            QuestDbProperties questProperties, Path ledgerPath, String table) {
        this.pages = Objects.requireNonNull(pages);
        this.calendars = Objects.requireNonNull(calendars);
        this.stockDetails = Objects.requireNonNull(stockDetails);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(questProperties);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);
        this.table = table;
    }

    @Override public String datasetId() { return DailyDataset.DEFINITION.datasetId(); }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public String tableName() { return table; }

    public static SyncJobDefinition definition() { return DailySyncAdapter.definition(true); }

    /** `bootstrapFrom` is required on first use; subsequent ranges start from the verified overlap. */
    public Plan plan(LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate, Mode requestedMode)
            throws Exception {
        Objects.requireNonNull(bootstrapFrom, "Explicit bounded bootstrap date required");
        Objects.requireNonNull(logicalDate, "Logical date required");
        Mode mode = requestedMode == null ? definition().defaultMode() : requestedMode;
        LocalDate to = DailySyncEndDate.resolve(requestedTo, ZonedDateTime.now(DailySyncEndDate.ZONE));
        if (bootstrapFrom.isAfter(to)) throw new IllegalArgumentException("Daily sync window is empty after provider completion ceiling");
        // Validate the exact finite request before touching target metadata.
        var request = definition().freeze(mode, Map.of(), bootstrapFrom, to, logicalDate);
        String target = targetId();
        var writer = new DailyWritePort(table, jdbc, questdb);
        writer.preflight();
        LocalDate from = bootstrapFrom;
        LocalDate checkpoint = null;
        int checked = 0;
        if (mode == Mode.INCREMENTAL) {
            SyncRunLedger ledger = DailyCheckpoint.hasHistorySchema(ledgerPath)
                    ? SyncRunLedger.openReadOnly(ledgerPath) : null;
            var coverage = ledger == null ? null : DailyCheckpoint.load(ledger, target, bootstrapFrom);
            DailyCheckpoint.validateExistingTargetDates(ledger, coverage, bootstrapFrom, calendars, writer);
            if (coverage != null) {
                if (coverage.through().isAfter(to)) {
                    throw new IllegalArgumentException("Verified daily checkpoint is beyond requested end; use bounded RECONCILE");
                }
                checkpoint = coverage.through();
                from = checkpoint.minusDays(definition().revisionDays() - 1L);
                if (from.isBefore(bootstrapFrom)) from = bootstrapFrom;
                checked = DailyCheckpoint.validateOverlap(ledger, coverage, from, calendars, writer);
            }
        }
        request = definition().freeze(mode, Map.of(), from, to, logicalDate);
        // D001 exact date coverage is verified during preflight; it is never replaced by weekday arithmetic.
        DailyTradingSessions.read(calendars, from, to);
        return new Plan(request, target, checkpoint, checked);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("daily-" + UUID.randomUUID(), plan, null, null);
    }

    /** Resume uses the exact frozen request and prior run; callers must not re-plan its bounds. */
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved=FrozenRunRequest.restore(ledgerPath,priorRunId,definition());
        return resume(new Plan(saved.request(),saved.targetId(),null,0),priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        if (priorRunId == null || !priorRunId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Exact prior daily run ID required");
        return execute("daily-" + UUID.randomUUID(), plan, priorRunId, null);
    }

    public SyncJobRunner.Result runAsGroupChild(String childRun, String parentRun, String expectedTarget,
                                                FrozenRequest request) throws Exception {
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("Daily group target changed before execution");
        return execute(childRun, new Plan(request, expectedTarget, null, 0), null, parentRun);
    }

    public String targetId() throws Exception {
        requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null) {
            throw new IllegalStateException("Exact QuestDB physical identity required for daily");
        }
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + table + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() == ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D007 execution requires a dedicated java_d007_daily_<suffix> isolated target");
    }

    private SyncJobRunner.Result execute(String runId, Plan plan, String priorRunId, String parentRunId) throws Exception {
        if (!plan.request().definition().equals(definition()) || !plan.request().definition().datasetId().equals("daily"))
            throw new IllegalArgumentException("Frozen daily job definition required");
        if (!targetId().equals(plan.targetId())) throw new IllegalStateException("Daily target changed since planning");
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var port = new DailyWritePort(table, jdbc, questdb);
        var adapter = new DailySyncAdapter(pages, calendars, stockDetails, port, evidence);
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<DailyMarketBar, DailyMarketBar.Key>(ledger,
                new DatasetIntervalLock(ledgerPath));
        BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId)
                    || parentRunId != null && ledger.cancellationRequested(parentRunId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read daily cancellation state", failure); }
        };
        return priorRunId == null
                ? runner.run(runId, parentRunId, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, parentRunId == null ? priorRunId : parentRunId, priorRunId,
                        plan.targetId(), plan.request(), adapter, cancelled);
    }
}
