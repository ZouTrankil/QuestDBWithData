package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** D008 owner: explicit finite windows, verified checkpoints and one shared sync runner. */
@Service
public final class DailyBasicJobService implements SyncJobOwner {
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, LocalDate checkpointBefore) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
        }
    }
    record TargetRange(LocalDate min, LocalDate max) {
        TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalArgumentException("Invalid daily_basic QuestDB target range");
        }
        boolean empty() { return min == null; }
    }

    private final TusharePageService pages;
    private final DailyBasicTradingDates tradingDates;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties properties;
    private final Path ledgerPath;
    private final String table;

    @org.springframework.beans.factory.annotation.Autowired
    public DailyBasicJobService(TusharePageService pages, ExchangeCalendarReadRepository calendar,
            org.springframework.jdbc.core.JdbcTemplate jdbc, @Lazy QuestDB questdb,
            QuestDbProperties properties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.daily-basic-table:daily_basic}") String table) {
        this(pages, calendar, jdbc, questdb, properties, Path.of(ledgerPath), table);
    }

    DailyBasicJobService(TusharePageService pages, ExchangeCalendarReadRepository calendar,
            org.springframework.jdbc.core.JdbcTemplate jdbc, QuestDB questdb,
            QuestDbProperties properties, Path ledgerPath, String table) {
        this.pages = Objects.requireNonNull(pages); this.tradingDates = new DailyBasicTradingDates(calendar);
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        this.properties = Objects.requireNonNull(properties); this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        DatasetDefinition.identifier(table); this.table = table;
    }

    @Override public String datasetId() { return "daily_basic"; }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public static SyncJobDefinition definition() { return DailyBasicSyncAdapter.definition(true); }
    public String tableName() { return table; }

    public String targetId() throws Exception {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact daily_basic QuestDB target identity required");
        String identity = properties.getHost() + ":" + properties.getPgPort() + ":" + properties.getQwpPort()
                + ":" + properties.getDatabase() + ":" + table + ":" + rows.getFirst().get("id")
                + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    /** Incremental starts from a verified contiguous run; the first bounded bootstrap must name its start. */
    public Plan plan(LocalDate bootstrapFrom, LocalDate end, LocalDate logicalDate, Mode requestedMode) throws Exception {
        end = resolveEnd(end, logicalDate);
        var job = definition();
        Mode mode = requestedMode == null ? job.defaultMode() : requestedMode;
        if (!job.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported daily_basic mode");
        if (bootstrapFrom != null && (bootstrapFrom.isAfter(end)
                || java.time.temporal.ChronoUnit.DAYS.between(bootstrapFrom, end) >= job.budget().maxWindowDays()))
            throw new IllegalArgumentException("Requested daily_basic window exceeds the bounded job budget");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && bootstrapFrom == null)
            throw new IllegalArgumentException("Explicit bounded from date required");
        // Validate the frozen request shape and caller bounds before any target lookup.
        job.freeze(mode, Map.of("trade_dates", "NONE"),
                bootstrapFrom == null ? end : bootstrapFrom, end, logicalDate);
        String target = targetId();
        var port = new DailyBasicWritePort(table, jdbc, questdb);
        port.preflight();
        TargetRange targetRange = readTargetRange();
        LocalDate from;
        Optional<DailyBasicCoverage.Coverage> checkpoint = Optional.empty();
        LocalDate checkpointAnchor = null;
        if (mode == Mode.INCREMENTAL) {
            checkpoint = DailyBasicCoverage.checkpoint(ledgerPath, target);
            if (checkpoint.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap start required before a verified checkpoint exists");
                from = bootstrapFrom;
                checkpointAnchor = bootstrapFrom;
            } else {
                DailyBasicCoverage.Coverage coverage = checkpoint.get();
                LocalDate verifiedThrough = coverage.through();
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("Bootstrap start applies only before a verified checkpoint; use bounded BACKFILL for an older range");
                if (verifiedThrough.isAfter(end)) throw new IllegalArgumentException("Checkpoint is after request end; use bounded RECONCILE");
                from = verifiedThrough.minusDays(job.revisionDays());
                checkpointAnchor = coverage.anchor();
            }
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            if (bootstrapFrom == null) throw new IllegalArgumentException("Explicit bounded from date required");
            from = bootstrapFrom;
        } else {
            throw new IllegalArgumentException("daily_basic supports only bounded incremental, backfill or reconcile");
        }
        if (from.isAfter(end)
                || java.time.temporal.ChronoUnit.DAYS.between(from, end) >= job.budget().maxWindowDays())
            throw new IllegalArgumentException("Resolved daily_basic window exceeds the bounded job budget");
        if (mode == Mode.INCREMENTAL) validateTargetRange(targetRange, checkpoint.orElse(null), bootstrapFrom, end, logicalDate);
        var tradeDates = tradingDates.read(from, end);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("trade_dates", DailyBasicSyncAdapter.encodeTradeDates(tradeDates));
        if (mode == Mode.INCREMENTAL) parameters.put("checkpointAnchor", checkpointAnchor);
        var request = job.freeze(mode, parameters,
                from, end, logicalDate);
        return new Plan(request, target, checkpoint.map(DailyBasicCoverage.Coverage::through).orElse(null));
    }

    static LocalDate resolveEnd(LocalDate end, LocalDate logicalDate) {
        Objects.requireNonNull(logicalDate, "daily_basic logical date required");
        LocalDate resolved = end == null ? logicalDate : end;
        if (resolved.isAfter(logicalDate))
            throw new IllegalArgumentException("daily_basic window cannot exceed its frozen logical date");
        return resolved;
    }

    static void validateTargetRange(TargetRange targetRange, DailyBasicCoverage.Coverage coverage,
            LocalDate bootstrapFrom, LocalDate requestTo, LocalDate logicalDate) {
        if (targetRange.empty()) {
            if (coverage != null && coverage.includesRows())
                throw new IllegalStateException("Previously verified daily_basic rows are absent from the QuestDB target");
            return;
        }
        if (coverage == null)
            throw new IllegalStateException("Nonempty daily_basic target has no verified incremental coverage; reconcile it before bootstrap");
        if (targetRange.max().isAfter(logicalDate))
            throw new IllegalStateException("daily_basic target contains a date after logicalDate");
        if (!coverage.includesRows() || coverage.firstRowDate() == null || coverage.lastRowDate() == null)
            throw new IllegalStateException("Nonempty daily_basic target has no verified source rows in its incremental coverage");
        // Exact receipt-backed endpoints detect both truncated verified history and ledgerless physical rows.
        if (!targetRange.min().equals(coverage.firstRowDate()) || !targetRange.max().equals(coverage.lastRowDate()))
            throw new IllegalStateException("QuestDB daily_basic physical range differs from receipt-backed verified row dates");
        if (targetRange.min().isBefore(coverage.from()) || targetRange.max().isAfter(coverage.through())
                || targetRange.max().isAfter(requestTo))
            throw new IllegalStateException("QuestDB daily_basic physical range is outside verified/request coverage");
    }

    private TargetRange readTargetRange() {
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return the daily_basic date range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB daily_basic date range is not a timestamp epoch");
            var minDate = com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var maxDate = com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return new TargetRange(minDate, maxDate);
        });
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("daily-basic-" + UUID.randomUUID(), null, plan.targetId(), plan.request(), null);
    }
    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("daily-basic-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId),
                plan.targetId(), plan.request(), null);
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved=FrozenRunRequest.restore(ledgerPath,priorRunId,definition());
        return resume(new Plan(saved.request(),saved.targetId(),null),priorRunId);
    }
    public SyncJobRunner.Result runAsGroupChild(String runId, String parentRunId, String priorRunId,
            String expectedTargetId, SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!targetId().equals(expectedTargetId)) throw new IllegalStateException("daily_basic group target changed before child run");
        return execute(runId, priorRunId, expectedTargetId, request, parentRunId);
    }

    public String revalidateGroupChild(String priorChild, String expectedTarget,
            SyncJobDefinition.FrozenRequest request, String parentGroupRunId) throws Exception {
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("daily_basic group target identity changed");
        var ledger = new SyncRunLedger(ledgerPath);
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve("daily-basic-recheck-" + UUID.randomUUID());
        var adapter = adapter(evidence);
        return VerifiedRunRecovery.revalidate(ledger, priorChild, expectedTarget, request, adapter,
                () -> {
                    if (Thread.currentThread().isInterrupted()) return true;
                    try { return ledger.cancellationRequested(parentGroupRunId); }
                    catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read group cancellation", failure); }
                }, evidence);
    }

    private SyncJobRunner.Result execute(String runId, String priorRunId, String expectedTarget,
            SyncJobDefinition.FrozenRequest request, String parentRunId) throws Exception {
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("daily_basic target changed since planning");
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<DailyBasic, DailyBasicKey>(ledger, new DatasetIntervalLock(ledgerPath));
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = adapter(evidence);
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            if (parentRunId == null) return false;
            try { return ledger.cancellationRequested(parentRunId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read daily_basic parent cancellation", failure); }
        };
        return priorRunId == null ? runner.run(runId, parentRunId, expectedTarget, request, adapter, cancelled)
                : runner.resume(runId, parentRunId == null ? priorRunId : parentRunId, priorRunId,
                        expectedTarget, request, adapter, cancelled);
    }

    private DailyBasicSyncAdapter adapter(Path evidence) {
        return new DailyBasicSyncAdapter(new DailyBasicSource(pages, new com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper(), evidence),
                tradingDates, new DailyBasicWritePort(table, jdbc, questdb), evidence);
    }
}
