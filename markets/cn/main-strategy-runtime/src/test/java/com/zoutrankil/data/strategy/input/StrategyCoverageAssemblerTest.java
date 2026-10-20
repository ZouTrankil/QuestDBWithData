package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class StrategyCoverageAssemblerTest {
    @TempDir Path root;
    ObjectMapper json = new ObjectMapper();

    Path prepare() throws Exception {
        var fixture = new StrategySourceCoverageTest();
        fixture.root = root;
        var tables = fixture.tables();
        Path certificate = Files.writeString(root.resolve("source.json"), "独立来源单元样本");
        String certSha = StrategySourceCoverage.sha(Files.readAllBytes(certificate));
        Path history =
                Files.write(
                        root.resolve("history.json"),
                        json.writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "java.strategy.point_in_time.v1",
                                        "decisionTimes",
                                        Map.of(),
                                        "stockMetadata",
                                        List.of(),
                                        "marketContext",
                                        List.of(),
                                        "sourceEvidence",
                                        List.of(
                                                Map.of(
                                                        "path",
                                                        certificate.toString(),
                                                        "sha256",
                                                        certSha)))));
        var deps =
                Map.ofEntries(
                        Map.entry(
                                "stock_daily",
                                List.of(
                                        "stk_factor",
                                        "stk_limit",
                                        "stk_suspend",
                                        "stk_st_daily",
                                        "daily_basic")),
                        Map.entry("l2_raw", List.of("l2_daily_features")),
                        Map.entry("etf_factor", List.of("etf_factor")),
                        Map.entry("etf_daily", List.of("etf_daily")),
                        Map.entry("stock_metadata", List.of("stock_detail_info")),
                        Map.entry("etf_reference", List.of("etf_basic")),
                        Map.entry("etf_portfolio", List.of("etf_portfolio")),
                        Map.entry("sentiment", List.of("market_sentiment_daily")),
                        Map.entry("market_context", List.of("regime_features_monitor_daily")),
                        Map.entry("calendar", List.of("exchange_calendar")));
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        var registered = new HashSet<String>();
        var sources = new TreeMap<String, Object>();
        for (var source : StrategySourceCoverage.KEYS.entrySet()) {
            var runs = new ArrayList<Object>();
            for (String dataset : deps.get(source.getKey())) {
                String id = "fixture-" + dataset;
                if (registered.add(id)) {
                    var def =
                            new SyncJobDefinition(
                                    "data." + dataset,
                                    1,
                                    dataset,
                                    1,
                                    "fixture",
                                    Set.of(Mode.BACKFILL),
                                    Mode.BACKFILL,
                                    Map.of(),
                                    "fixture",
                                    "fixture",
                                    "fixture",
                                    new RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1)),
                                    Duration.ofSeconds(30),
                                    new Budget(1, 1, 1, 100, 10000),
                                    0,
                                    List.of(),
                                    Frequency.MANUAL,
                                    ZoneOffset.UTC,
                                    true,
                                    false);
                    ledger.createRun(
                            id,
                            null,
                            "fixture-target",
                            def.freeze(
                                    Mode.BACKFILL,
                                    Map.of(),
                                    fixture.day,
                                    fixture.day,
                                    fixture.day));
                    ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
                    ledger.transition(
                            id,
                            1,
                            SyncRunState.VERIFIED,
                            json.writeValueAsString(
                                    Map.of(
                                            "verification",
                                            Map.of(
                                                    "passed",
                                                    true,
                                                    "expectedRows",
                                                    1,
                                                    "actualRows",
                                                    1,
                                                    "matchedRows",
                                                    1,
                                                    "mismatchedRows",
                                                    0,
                                                    "duplicateKeys",
                                                    0,
                                                    "missingKeys",
                                                    0,
                                                    "sourceFingerprint",
                                                    "a".repeat(64),
                                                    "readbackEvidence",
                                                    certificate.toString(),
                                                    "writerStopped",
                                                    true))));
                }
                runs.add(Map.of("ledger", "ledger.sqlite3", "runId", id));
            }
            var keys =
                    tables.get(source.getKey()).stream()
                            .map(
                                    row ->
                                            source.getValue().stream()
                                                    .map(k -> row.get(k).toString())
                                                    .toList())
                            .toList();
            sources.put(
                    source.getKey(),
                    Map.of(
                            "expectedKeys",
                            keys,
                            "certificate",
                            certificate.toString(),
                            "certificateSha256",
                            certSha,
                            "upstreamRuns",
                            runs));
        }
        return Files.write(
                root.resolve("inputs.json"),
                json.writeValueAsBytes(
                        Map.of(
                                "schema",
                                "java.strategy.coverage_inputs.v1",
                                "start",
                                fixture.day.toString(),
                                "end",
                                fixture.day.toString(),
                                "warmupStart",
                                fixture.warmup.toString(),
                                "sourceClocksSha256",
                                StrategySourceCoverage.objectSha(fixture.clocks),
                                "sources",
                                sources,
                                "pointInTime",
                                Map.of(
                                        "path",
                                        history.toString(),
                                        "sha256",
                                        StrategySourceCoverage.sha(Files.readAllBytes(history))))));
    }

    @Test
    void validUpstreamsProduceUsableCoverageAndInputChangesBlockThePublicationGate()
            throws Exception {
        Path input = prepare();
        var fixture = new StrategySourceCoverageTest();
        fixture.root = root;
        var out =
                StrategyCoverageAssembler.assemble(
                        input,
                        StrategySourceCoverage.sha(Files.readAllBytes(input)),
                        root.resolve("result.json"),
                        fixture.clocks);
        var coverage = new StrategySourceCoverage(out.path().toString(), out.sha256());
        assertDoesNotThrow(
                () ->
                        coverage.verify(
                                fixture.day,
                                fixture.day,
                                fixture.warmup,
                                fixture.tables(),
                                fixture.clocks));
        Files.writeString(input, "changed");
        assertThrows(IllegalStateException.class, coverage::revalidate);
    }

    @Test
    void unrelatedUpstreamCannotCertifyStockCoverage() throws Exception {
        Path input = prepare();
        var document = (ObjectNode) json.readTree(Files.readAllBytes(input));
        ((ObjectNode) document.path("sources").path("stock_daily"))
                .set(
                        "upstreamRuns",
                        json.valueToTree(
                                List.of(
                                        Map.of(
                                                "ledger",
                                                "ledger.sqlite3",
                                                "runId",
                                                "fixture-exchange_calendar"))));
        Files.write(input, json.writeValueAsBytes(document));
        assertThrows(
                IllegalStateException.class,
                () ->
                        StrategyCoverageAssembler.assemble(
                                input,
                                StrategySourceCoverage.sha(Files.readAllBytes(input)),
                                root.resolve("result.json"),
                                new StrategySourceCoverageTest().clocks));
        assertFalse(Files.exists(root.resolve("result.json")));
    }

    @Test
    void versionTwoAssemblyUsesNewKeysWithoutRewritingTheApprovedV1Input() throws Exception {
        Path legacy = prepare();
        byte[] original = Files.readAllBytes(legacy);
        var document = json.readTree(original);
        var object = (ObjectNode) document;
        object.put("schema", StrategyInputSchema.V2.approvalSchema());
        object.put("inputSchema", StrategyInputSchema.V2.inputSchema());
        object.put("keyEncoding", StrategyInputSchema.V2.keyEncoding());
        for (var entry : StrategyInputSchema.V2.keys().entrySet()) {
            ((ObjectNode) document.path("sources").path(entry.getKey()))
                    .set("businessKeys", json.valueToTree(entry.getValue()));
        }
        byte[] bytes = json.writeValueAsBytes(document);
        Path input = Files.write(root.resolve("v2-approved-unit-input.json"), bytes);
        var fixture = new StrategySourceCoverageTest();
        fixture.root = root;
        var publication =
                StrategyCoverageAssembler.assemble(
                        input,
                        StrategySourceCoverage.sha(bytes),
                        root.resolve("v2-coverage.json"),
                        fixture.clocks);
        var coverage =
                new StrategySourceCoverage(publication.path().toString(), publication.sha256());
        assertEquals(StrategyInputSchema.V2, coverage.inputSchema());
        assertDoesNotThrow(
                () ->
                        coverage.verify(
                                fixture.day,
                                fixture.day,
                                fixture.warmup,
                                fixture.standardTables(),
                                fixture.clocks));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        Files.writeString(input, "单元样本：批准输入被替换");
        assertThrows(IllegalStateException.class, coverage::revalidate);
    }
}
