package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.L2DailyFeaturesWritePort;
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
import java.util.Comparator;
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

/** D086 owner for bounded manifest-certified daily feature Parquet synchronization. */
@Service
public final class L2DailyFeaturesJobService implements SyncJobOwner {
    public record Plan(FrozenRequest request, String targetId, LocalDate bootstrapFrom,
                       LocalDate verifiedThrough, int targetDatesChecked,
                       L2DailyFeaturesParquetSource.Inspection source) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(bootstrapFrom);
            Objects.requireNonNull(source);
            if (targetDatesChecked < 0) throw new IllegalArgumentException("Negative D086 target date count");
        }
    }

    public record Status(String runId, SyncRunState state, String targetId, String logicalDate,
                         int sourceRows, int verifiedRows, int verifiedSlices,
                         int unresolvedSlices, List<String> errorCodes) {
        public Status { errorCodes = List.copyOf(errorCodes); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_ID = "data.l2_daily_features";
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");

    private final ExchangeCalendarReadRepository calendars;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;
    private final Path ledgerPath;
    private final String targetTable;
    private final L2DailyFeaturesParquetSource source;

    @Autowired
    public L2DailyFeaturesJobService(ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc,
            @Lazy QuestDB questdb, QuestDbProperties questProperties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.l2-daily-features.target-table:}") String targetTable,
            @Value("${app.sync.l2-daily-features.dataset-root:D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset}") String datasetRoot,
            @Value("${app.sync.l2-daily-features.python-executable:D:/work/fund_2/back-monitor/.venv/Scripts/python.exe}") String pythonExecutable,
            @Value("${app.sync.l2-daily-features.reader-script:tools/read_l2_daily_features.py}") String readerScript) {
        this.calendars = Objects.requireNonNull(calendars);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(questProperties);
        this.ledgerPath = Path.of(ledgerPath).toAbsolutePath().normalize();
        this.targetTable = targetTable == null ? "" : targetTable.trim();
        this.source = new L2DailyFeaturesParquetSource(Path.of(datasetRoot), Path.of(readerScript), pythonExecutable);
    }

    @Override public String datasetId() { return L2DailyFeaturesDataset.DEFINITION.datasetId(); }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }

    public static SyncJobDefinition definition() {
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put("source_root_id", new Parameter(ParameterType.STRING, true, 64, 1, Set.of()));
        parameters.put("symbols", new Parameter(ParameterType.STRING_LIST, false, 16, 1000, Set.of()));
        return new SyncJobDefinition(JOB_ID, 1, L2DailyFeaturesDataset.DEFINITION.datasetId(), 1,
                "l2_daily_features_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE, Mode.INGEST), Mode.INCREMENTAL,
                parameters, "file.bounded", "l2_daily_features.parquet_date", "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(10)), Duration.ofHours(2),
                new Budget(31, 25_000, 25_000, 300_000, L2DailyFeaturesWritePort.MAX_BATCH_BYTES),
                3, List.of(new JobRef("data.l2_dataset_manifest", 1)), Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"), true, false);
    }

    public Plan plan(LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate,
            Mode requestedMode, List<String> requestedSymbols) throws Exception {
        Objects.requireNonNull(bootstrapFrom, "Explicit bounded D086 bootstrap date required");
        Objects.requireNonNull(requestedTo, "Explicit D086 end date required");
        Objects.requireNonNull(logicalDate, "D086 logical date required");
        if (requestedTo.isBefore(bootstrapFrom)
                || java.time.temporal.ChronoUnit.DAYS.between(bootstrapFrom, requestedTo) >= 31)
            throw new IllegalArgumentException("D086 request must be a nonempty window of at most 31 calendar days");
        var symbols = canonicalSymbols(requestedSymbols);
        Mode mode = requestedMode == null ? definition().defaultMode() : requestedMode;
        if (!definition().supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D086 sync mode");
        String target = targetId();
        var writer = port();
        writer.preflight();
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate previous = latestVerifiedThrough(ledger, target, source.sourceRootIdentity(), symbols);
        if (mode == Mode.INCREMENTAL && previous != null && previous.isAfter(requestedTo))
            throw new IllegalArgumentException("D086 checkpoint exceeds requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && previous != null) {
            LocalDate overlap = previous.minusDays(definition().revisionDays() - 1L);
            if (overlap.isAfter(from)) from = overlap;
        }
        if (from.isAfter(requestedTo)) throw new IllegalArgumentException("D086 incremental window is empty");

        var openDays = DailyTradingSessions.read(calendars, from, requestedTo);
        var inspection = source.inspect(from, requestedTo, symbols,
                definition().budget().maxRows(), definition().budget().maxSlices());
        if (!inspection.dates().equals(openDays))
            throw new IllegalStateException("D086 source partitions do not cover the registered SSE open sessions");

        var existingDates = writer.readExistingDates();
        LocalDate latestTargetDate = existingDates.stream().max(Comparator.naturalOrder()).orElse(null);
        if (latestTargetDate != null && (previous == null || latestTargetDate.isAfter(previous)))
            throw new IllegalStateException("D086 isolated target contains data beyond its verified ledger checkpoint");

        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_root_id", source.sourceRootIdentity());
        if (!symbols.isEmpty()) parameters.put("symbols", symbols);
        FrozenRequest request = definition().freeze(mode, parameters, from, requestedTo, logicalDate);
        return new Plan(request, target, bootstrapFrom, previous, existingDates.size(), inspection);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute(plan, null); }

    public SyncJobRunner.Result resume(Plan plan, String previousRunId) throws Exception {
        if (previousRunId == null || !previousRunId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Exact prior D086 run ID required");
        return execute(plan, previousRunId);
    }

    public void createIsolatedTarget(String table) {
        L2DailyFeaturesWritePort.requireIsolatedTableName(table);
        new L2DailyFeaturesWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }

    public String targetId() throws Exception {
        L2DailyFeaturesWritePort.requireIsolatedTableName(targetTable);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", targetTable);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D086 isolated QuestDB table identity required");
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
            throw new IllegalArgumentException("Run does not belong to D086");
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
        if (!JOB_ID.equals(run.jobId())) throw new IllegalArgumentException("Run does not belong to D086");
        return ledger.requestCancellation(runId);
    }

    private SyncJobRunner.Result execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        if (!plan.request().definition().equals(definition()) || !targetId().equals(plan.targetId()))
            throw new IllegalStateException("D086 definition or isolated target changed since planning");
        var parameters = plan.request().parameters();
        var symbols = canonicalSymbols((List<String>) parameters.getOrDefault("symbols", List.of()));
        var writer = port();
        var adapter = new L2DailyFeaturesSyncAdapter(source, plan.source(), symbols, definition(), writer);
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<com.zoutrankil.data.domain.L2DailyFeatures,
                com.zoutrankil.data.domain.L2DailyFeaturesKey>(ledger, new DatasetIntervalLock(ledgerPath));
        String runId = "d086-" + RUN_STAMP.format(java.time.Instant.now().atZone(ZoneId.of("UTC")))
                + "-" + UUID.randomUUID();
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (SQLException failure) { throw new IllegalStateException("Cannot read D086 cancellation state", failure); }
        };
        return previousRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, previousRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }

    private L2DailyFeaturesWritePort port() { return new L2DailyFeaturesWritePort(targetTable, jdbc, questdb); }

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
            if (++pages > 100) throw new IllegalStateException("D086 ledger exceeds its checkpoint scan bound");
        }
        return latest;
    }

    private static List<String> readSymbols(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("D086 frozen symbol filter is malformed");
        var result = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new IllegalStateException("D086 frozen symbol is not text");
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static List<String> canonicalSymbols(List<String> requested) {
        if (requested == null || requested.size() > 1000)
            throw new IllegalArgumentException("D086 symbol list exceeds 1000 entries");
        var symbols = requested.stream().map(value -> {
            if (value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                throw new IllegalArgumentException("Canonical six-digit D086 symbol required");
            return value;
        }).distinct().sorted().toList();
        if (symbols.size() != requested.size()) throw new IllegalArgumentException("Duplicate D086 symbol filter");
        return symbols;
    }
}
