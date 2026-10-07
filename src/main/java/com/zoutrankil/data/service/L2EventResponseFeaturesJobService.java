package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.L2EventResponseFeaturesDataset;
import com.zoutrankil.data.domain.L2EventResponseFeatures;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.L2EventResponseFeaturesWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** D088 owner for bounded, manifest-certified event-response Parquet ingestion. */
@Service
public final class L2EventResponseFeaturesJobService implements SyncJobOwner {
    public record Plan(FrozenRequest request, String targetId, LocalDate bootstrapFrom,
                       LocalDate verifiedThrough, int targetDatesChecked,
                       L2EventResponseFeaturesParquetSource.Inspection source) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(bootstrapFrom);
            Objects.requireNonNull(source);
            if (targetDatesChecked < 0) throw new IllegalArgumentException("Negative D088 target date count");
        }
    }

    public record Status(String runId, SyncRunState state, String targetId, String logicalDate,
                         int sourceRows, int verifiedRows, int verifiedSlices,
                         int unresolvedSlices, List<String> errorCodes) {
        public Status { errorCodes = List.copyOf(errorCodes); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_ID = "data.l2_event_response_features";
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");

    private final ExchangeCalendarReadRepository calendars;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;
    private final Path ledgerPath;
    private final String targetTable;
    private final L2EventResponseFeaturesParquetSource source;

    @Autowired
    public L2EventResponseFeaturesJobService(ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc,
            @Lazy QuestDB questdb, QuestDbProperties questProperties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.l2-event-response-features.target-table:}") String targetTable,
            @Value("${app.sync.l2-event-response-features.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-event-response-features.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-event-response-features.reader-script:tools/read_l2_event_response_features.py}") String readerScript) {
        this.calendars = Objects.requireNonNull(calendars);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(questProperties);
        this.ledgerPath = Path.of(ledgerPath).toAbsolutePath().normalize();
        this.targetTable = targetTable == null ? "" : targetTable.trim();
        this.source = new L2EventResponseFeaturesParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }

    @Override public String datasetId() { return L2EventResponseFeaturesDataset.DEFINITION.datasetId(); }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }

    public static SyncJobDefinition definition() {
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put("source_root_id", new Parameter(ParameterType.STRING, true, 64, 1, Set.of()));
        parameters.put("symbols", new Parameter(ParameterType.STRING_LIST, true, 16, 100, Set.of()));
        return new SyncJobDefinition(JOB_ID, 1, L2EventResponseFeaturesDataset.DEFINITION.datasetId(), 1,
                "l2_event_response_features_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE, Mode.INGEST), Mode.INCREMENTAL,
                parameters, "file.bounded", "l2_event_response_features.parquet_date", "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(10)), Duration.ofHours(2),
                new Budget(31, 10_000, 25_000, 300_000, L2EventResponseFeaturesWritePort.MAX_BATCH_BYTES),
                3, List.of(new JobRef("data.l2_dataset_manifest", 1),
                        new JobRef("data.l2_intraday_bar_features", 1)), Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"), true, false);
    }

    public Plan plan(LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate,
            Mode requestedMode, List<String> requestedSymbols) throws Exception {
        Objects.requireNonNull(bootstrapFrom, "Explicit bounded D088 bootstrap date required");
        Objects.requireNonNull(requestedTo, "Explicit D088 end date required");
        Objects.requireNonNull(logicalDate, "D088 logical date required");
        if (requestedTo.isBefore(bootstrapFrom)
                || java.time.temporal.ChronoUnit.DAYS.between(bootstrapFrom, requestedTo) >= 31)
            throw new IllegalArgumentException("D088 request must be a nonempty window of at most 31 calendar days");
        var symbols = canonicalSymbols(requestedSymbols);
        Mode mode = requestedMode == null ? definition().defaultMode() : requestedMode;
        if (!definition().supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D088 sync mode");
        String target = targetId();
        var writer = port();
        writer.preflight();
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate previous = latestVerifiedThrough(ledger, target, source.sourceRootIdentity(), symbols);
        if (mode == Mode.INCREMENTAL && previous != null && previous.isAfter(requestedTo))
            throw new IllegalArgumentException("D088 checkpoint exceeds requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && previous != null) {
            LocalDate overlap = previous.minusDays(definition().revisionDays() - 1L);
            if (overlap.isAfter(from)) from = overlap;
        }
        if (from.isAfter(requestedTo)) throw new IllegalArgumentException("D088 incremental window is empty");

        var openDays = DailyTradingSessions.read(calendars, from, requestedTo);
        var inspection = source.inspect(from, requestedTo, symbols,
                definition().budget().maxRows(), definition().budget().maxSlices());
        if (!inspection.dates().equals(openDays))
            throw new IllegalStateException("D088 source partitions do not cover the registered SSE open sessions");

        int targetRows = writer.rowCount();
        LocalDate latestTargetDate = writer.readLatestTradeDate();
        if (mode != Mode.RECONCILE && latestTargetDate != null
                && (previous == null || latestTargetDate.isAfter(previous)))
            throw new IllegalStateException("D088 isolated target contains data beyond its verified ledger checkpoint");
        if ((targetRows == 0) != (latestTargetDate == null))
            throw new IllegalStateException("D088 target row count and timestamp frontier disagree");

        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_root_id", source.sourceRootIdentity());
        parameters.put("symbols", symbols);
        FrozenRequest request = definition().freeze(mode, parameters, from, requestedTo, logicalDate);
        return new Plan(request, target, bootstrapFrom, previous, targetRows, inspection);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute(plan, null); }

    public SyncJobRunner.Result resume(Plan plan, String previousRunId) throws Exception {
        if (previousRunId == null || !previousRunId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Exact prior D088 run ID required");
        return execute(plan, previousRunId);
    }

    public void createIsolatedTarget(String table) {
        L2EventResponseFeaturesWritePort.requireIsolatedTableName(table);
        new L2EventResponseFeaturesWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }

    public String isolatedTableName() {
        L2EventResponseFeaturesWritePort.requireIsolatedTableName(targetTable);
        return targetTable;
    }

    /**
     * Prepared write groups must contain the complete, manifest-certified source interval for
     * their selected symbols. This prevents an arbitrary row payload from bypassing D085 lineage.
     */
    public void verifyPreparedWriteRows(List<L2EventResponseFeatures> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > 10_000)
            throw new IllegalArgumentException("D088 prepared writes require 1..10000 source rows");
        var symbols = rows.stream().map(L2EventResponseFeatures::symbol).distinct().sorted().toList();
        if (symbols.size() > 100) throw new IllegalArgumentException("D088 prepared write exceeds its symbol bound");
        LocalDate from = rows.stream().map(L2EventResponseFeatures::tradeDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = rows.stream().map(L2EventResponseFeatures::tradeDate).max(LocalDate::compareTo).orElseThrow();
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("D088 prepared write exceeds its 31-day source bound");
        var expectedByKey = new LinkedHashMap<com.zoutrankil.data.domain.L2EventResponseFeaturesKey,
                L2EventResponseFeatures>();
        for (var row : rows) {
            if (expectedByKey.putIfAbsent(row.key(), row) != null)
                throw new IllegalArgumentException("D088 prepared write repeats a complete business key");
        }
        var openDays = DailyTradingSessions.read(calendars, from, to);
        var inspection = source.inspect(from, to, symbols, 10_000, 10_000);
        if (!inspection.dates().equals(openDays) || inspection.selectedRows() != rows.size())
            throw new IllegalArgumentException("D088 prepared write is not a complete certified trading-session interval");
        source.stream(inspection, symbols, 10_000, 10_000, page -> {
            for (var sourceRow : page.rows()) {
                var prepared = expectedByKey.remove(sourceRow.key());
                if (prepared == null || !prepared.equals(sourceRow))
                    throw new IllegalArgumentException("D088 prepared row differs from its manifest-certified Parquet value");
            }
        }, () -> false, definition().timeout());
        if (!expectedByKey.isEmpty())
            throw new IllegalArgumentException("D088 prepared write omits manifest-certified source keys");
    }

    public String targetId() throws Exception {
        L2EventResponseFeaturesWritePort.requireIsolatedTableName(targetTable);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", targetTable);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D088 isolated QuestDB table identity required");
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + targetTable + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    public Status status(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != definition().version())
            throw new IllegalArgumentException("Run does not belong to D088");
        var state = ledger.get(runId);
        int verifiedSlices = 0, unresolvedSlices = 0, sourceRows = 0, verifiedRows = 0;
        var errors = new HashSet<String>();
        String cursor = null;
        while (true) {
            var entries = ledger.entries(runId, cursor, 1000);
            if (entries.isEmpty()) break;
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() == SyncRunState.VERIFIED || entry.state() == SyncRunState.VERIFIED_EMPTY) {
                    verifiedSlices++;
                    JsonNode payload = JSON.readTree(entry.payloadJson());
                    int count = payload.path("verification").path("expectedRows").asInt(
                            payload.path("returnedRows").asInt(0));
                    sourceRows = Math.addExact(sourceRows, Math.max(0, count));
                    verifiedRows = Math.addExact(verifiedRows, Math.max(0, count));
                } else {
                    unresolvedSlices++;
                    JsonNode payload = JSON.readTree(entry.payloadJson());
                    String code = payload.path("errorCode").asText("");
                    if (!code.isBlank()) errors.add(code);
                }
            }
            cursor = entries.getLast().id();
        }
        return new Status(runId, state.state(), run.targetId(), run.logicalDate(), sourceRows, verifiedRows,
                verifiedSlices, unresolvedSlices, errors.stream().sorted().toList());
    }

    public boolean cancel(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId())) throw new IllegalArgumentException("Run does not belong to D088");
        return ledger.requestCancellation(runId);
    }

    private SyncJobRunner.Result execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        if (!plan.request().definition().equals(definition()) || !targetId().equals(plan.targetId()))
            throw new IllegalStateException("D088 definition or isolated target changed since planning");
        var parameters = plan.request().parameters();
        var symbols = canonicalSymbols((List<String>) parameters.getOrDefault("symbols", List.of()));
        var writer = port();
        var adapter = new L2EventResponseFeaturesSyncAdapter(source, plan.source(), symbols, definition(), writer);
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<com.zoutrankil.data.domain.L2EventResponseFeatures,
                com.zoutrankil.data.domain.L2EventResponseFeaturesKey>(ledger, new DatasetIntervalLock(ledgerPath));
        String runId = "d088-" + RUN_STAMP.format(java.time.Instant.now().atZone(ZoneId.of("UTC")))
                + "-" + UUID.randomUUID();
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (SQLException failure) { throw new IllegalStateException("Cannot read D088 cancellation state", failure); }
        };
        return previousRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, previousRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }

    private L2EventResponseFeaturesWritePort port() { return new L2EventResponseFeaturesWritePort(targetTable, jdbc, questdb); }

    private LocalDate latestVerifiedThrough(SyncRunLedger ledger, String target,
            String sourceRootId, List<String> symbols) throws Exception {
        LocalDate latest = null;
        String cursor = null;
        int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000);
            if (history.isEmpty()) break;
            for (var summary : history) {
                if (!target.equals(summary.targetId()) || summary.state() != SyncRunState.VERIFIED) continue;
                var run = ledger.getRun(summary.id());
                JsonNode parameters = JSON.readTree(run.frozenJson()).path("parameters");
                if (!sourceRootId.equals(parameters.path("source_root_id").asText(""))
                        || !symbols.equals(readSymbols(parameters.get("symbols")))) continue;
                String end = JSON.readTree(run.frozenJson()).path("to").asText("");
                if (!end.isBlank()) {
                    LocalDate day = LocalDate.parse(end);
                    if (latest == null || day.isAfter(latest)) latest = day;
                }
            }
            cursor = history.getLast().id();
            if (++pages > 100) throw new IllegalStateException("D088 ledger exceeds its checkpoint scan bound");
        }
        return latest;
    }

    private static List<String> readSymbols(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("D088 frozen symbol filter is malformed");
        var result = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new IllegalStateException("D088 frozen symbol is not text");
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static List<String> canonicalSymbols(List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100)
            throw new IllegalArgumentException("D088 requires 1..100 explicit stock symbols");
        var symbols = requested.stream().map(value -> {
            if (value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                throw new IllegalArgumentException("Canonical six-digit D088 symbol required");
            return value;
        }).distinct().sorted().toList();
        if (symbols.size() != requested.size()) throw new IllegalArgumentException("Duplicate D088 symbol filter");
        return symbols;
    }
}
