package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatureField;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatures;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesDataset;
import com.zoutrankil.questdbwithdata.domain.ReadGroupRequest;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.domain.WriteGroupRequest;
import com.zoutrankil.questdbwithdata.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.questdbwithdata.repository.ExchangeCalendarReadRepository;
import com.zoutrankil.questdbwithdata.repository.L2DatasetManifestReadRepository;
import com.zoutrankil.questdbwithdata.repository.L2IntradayBarFeaturesReadRepository;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import io.questdb.client.QuestDB;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in live D087 check; every write is confined to its explicit java_d087_* acceptance table. */
class L2IntradayBarFeaturesLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-09-21");
    private static final LocalDate BACKFILL_TO = LocalDate.parse("2026-09-23");
    private static final LocalDate INCREMENTAL_TO = LocalDate.parse("2026-09-24");
    private static final LocalDate LOGICAL_DATE = LocalDate.parse("2026-09-30");
    private static final String SYMBOL = "000001.SZ";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();

    @Test
    void certifiedParquetBackfillIsIdempotentAndIncrementalResumeMatchesEveryField() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D087_LIVE_ACCEPTANCE")),
                "Set D087_LIVE_ACCEPTANCE=true for the bounded local QuestDB and Parquet acceptance run");
        String username = requiredEnv("APP_QUESTDB_USERNAME");
        String password = requiredEnv("APP_QUESTDB_PASSWORD");
        String target = requiredEnv("APP_SYNC_L2INTRADAYBARFEATURES_TARGETTABLE");
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
        Path datasetRoot = Path.of(requiredEnv("APP_SYNC_L2INTRADAYBARFEATURES_DATASETROOT"));
        String python = requiredEnv("APP_SYNC_L2INTRADAYBARFEATURES_PYTHONEXECUTABLE");
        Path helper = Path.of(requiredEnv("APP_SYNC_L2INTRADAYBARFEATURES_READERSCRIPT"));
        Path evidenceRoot = Path.of("artifacts/java-migration/D087").toAbsolutePath().normalize();
        Files.createDirectories(evidenceRoot.resolve("commands"));
        Instant started = Instant.now();

        try (QuestDB questdb = QuestDB.connect(properties.qwpConfig())) {
            var service = new L2IntradayBarFeaturesJobService(calendars, jdbc, questdb, properties,
                    ledger.toString(), target, datasetRoot.toString(), python, helper.toString());
            service.createIsolatedTarget(target);
            var firstPlan = service.plan(FROM, BACKFILL_TO, LOGICAL_DATE, SyncJobDefinition.Mode.BACKFILL,
                    List.of(SYMBOL));
            assertTrue(firstPlan.source().selectedRows() > 0, "acceptance must include real source rows");
            assertEquals(List.of(FROM, FROM.plusDays(1), BACKFILL_TO), firstPlan.source().dates());

            var source = new L2IntradayBarFeaturesParquetSource(datasetRoot, helper, python);
            assertThrows(CancellationException.class, () -> source.stream(firstPlan.source(), List.of(SYMBOL),
                    L2IntradayBarFeaturesJobService.definition().budget().maxRows(),
                    L2IntradayBarFeaturesJobService.definition().budget().maxSlices(),
                    page -> fail("cancelled D087 stream emitted a page"), () -> true, Duration.ofMinutes(2)));
            var stale = firstPlan.source();
            var staleFingerprint = new L2IntradayBarFeaturesParquetSource.Inspection(stale.from(), stale.to(),
                    stale.dates(), stale.sourceRows(), stale.selectedRows(), stale.files(), stale.pages(),
                    stale.sourceBytes(), "0".repeat(64), stale.schemaFingerprint(), stale.parserVersion(),
                    stale.rootIdentity(), stale.completeForSelectedSymbols());
            IOException staleFailure = assertThrows(IOException.class, () -> source.stream(staleFingerprint,
                    List.of(SYMBOL), L2IntradayBarFeaturesJobService.definition().budget().maxRows(),
                    L2IntradayBarFeaturesJobService.definition().budget().maxSlices(),
                    page -> fail("stale D087 fingerprint emitted a page"), () -> false, Duration.ofMinutes(2)));
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
                    "replaying the bounded D087 backfill must be idempotent");

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
            assertTrue(resumed.reusedRows() > 0, "exact D087 resume must reuse verified slices");
            assertEquals(incrementalRows, readBack(reader, target, FROM, INCREMENTAL_TO));
            assertFalse(service.cancel(incrementalRun.runId()), "a verified run must not accept a late cancellation");

            Path independentCapture = evidenceRoot.resolve("commands/source-parquet-20260921-24.jsonl");
            captureIndependentSource(python, helper, datasetRoot, incrementalPlan.source(), independentCapture);
            var expectedRows = parseIndependentCapture(independentCapture);
            assertEquals(incrementalPlan.source().selectedRows(), expectedRows.size());
            var sortedTargetRows = sort(incrementalRows);
            assertEquals(sort(expectedRows), sortedTargetRows,
                    "all sixty D087 fields must match the independently captured Parquet rows");
            assertEquals(expectedRows.size() * L2IntradayBarFeatureField.values().length,
                    expectedRows.size() * 60, "field comparison count must cover the complete sixty-column contract");

            var readRepo = new L2IntradayBarFeaturesReadRepository(reader, target);
            var datasetRegistry = new DatasetRegistry(List.of(calendars,
                    new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"), readRepo));
            var d087Mapper = new L2IntradayBarFeaturesMapper();
            var readGroup = new ReadGroupReader(datasetRegistry, reader, List.of(
                    new ReadGroupReader.Binding<>(readRepo.definition(), L2IntradayBarFeatures.class,
                            d087Mapper::fromValues, () -> null)));
            Instant rangeFrom = FROM.atStartOfDay(EXCHANGE_ZONE).toInstant();
            Instant rangeTo = INCREMENTAL_TO.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
            var typedQuery = new DatasetReadQuery(L2IntradayBarFeaturesMapper.columns(), Map.of(), "minute",
                    rangeFrom, rangeTo, 200, null);
            var typedRead = readGroup.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "d087-typed-page", readRepo.definition().datasetId(), readRepo.definition().schemaVersion(),
                    typedQuery)), Duration.ofMinutes(1)), () -> false);
            assertTrue(typedRead.complete());
            assertEquals(L2IntradayBarFeatures.class, typedRead.require("d087-typed-page").rowType());
            assertEquals(200, typedRead.require("d087-typed-page").typedPage(L2IntradayBarFeatures.class).rows().size());

            var writeGroupEvidence = verifyWriteGroupIntegration(datasetRegistry, reader, jdbc, questdb,
                    service, target, ledger, expectedRows, incrementalRows, evidenceRoot);

            var registeredDatasets = new DatasetRegistry(List.of(calendars,
                    new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"), readRepo));
            var calendarDefinition = ExchangeCalendarSyncAdapter.definition(true);
            var manifestDefinition = L2DatasetManifestJobService.definition();
            var featureDefinition = L2IntradayBarFeaturesJobService.definition();
            var registeredJobs = new SyncJobRegistry(List.of(calendarDefinition, manifestDefinition, featureDefinition),
                    registeredDatasets, Map.of(calendarDefinition.datasetId(), calendarDefinition.supportedModes(),
                    manifestDefinition.datasetId(), manifestDefinition.supportedModes(),
                    featureDefinition.datasetId(), featureDefinition.supportedModes()),
                    new SyncJobRegistry.Policies(Set.of("file.bounded", "tushare.shared"),
                            Set.of("exchange_calendar.year", "l2_manifest.parquet_date",
                                    "l2_intraday_bar_features.parquet_date"), Set.of("questdb.full_key_values")));
            var dispatcher = new CommandLineRunner(null, registeredDatasets, registeredJobs, null, null, null,
                    null, null, null, null, null);
            var serviceField = CommandLineRunner.class.getDeclaredField("l2IntradayBarFeaturesService");
            serviceField.setAccessible(true);
            serviceField.set(dispatcher, service);
            String jobList = invoke(dispatcher, "list-sync-jobs");
            String datasetList = invoke(dispatcher, "show-dataset-definitions");
            String jobDetails = invoke(dispatcher, "show-sync-job", "--job=data.l2_intraday_bar_features", "--version=1");
            String preview = invoke(dispatcher, "plan-l2-intraday-bar-features-job", "--from=" + FROM,
                    "--to=" + INCREMENTAL_TO, "--logical-date=" + LOGICAL_DATE,
                    "--mode=backfill", "--symbols=" + SYMBOL);
            String runStatus = invoke(dispatcher, "l2-intraday-bar-features-job-status",
                    "--run=" + incrementalRun.runId());
            String runSlices = invoke(dispatcher, "show-sync-run", "--run=" + incrementalRun.runId(),
                    "--ledger=" + ledger, "--limit=100");
            boolean registered = jobList.contains("data.l2_intraday_bar_features")
                    && datasetList.contains("l2_intraday_bar_features");
            boolean management = jobDetails.contains("l2_intraday_bar_features_owner")
                    && preview.contains("PLANNED");
            boolean runVisibility = runStatus.contains("VERIFIED") && runSlices.contains("\"entries\"")
                    && runSlices.contains("VERIFIED");
            assertTrue(registered && management && runVisibility,
                    "D087 job, plan preview, status and slice evidence must be visible through management CLI");

            writeAcceptanceEvidence(target, service.targetId(), incrementalPlan, firstRun, repeatRun,
                    incrementalRun, resumed, expectedRows.size(), incrementalRows.size(),
                    writeGroupEvidence, true, true, registered, management, runVisibility, started,
                    evidenceRoot.resolve("live-acceptance-20260930.json"));
        }
    }

    private static Map<String, Object> verifyWriteGroupIntegration(DatasetRegistry datasets,
            QuestDbBoundedReader reader, JdbcTemplate jdbc, QuestDB questdb,
            L2IntradayBarFeaturesJobService owner, String target, Path ledger,
            List<L2IntradayBarFeatures> independentRows, List<L2IntradayBarFeatures> before,
            Path evidenceRoot) throws Exception {
        var dayRows = independentRows.stream().filter(row -> row.tradeDate().equals(FROM)).toList();
        assertTrue(dayRows.size() > 1);
        var service = new StockBasicWriteGroupService(datasets, null, jdbc, questdb, ledger.toString());
        var ownerField = StockBasicWriteGroupService.class.getDeclaredField("l2IntradayBarFeaturesTarget");
        ownerField.setAccessible(true);
        ownerField.set(service, owner);

        Path fullRequest = evidenceRoot.resolve("commands/write-group-certified-sample-20260921.json");
        writeGroupRequest(fullRequest, dayRows);
        var result = service.run(fullRequest, null);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(dayRows.size(), result.members().getFirst().verifiedRows());
        assertEquals(before, readBack(reader, target, FROM, INCREMENTAL_TO),
                "writing the same certified day through the write group must be idempotent");

        Path incompleteRequest = evidenceRoot.resolve("commands/write-group-incomplete-rejected-20260921.json");
        writeGroupRequest(incompleteRequest, dayRows.subList(0, dayRows.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> service.run(incompleteRequest, null),
                "the D087 write group must reject a prepared payload that omits certified source keys");
        assertEquals(before, readBack(reader, target, FROM, INCREMENTAL_TO),
                "rejected write group input must leave QuestDB unchanged");
        return Map.of("state", result.state(), "verifiedRows", result.members().getFirst().verifiedRows(),
                "omittedSourceKeyRejected", true, "targetUnchangedAfterRejection", true,
                "acceptedInput", evidenceRoot.relativize(fullRequest.toAbsolutePath().normalize()).toString(),
                "rejectedInput", evidenceRoot.relativize(incompleteRequest.toAbsolutePath().normalize()).toString());
    }

    private static void writeGroupRequest(Path path, List<L2IntradayBarFeatures> rows) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("batchId", "d087-sample-" + path.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]", "-"));
        root.put("logicalDate", LOGICAL_DATE.toString());
        ArrayNode members = root.putArray("members");
        ObjectNode member = members.addObject();
        member.put("memberId", "d087-certified-source-sample");
        member.put("datasetId", L2IntradayBarFeaturesDataset.DEFINITION.datasetId());
        member.put("definitionVersion", L2IntradayBarFeaturesDataset.DEFINITION.schemaVersion());
        member.put("batchId", "d087-day-20260921");
        ArrayNode inputRows = member.putArray("rows");
        var mapper = new L2IntradayBarFeaturesMapper();
        for (var row : rows) inputRows.add(JSON.valueToTree(mapper.values(row).asMap()));
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static void captureIndependentSource(String python, Path helper, Path root,
            L2IntradayBarFeaturesParquetSource.Inspection inspection, Path output) throws Exception {
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
            throw new java.util.concurrent.TimeoutException("Independent D087 Parquet capture exceeded three minutes");
        }
        if (process.exitValue() != 0)
            throw new IOException("Independent D087 Parquet capture failed; evidence retained at " + error);
    }

    private static List<L2IntradayBarFeatures> parseIndependentCapture(Path path) throws Exception {
        var mapper = new L2IntradayBarFeaturesMapper();
        var rows = new ArrayList<L2IntradayBarFeatures>();
        Set<com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesKey> keys = new HashSet<>();
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

    private static List<L2IntradayBarFeatures> readBack(QuestDbBoundedReader reader, String table,
            LocalDate from, LocalDate toInclusive) {
        var repository = new L2IntradayBarFeaturesReadRepository(reader, table);
        Instant start = from.atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant end = toInclusive.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        var result = new ArrayList<L2IntradayBarFeatures>();
        com.zoutrankil.questdbwithdata.domain.DatasetReadCursor cursor = null;
        do {
            var page = repository.findRange(start, end, 200, cursor);
            result.addAll(page.rows());
            cursor = page.nextCursor();
        } while (cursor != null);
        return sort(result);
    }

    private static List<L2IntradayBarFeatures> sort(List<L2IntradayBarFeatures> rows) {
        return rows.stream().sorted(Comparator.comparing(L2IntradayBarFeatures::symbol)
                .thenComparing(L2IntradayBarFeatures::minute)).toList();
    }

    private static String invoke(CommandLineRunner runner, String... args) throws Exception {
        PrintStream previous = System.out;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            runner.run(new DefaultApplicationArguments(args));
            return output.toString(StandardCharsets.UTF_8);
        } finally { System.setOut(previous); }
    }

    private static void assertVerified(SyncJobRunner.Result run, int expectedRows) {
        assertTrue(run.state() == SyncRunState.VERIFIED || run.state() == SyncRunState.VERIFIED_EMPTY,
                "D087 run failed: " + run.state() + " " + run.errorCode());
        assertEquals(expectedRows, run.sourceRows());
        assertEquals(expectedRows, run.verifiedRows());
    }

    private static void writeAcceptanceEvidence(String target, String targetId,
            L2IntradayBarFeaturesJobService.Plan plan, SyncJobRunner.Result backfill,
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
        output.put("pageRows", L2IntradayBarFeaturesParquetSource.PAGE_ROWS);
        output.put("fieldCount", L2IntradayBarFeatureField.values().length);
        output.put("independentFullFieldComparisons", Math.multiplyExact(sourceRows,
                L2IntradayBarFeatureField.values().length));
        output.put("sourceCancellationObserved", sourceCancellation);
        output.put("staleFingerprintRejected", staleRejected);
        output.put("datasetAndJobRegistered", registered);
        output.put("managementPlanPreviewAndJobDetails", management);
        output.put("managementRunStatusAndSlices", runVisibility);
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
