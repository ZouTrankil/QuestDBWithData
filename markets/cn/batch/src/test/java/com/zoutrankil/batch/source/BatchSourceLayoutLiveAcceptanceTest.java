package com.zoutrankil.batch.source;

import static com.zoutrankil.batch.testsupport.NativeSourceFixtures.request;
import static com.zoutrankil.batch.testsupport.NativeSourceFixtures.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.StageExecutor;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.batch.testsupport.BatchLedgerTestDatabase;
import com.zoutrankil.data.service.PageExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 本机专属空库的有限夹具验收；不访问来源或正式表，失败保留原件和未决目标。 */
@EnabledIfEnvironmentVariable(named = "JDB_LAYOUT_LIVE_ACCEPTANCE", matches = "true")
class BatchSourceLayoutLiveAcceptanceTest {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:19030");
    private final HttpClient http =
            HttpClient.newBuilder()
                    .proxy(ProxySelector.of(null))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
    private final List<Map<String, Object>> products = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private JsonNode server;
    private Path evidence;

    @Test
    @DisabledIfEnvironmentVariable(named = "JDB_LAYOUT_RECONCILE_ONLY", matches = "true")
    void bothLayoutsAndEmptySnapshotsRoundTripAndReopenThroughActualWriter() throws Exception {
        Path record = Path.of(System.getenv("JDB_LAYOUT_PRIVATE_SERVER")).toRealPath();
        byte[] bytes = Files.readAllBytes(record);
        assertEquals(System.getenv("JDB_LAYOUT_PRIVATE_SERVER_SHA256"), sha(bytes));
        server = Json.MAPPER.readTree(bytes);
        evidence = record.getParent();
        assertFalse(Files.exists(evidence.resolve("report.json")), "原验收不能覆盖重跑");
        report.put("schemaVersion", 1);
        report.put("state", "PREPARED");
        report.put("sourceCoverageVerified", false);
        report.put("physicalMigrationExecuted", false);
        report.put("modelAcceptanceVerified", false);
        report.put("products", products);
        save();
        try {
            requirePrivateServer();
            assertEquals(
                    0, sql("SELECT count() FROM tables()").path("dataset").get(0).get(0).asLong());
            var database = BatchLedgerTestDatabase.create(evidence.resolve("ledger"));
            var collector = new SourceCollector(evidence);
            for (String dataset :
                    List.of("etf_adj", "etf_daily", "etf_share", "stk_st_daily", "stk_suspend")) {
                String code = dataset.startsWith("etf") ? "510300.SH" : "000001.SZ";
                var collected =
                        collector.collect(
                                request(dataset, Set.of(code)),
                                params ->
                                        new PageExecutor.Page(
                                                List.of(row(dataset, code)), null, true, "人工单行夹具"));
                var source = SourceContract.load(dataset);
                for (String version : List.of(source.version(), dataset + "-trade-date-v2"))
                    accept(database, collector, collected, source, version);
            }
            for (String dataset : List.of("stk_st_daily", "stk_suspend")) {
                var collected =
                        collector.collect(
                                new SourceCollector.Request(
                                        dataset,
                                        LocalDate.of(2026, 9, 28),
                                        Set.of("000001.SZ"),
                                        "人工空窗范围-v1",
                                        null),
                                params -> new PageExecutor.Page(List.of(), null, true, "人工空窗夹具"));
                // 空窗要有独立证券范围身份，避免和同一天的正记录混成一个实例。
                var source = SourceContract.load(dataset);
                accept(database, collector, collected, source, dataset + "-trade-date-v2");
            }
            assertEquals(
                    0L,
                    database.jdbc.queryForObject(
                            "SELECT count(*) FROM write_intent WHERE delivery<>'VERIFIED'",
                            Long.class));
            assertEquals(
                    12L,
                    database.jdbc.queryForObject(
                            "SELECT sum(attempt) FROM write_intent", Long.class));
            assertEquals(12, products.size());
            report.put("state", "VERIFIED_BEFORE_CLEANUP");
            save();
            // 全部原意图终结且按原对象身份复核后，才清理本机空库中的专属表。
            for (var product : products) {
                requirePrivateServer();
                String table = product.get("target").toString();
                assertEquals(product.get("identity"), identity(table));
                sql("DROP TABLE " + table);
            }
            assertEquals(
                    0L, sql("SELECT count() FROM tables()").path("dataset").get(0).get(0).asLong());
            report.put("state", "VERIFIED_AND_CLEANED");
            report.put("intentAttempts", 12);
            save();
        } catch (Throwable failure) {
            report.put("state", "INCOMPLETE_PRESERVED");
            report.put("failure", failure.getClass().getSimpleName() + ":" + failure.getMessage());
            save();
            throw failure;
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JDB_LAYOUT_RECONCILE_ONLY", matches = "true")
    void readOnlyFinishOriginalVerifiedRun() throws Exception {
        Path record = Path.of(System.getenv("JDB_LAYOUT_PRIVATE_SERVER")).toRealPath();
        byte[] metadata = Files.readAllBytes(record);
        assertEquals(System.getenv("JDB_LAYOUT_PRIVATE_SERVER_SHA256"), sha(metadata));
        server = Json.MAPPER.readTree(metadata);
        evidence = record.getParent();
        Path original = evidence.resolve("report.json");
        byte[] failedBytes = Files.readAllBytes(original);
        assertEquals(System.getenv("JDB_LAYOUT_ORIGINAL_REPORT_SHA256"), sha(failedBytes));
        var failed = Json.MAPPER.readTree(failedBytes);
        assertEquals("INCOMPLETE_PRESERVED", failed.path("state").asText());
        assertFalse(Files.exists(evidence.resolve("readonly-reconciliation.json")));
        requirePrivateServer();
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl(
                "jdbc:sqlite:file:" + evidence.resolve("ledger/metadata.sqlite") + "?mode=ro");
        var ledger = new JdbcTemplate(dataSource);
        assertEquals(
                0L,
                ledger.queryForObject(
                        "SELECT count(*) FROM write_intent WHERE delivery<>'VERIFIED'",
                        Long.class));
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("originalReportSHA256", sha(failedBytes));
        receipt.put("resent", false);
        receipt.put("state", "READONLY_VERIFIED_BEFORE_CLEANUP");
        var verified = new ArrayList<Map<String, Object>>();
        for (var product : failed.path("products")) {
            String table = product.path("target").asText();
            assertTrue(table.matches("jdb_test_[a-z0-9_]+"));
            assertEquals(
                    1L,
                    ledger.queryForObject(
                            "SELECT count(*) FROM write_intent WHERE target=? AND delivery='VERIFIED'",
                            Long.class,
                            table));
            String id = identity(table);
            assertEquals(product.path("identity").asText(), id);
            var source = SourceContract.load(product.path("dataset").asText());
            var contract = source.writeLayout(product.path("version").asText()).target();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> expected =
                    Json.MAPPER.convertValue(product.path("expected"), List.class);
            String before = stableWal(table, expected.size());
            assertFull(contract, table, expected);
            assertEquals(before, stableWal(table, expected.size()));
            assertEquals(id, identity(table));
            verified.add(
                    Map.of("target", table, "identity", id, "WAL", before, "expected", expected));
        }
        receipt.put("objects", verified);
        Path output = evidence.resolve("readonly-reconciliation.json");
        Files.writeString(output, Json.write(receipt));
        for (var object : verified) {
            requirePrivateServer();
            String table = object.get("target").toString();
            assertEquals(object.get("identity"), identity(table));
            sql("DROP TABLE " + table);
        }
        assertEquals(
                0L, sql("SELECT count() FROM tables()").path("dataset").get(0).get(0).asLong());
        assertArrayEquals(failedBytes, Files.readAllBytes(original));
        receipt.put("state", "VERIFIED_EXISTING_AND_CLEANED");
        Files.writeString(output, Json.write(receipt));
    }

    private void accept(
            BatchLedgerTestDatabase database,
            SourceCollector collector,
            SourceCollector.Collected collected,
            SourceContract source,
            String version)
            throws Exception {
        var frozen = collector.read(collected.fingerprint());
        byte[] original = Files.readAllBytes(collector.path(collected.fingerprint()));
        var day = LocalDate.of(2026, 9, 28);
        var now = Instant.now();
        var run =
                new RunRequest(
                        "fixture-" + version + "-" + frozen.rows().size(),
                        "source_" + source.dataset(),
                        day,
                        day,
                        day,
                        version,
                        "0",
                        null,
                        null,
                        collected.fingerprint(),
                        "人工日历夹具-v1",
                        "Asia/Shanghai",
                        now,
                        now,
                        collected.scopeIdentity());
        database.ledger.register(run);
        String originalRequest = Json.write(database.ledger.request(run.instanceId()));
        var layout = source.writeLayout(version);
        var physical =
                frozen.rows().stream()
                        .map(
                                r ->
                                        layout.physicalRow(
                                                r,
                                                database.ledger.businessCreatedAt(
                                                        run.instanceId())))
                        .toList();
        String target =
                "jdb_test_"
                        + database.ledger.environmentNamespace()
                        + "_"
                        + source.dataset()
                        + "_"
                        + run.instanceId();
        assertTrue(target.matches("jdb_test_[a-z0-9_]+"));
        assertTrue(
                sql("SELECT id FROM tables() WHERE table_name='" + target + "'")
                        .path("dataset")
                        .isEmpty());
        var product = new LinkedHashMap<String, Object>();
        product.put("dataset", source.dataset());
        product.put("version", version);
        product.put("sourceFingerprint", collected.fingerprint());
        product.put("instance", run.instanceId());
        product.put("target", target);
        product.put("expected", physical);
        product.put("state", "WRITE_INTENT_PREPARED");
        products.add(product);
        save();
        requirePrivateServer();
        var writer = new SourceProductRunner(database.ledger, collector, evidence, ENDPOINT, "");
        StageExecutor.Result result;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        do {
            result = writer.execute(run, source.dataset());
            product.put("state", result.state().name());
            save();
            if (result.state().ready()) break;
            assertEquals(BusinessState.VERIFYING, result.state(), result.reason());
            // 只允许 ACK 已确定的可见性重验；UNKNOWN 不进入自动重试。
            assertEquals(
                    0L,
                    database.jdbc.queryForObject(
                            "SELECT count(*) FROM write_intent WHERE instance_id=? AND delivery<>'ACKNOWLEDGED'",
                            Long.class,
                            run.instanceId()));
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertEquals(
                physical.isEmpty() ? BusinessState.VERIFIED_EMPTY : BusinessState.VERIFIED,
                result.state(),
                result.reason());
        database.ledger.completeSource(run, result, null);
        String id = identity(target);
        product.put("identity", id);
        save();
        String before = stableWal(target, physical.size());
        assertFull(layout.target(), target, physical);
        assertEquals(before, stableWal(target, physical.size()));
        assertEquals(id, identity(target));
        var reopened =
                new SourceProductRunner(
                        database.reopen().ledger,
                        new SourceCollector(evidence),
                        evidence,
                        ENDPOINT,
                        "");
        assertEquals(result.state(), reopened.execute(run, source.dataset()).state());
        assertEquals(
                1,
                database.jdbc.queryForObject(
                        "SELECT sum(attempt) FROM write_intent WHERE instance_id=?",
                        Integer.class,
                        run.instanceId()));
        assertEquals(originalRequest, Json.write(database.ledger.request(run.instanceId())));
        assertArrayEquals(original, Files.readAllBytes(collector.path(collected.fingerprint())));
        product.put(
                "tableMetadata",
                sql("SELECT partitionBy,walEnabled,dedup FROM tables() WHERE table_name='"
                                + target
                                + "'")
                        .path("dataset"));
        product.put("wal", before);
        product.put("state", "VERIFIED_AND_REOPENED");
        save();
    }

    private void assertFull(
            SourceContract contract, String table, List<Map<String, Object>> expected)
            throws Exception {
        var tableMetadata =
                sql("SELECT partitionBy,walEnabled,dedup FROM tables() WHERE table_name='"
                                + table
                                + "'")
                        .path("dataset");
        assertEquals(1, tableMetadata.size());
        assertEquals(contract.partitionBy(), tableMetadata.get(0).get(0).asText());
        assertTrue(tableMetadata.get(0).get(1).asBoolean());
        assertTrue(tableMetadata.get(0).get(2).asBoolean());
        var metadata =
                sql("SELECT \"column\",\"type\",\"upsertKey\",\"designated\" FROM table_columns('"
                                + table
                                + "')")
                        .path("dataset");
        assertEquals(contract.columns().size(), metadata.size());
        for (int i = 0; i < metadata.size(); i++) {
            var c = contract.columns().get(i);
            var actual = metadata.get(i);
            assertEquals(c.target(), actual.get(0).asText());
            assertEquals(c.type(), actual.get(1).asText());
            assertEquals(c.key(), actual.get(2).asBoolean());
            assertEquals(c.target().equals(contract.timestampColumn()), actual.get(3).asBoolean());
        }
        var actual = sql("SELECT * FROM " + table + " LIMIT 2").path("dataset");
        assertEquals(expected.size(), actual.size());
        for (int row = 0; row < expected.size(); row++)
            for (int col = 0; col < contract.columns().size(); col++) {
                var c = contract.columns().get(col);
                Object value = expected.get(row).get(c.target());
                var cell = actual.get(row).get(col);
                if (value == null) assertTrue(cell.isNull(), c.target());
                else if (c.type().startsWith("TIMESTAMP")) {
                    String text = value.toString();
                    Instant point = Instant.parse(text.length() == 10 ? text + "T00:00:00Z" : text);
                    assertEquals(point, Instant.parse(cell.asText()), c.target());
                } else if (value instanceof Number n)
                    assertEquals(n.doubleValue(), cell.doubleValue(), 0.0, c.target());
                else assertEquals(value.toString(), cell.asText(), c.target());
            }
    }

    private String stableWal(String target, int rows) throws Exception {
        var data =
                sql("SELECT suspended,writerTxn,sequencerTxn FROM wal_tables() WHERE name='"
                                + target
                                + "'")
                        .path("dataset");
        assertEquals(1, data.size());
        var r = data.get(0);
        assertFalse(r.get(0).asBoolean());
        assertTrue(r.get(1).isIntegralNumber() && r.get(2).isIntegralNumber());
        assertEquals(r.get(1).asLong(), r.get(2).asLong());
        assertTrue(r.get(1).asLong() >= 0 || rows == 0 && r.get(1).asLong() == -1);
        return data.toString();
    }

    private String identity(String table) throws Exception {
        var data =
                sql("SELECT id,directoryName FROM tables() WHERE table_name='" + table + "'")
                        .path("dataset");
        assertEquals(1, data.size());
        return data.toString();
    }

    private void requirePrivateServer() throws Exception {
        Path root = Path.of(server.path("root").asText()).toRealPath();
        var commonGit =
                new ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
                        .start();
        assertTrue(commonGit.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, commonGit.exitValue());
        Path repository =
                Path.of(
                                new String(
                                                commonGit.getInputStream().readAllBytes(),
                                                StandardCharsets.UTF_8)
                                        .trim())
                        .toRealPath()
                        .getParent();
        assertEquals(repository, Path.of(server.path("repository").asText()).toRealPath());
        assertTrue(root.startsWith(repository.resolve("var/cn-batch-layout-v2-live").toRealPath()));
        assertEquals(evidence.resolve("database").toRealPath(), root);
        assertEquals(
                server.path("configuration").asText(),
                Files.readString(root.resolve("conf/server.conf")));
        var process = ProcessHandle.of(server.path("pid").asLong()).orElseThrow();
        assertTrue(process.isAlive());
        assertEquals(
                server.path("startedAt").asText(),
                process.info().startInstant().orElseThrow().toString());
        assertEquals(
                Path.of(server.path("java").asText()).toRealPath(),
                Path.of(process.info().command().orElseThrow()).toRealPath());
        var args = List.of(process.info().arguments().orElseThrow());
        assertTrue(args.contains("io.questdb/io.questdb.ServerMain"));
        assertEquals(root.toString(), args.get(args.indexOf("-d") + 1));
        var lsof =
                new ProcessBuilder(
                                "/usr/sbin/lsof",
                                "-nP",
                                "-a",
                                "-p",
                                Long.toString(process.pid()),
                                "-iTCP:19030",
                                "-iTCP:18842",
                                "-sTCP:LISTEN",
                                "-Fpn")
                        .start();
        assertTrue(lsof.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, lsof.exitValue());
        String listeners = new String(lsof.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(
                listeners.contains("n127.0.0.1:19030\n")
                        && listeners.contains("n127.0.0.1:18842\n"));
        assertFalse(listeners.contains("n*:"));
    }

    private JsonNode sql(String query) throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        ENDPOINT.resolve(
                                                "/exec?query="
                                                        + URLEncoder.encode(
                                                                query, StandardCharsets.UTF_8)))
                                .timeout(Duration.ofSeconds(15))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), query);
        var result = Json.MAPPER.readTree(response.body());
        assertFalse(result.has("error"), result.toString());
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void save() throws Exception {
        Files.writeString(evidence.resolve("report.json"), Json.write(report));
    }
}
