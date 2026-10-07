package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.L2DatasetManifestReadRepository;
import com.zoutrankil.data.repository.L2DailyFeaturesReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import io.questdb.client.QuestDB;
import com.zoutrankil.data.cli.CommandLineRunner;
import com.zoutrankil.data.cli.CatalogCommands;
import com.zoutrankil.data.cli.CliCommandRegistry;
import com.zoutrankil.data.cli.L2Commands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in live D086 check; writes only to its explicitly named java_d086_* table. */
class L2DailyFeaturesLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-09-21");
    private static final LocalDate BACKFILL_TO = LocalDate.parse("2026-09-23");
    private static final LocalDate INCREMENTAL_TO = LocalDate.parse("2026-09-24");
    private static final LocalDate LOGICAL_DATE = LocalDate.parse("2026-09-30");
    private static final List<String> SYMBOLS = List.of("000001.SZ");

    @Test
    void backfillIsIdempotentAndIncrementalReplaysTheRevisionWindow() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D086_LIVE_ACCEPTANCE")),
                "Set D086_LIVE_ACCEPTANCE=true to run against local QuestDB and the source Parquet store");

        String username = requiredEnv("APP_QUESTDB_USERNAME");
        String password = requiredEnv("APP_QUESTDB_PASSWORD");
        String target = requiredEnv("APP_SYNC_L2DAILYFEATURES_TARGETTABLE");
        QuestDbProperties properties = new QuestDbProperties();
        properties.setHost(requiredEnv("APP_QUESTDB_HOST"));
        properties.setPgPort(Integer.parseInt(requiredEnv("APP_QUESTDB_PGPORT")));
        properties.setQwpPort(Integer.parseInt(requiredEnv("APP_QUESTDB_QWPPORT")));
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setDatabase("qdb");

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + properties.getHost() + ":" + properties.getPgPort()
                + "/" + properties.getDatabase());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        var jdbc = new JdbcTemplate(dataSource);
        var reader = new QuestDbBoundedReader(jdbc);
        var calendars = new ExchangeCalendarReadRepository(reader);

        var ledger = Path.of(requiredEnv("APP_SYNC_LEDGERPATH")).toAbsolutePath().normalize();
        var dataRoot = Path.of(requiredEnv("APP_SYNC_L2DAILYFEATURES_DATASETROOT"));
        var python = requiredEnv("APP_SYNC_L2DAILYFEATURES_PYTHONEXECUTABLE");
        var helper = Path.of(requiredEnv("APP_SYNC_L2DAILYFEATURES_READERSCRIPT"));

        try (QuestDB questdb = QuestDB.connect(properties.qwpConfig())) {
            var service = new L2DailyFeaturesJobService(calendars, jdbc, questdb, properties, ledger.toString(),
                    target, dataRoot.toString(), python, helper.toString());
            service.createIsolatedTarget(target);

            var firstPlan = service.plan(FROM, BACKFILL_TO, LOGICAL_DATE, Mode.BACKFILL, SYMBOLS);
            assertEquals(3, firstPlan.source().selectedRows());
            assertEquals(List.of(FROM, FROM.plusDays(1), BACKFILL_TO), firstPlan.source().dates());
            var source = new L2DailyFeaturesParquetSource(dataRoot, helper, python);
            assertThrows(CancellationException.class, () -> source.stream(firstPlan.source(), SYMBOLS,
                    L2DailyFeaturesJobService.definition().budget().maxRows(),
                    L2DailyFeaturesJobService.definition().budget().maxSlices(), page -> fail("cancelled source emitted a page"),
                    () -> true, Duration.ofMinutes(2)));
            var stale = firstPlan.source();
            var staleFingerprint = new L2DailyFeaturesParquetSource.Inspection(stale.from(), stale.to(), stale.dates(),
                    stale.sourceRows(), stale.selectedRows(), stale.files(), stale.pages(), stale.sourceBytes(),
                    "0".repeat(64), stale.schemaFingerprint(), stale.parserVersion(), stale.rootIdentity(),
                    stale.completeForSelectedSymbols());
            IOException sourceFailure = assertThrows(IOException.class, () -> source.stream(staleFingerprint, SYMBOLS,
                    L2DailyFeaturesJobService.definition().budget().maxRows(),
                    L2DailyFeaturesJobService.definition().budget().maxSlices(), page -> fail("stale source emitted a page"),
                    () -> false, Duration.ofMinutes(2)));
            assertTrue(sourceFailure.getMessage().contains("reader failed"));

            var firstRun = service.run(firstPlan);
            assertVerified(firstRun.state(), firstRun.sourceRows(), firstRun.verifiedRows(), 3);
            var firstRows = readBack(reader, target);
            assertEquals(3, firstRows.size());
            assertTrue(firstRows.stream().allMatch(row -> row.features().size() == L2DailyFeatureField.values().length - 2));

            var repeatPlan = service.plan(FROM, BACKFILL_TO, LOGICAL_DATE, Mode.BACKFILL, SYMBOLS);
            var repeatRun = service.run(repeatPlan);
            assertVerified(repeatRun.state(), repeatRun.sourceRows(), repeatRun.verifiedRows(), 3);
            var repeatedRows = readBack(reader, target);
            assertEquals(firstRows, repeatedRows, "same bounded backfill must remain idempotent");

            var incrementalPlan = service.plan(FROM, INCREMENTAL_TO, LOGICAL_DATE, Mode.INCREMENTAL, SYMBOLS);
            assertEquals(BACKFILL_TO, incrementalPlan.verifiedThrough());
            assertEquals(FROM, incrementalPlan.request().from(), "three calendar day revision overlap must be replayed");
            assertEquals(INCREMENTAL_TO, incrementalPlan.request().to());
            assertEquals(4, incrementalPlan.source().selectedRows());
            var incrementalRun = service.run(incrementalPlan);
            assertVerified(incrementalRun.state(), incrementalRun.sourceRows(), incrementalRun.verifiedRows(), 4);
            var finalRows = readBack(reader, target);
            assertEquals(4, finalRows.size());
            assertEquals(SyncRunState.VERIFIED, service.status(incrementalRun.runId()).state());
            var resumedRun = service.resume(incrementalPlan, incrementalRun.runId());
            assertVerified(resumedRun.state(), resumedRun.sourceRows(), resumedRun.verifiedRows(), 4);
            assertTrue(resumedRun.reusedRows() > 0, "exact D086 resume should reuse its verified slices");
            assertEquals(finalRows, readBack(reader, target));

            var registeredDatasets = new com.zoutrankil.data.service.DatasetRegistry(List.of(
                    calendars,
                    new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"),
                    new L2DailyFeaturesReadRepository(reader, target)));
            var calendarDefinition = ExchangeCalendarSyncAdapter.definition(true);
            var manifestDefinition = L2DatasetManifestJobService.definition();
            var featureDefinition = L2DailyFeaturesJobService.definition();
            var registeredJobs = new com.zoutrankil.data.service.SyncJobRegistry(
                    List.of(calendarDefinition, manifestDefinition, featureDefinition), registeredDatasets,
                    Map.of(calendarDefinition.datasetId(), calendarDefinition.supportedModes(),
                            manifestDefinition.datasetId(), manifestDefinition.supportedModes(),
                            featureDefinition.datasetId(), featureDefinition.supportedModes()),
                    new com.zoutrankil.data.service.SyncJobRegistry.Policies(
                            java.util.Set.of("file.bounded", "tushare.shared"),
                            java.util.Set.of("exchange_calendar.year", "l2_manifest.parquet_date",
                                    "l2_daily_features.parquet_date"),
                            java.util.Set.of("questdb.full_key_values")));
            var dispatcher = new CommandLineRunner(new CliCommandRegistry(List.of(
                    new CatalogCommands(registeredDatasets, registeredJobs),
                    new L2Commands(null, service, null, null, null))));
            String jobList = invoke(dispatcher, "list-sync-jobs");
            String datasetList = invoke(dispatcher, "show-dataset-definitions");
            String jobDetails = invoke(dispatcher, "show-sync-job", "--job=data.l2_daily_features", "--version=1");
            String preview = invoke(dispatcher, "plan-l2-daily-features-job", "--from=" + FROM,
                    "--to=" + INCREMENTAL_TO, "--logical-date=" + LOGICAL_DATE, "--mode=incremental",
                    "--symbols=" + SYMBOLS.getFirst());
            String runStatus = invoke(dispatcher, "l2-daily-features-job-status", "--run=" + incrementalRun.runId());
            String runSlices = invoke(dispatcher, "show-sync-run", "--run=" + incrementalRun.runId(),
                    "--ledger=" + ledger, "--limit=100");
            assertTrue(jobList.contains("data.l2_daily_features"));
            assertTrue(datasetList.contains("l2_daily_features"));
            assertTrue(jobDetails.contains("l2_daily_features_owner"));
            assertTrue(preview.contains("PLANNED"));
            assertTrue(runStatus.contains("VERIFIED"));
            assertTrue(runSlices.contains("\"entries\"") && runSlices.contains("VERIFIED"));

            var expectedRows = readIndependentParquetRows();
            assertEquals(expectedRows, finalRows, "all 110 source fields must match the independent Parquet capture");
            writeEvidence(target, incrementalPlan.source(), firstRun, repeatRun, incrementalPlan, incrementalRun,
                    resumedRun, firstRows.size(), repeatedRows.size(), finalRows.size(), expectedRows.size(), true, true,
                    jobList.contains("data.l2_daily_features") && datasetList.contains("l2_daily_features"),
                    preview.contains("PLANNED") && jobDetails.contains("l2_daily_features_owner"),
                    runStatus.contains("VERIFIED") && runSlices.contains("\"entries\"")
                            && runSlices.contains("VERIFIED"));
        }
    }

    private static List<com.zoutrankil.data.domain.L2DailyFeatures> readBack(
            QuestDbBoundedReader reader, String target) {
        return new L2DailyFeaturesReadRepository(reader, target)
                .findRange(FROM, INCREMENTAL_TO.plusDays(1), 10, null).rows();
    }

    private static List<L2DailyFeatures> readIndependentParquetRows() throws Exception {
        Path evidence = Path.of("artifacts/java-migration/D086/commands/source-parquet-20260921-24.jsonl");
        ObjectMapper json = new ObjectMapper();
        L2DailyFeaturesMapper mapper = new L2DailyFeaturesMapper();
        var rows = new ArrayList<L2DailyFeatures>();
        for (String line : java.nio.file.Files.readAllLines(evidence, StandardCharsets.UTF_8)) {
            JsonNode record = json.readTree(line);
            if (!"page".equals(record.path("kind").asText())) continue;
            LocalDate date = LocalDate.parse(record.path("responseEvidence").path("date").asText(),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            for (JsonNode row : record.path("rows")) rows.add(mapper.fromParquet(row, date));
        }
        return rows.stream().filter(row -> row.symbol().equals(SYMBOLS.getFirst())
                && !row.tradeDate().isBefore(FROM) && !row.tradeDate().isAfter(INCREMENTAL_TO))
                .sorted(java.util.Comparator.comparing(L2DailyFeatures::tradeDate).thenComparing(L2DailyFeatures::symbol))
                .toList();
    }

    private static String invoke(CommandLineRunner runner, String... args) throws Exception {
        PrintStream previous = System.out;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            runner.run(new DefaultApplicationArguments(args));
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(previous);
        }
    }

    private static void writeEvidence(String target, L2DailyFeaturesParquetSource.Inspection source,
            SyncJobRunner.Result backfill, SyncJobRunner.Result repeated, L2DailyFeaturesJobService.Plan incrementalPlan,
            SyncJobRunner.Result incremental, SyncJobRunner.Result resumed, int firstRows, int repeatedRows,
            int finalRows, int expectedRows,
            boolean cancellationObserved, boolean fingerprintFailureRejected, boolean registered,
            boolean managementPreview, boolean managementRunStatus) throws Exception {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("status", "VERIFIED");
        evidence.put("targetTable", target);
        evidence.put("sourceRootIdentity", source.rootIdentity());
        evidence.put("sourceFingerprint", source.sourceFingerprint());
        evidence.put("schemaFingerprint", source.schemaFingerprint());
        evidence.put("sourceWindow", List.of(FROM.toString(), INCREMENTAL_TO.toString()));
        evidence.put("sourceSelectedRows", source.selectedRows());
        evidence.put("sourceFiles", source.files());
        evidence.put("sourceBytes", source.sourceBytes());
        evidence.put("fieldCount", L2DailyFeatureField.values().length);
        evidence.put("independentFullFieldComparisons", Math.multiplyExact(expectedRows, L2DailyFeatureField.values().length));
        evidence.put("boundedPages", source.pages());
        evidence.put("sourceCancellationObserved", cancellationObserved);
        evidence.put("staleFingerprintRejected", fingerprintFailureRejected);
        evidence.put("datasetAndJobRegistered", registered);
        evidence.put("managementPlanPreviewAndJobDetails", managementPreview);
        evidence.put("managementRunStatusAndSlices", managementRunStatus);
        evidence.put("rateLimit", "not applicable: source is local bounded Parquet/manifest IO; no remote API calls");
        evidence.put("automaticRetry", "disabled: deterministic local source failure remains visible for reconciliation");
        evidence.put("firstBackfill", runEvidence(backfill));
        evidence.put("idempotentBackfill", runEvidence(repeated));
        evidence.put("incremental", Map.of("run", runEvidence(incremental),
                "checkpointBefore", incrementalPlan.verifiedThrough(), "from", incrementalPlan.request().from(),
                "to", incrementalPlan.request().to(), "replayedRevisionDays", 3));
        evidence.put("resume", runEvidence(resumed));
        evidence.put("readbackRows", Map.of("afterBackfill", firstRows, "afterRepeatedBackfill", repeatedRows,
                "afterIncremental", finalRows, "independentSource", expectedRows));
        Path output = Path.of("artifacts/java-migration/D086/live-acceptance-20260930.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(evidence) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> runEvidence(SyncJobRunner.Result result) {
        var row = new LinkedHashMap<String, Object>();
        row.put("runId", result.runId());
        row.put("state", result.state());
        row.put("sourceRows", result.sourceRows());
        row.put("verifiedRows", result.verifiedRows());
        row.put("reusedRows", result.reusedRows());
        return row;
    }

    private static void assertVerified(SyncRunState state, int sourceRows, int verifiedRows, int expectedRows) {
        assertTrue(state == SyncRunState.VERIFIED || state == SyncRunState.VERIFIED_EMPTY,
                "D086 run state: " + state);
        assertEquals(expectedRows, sourceRows, "bounded D086 source row count");
        assertEquals(sourceRows, verifiedRows, "every source row must receive full-key/full-column readback");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment setting " + name);
        return value;
    }
}
