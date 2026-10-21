package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import io.questdb.client.Sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

class VerifiedMarketDataPublicationPageTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final DatasetDefinition definition =
            new DatasetDefinition(
                    "page_test",
                    1,
                    "test",
                    "test",
                    "page_test",
                    ObjectKind.TABLE,
                    List.of(
                            new Column(
                                    "ts",
                                    "ts",
                                    "ts",
                                    StorageType.TIMESTAMP,
                                    false,
                                    "UTC 时间",
                                    new TemporalContract(
                                            TemporalKind.INSTANT,
                                            "ISO-8601",
                                            "UTC",
                                            "MICROS",
                                            "事件时刻")),
                            new Column(
                                    "symbol",
                                    "symbol",
                                    "symbol",
                                    StorageType.SYMBOL,
                                    false,
                                    "完整证券键",
                                    null),
                            new Column(
                                    "price",
                                    "price",
                                    "price",
                                    StorageType.DOUBLE,
                                    true,
                                    "显式空值",
                                    null)),
                    List.of("ts", "symbol"),
                    List.of("ts", "symbol"),
                    "ts",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "测试分页与逐字段验证");

    @Test
    void anExactlyAutoFlushedBatchStillRequiresItsPublishedAcknowledgement() {
        var pending = new AtomicInteger();
        var published = new AtomicInteger();
        var acknowledged = new AtomicBoolean(true);
        var sender =
                (Sender)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Sender.class},
                                (proxy, method, args) -> {
                                    return switch (method.getName()) {
                                        case "at" -> {
                                            if (pending.incrementAndGet() == 10000) {
                                                published.incrementAndGet();
                                                pending.set(0);
                                            }
                                            yield null;
                                        }
                                        case "flushAndGetSequence" -> {
                                            if (pending.getAndSet(0) == 0) yield -1L;
                                            yield (long) published.incrementAndGet();
                                        }
                                        case "drain" -> {
                                            if (pending.getAndSet(0) > 0)
                                                published.incrementAndGet();
                                            yield published.get() > 0 && acknowledged.get();
                                        }
                                        case "close" -> null;
                                        default ->
                                                throw new UnsupportedOperationException(
                                                        method.getName());
                                    };
                                });
        for (int i = 0; i < 10000; i++) sender.at(Instant.EPOCH);
        assertEquals(-1, sender.flushAndGetSequence(), "整批已自动发送时，空刷新没有新序号");
        assertDoesNotThrow(() -> VerifiedMarketDataStore.awaitPublication(sender));
        acknowledged.set(false);
        assertThrows(
                IllegalStateException.class,
                () -> VerifiedMarketDataStore.awaitPublication(sender));
    }

    @Test
    void restoresOriginalPageCapacityAndRejectsPreparedOrBudgetChanges() throws Exception {
        var legacy =
                json.readTree(
                        "{\"definition\":{\"version\":1,\"budget\":{\"maxBatchBytes\":4194304}},\"parameters\":{}}");
        assertEquals(
                VerifiedMarketDataStore.PublicationPage.DEFAULT,
                VerifiedMarketDataStore.restoredPublicationPage(json.readTree("{}"), legacy));
        var request =
                json.readTree(
                        "{\"definition\":{\"version\":2,\"budget\":{\"maxBatchBytes\":8388608}},\"parameters\":{\"publication_page_rows\":10000,\"publication_page_bytes\":8388608}}");
        var prepared = json.readTree("{\"publicationPage\":{\"rows\":10000,\"bytes\":8388608}}");
        assertEquals(
                10000, VerifiedMarketDataStore.restoredPublicationPage(prepared, request).rows());
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.restoredPublicationPage(
                                json.readTree("{}"), request));
        assertThrows(
                IllegalStateException.class,
                () -> VerifiedMarketDataStore.restoredPublicationPage(prepared, legacy));
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.publicationPage(
                                2, request.path("parameters"), 4194304));
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.restoredPublicationPage(
                                json.readTree(
                                        "{\"publicationPage\":{\"rows\":10000.5,\"bytes\":8388608}}"),
                                request));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedMarketDataStore.PublicationPage(32769, 33554432));
    }

    @Test
    void largerPagesRequireVersionThreeAndCannotChangeAnOldFrozenBudget() throws Exception {
        var request =
                json.readTree(
                        "{\"definition\":{\"version\":3,\"budget\":{\"maxBatchBytes\":33554432}},\"parameters\":{\"publication_page_rows\":32768,\"publication_page_bytes\":33554432}}");
        var prepared = json.readTree("{\"publicationPage\":{\"rows\":32768,\"bytes\":33554432}}");
        var page = new VerifiedMarketDataStore.PublicationPage(32768, 33554432);
        assertEquals(page, VerifiedMarketDataStore.restoredPublicationPage(prepared, request));
        assertEquals(3, page.requestVersion());
        assertEquals(1, VerifiedMarketDataStore.PublicationPage.DEFAULT.requestVersion());
        assertEquals(
                2, new VerifiedMarketDataStore.PublicationPage(10000, 8388608).requestVersion());
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.publicationPage(
                                2, request.path("parameters"), 33554432));
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.publicationPage(
                                4, request.path("parameters"), 33554432));
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.publicationPage(
                                3, request.path("parameters"), 8388608));
        assertThrows(
                IllegalStateException.class,
                () ->
                        VerifiedMarketDataStore.restoredPublicationPage(
                                json.readTree(
                                        "{\"publicationPage\":{\"rows\":10000,\"bytes\":8388608}}"),
                                request));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedMarketDataStore.PublicationPage(32768, 33554433));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new VerifiedBatchExecutor.Policy(
                                32768, 33554432, 4, Duration.ofSeconds(1), Duration.ofMillis(1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SyncJobRunner.Page<>(
                                Collections.nCopies(32769, Map.of("key", "value")),
                                "source",
                                "response",
                                null));
    }

    @Test
    void tenThousandRowsRequireReadyWalBoundedReadsAndExactValues() throws Exception {
        boundedPageRequiresReadyWalAndExactValues(10000, 8388608);
    }

    @Test
    void thirtyTwoThousandRowsRetainEveryBoundedReadAndRejectAChangedOrDuplicateLastKey()
            throws Exception {
        boundedPageRequiresReadyWalAndExactValues(32768, 33554432);
    }

    @Test
    void nativeRunnerCommitsBulkIntentBeforeOneSendAndVerifiesEveryRow() throws Exception {
        int count = 32768;
        var rows = new ArrayList<Map<String, Object>>();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            var row = new LinkedHashMap<String, Object>();
            row.put("ts", start.plusSeconds(i).toString());
            row.put("symbol", "X");
            row.put("price", i / 10.);
            rows.add(row);
        }
        var integer =
                new SyncJobDefinition.Parameter(
                        SyncJobDefinition.ParameterType.INTEGER, true, 20, 1, Set.of());
        var request =
                new SyncJobDefinition(
                                "test.bulk",
                                3,
                                "page_test",
                                1,
                                "test",
                                Set.of(SyncJobDefinition.Mode.INGEST),
                                SyncJobDefinition.Mode.INGEST,
                                Map.of(
                                        "publication_page_rows",
                                        integer,
                                        "publication_page_bytes",
                                        integer),
                                "test",
                                "test",
                                "test",
                                new SyncJobDefinition.RetryPolicy(
                                        1, Duration.ofMillis(1), Duration.ofMillis(1)),
                                Duration.ofSeconds(30),
                                new SyncJobDefinition.Budget(1, 1, 1, 100000, 33554432),
                                0,
                                List.of(),
                                SyncJobDefinition.Frequency.MANUAL,
                                ZoneOffset.UTC,
                                true,
                                false)
                        .freeze(
                                null,
                                Map.of(
                                        "publication_page_rows",
                                        count,
                                        "publication_page_bytes",
                                        33554432),
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 1));
        Path path = root.resolve("bulk-native.sqlite3");
        var ledger = new SyncRunLedger(path);
        var stored = new LinkedHashMap<String, Map<String, Object>>();
        var codec = VerifiedMarketDataStore.codec(definition);
        var sends = new AtomicInteger();
        var port =
                new VerifiedBatchExecutor.Port<Map<String, Object>, String>() {
                    public void preflight() {}

                    public void send(List<Map<String, Object>> batch) throws Exception {
                        assertEquals(count, batch.size());
                        assertTrue(
                                ledger.entries("bulk", null, 100).stream()
                                        .anyMatch(
                                                x ->
                                                        x.kind() == SyncRunLedger.Kind.SLICE
                                                                && x.state()
                                                                        == SyncRunState.SUBMITTED));
                        sends.incrementAndGet();
                        batch.forEach(row -> stored.put(codec.key(row), row));
                    }

                    public List<Map<String, Object>> readback(List<String> keys) {
                        return keys.stream().map(stored::get).toList();
                    }

                    public boolean walSettled() {
                        return true;
                    }

                    public boolean uncertainSenderStopped() {
                        return true;
                    }
                };
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    public void preflight(SyncJobDefinition.FrozenRequest frozen) {}

                    public SyncJobRunner.SourceCompletion fetch(
                            SyncJobDefinition.FrozenRequest frozen,
                            SyncJobRunner.PageConsumer<Map<String, Object>> consumer,
                            BooleanSupplier cancelled)
                            throws Exception {
                        consumer.accept(
                                new SyncJobRunner.Page<>(
                                        rows, "bulk-source", "bulk-response", null));
                        return new SyncJobRunner.SourceCompletion(1, count, true, "bulk-complete");
                    }

                    public VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec() {
                        return codec;
                    }

                    public VerifiedBatchExecutor.Port<Map<String, Object>, String> port() {
                        return port;
                    }

                    public int maxWriteBatchRows(SyncJobDefinition.FrozenRequest frozen) {
                        return VerifiedMarketDataStore.publicationPage(
                                        frozen.definition().version(),
                                        json.valueToTree(frozen.parameters()),
                                        frozen.definition().budget().maxBatchBytes())
                                .rows();
                    }
                };
        var result =
                new SyncJobRunner<Map<String, Object>, String>(
                                ledger, new DatasetIntervalLock(path))
                        .run("bulk", null, "target", request, adapter, () -> false);
        assertEquals(
                SyncRunState.VERIFIED,
                result.state(),
                result + " " + ledger.get("bulk").payloadJson());
        assertEquals(count, result.verifiedRows());
        assertEquals(1, sends.get());
        assertEquals(count, stored.size());
        assertTrue(
                ledger.entries("bulk", null, 100).stream()
                        .allMatch(x -> x.state() == SyncRunState.VERIFIED));
    }

    private void boundedPageRequiresReadyWalAndExactValues(int rowCount, int byteLimit)
            throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < rowCount; i++) {
            var row = new LinkedHashMap<String, Object>();
            row.put("ts", start.plusSeconds(i).toString());
            row.put("symbol", "X");
            row.put("price", i % 1000 == 0 ? null : i / 10.);
            rows.add(row);
        }
        var codec = VerifiedMarketDataStore.codec(definition);
        var wanted = new LinkedHashMap<String, Map<String, Object>>();
        rows.forEach(row -> wanted.put(codec.key(row), row));
        var mode = new AtomicInteger();
        var reads = new AtomicInteger();
        var walStage = new AtomicInteger();
        var walReads = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/exec",
                exchange -> {
                    String sql =
                            URLDecoder.decode(
                                    exchange.getRequestURI().getRawQuery().split("&query=", 2)[1],
                                    StandardCharsets.UTF_8);
                    List<String> names;
                    var values = new ArrayList<List<Object>>();
                    if (sql.startsWith("SELECT id,directoryName")) {
                        names = List.of("id", "directoryName");
                        values.add(List.of(1, "page_test~1"));
                    } else if (sql.contains("wal_tables()")) {
                        int count = walReads.incrementAndGet();
                        // 状态5模拟前两次仍在追WAL，随后正常可读。
                        if (walStage.get() == 5 && count > 2) walStage.set(0);
                        int stage = walStage.get();
                        names =
                                List.of(
                                        "walEnabled",
                                        "table_suspended",
                                        "writerTxn",
                                        "sequencerTxn",
                                        "suspended",
                                        "bufferedTxnSize");
                        values.add(
                                List.of(
                                        true,
                                        false,
                                        1,
                                        stage == 1 || stage == 5 ? 2 : 1,
                                        stage == 2,
                                        stage == 3 ? 1 : 0));
                    } else {
                        reads.incrementAndGet();
                        assertTrue(sql.endsWith("LIMIT 10001"));
                        names = List.of("ts", "symbol", "price");
                        var lower = Pattern.compile("\\\"ts\\\">='([^']+)'").matcher(sql);
                        var upper = Pattern.compile("\\\"ts\\\"<='([^']+)'").matcher(sql);
                        if (!lower.find() || !upper.find())
                            throw new IllegalStateException("回读必须有时间边界");
                        Instant from = Instant.parse(lower.group(1)),
                                to = Instant.parse(upper.group(1));
                        for (var row : rows) {
                            Instant time = Instant.parse(row.get("ts").toString());
                            if (time.isBefore(from) || time.isAfter(to)) continue;
                            Object price =
                                    mode.get() == 1 && time.equals(start.plusSeconds(rowCount - 1))
                                            ? -42.
                                            : row.get("price");
                            values.add(Arrays.asList(row.get("ts"), "X", price));
                            if (mode.get() == 2 && time.equals(start.plusSeconds(rowCount - 1)))
                                values.add(Arrays.asList(row.get("ts"), "X", price));
                        }
                        values.add(Arrays.asList(from.toString(), "UNREQUESTED", 123.));
                        // 最后一段读完才出现新WAL，检验值匹配后的WAL门槛仍生效。
                        if (to.equals(start.plusSeconds(rowCount - 1)))
                            walStage.compareAndSet(4, 1);
                    }
                    byte[] body =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "columns",
                                            names.stream().map(n -> Map.of("name", n)).toList(),
                                            "dataset",
                                            values));
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var identity = new LinkedHashMap<String, Object>();
            identity.put("id", 1);
            identity.put("directoryName", "page_test~1");
            String target =
                    "market-" + VerifiedMarketDataStore.sha(json.writeValueAsBytes(identity));
            var store =
                    new VerifiedMarketDataStore(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "",
                            "",
                            "",
                            root);
            var page = new VerifiedMarketDataStore.PublicationPage(rowCount, byteLimit);
            var port =
                    new VerifiedBatchExecutor.Port<Map<String, Object>, String>() {
                        public void preflight() {}

                        public void send(List<Map<String, Object>> batch) {
                            assertEquals(rowCount, batch.size());
                        }

                        public List<Map<String, Object>> readback(List<String> keys)
                                throws Exception {
                            return store.readbackRows(definition, wanted, target, keys, page);
                        }

                        public boolean walSettled() throws Exception {
                            return store.settled("page_test");
                        }

                        public boolean uncertainSenderStopped() {
                            return true;
                        }
                    };
            var policy =
                    new VerifiedBatchExecutor.Policy(
                            rowCount, byteLimit, 1, Duration.ofSeconds(3), Duration.ofMillis(1));
            var success = new VerifiedBatchExecutor<>(policy, codec, port).execute(rows.iterator());
            assertEquals(VerifiedBatchExecutor.Status.VERIFIED, success.status());
            assertEquals(rowCount, success.verifiedRows());
            assertEquals((rowCount + 4999) / 5000, reads.get());
            var negative =
                    new VerifiedBatchExecutor.Policy(
                            rowCount, byteLimit, 1, Duration.ofMillis(50), Duration.ofMillis(1));
            for (int stage : List.of(1, 2, 3)) {
                walStage.set(stage);
                reads.set(0);
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                store.readbackRows(
                                        definition,
                                        wanted,
                                        "wrong-target",
                                        List.copyOf(wanted.keySet()),
                                        page));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                store.readbackRows(
                                        definition, wanted, target, List.of("unknown-key"), page));
                assertEquals(
                        VerifiedBatchExecutor.Status.IN_DOUBT,
                        new VerifiedBatchExecutor<>(negative, codec, port)
                                .execute(rows.iterator())
                                .status());
                assertEquals(0, reads.get(), "WAL未就绪时不能反复扫描数据行，状态=" + stage);
            }
            walStage.set(5);
            walReads.set(0);
            reads.set(0);
            var afterWal =
                    new VerifiedBatchExecutor<>(policy, codec, port).execute(rows.iterator());
            assertEquals(VerifiedBatchExecutor.Status.VERIFIED, afterWal.status());
            assertEquals(rowCount, afterWal.verifiedRows());
            // WAL 在分段之间追平时，先前缺失的段仍需重读，不能要求每段恰好只查一次。
            assertTrue(reads.get() >= (rowCount + 4999) / 5000);
            assertTrue(walReads.get() > 2);
            walStage.set(4);
            reads.set(0);
            assertEquals(
                    VerifiedBatchExecutor.Status.IN_DOUBT,
                    new VerifiedBatchExecutor<>(negative, codec, port)
                            .execute(rows.iterator())
                            .status());
            assertEquals((rowCount + 4999) / 5000, reads.get());
            walStage.set(0);
            mode.set(1);
            assertEquals(
                    VerifiedBatchExecutor.Status.IN_DOUBT,
                    new VerifiedBatchExecutor<>(negative, codec, port)
                            .execute(rows.iterator())
                            .status());
            mode.set(2);
            assertEquals(
                    VerifiedBatchExecutor.Status.IN_DOUBT,
                    new VerifiedBatchExecutor<>(negative, codec, port)
                            .execute(rows.iterator())
                            .status());
        } finally {
            server.stop(0);
        }
    }
}
