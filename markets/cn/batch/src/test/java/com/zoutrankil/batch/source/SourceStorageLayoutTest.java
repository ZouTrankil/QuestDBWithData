package com.zoutrankil.batch.source;

import static com.zoutrankil.batch.testsupport.NativeSourceFixtures.request;
import static com.zoutrankil.batch.testsupport.NativeSourceFixtures.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.LaunchService;
import com.zoutrankil.batch.postclose.PostCloseSourcePlan;
import com.zoutrankil.batch.storage.SqliteLedger;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.batch.testsupport.BatchLedgerTestDatabase;
import com.zoutrankil.batch.writer.QuestDbTablePort;
import com.zoutrankil.data.service.PageExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.launch.JobOperator;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP 夹具核对实际生产执行器和 SQLite，不代替来源或 QuestDB 实库验收。 */
class SourceStorageLayoutTest {
    @TempDir Path root;
    private static final Instant AT = Instant.parse("2026-09-28T12:13:14.123456789Z");

    @ParameterizedTest
    @ValueSource(strings = {"etf_adj", "etf_daily", "etf_share", "stk_st_daily", "stk_suspend"})
    void bothLayoutsWriteReadAndReopenWithoutChangingFrozenSource(String dataset) throws Exception {
        var collector = new SourceCollector(root);
        String code = dataset.startsWith("etf") ? "510300.SH" : "000001.SZ";
        var collected =
                collector.collect(
                        request(dataset, Set.of(code)),
                        params ->
                                new PageExecutor.Page(
                                        List.of(row(dataset, code)), null, true, "fixture-v1"));
        var frozen = collector.read(collected.fingerprint());
        byte[] original = Files.readAllBytes(collector.path(collected.fingerprint()));
        var source = SourceContract.load(dataset);
        var database = BatchLedgerTestDatabase.create(root.resolve("ledger"));
        var identities = new ArrayList<String>();
        var targets = new ArrayList<String>();
        for (String version : List.of(source.version(), dataset + "-trade-date-v2")) {
            var layout = source.writeLayout(version);
            var run = run(dataset, version, collected.fingerprint(), collected.scopeIdentity());
            identities.add(run.instanceId());
            database.ledger.register(run);
            var physical =
                    frozen.rows().stream()
                            .map(
                                    r ->
                                            layout.physicalRow(
                                                    r,
                                                    database.ledger.businessCreatedAt(
                                                            run.instanceId())))
                            .toList();
            String carrier = version.equals(source.version()) ? "timestamp" : "trade_date";
            assertEquals("2026-09-28", physical.getFirst().get(carrier));
            assertFalse(
                    physical.getFirst()
                            .containsKey(carrier.equals("timestamp") ? "trade_date" : "timestamp"));
            assertEquals(source.partitionBy(), layout.target().partitionBy());
            assertEquals(
                    source.columns().stream()
                            .filter(c -> c.target().equals("timestamp"))
                            .findFirst()
                            .orElseThrow()
                            .type(),
                    layout.target().columns().stream()
                            .filter(c -> c.target().equals(carrier))
                            .findFirst()
                            .orElseThrow()
                            .type());
            if (dataset.equals("etf_daily")) assertNull(physical.getFirst().get("pre_close"));
            if (dataset.equals("etf_share")) {
                assertNull(physical.getFirst().get("fund_type"));
                assertNull(physical.getFirst().get("market"));
            }
            try (var server = new TargetFixture(layout.target(), physical)) {
                var runner =
                        new SourceProductRunner(
                                database.ledger, collector, root, server.endpoint(), "");
                var result = runner.execute(run, dataset);
                assertEquals(BusinessState.VERIFIED, result.state(), result.reason());
                assertEquals(1, server.posts.get());
                var certificate =
                        Json.MAPPER.readTree(
                                Files.readString(
                                        root.resolve("writes")
                                                .resolve(run.instanceId())
                                                .resolve("certificate.json")));
                String target = certificate.path("target").asText();
                targets.add(target);
                assertEquals(version, certificate.path("definitionVersion").asText());
                assertEquals(
                        List.of(
                                new String(
                                        QuestDbTablePort.encode(layout.target(), target, physical),
                                        StandardCharsets.UTF_8)),
                        server.bodies);
                // 重建执行器与数据库连接后只核验原意图，不产生第二次 POST。
                var reopened =
                        new SourceProductRunner(
                                database.reopen().ledger,
                                new SourceCollector(root),
                                root,
                                server.endpoint(),
                                "");
                assertEquals(BusinessState.VERIFIED, reopened.execute(run, dataset).state());
                assertEquals(1, server.posts.get());
                assertTrue(
                        server.queries.stream()
                                .anyMatch(
                                        q ->
                                                q.contains(
                                                        "WHERE "
                                                                + carrier
                                                                + "='2026-09-28T00:00:00.000000Z'")));
                assertTrue(server.queries.stream().noneMatch(q -> q.contains("08:00:00")));
            }
            assertArrayEquals(
                    original, Files.readAllBytes(collector.path(collected.fingerprint())));
            assertEquals(
                    source.version(), collector.read(collected.fingerprint()).definitionVersion());
        }
        assertNotEquals(identities.get(0), identities.get(1));
        assertNotEquals(targets.get(0), targets.get(1));
    }

    @Test
    void unknownDeliveryDoesNotResendAfterReopening() throws Exception {
        String dataset = "etf_adj";
        var collector = new SourceCollector(root);
        var collected =
                collector.collect(
                        request(dataset, Set.of("510300.SH")),
                        params ->
                                new PageExecutor.Page(
                                        List.of(row(dataset, "510300.SH")),
                                        null,
                                        true,
                                        "fixture-v1"));
        var run =
                run(
                        dataset,
                        dataset + "-trade-date-v2",
                        collected.fingerprint(),
                        collected.scopeIdentity());
        var database = BatchLedgerTestDatabase.create(root.resolve("ledger"));
        database.ledger.register(run);
        var layout = SourceContract.load(dataset).writeLayout(run.definitionVersion());
        var physical =
                collector.read(collected.fingerprint()).rows().stream()
                        .map(r -> layout.physicalRow(r, AT))
                        .toList();
        try (var server = new TargetFixture(layout.target(), physical)) {
            server.status = 500;
            assertEquals(
                    BusinessState.IN_DOUBT,
                    new SourceProductRunner(database.ledger, collector, root, server.endpoint(), "")
                            .execute(run, dataset)
                            .state());
            assertEquals(
                    BusinessState.IN_DOUBT,
                    new SourceProductRunner(
                                    database.reopen().ledger,
                                    collector,
                                    root,
                                    server.endpoint(),
                                    "")
                            .execute(run, dataset)
                            .state());
            assertEquals(1, server.posts.get());
            assertEquals(
                    1L,
                    database.jdbc.queryForObject(
                            "SELECT count(*) FROM write_intent WHERE delivery='UNKNOWN'",
                            Long.class));
        }
    }

    @Test
    void unsupportedVersionIsRejectedBeforeLedgerOrJobLaunch() throws Exception {
        var collector = new SourceCollector(root);
        var collected =
                collector.collect(
                        request("etf_adj", Set.of("510300.SH")),
                        params ->
                                new PageExecutor.Page(
                                        List.of(row("etf_adj", "510300.SH")),
                                        null,
                                        true,
                                        "fixture-v1"));
        var ledger = mock(SqliteLedger.class);
        var operator = mock(JobOperator.class);
        var launches = new LaunchService(ledger, operator, List.of(), collector);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        launches.launch(
                                run(
                                        "etf_adj",
                                        "etf_adj-v999",
                                        collected.fingerprint(),
                                        collected.scopeIdentity())));
        verifyNoInteractions(ledger, operator);
    }

    @Test
    void versionsAreExplicitAndPlansPinTheChosenLayout() {
        var legacy = SourceContract.load("etf_adj");
        assertSame(legacy, legacy.writeLayout(legacy.version()).target());
        assertThrows(IllegalArgumentException.class, () -> legacy.writeLayout("etf_adj-v2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceContract.load("daily").writeLayout("daily-trade-date-v2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceContract.load("us_tbr").writeLayout("us_tbr-trade-date-v2"));
        var plan =
                new PostCloseSourcePlan.Source(
                        "etf_adj",
                        "etf_adj-trade-date-v2",
                        Set.of("510300.SH"),
                        "fixture-v1",
                        "0",
                        null,
                        null);
        assertEquals("etf_adj-trade-date-v2", plan.definitionVersion());
    }

    @Test
    void layoutCannotAlterPrecisionKeysOrOtherColumns() {
        var source = SourceContract.load("etf_daily");
        var target = source.writeLayout("etf_daily-trade-date-v2").target();
        var columns =
                target.columns().stream()
                        .map(
                                c ->
                                        c.target().equals("trade_date")
                                                ? new SourceContract.Column(
                                                        c.source(), c.target(), "TIMESTAMP", true)
                                                : c)
                        .toList();
        var wrong =
                new SourceContract(
                        target.dataset(),
                        target.version(),
                        target.endpoint(),
                        target.dateParameter(),
                        target.sourceDate(),
                        target.timestampColumn(),
                        target.pageSize(),
                        target.paged(),
                        target.emptyAllowed(),
                        target.maxRows(),
                        target.maxBytes(),
                        target.sourceDocumentation(),
                        columns,
                        target.partitionBy());
        assertThrows(IllegalArgumentException.class, () -> new SourceStorageLayout(source, wrong));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        source.writeLayout(target.version())
                                .physicalRow(Map.of("ts_code", "510300.SH"), AT));
    }

    private static RunRequest run(
            String dataset, String version, String fingerprint, String scope) {
        var date = LocalDate.of(2026, 9, 28);
        return new RunRequest(
                "fixture-" + version,
                "source_" + dataset,
                date,
                date,
                date,
                version,
                "0",
                null,
                null,
                fingerprint,
                "fixture-calendar-v1",
                "Asia/Shanghai",
                AT,
                AT,
                scope);
    }

    private static final class TargetFixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger posts = new AtomicInteger();
        final List<String> queries = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        volatile int status = 204;

        TargetFixture(SourceContract contract, List<Map<String, Object>> rows) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        if (exchange.getRequestMethod().equals("POST")) {
                            posts.incrementAndGet();
                            bodies.add(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            exchange.sendResponseHeaders(status, -1);
                            exchange.close();
                            return;
                        }
                        String q =
                                URLDecoder.decode(
                                        exchange.getRequestURI().getRawQuery(),
                                        StandardCharsets.UTF_8);
                        queries.add(q);
                        Object value;
                        if (q.contains("table_columns")) {
                            value =
                                    Map.of(
                                            "columns",
                                            List.of(
                                                    Map.of("name", "column"),
                                                    Map.of("name", "type"),
                                                    Map.of("name", "upsertKey"),
                                                    Map.of("name", "designated")),
                                            "dataset",
                                            contract.columns().stream()
                                                    .map(
                                                            c ->
                                                                    List.of(
                                                                            c.target(),
                                                                            c.type(),
                                                                            c.key(),
                                                                            c.target()
                                                                                    .equals(
                                                                                            contract
                                                                                                    .timestampColumn())))
                                                    .toList());
                        } else if (q.contains("wal_tables"))
                            value = Map.of("dataset", List.of(List.of(false)));
                        else if (q.contains("count()"))
                            value = Map.of("dataset", List.of(List.of(rows.size())));
                        else if (q.contains("SELECT")) {
                            var values =
                                    rows.stream()
                                            .map(
                                                    r ->
                                                            contract.columns().stream()
                                                                    .map(
                                                                            c -> {
                                                                                Object v =
                                                                                        r.get(
                                                                                                c
                                                                                                        .target());
                                                                                return v != null
                                                                                                && c.type()
                                                                                                        .startsWith(
                                                                                                                "TIMESTAMP")
                                                                                                && v.toString()
                                                                                                                .length()
                                                                                                        == 10
                                                                                        ? v
                                                                                                + "T00:00:00Z"
                                                                                        : v;
                                                                            })
                                                                    .toList())
                                            .toList();
                            value = Map.of("dataset", values);
                        } else value = Map.of("dataset", List.of());
                        byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
