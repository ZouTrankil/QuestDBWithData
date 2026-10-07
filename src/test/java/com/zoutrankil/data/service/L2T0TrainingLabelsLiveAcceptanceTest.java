package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.L2T0TrainingLabelField;
import com.zoutrankil.data.domain.L2T0TrainingLabels;
import com.zoutrankil.data.domain.L2T0TrainingLabelsDataset;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.mapper.L2T0TrainingLabelsMapper;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.L2DatasetManifestReadRepository;
import com.zoutrankil.data.repository.L2IntradayBarFeaturesReadRepository;
import com.zoutrankil.data.repository.L2T0TrainingLabelsReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import io.questdb.client.QuestDB;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in live D089 check; every write is confined to its explicit java_d089_* acceptance table. */
class L2T0TrainingLabelsLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-09-21");
    private static final LocalDate BACKFILL_TO = LocalDate.parse("2026-09-23");
    private static final LocalDate INCREMENTAL_TO = LocalDate.parse("2026-09-24");
    private static final LocalDate LOGICAL_DATE = LocalDate.parse("2026-09-30");
    private static final String SYMBOL = "000001.SZ";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();

    @Test
    void certifiedParquetBackfillIsIdempotentAndIncrementalResumeMatchesEveryField() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D089_LIVE_ACCEPTANCE")),
                "Set D089_LIVE_ACCEPTANCE=true for the bounded local QuestDB and Parquet acceptance run");
        String username = requiredEnv("APP_QUESTDB_USERNAME");
        String password = requiredEnv("APP_QUESTDB_PASSWORD");
        String target = requiredEnv("APP_SYNC_L2T0TRAININGLABELS_TARGETTABLE");
        String database = System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb");
        QuestDbProperties properties = new QuestDbProperties();
        properties.setHost(requiredEnv("APP_QUESTDB_HOST"));
        properties.setPgPort(Integer.parseInt(requiredEnv("APP_QUESTDB_PGPORT")));
        properties.setQwpPort(Integer.parseInt(requiredEnv("APP_QUESTDB_QWPPORT")));
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setDatabase(database);

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + properties.getHost() + ":" + properties.getPgPort()
                + "/" + database + "?sslmode=disable");
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        var jdbc = new JdbcTemplate(dataSource);
        var reader = new QuestDbBoundedReader(jdbc);
        var calendars = new ExchangeCalendarReadRepository(reader);
        Path ledger = Path.of(requiredEnv("APP_SYNC_LEDGERPATH")).toAbsolutePath().normalize();
        Path datasetRoot = Path.of(requiredEnv("APP_SYNC_L2T0TRAININGLABELS_DATASETROOT"));
        String python = requiredEnv("APP_SYNC_L2T0TRAININGLABELS_PYTHONEXECUTABLE");
        Path helper = Path.of(requiredEnv("APP_SYNC_L2T0TRAININGLABELS_READERSCRIPT"));
        Path evidenceRoot = Path.of("artifacts/java-migration/D089").toAbsolutePath().normalize();
        Files.createDirectories(evidenceRoot.resolve("commands"));
        Instant started = Instant.now();

        try (QuestDB questdb = QuestDB.connect(properties.qwpConfig())) {
            var service = new L2T0TrainingLabelsJobService(calendars, jdbc, questdb, properties,
                    ledger.toString(), target, datasetRoot.toString(), python, helper.toString());
            service.createIsolatedTarget(target);
            var firstPlan = service.plan(FROM, BACKFILL_TO, LOGICAL_DATE, SyncJobDefinition.Mode.BACKFILL,
                    List.of(SYMBOL));
            assertTrue(firstPlan.source().selectedRows() > 0, "acceptance must include real source rows");
            assertEquals(List.of(FROM, FROM.plusDays(1), BACKFILL_TO), firstPlan.source().dates());

            var source = new L2T0TrainingLabelsParquetSource(datasetRoot, helper, python);
            assertThrows(CancellationException.class, () -> source.stream(firstPlan.source(), List.of(SYMBOL),
                    L2T0TrainingLabelsJobService.definition().budget().maxRows(),
                    L2T0TrainingLabelsJobService.definition().budget().maxSlices(),
                    page -> fail("cancelled D089 stream emitted a page"), () -> true, Duration.ofMinutes(2)));
            var stale = firstPlan.source();
            var staleFingerprint = new L2T0TrainingLabelsParquetSource.Inspection(stale.from(), stale.to(),
                    stale.dates(), stale.sourceRows(), stale.selectedRows(), stale.files(), stale.pages(),
                    stale.sourceBytes(), "0".repeat(64), stale.schemaFingerprint(), stale.parserVersion(),
                    stale.rootIdentity(), stale.completeForSelectedSymbols(), stale.outcomeCoverageByHorizon());
            IOException staleFailure = assertThrows(IOException.class, () -> source.stream(staleFingerprint,
                    List.of(SYMBOL), L2T0TrainingLabelsJobService.definition().budget().maxRows(),
                    L2T0TrainingLabelsJobService.definition().budget().maxSlices(),
                    page -> fail("stale D089 fingerprint emitted a page"), () -> false, Duration.ofMinutes(2)));
            assertTrue(staleFailure.getMessage().contains("reader failed"));

            var firstRun = service.run(firstPlan);
            assertVerified(firstRun, firstPlan.source().selectedRows());
            var firstRows = readBack(reader, target, FROM, BACKFILL_TO);
            assertEquals(firstPlan.source().selectedRows(), firstRows.size());

            var repeatPlan = service.plan(FROM, BACKFILL_TO, LOGICAL_DATE,
                    SyncJobDefinition.Mode.BACKFILL, List.of(SYMBOL));
            var repeatRun = service.run(repeatPlan);
            assertVerified(repeatRun, firstRows.size());
            assertEquals(firstRows, readBack(reader, target, FROM, BACKFILL_TO),
                    "replaying the bounded D089 backfill must be idempotent");

            var incrementalPlan = service.plan(FROM, INCREMENTAL_TO, LOGICAL_DATE,
                    SyncJobDefinition.Mode.INCREMENTAL, List.of(SYMBOL));
            assertEquals(BACKFILL_TO, incrementalPlan.verifiedThrough());
            assertEquals(FROM, incrementalPlan.request().from(), "the three calendar day revision window is replayed");
            assertEquals(INCREMENTAL_TO, incrementalPlan.request().to());
            assertEquals(4, incrementalPlan.source().dates().size());
            var incrementalRun = service.run(incrementalPlan);
            assertVerified(incrementalRun, incrementalPlan.source().selectedRows());
            var incrementalRows = readBack(reader, target, FROM, INCREMENTAL_TO);
            assertEquals(incrementalPlan.source().selectedRows(), incrementalRows.size());
            assertEquals(SyncRunState.VERIFIED, service.status(incrementalRun.runId()).state());

            var resumed = service.resume(incrementalPlan, incrementalRun.runId());
            assertVerified(resumed, incrementalPlan.source().selectedRows());
            assertTrue(resumed.reusedRows() > 0, "exact D089 resume must reuse verified slices");
            assertEquals(incrementalRows, readBack(reader, target, FROM, INCREMENTAL_TO));
            assertFalse(service.cancel(incrementalRun.runId()), "a verified run must not accept a late cancellation");

            Path independentCapture = evidenceRoot.resolve("commands/source-parquet-20260921-24.jsonl");
            captureIndependentSource(python, helper, datasetRoot, incrementalPlan.source(), independentCapture);
            var expectedRows = parseIndependentCapture(independentCapture);
            assertEquals(incrementalPlan.source().selectedRows(), expectedRows.size());
            var sortedTargetRows = sort(incrementalRows);
            assertEquals(sort(expectedRows), sortedTargetRows,
                    "all 62 D089 fields must match the independently captured Parquet rows");
            assertEquals(expectedRows.size() * L2T0TrainingLabelField.values().length,
                    expectedRows.size() * 62, "field comparison count must cover the complete 62-column contract");

            var readRepo = new L2T0TrainingLabelsReadRepository(reader, target);
            var datasetRegistry = new DatasetRegistry(List.of(calendars,
                    new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"), new L2IntradayBarFeaturesReadRepository(reader, "l2_intraday_bar_features"), readRepo));
            var d089Mapper = new L2T0TrainingLabelsMapper();
            var readGroup = new ReadGroupReader(datasetRegistry, reader, List.of(
                    new ReadGroupReader.Binding<>(readRepo.definition(), L2T0TrainingLabels.class,
                            d089Mapper::fromValues, () -> null)));
            Instant rangeFrom = FROM.atStartOfDay(EXCHANGE_ZONE).toInstant();
            Instant rangeTo = INCREMENTAL_TO.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
            var typedQuery = new DatasetReadQuery(L2T0TrainingLabelsMapper.columns(), Map.of(), "minute",
                    rangeFrom, rangeTo, 200, null);
            var typedRead = readGroup.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "d089-typed-page", readRepo.definition().datasetId(), readRepo.definition().schemaVersion(),
                    typedQuery)), Duration.ofMinutes(1)), () -> false);
            assertTrue(typedRead.complete());
            assertEquals(L2T0TrainingLabels.class, typedRead.require("d089-typed-page").rowType());
            assertEquals(200, typedRead.require("d089-typed-page").typedPage(L2T0TrainingLabels.class).rows().size());

            var manifestReadRepo = new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest");
            var barReadRepo = new L2IntradayBarFeaturesReadRepository(reader, "l2_intraday_bar_features");
            var registeredDatasets = new DatasetRegistry(List.of(calendars, manifestReadRepo, barReadRepo, readRepo));
            var calendarDefinition = ExchangeCalendarSyncAdapter.definition(true);
            var manifestDefinition = L2DatasetManifestJobService.definition();
            var barDefinition = L2IntradayBarFeaturesJobService.definition();
            var featureDefinition = L2T0TrainingLabelsJobService.definition();
            var registeredJobs = new SyncJobRegistry(List.of(calendarDefinition, manifestDefinition, barDefinition, featureDefinition),
                    registeredDatasets, Map.of(calendarDefinition.datasetId(), calendarDefinition.supportedModes(),
                    manifestDefinition.datasetId(), manifestDefinition.supportedModes(),
                    barDefinition.datasetId(), barDefinition.supportedModes(),
                    featureDefinition.datasetId(), featureDefinition.supportedModes()),
                    new SyncJobRegistry.Policies(Set.of("file.bounded", "tushare.shared"),
                            Set.of("exchange_calendar.year", "l2_manifest.parquet_date",
                                    "l2_intraday_bar_features.parquet_date", "l2_t0_training_labels.parquet_date"),
                            Set.of("questdb.full_key_values")));
            assertEquals("l2_t0_training_labels", registeredDatasets.require("l2_t0_training_labels")
                    .definition().datasetId());
            assertEquals("data.l2_t0_training_labels", registeredJobs.require("data.l2_t0_training_labels", 1).jobId());
            var registryPreview = registeredJobs.prepare("data.l2_t0_training_labels", 1,
                    SyncJobDefinition.Mode.BACKFILL, incrementalPlan.request().parameters(),
                    FROM, INCREMENTAL_TO, LOGICAL_DATE);
            assertEquals(INCREMENTAL_TO, registryPreview.to());
            assertEquals(SyncRunState.VERIFIED, service.status(incrementalRun.runId()).state());
            var visibleStatus = service.status(incrementalRun.runId());
            assertTrue(visibleStatus.verifiedSlices() > 0,
                    "verified run slices must be visible through the D089 job owner");

            writeAcceptanceEvidence(target, service.targetId(), incrementalPlan, firstRun, repeatRun,
                    incrementalRun, resumed, expectedRows.size(), incrementalRows.size(),
                    Map.of("typedWriterPort", "VERIFIED", "writeGroupSourceCertification", "registered; runtime path statically reviewed"),
                    true, true, true, true, true, started,
                    evidenceRoot.resolve("live-acceptance-20260930.json"));
        }
    }

    private static void captureIndependentSource(String python, Path helper, Path root,
            L2T0TrainingLabelsParquetSource.Inspection inspection, Path output) throws Exception {
        Path error = output.resolveSibling("source-parquet-20260921-24.stderr.txt");
        var command = new ArrayList<String>(List.of(Path.of(python).toAbsolutePath().normalize().toString(),
                helper.toAbsolutePath().normalize().toString(), "--dataset-root", root.toAbsolutePath().normalize().toString(),
                "--from-date", "20260921", "--to-date", "20260924", "--page-rows", "200",
                "--max-files", "10000", "--max-rows", "300000", "--max-bytes", "67108864",
                "--max-output-bytes", "67108864", "--symbol", SYMBOL, "--stream",
                "--expected-fingerprint", inspection.sourceFingerprint()));
        var processBuilder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile());
        processBuilder.environment().keySet().removeIf(key -> key.startsWith("QUESTDB_")
                || key.startsWith("APP_QUESTDB_") || key.contains("TUSHARE"));
        Process process = processBuilder.start();
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new java.util.concurrent.TimeoutException("Independent D089 Parquet capture exceeded three minutes");
        }
        if (process.exitValue() != 0)
            throw new IOException("Independent D089 Parquet capture failed; evidence retained at " + error);
    }

    private static List<L2T0TrainingLabels> parseIndependentCapture(Path path) throws Exception {
        var mapper = new L2T0TrainingLabelsMapper();
        var rows = new ArrayList<L2T0TrainingLabels>();
        Set<com.zoutrankil.data.domain.L2T0TrainingLabelsKey> keys = new HashSet<>();
        int completions = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            JsonNode record = JSON.readTree(line);
            if ("completion".equals(record.path("kind").asText())) {
                assertTrue(record.path("complete").asBoolean());
                assertTrue(record.path("completeForSelectedSymbols").asBoolean());
                completions++;
            }
            if (!"page".equals(record.path("kind").asText())) continue;
            LocalDate date = LocalDate.parse(record.path("responseEvidence").path("date").asText(),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            for (JsonNode row : record.path("rows")) {
                var typed = mapper.fromParquet(row, date);
                assertTrue(keys.add(typed.key()), "independent source capture repeated a business key");
                rows.add(typed);
            }
        }
        assertEquals(1, completions, "independent source capture needs exactly one completion record");
        return sort(rows);
    }

    private static List<L2T0TrainingLabels> readBack(QuestDbBoundedReader reader, String table,
            LocalDate from, LocalDate toInclusive) {
        var repository = new L2T0TrainingLabelsReadRepository(reader, table);
        Instant start = from.atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant end = toInclusive.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        var result = new ArrayList<L2T0TrainingLabels>();
        com.zoutrankil.data.domain.DatasetReadCursor cursor = null;
        do {
            var page = repository.findRange(start, end, 200, cursor);
            result.addAll(page.rows());
            cursor = page.nextCursor();
        } while (cursor != null);
        return sort(result);
    }

    private static List<L2T0TrainingLabels> sort(List<L2T0TrainingLabels> rows) {
        return rows.stream().sorted(Comparator.comparing(L2T0TrainingLabels::symbol)
                .thenComparing(L2T0TrainingLabels::minute)).toList();
    }

    private static void assertVerified(SyncJobRunner.Result run, int expectedRows) {
        assertTrue(run.state() == SyncRunState.VERIFIED || run.state() == SyncRunState.VERIFIED_EMPTY,
                "D089 run failed: " + run.state() + " " + run.errorCode());
        assertEquals(expectedRows, run.sourceRows());
        assertEquals(expectedRows, run.verifiedRows());
    }

    private static void writeAcceptanceEvidence(String target, String targetId,
            L2T0TrainingLabelsJobService.Plan plan, SyncJobRunner.Result backfill,
            SyncJobRunner.Result repeated, SyncJobRunner.Result incremental,
            SyncJobRunner.Result resumed, int sourceRows, int readbackRows, Map<String,Object> writeGroup,
            boolean sourceCancellation, boolean staleRejected, boolean registered,
            boolean management, boolean runVisibility, Instant started, Path path) throws Exception {
        var output = new LinkedHashMap<String,Object>();
        output.put("status", "VERIFIED");
        output.put("humanReview", "pending_review");
        output.put("targetTable", target);
        output.put("targetId", targetId);
        output.put("sourceRootIdentity", plan.source().rootIdentity());
        output.put("sourceFingerprint", plan.source().sourceFingerprint());
        output.put("schemaFingerprint", plan.source().schemaFingerprint());
        output.put("sourceWindow", List.of(FROM.toString(), INCREMENTAL_TO.toString()));
        output.put("sourceSelectedRows", sourceRows);
        output.put("manifestSourceRows", plan.source().sourceRows());
        output.put("sourceFiles", plan.source().files());
        output.put("sourceBytes", plan.source().sourceBytes());
        output.put("boundedPages", plan.source().pages());
        output.put("pageRows", L2T0TrainingLabelsParquetSource.PAGE_ROWS);
        output.put("fieldCount", L2T0TrainingLabelField.values().length);
        output.put("outcomeCoverageByHorizon", plan.source().outcomeCoverageByHorizon());
        output.put("missingOutcomeLabelsAreNotNegativeOutcome", true);
        output.put("independentFullFieldComparisons", Math.multiplyExact(sourceRows,
                L2T0TrainingLabelField.values().length));
        output.put("sourceCancellationObserved", sourceCancellation);
        output.put("staleFingerprintRejected", staleRejected);
        output.put("datasetAndJobRegistered", registered);
        output.put("registryPlanPreparation", management);
        output.put("jobOwnerRunStatusAndSlices", runVisibility);
        output.put("targetReadbackRows", readbackRows);
        output.put("targetReadbackFullFieldEquality", true);
        output.put("writeGroup", writeGroup);
        output.put("retryPolicy", "maxAttempts=1; deterministic local Parquet errors remain visible for explicit reconciliation");
        output.put("rateLimit", "not applicable: local manifest/Parquet source; process, page, file, row and byte budgets are finite");
        output.put("runEvidence", Map.of("backfill", runEvidence(backfill), "idempotentBackfill", runEvidence(repeated),
                "incremental", runEvidence(incremental), "resume", runEvidence(resumed)));
        output.put("checkpoint", Map.of("beforeIncremental", plan.verifiedThrough() == null
                        ? BACKFILL_TO.toString() : plan.verifiedThrough().toString(),
                "replayedFrom", plan.request().from().toString(), "through", plan.request().to().toString(),
                "revisionOverlapCalendarDays", 3));
        output.put("startedAt", started.toString());
        output.put("finishedAt", Instant.now().toString());
        output.put("evidence", List.of("commands/preflight-source-20260930.json",
                "commands/source-parquet-20260921-24.jsonl", "live-acceptance-20260930.json"));
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output)
                + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Map<String,Object> runEvidence(SyncJobRunner.Result run) {
        return Map.of("runId", run.runId(), "state", run.state(), "sourceRows", run.sourceRows(),
                "verifiedRows", run.verifiedRows(), "reusedRows", run.reusedRows());
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing acceptance setting " + name);
        return value;
    }
}


