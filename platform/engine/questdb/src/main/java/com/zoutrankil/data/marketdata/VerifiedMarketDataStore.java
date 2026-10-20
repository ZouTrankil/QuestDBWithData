package com.zoutrankil.data.marketdata;

import static com.zoutrankil.data.domain.DatasetDefinition.StorageType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import io.questdb.client.Sender;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 复用正式同步账本及未决锁协议；外部市场负责来源规则，本类只负责有界写入和逐字段回读。 */
public final class VerifiedMarketDataStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record PublicationPage(int rows, int bytes) {
        public static final PublicationPage DEFAULT = new PublicationPage(5000, 4 * 1024 * 1024);

        public PublicationPage {
            if (rows < 1 || rows > 10000 || bytes < 1 || bytes > 8 * 1024 * 1024)
                throw new IllegalArgumentException("发布分页须为 1–10000 行且不超过 8 MiB");
        }
    }

    private final URI endpoint;
    private final String authorization, username, password;
    private final Path root, ledgerPath;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .proxy(
                            new ProxySelector() {
                                public List<Proxy> select(URI uri) {
                                    return List.of(Proxy.NO_PROXY);
                                }

                                public void connectFailed(URI u, SocketAddress a, IOException e) {}
                            })
                    .build();

    public VerifiedMarketDataStore(
            URI endpoint, String authorization, String username, String password, Path root) {
        if (!"http".equals(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null)
            throw new IllegalArgumentException("当前市场写入端点要求明确的 HTTP QuestDB 地址");
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.username = username;
        this.password = password;
        this.root = root.toAbsolutePath().normalize();
        ledgerPath = this.root.resolve("sync-ledger.sqlite3");
    }

    /** SQL 只接受程序维护的标识符和显式转义的来源值；错误信息不带账号或来源 key。 */
    public List<Map<String, Object>> query(String sql) throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint.toString().replaceAll("/$", "")
                                                + "/exec?limit=0,100001&query="
                                                + URLEncoder.encode(sql, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofMinutes(2))
                        .GET();
        if (!authorization.isBlank()) request.header("Authorization", authorization);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        var body = JSON.readTree(response.body());
        if (response.statusCode() != 200 || body.has("error"))
            throw new IllegalStateException(
                    "QuestDB 查询失败：" + body.path("error").asText("HTTP " + response.statusCode()));
        var result = new ArrayList<Map<String, Object>>();
        var columns = body.path("columns");
        for (var values : body.path("dataset")) {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 0; i < columns.size(); i++)
                row.put(
                        columns.get(i).path("name").asText(),
                        JSON.convertValue(values.get(i), Object.class));
            result.add(row);
        }
        return result;
    }

    public void ensure(DatasetDefinition definition) throws Exception {
        if (definition.objectKind() != DatasetDefinition.ObjectKind.TABLE)
            throw new IllegalArgumentException("视图不能通过普通表初始化入口建表");
        var columns =
                definition.columns().stream()
                        .map(c -> q(c.storageName()) + " " + c.storageType())
                        .toList();
        query(
                "CREATE TABLE IF NOT EXISTS "
                        + q(definition.objectName())
                        + " ("
                        + String.join(",", columns)
                        + ") TIMESTAMP("
                        + q(definition.designatedTimestamp())
                        + ") PARTITION BY "
                        + definition.partition()
                        + " WAL DEDUP UPSERT KEYS("
                        + String.join(
                                ",",
                                definition.dedupKey().stream()
                                        .map(VerifiedMarketDataStore::q)
                                        .toList())
                        + ")");
        requireSchema(definition);
    }

    private void requireSchema(DatasetDefinition d) throws Exception {
        var actual =
                query(
                        "SELECT \"column\",\"type\",designated,upsertKey FROM table_columns("
                                + literal(d.objectName())
                                + ")");
        if (actual.size() != d.columns().size())
            throw new IllegalStateException("市场目标列数与定义不符：" + d.objectName());
        for (int i = 0; i < actual.size(); i++) {
            var a = actual.get(i);
            var c = d.columns().get(i);
            if (!c.storageName().equals(a.get("column"))
                    || !c.storageType().name().equals(a.get("type"))
                    || !Objects.equals(d.dedupKey().contains(c.storageName()), a.get("upsertKey"))
                    || !Objects.equals(
                            d.designatedTimestamp().equals(c.storageName()), a.get("designated")))
                throw new IllegalStateException("市场目标字段、时间或去重键不符：" + c.storageName());
        }
        var tables =
                query(
                        "SELECT partitionBy,walEnabled,table_type FROM tables() WHERE table_name="
                                + literal(d.objectName()));
        if (tables.size() != 1
                || !d.partition().name().equals(tables.getFirst().get("partitionBy"))
                || !Boolean.TRUE.equals(tables.getFirst().get("walEnabled"))
                || !"T".equals(tables.getFirst().get("table_type")))
            throw new IllegalStateException("市场写入只接受定义中的 WAL 普通表");
    }

    private String identity(DatasetDefinition d) throws Exception {
        var rows =
                query(
                        "SELECT id,directoryName FROM tables() WHERE table_name="
                                + literal(d.objectName()));
        if (rows.size() != 1) throw new IllegalStateException("市场物理目标缺失");
        return "market-" + sha(JSON.writeValueAsBytes(rows.getFirst()));
    }

    public boolean settled(String table) throws Exception {
        DatasetDefinition.identifier(table);
        var rows =
                query(
                        "SELECT t.walEnabled,t.table_suspended,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name="
                                + literal(table));
        if (rows.size() != 1) return false;
        var row = rows.getFirst();
        return Boolean.TRUE.equals(row.get("walEnabled"))
                && Boolean.FALSE.equals(row.get("table_suspended"))
                && Boolean.FALSE.equals(row.get("suspended"))
                && row.get("writerTxn") instanceof Number a
                && row.get("sequencerTxn") instanceof Number b
                && a.longValue() == b.longValue()
                && row.get("bufferedTxnSize") instanceof Number c
                && c.longValue() == 0;
    }

    public SyncJobRunner.Result publish(
            DatasetDefinition d,
            List<Map<String, Object>> source,
            Path raw,
            String expectedSha,
            Map<String, Object> evidence)
            throws Exception {
        return publish(d, source, raw, expectedSha, evidence, PublicationPage.DEFAULT);
    }

    /** 批次布局单独冻结；扩大一次发送不扩大单次 HTTP 回读，也不跳过完整键值与 WAL 验证。 */
    public SyncJobRunner.Result publish(
            DatasetDefinition d,
            List<Map<String, Object>> source,
            Path raw,
            String expectedSha,
            Map<String, Object> evidence,
            PublicationPage page)
            throws Exception {
        try (var lease =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            return publishInside(
                    d, source, raw, expectedSha, evidence, false, Objects.requireNonNull(page));
        }
    }

    /** 补缺专用：调用方给出确认缺失的键，实际发送前再次拒绝任何已出现的键。 */
    public SyncJobRunner.Result publishMissing(
            DatasetDefinition d,
            List<Map<String, Object>> source,
            Path raw,
            String expectedSha,
            Map<String, Object> evidence)
            throws Exception {
        try (var lease =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            return publishInside(
                    d, source, raw, expectedSha, evidence, true, PublicationPage.DEFAULT);
        }
    }

    public void requireNoUnresolved(String dataset) throws Exception {
        if (!Files.isRegularFile(ledgerPath)) return;
        try (var connection =
                        DriverManager.getConnection(
                                "jdbc:sqlite:file:"
                                        + ledgerPath.toString().replace('\\', '/')
                                        + "?mode=ro");
                var statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM sync_interval_locks WHERE dataset_id=?")) {
            statement.setString(1, dataset);
            try (var rows = statement.executeQuery()) {
                if (rows.next() && rows.getLong(1) != 0)
                    throw new IllegalStateException("该数据集有未结束运行或未决锁，先核对原运行");
            }
        }
    }

    private SyncJobRunner.Result publishInside(
            DatasetDefinition d,
            List<Map<String, Object>> source,
            Path raw,
            String expectedSha,
            Map<String, Object> evidence,
            boolean missingOnly,
            PublicationPage page)
            throws Exception {
        d.requireCapability(DatasetDefinition.Capability.WRITE);
        if (d.objectKind() != DatasetDefinition.ObjectKind.TABLE)
            throw new IllegalArgumentException("正式发送只接受可写普通表");
        if (source.isEmpty() || source.size() > 100_000)
            throw new IllegalArgumentException("来源必须明确非空且不超过十万行");
        if ((source.size() + page.rows() - 1) / page.rows() > 100)
            throw new IllegalArgumentException("发布分页超过冻结的一百页预算");
        if (!sha(Files.readAllBytes(raw)).equals(expectedSha))
            throw new IllegalStateException("冻结的来源响应摘要已改变");
        var rows = source.stream().map(row -> normalize(d, row)).toList();
        var codec = codec(d);
        var wanted = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows)
            if (wanted.put(codec.key(row), row) != null)
                throw new IllegalArgumentException("来源业务键重复");
        ensure(d);
        String target = identity(d), run = "market-" + UUID.randomUUID();
        var prepared = root.resolve("prepared").resolve(run + ".json");
        Files.createDirectories(prepared.getParent());
        byte[] bytes =
                JSON.writeValueAsBytes(
                        Map.of(
                                "definition",
                                d,
                                "raw",
                                raw.toAbsolutePath().toString(),
                                "sourceSha256",
                                expectedSha,
                                "evidence",
                                evidence,
                                "rows",
                                rows,
                                "writerPid",
                                ProcessHandle.current().pid(),
                                "writerStartedAt",
                                ProcessHandle.current()
                                        .info()
                                        .startInstant()
                                        .orElseThrow()
                                        .toString(),
                                "publicationPage",
                                page));
        Files.write(prepared, bytes, StandardOpenOption.CREATE_NEW);
        String preparedSha = sha(bytes);
        var first =
                rows.stream()
                        .map(
                                row ->
                                        Instant.parse(row.get(d.designatedTimestamp()).toString())
                                                .atZone(ZoneOffset.UTC)
                                                .toLocalDate())
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        var last =
                rows.stream()
                        .map(
                                row ->
                                        Instant.parse(row.get(d.designatedTimestamp()).toString())
                                                .atZone(ZoneOffset.UTC)
                                                .toLocalDate())
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        var parameter = new Parameter(ParameterType.STRING, true, 4096, 1, Set.of());
        var parameters =
                new TreeMap<String, Parameter>(
                        Map.of("prepared_sha256", parameter, "source_sha256", parameter));
        var values =
                new TreeMap<String, Object>(
                        Map.of("prepared_sha256", preparedSha, "source_sha256", expectedSha));
        boolean custom = !page.equals(PublicationPage.DEFAULT);
        if (custom) {
            var integer = new Parameter(ParameterType.INTEGER, true, 20, 1, Set.of());
            parameters.put("publication_page_rows", integer);
            parameters.put("publication_page_bytes", integer);
            values.put("publication_page_rows", page.rows());
            values.put("publication_page_bytes", page.bytes());
        }
        var job =
                new SyncJobDefinition(
                        "market." + d.datasetId(),
                        custom ? 2 : 1,
                        d.datasetId(),
                        d.schemaVersion(),
                        d.owner(),
                        Set.of(Mode.INGEST),
                        Mode.INGEST,
                        parameters,
                        "market.external.frozen",
                        custom ? "market.source.pages.v2" : "market.source.pages",
                        "market.full_key_values",
                        new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                        Duration.ofMinutes(15),
                        new Budget(36600, 100, 100, 100000, page.bytes()),
                        0,
                        List.of(),
                        Frequency.MANUAL,
                        ZoneOffset.UTC,
                        true,
                        false);
        var frozen = job.freeze(Mode.INGEST, values, first, last, LocalDate.now(ZoneOffset.UTC));
        var port =
                new VerifiedBatchExecutor.Port<Map<String, Object>, String>() {
                    private volatile boolean senderStopped = true;

                    public void preflight() throws Exception {
                        if (!identity(d).equals(target)
                                || !sha(Files.readAllBytes(prepared)).equals(preparedSha)
                                || !sha(Files.readAllBytes(raw)).equals(expectedSha))
                            throw new IllegalStateException("物理目标或冻结来源发生变化，停止发布");
                        requireSchema(d);
                        if (!settled(d.objectName()))
                            throw new IllegalStateException("市场目标 WAL 尚未结清，禁止继续发送");
                    }

                    public void send(List<Map<String, Object>> batch) throws Exception {
                        preflight();
                        if (missingOnly && !presentKeys(d, batch).isEmpty())
                            throw new IllegalStateException("补缺键在发送前已存在，停止发送并核对原运行");
                        senderStopped = false;
                        try {
                            sendRows(d, batch, run);
                        } finally {
                            senderStopped = true;
                        }
                    }

                    public List<Map<String, Object>> readback(List<String> keys) throws Exception {
                        return readbackRows(d, wanted, target, keys, page);
                    }

                    public boolean walSettled() throws Exception {
                        return identity(d).equals(target) && settled(d.objectName());
                    }

                    public boolean uncertainSenderStopped() {
                        return senderStopped;
                    }
                };
        var adapter =
                new SyncJobRunner.Adapter<Map<String, Object>, String>() {
                    public void preflight(FrozenRequest request) throws Exception {
                        port.preflight();
                    }

                    public SyncJobRunner.SourceCompletion fetch(
                            FrozenRequest request,
                            SyncJobRunner.PageConsumer<Map<String, Object>> consumer,
                            BooleanSupplier cancelled)
                            throws Exception {
                        var original =
                                publicationPage(
                                        request.definition().version(),
                                        JSON.valueToTree(request.parameters()),
                                        request.definition().budget().maxBatchBytes());
                        if (!original.equals(page))
                            throw new IllegalStateException("冻结发布分页与本次布局不符");
                        int pages = 0;
                        for (int i = 0; i < rows.size(); i += original.rows()) {
                            if (cancelled.getAsBoolean()) throw new CancellationException();
                            if (!sha(Files.readAllBytes(prepared)).equals(preparedSha))
                                throw new IllegalStateException("规范化冻结文件已变化");
                            consumer.accept(
                                    new SyncJobRunner.Page<>(
                                            rows.subList(
                                                    i, Math.min(i + original.rows(), rows.size())),
                                            expectedSha,
                                            prepared.toString(),
                                            Integer.toString(i)));
                            pages++;
                        }
                        return new SyncJobRunner.SourceCompletion(
                                pages, rows.size(), true, prepared.toString());
                    }

                    public VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec() {
                        return codec;
                    }

                    public VerifiedBatchExecutor.Port<Map<String, Object>, String> port() {
                        return port;
                    }

                    // 容量取自原请求，不随当前配置漂移；原默认五千行及四 MiB 保持兼容。
                    public int maxWriteBatchRows(FrozenRequest request) {
                        return publicationPage(
                                        request.definition().version(),
                                        JSON.valueToTree(request.parameters()),
                                        request.definition().budget().maxBatchBytes())
                                .rows();
                    }

                    public Duration visibilityTimeout() {
                        return Duration.ofMinutes(2);
                    }
                };
        var ledger = new SyncRunLedger(ledgerPath);
        SyncJobRunner.Result result;
        try (var anchor = ledger.keepOpen()) {
            result =
                    new SyncJobRunner<Map<String, Object>, String>(
                                    ledger, new DatasetIntervalLock(ledgerPath))
                            .run(
                                    run,
                                    null,
                                    target,
                                    frozen,
                                    adapter,
                                    () -> Thread.currentThread().isInterrupted());
        }
        var receipt = root.resolve("receipts").resolve(run + ".json");
        Files.createDirectories(receipt.getParent());
        Files.write(
                receipt,
                JSON.writeValueAsBytes(
                        Map.of(
                                "result",
                                result,
                                "dataset",
                                d.datasetId(),
                                "prepared",
                                prepared.toString(),
                                "preparedSha256",
                                preparedSha,
                                "sourceSha256",
                                expectedSha,
                                "targetId",
                                target)),
                StandardOpenOption.CREATE_NEW);
        if (result.state() != SyncRunState.VERIFIED)
            throw new IllegalStateException(
                    "市场发布未验收，保留原运行和锁：" + run + " " + result.state() + " " + result.errorCode());
        return result;
    }

    static PublicationPage publicationPage(int version, JsonNode parameters, int bytes) {
        if (version == 1
                && !parameters.has("publication_page_rows")
                && !parameters.has("publication_page_bytes")
                && bytes == PublicationPage.DEFAULT.bytes()) return PublicationPage.DEFAULT;
        if (version != 2
                || !parameters.path("publication_page_rows").isIntegralNumber()
                || !parameters.path("publication_page_bytes").isIntegralNumber()
                || !parameters.path("publication_page_rows").canConvertToInt()
                || !parameters.path("publication_page_bytes").canConvertToInt())
            throw new IllegalStateException("原冻结发布分页缺失或版本不符");
        var page =
                new PublicationPage(
                        parameters.path("publication_page_rows").intValue(),
                        parameters.path("publication_page_bytes").intValue());
        if (page.bytes() != bytes) throw new IllegalStateException("原冻结发布字节预算不符");
        return page;
    }

    static PublicationPage restoredPublicationPage(JsonNode document, JsonNode request)
            throws Exception {
        var declared =
                publicationPage(
                        request.path("definition").path("version").asInt(),
                        request.path("parameters"),
                        request.path("definition").path("budget").path("maxBatchBytes").asInt());
        if (document.has("publicationPage")
                && (!document.path("publicationPage").path("rows").isIntegralNumber()
                        || !document.path("publicationPage").path("bytes").isIntegralNumber()))
            throw new IllegalStateException("原 prepared 发布分页必须是整数");
        var stored =
                document.has("publicationPage")
                        ? JSON.treeToValue(document.path("publicationPage"), PublicationPage.class)
                        : PublicationPage.DEFAULT;
        if (!stored.equals(declared)) throw new IllegalStateException("原 prepared 与冻结发布分页不符");
        return stored;
    }

    List<Map<String, Object>> readbackRows(
            DatasetDefinition d,
            Map<String, Map<String, Object>> wanted,
            String target,
            List<String> keys,
            PublicationPage page)
            throws Exception {
        if (keys.isEmpty() || keys.size() > page.rows() || !wanted.keySet().containsAll(keys))
            throw new IllegalArgumentException("回读键不属于冻结来源");
        int partSize = 5000;
        if (keys.size() > 1000
                && d.dedupKey().stream()
                        .filter(k -> !k.equals(d.designatedTimestamp()))
                        .anyMatch(
                                k ->
                                        keys.stream()
                                                        .map(wanted::get)
                                                        .map(r -> r.get(k))
                                                        .distinct()
                                                        .count()
                                                > 1000)) partSize = 1000;
        if (keys.size() > partSize) {
            var parts = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < keys.size(); i += partSize)
                parts.addAll(
                        readbackRows(
                                d,
                                wanted,
                                target,
                                keys.subList(i, Math.min(i + partSize, keys.size())),
                                page));
            return parts;
        }
        if (!identity(d).equals(target)) throw new IllegalStateException("回读物理目标已改变");
        // WAL追赶期间只查状态，避免反复扫描大批价格；值匹配后仍由执行器再次核对WAL。
        if (!settled(d.objectName())) return List.of();
        var expected = keys.stream().map(wanted::get).toList();
        String ts = d.designatedTimestamp();
        var times =
                expected.stream()
                        .map(row -> Instant.parse(row.get(ts).toString()))
                        .sorted()
                        .map(Instant::toString)
                        .toList();
        var predicates = new ArrayList<String>();
        predicates.add(q(ts) + ">=" + literal(times.getFirst()));
        predicates.add(q(ts) + "<=" + literal(times.getLast()));
        for (String key : d.dedupKey())
            if (!key.equals(ts)) {
                var values =
                        expected.stream()
                                .map(row -> row.get(key))
                                .distinct()
                                .map(
                                        v ->
                                                v instanceof Number
                                                        ? v.toString()
                                                        : literal(v.toString()))
                                .toList();
                predicates.add(q(key) + " IN (" + String.join(",", values) + ")");
            }
        var found =
                query(
                        "SELECT "
                                + String.join(
                                        ",",
                                        d.storageColumns().stream()
                                                .map(VerifiedMarketDataStore::q)
                                                .toList())
                                + " FROM "
                                + q(d.objectName())
                                + " WHERE "
                                + String.join(" AND ", predicates)
                                + " LIMIT 10001");
        if (found.size() > 10000) throw new IllegalStateException("回读范围超过明确上限");
        var codec = codec(d);
        var requested = new HashSet<>(keys);
        return found.stream()
                .map(row -> normalize(d, row))
                .filter(row -> requested.contains(codec.key(row)))
                .toList();
    }

    private void sendRows(DatasetDefinition d, List<Map<String, Object>> batch, String run)
            throws Exception {
        var builder =
                Sender.builder(Sender.Transport.WEBSOCKET)
                        .address(endpoint.getHost())
                        .port(endpoint.getPort() < 0 ? 80 : endpoint.getPort())
                        .autoFlushRows(10000)
                        .autoFlushBytes(4 * 1024 * 1024)
                        .autoFlushIntervalMillis(60000)
                        .initialConnectRetry(false)
                        .connectTimeoutMillis(10000);
        if (!password.isBlank()) builder.httpUsernamePassword(username, password);
        try (var sender = builder.build()) {
            for (var row : batch) {
                sender.table(d.objectName());
                // QWP 原生支持 INT；保留标签在前，所有字段按正式定义发送。
                for (var c : d.columns())
                    if (c.storageType() == StorageType.SYMBOL && row.get(c.storageName()) != null)
                        sender.symbol(c.storageName(), row.get(c.storageName()).toString());
                for (var c : d.columns()) {
                    Object value = row.get(c.storageName());
                    if (value == null
                            || c.storageType() == StorageType.SYMBOL
                            || c.storageName().equals(d.designatedTimestamp())) continue;
                    switch (c.storageType()) {
                        case DOUBLE ->
                                sender.doubleColumn(
                                        c.storageName(), ((Number) value).doubleValue());
                        case LONG ->
                                sender.longColumn(c.storageName(), ((Number) value).longValue());
                        case INT -> sender.intColumn(c.storageName(), ((Number) value).intValue());
                        case BOOLEAN -> sender.boolColumn(c.storageName(), (Boolean) value);
                        case TIMESTAMP ->
                                sender.timestampColumn(
                                        c.storageName(), Instant.parse(value.toString()));
                        case TIMESTAMP_NS ->
                                sender.timestampColumn(
                                        c.storageName(),
                                        epochNanos(Instant.parse(value.toString())),
                                        ChronoUnit.NANOS);
                        case VARCHAR, STRING ->
                                sender.stringColumn(c.storageName(), value.toString());
                        default ->
                                throw new IllegalArgumentException("未支持的市场写入类型：" + c.storageType());
                    }
                }
                Instant timestamp = Instant.parse(row.get(d.designatedTimestamp()).toString());
                boolean nanos =
                        d.columns().stream()
                                .anyMatch(
                                        c ->
                                                c.storageName().equals(d.designatedTimestamp())
                                                        && c.storageType()
                                                                == StorageType.TIMESTAMP_NS);
                if (nanos) sender.at(epochNanos(timestamp), ChronoUnit.NANOS);
                else sender.at(timestamp);
            }
            awaitPublication(sender);
        } catch (Exception failure) {
            var diagnostics = root.resolve("transport-errors");
            Files.createDirectories(diagnostics);
            // 只记录类型，不复制可能含账号的底层异常正文。
            Files.write(
                    diagnostics.resolve(run + "-" + UUID.randomUUID() + ".json"),
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    run,
                                    "exception",
                                    failure.getClass().getName(),
                                    "at",
                                    Instant.now().toString())),
                    StandardOpenOption.CREATE_NEW);
            throw failure;
        }
    }

    /** drain 覆盖已自动刷新的序号；空 flush 不产生新序号，不能因此误判已经发送的整批。 */
    static void awaitPublication(Sender sender) {
        if (!sender.drain(30000)) throw new IllegalStateException("QWP 未确认；保留原意图并核对实库");
    }

    /** 仅处理原进程已停止、整张目标表仍为零事务且零行的单切片；不自动重发任何部分可见的写入。 */
    public Map<String, Object> reconcileUndelivered(
            String run, String expectedPreparedSha, Path stoppedEvidence) throws Exception {
        try (var lease =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            return reconcileUndeliveredInside(run, expectedPreparedSha, stoppedEvidence);
        }
    }

    private Map<String, Object> reconcileUndeliveredInside(
            String run, String expectedPreparedSha, Path stoppedEvidence) throws Exception {
        if (!run.matches("market-[a-f0-9-]{36}")) throw new IllegalArgumentException("原运行标识非法");
        Path prepared = root.resolve("prepared").resolve(run + ".json"),
                receipt = root.resolve("receipts").resolve(run + ".json");
        byte[] frozen = Files.readAllBytes(prepared), stopped = Files.readAllBytes(stoppedEvidence);
        var card = JSON.readTree(Files.readAllBytes(receipt));
        var document = JSON.readTree(frozen);
        var stop = JSON.readTree(stopped);
        if (!sha(frozen).equals(expectedPreparedSha)
                || !card.path("preparedSha256").asText().equals(expectedPreparedSha)
                || !stop.path("writerStopped").asBoolean(false)
                || stop.path("evidence").asText().isBlank()
                || !stop.path("runId").asText().equals(run)
                || Instant.parse(stop.path("inspectedAt").asText())
                        .isBefore(Instant.now().minusSeconds(300)))
            throw new IllegalArgumentException("需要原冻结摘要及五分钟内取得的原发送进程停止证据");
        if (document.has("writerPid")) {
            var process = ProcessHandle.of(document.path("writerPid").asLong());
            if (process.isPresent()
                    && process.get().isAlive()
                    && process.get()
                            .info()
                            .startInstant()
                            .map(
                                    i ->
                                            i.toString()
                                                    .equals(
                                                            document.path("writerStartedAt")
                                                                    .asText()))
                            .orElse(true)) throw new IllegalStateException("原发送进程仍在运行");
        }
        var d = JSON.treeToValue(document.path("definition"), DatasetDefinition.class);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : document.path("rows"))
            rows.add(normalize(d, JSON.convertValue(row, Map.class)));
        if (rows.isEmpty() || rows.size() > 1000)
            throw new IllegalArgumentException("零投递核对入口仅接受单批一千行以内");
        var raw = Path.of(document.path("raw").asText());
        String sourceSha = document.path("sourceSha256").asText();
        if (!sha(Files.readAllBytes(raw)).equals(sourceSha)
                || !identity(d).equals(card.path("targetId").asText()))
            throw new IllegalStateException("来源或原物理目标变化");
        requireSchema(d);
        var ledger = SyncRunLedger.openExisting(ledgerPath);
        var original = ledger.getRun(run);
        var request = JSON.readTree(original.frozenJson());
        if (!original.targetId().equals(identity(d))
                || !request.path("parameters")
                        .path("prepared_sha256")
                        .asText()
                        .equals(expectedPreparedSha))
            throw new IllegalStateException("原账本与冻结来源不一致");
        var entries = ledger.entries(run, null, 1000);
        if (entries.size() != 3
                || entries.stream().anyMatch(e -> e.state() != SyncRunState.IN_DOUBT)
                || entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE).count() != 1)
            throw new IllegalStateException("仅支持原运行、尝试和唯一切片均为未决的零投递核对");
        var scope =
                new IntervalLockStore.Scope(
                        d.datasetId(),
                        LocalDate.parse(request.path("from").asText()),
                        LocalDate.parse(request.path("to").asText()));
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(run, scope);
        if (lease == null || !lease.inDoubt()) throw new IllegalStateException("原未决锁缺失");
        var before = query("SELECT count() rows FROM " + q(d.objectName()));
        var frontier =
                query(
                        "SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name="
                                + literal(d.objectName()));
        if (!settled(d.objectName())
                || ((Number) before.getFirst().get("rows")).longValue() != 0
                || frontier.size() != 1
                || ((Number) frontier.getFirst().get("writerTxn")).longValue() != 0
                || ((Number) frontier.getFirst().get("sequencerTxn")).longValue() != 0)
            throw new IllegalStateException("目标已有可见数据或事务，不能通过零投递入口恢复");
        Path folder = root.resolve("reconciliations").resolve(run + "-" + UUID.randomUUID());
        Files.createDirectories(folder);
        var intent =
                Map.of(
                        "runId",
                        run,
                        "preparedSha256",
                        expectedPreparedSha,
                        "targetId",
                        original.targetId(),
                        "stoppedEvidenceSha256",
                        sha(stopped),
                        "beforeRows",
                        before,
                        "beforeWal",
                        frontier,
                        "recoveryStartedAt",
                        Instant.now().toString());
        Files.write(
                folder.resolve("intent.json"),
                JSON.writeValueAsBytes(intent),
                StandardOpenOption.CREATE_NEW);
        // 原锁持续持有；明确的零投递证据先冻结，再完成原来同一批业务键，不新建发布运行。
        sendRows(d, rows, run);
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        List<Map<String, Object>> actual = List.of();
        var codec = codec(d);
        var expected = new TreeMap<String, String>();
        for (var row : rows)
            if (expected.put(codec.key(row), sha(codec.canonicalBytes(row))) != null)
                throw new IllegalStateException("原来源业务键重复");
        boolean matched = false;
        while (System.nanoTime() < deadline) {
            actual =
                    query(
                                    "SELECT "
                                            + String.join(
                                                    ",",
                                                    d.storageColumns().stream()
                                                            .map(VerifiedMarketDataStore::q)
                                                            .toList())
                                            + " FROM "
                                            + q(d.objectName())
                                            + " LIMIT 1001")
                            .stream()
                            .map(r -> normalize(d, r))
                            .toList();
            var found = new TreeMap<String, String>();
            boolean duplicate = false;
            for (var row : actual)
                if (found.put(codec.key(row), sha(codec.canonicalBytes(row))) != null)
                    duplicate = true;
            if (!duplicate
                    && expected.equals(found)
                    && settled(d.objectName())
                    && identity(d).equals(original.targetId())) {
                matched = true;
                break;
            }
            Thread.sleep(200);
        }
        if (!matched) throw new IllegalStateException("原运行恢复后仍未逐字段核对通过，保留原未决锁");
        Path readback = folder.resolve("readback.json");
        Files.write(
                readback,
                JSON.writeValueAsBytes(
                        Map.of(
                                "rows",
                                actual,
                                "sourceSha256",
                                sourceSha,
                                "targetId",
                                original.targetId(),
                                "walSettled",
                                true)),
                StandardOpenOption.CREATE_NEW);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("passed", true);
        proof.put("expectedRows", rows.size());
        proof.put("actualRows", actual.size());
        proof.put("matchedRows", rows.size());
        proof.put("mismatchedRows", 0);
        proof.put("duplicateKeys", 0);
        proof.put("missingKeys", 0);
        proof.put("sourceFingerprint", sourceSha);
        proof.put("readbackEvidence", readback + "#" + sha(Files.readAllBytes(readback)));
        proof.put("writerStopped", true);
        String payload =
                JSON.writeValueAsString(
                        Map.of(
                                "verification",
                                proof,
                                "reconciliationIntent",
                                folder.resolve("intent.json").toString()));
        for (var kind :
                List.of(
                        SyncRunLedger.Kind.SLICE,
                        SyncRunLedger.Kind.ATTEMPT,
                        SyncRunLedger.Kind.RUN))
            for (var entry : entries)
                if (entry.kind() == kind)
                    ledger.transition(entry.id(), entry.revision(), SyncRunState.VERIFIED, payload);
        locks.releaseAfterReconciliation(lease, true, true);
        var result =
                Map.<String, Object>of(
                        "runId",
                        run,
                        "state",
                        "VERIFIED",
                        "rows",
                        rows.size(),
                        "reconciliation",
                        folder.toString());
        Files.write(
                folder.resolve("receipt.json"),
                JSON.writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
        return result;
    }

    /** 显式核对原冻结运行；现有键必须逐字段一致，只在明确授权 repairMissing 时补已证实缺失的键。 */
    public Map<String, Object> reconcileFrozen(
            String run, String preparedSha, Path stoppedEvidence, boolean repairMissing)
            throws Exception {
        try (var publisher =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            if (!run.matches("market-[a-f0-9-]{36}")) throw new IllegalArgumentException("原运行标识非法");
            Path prepared = root.resolve("prepared/" + run + ".json");
            byte[] bytes = Files.readAllBytes(prepared),
                    stopBytes = Files.readAllBytes(stoppedEvidence);
            var document = JSON.readTree(bytes);
            var stop = JSON.readTree(stopBytes);
            var receipt =
                    JSON.readTree(Files.readAllBytes(root.resolve("receipts/" + run + ".json")));
            if (!sha(bytes).equals(preparedSha)
                    || !receipt.path("preparedSha256").asText().equals(preparedSha)
                    || !stop.path("writerStopped").asBoolean(false)
                    || !stop.path("runId").asText().equals(run)
                    || stop.path("evidence").asText().isBlank()
                    || Instant.parse(stop.path("inspectedAt").asText())
                            .isBefore(Instant.now().minusSeconds(300))
                    || !document.has("writerPid"))
                throw new IllegalArgumentException("原摘要或原进程停止证据不符");
            var process = ProcessHandle.of(document.path("writerPid").asLong());
            if (process.isPresent()
                    && process.get().isAlive()
                    && process.get()
                            .info()
                            .startInstant()
                            .map(
                                    i ->
                                            i.toString()
                                                    .equals(
                                                            document.path("writerStartedAt")
                                                                    .asText()))
                            .orElse(true)) throw new IllegalStateException("原发送进程仍在运行");
            var d = JSON.treeToValue(document.path("definition"), DatasetDefinition.class);
            var rows = new ArrayList<Map<String, Object>>();
            for (var r : document.path("rows"))
                rows.add(normalize(d, JSON.convertValue(r, Map.class)));
            if (rows.isEmpty() || rows.size() > 10000)
                throw new IllegalArgumentException("此显式恢复只支持首个切片阶段中断且总量不超过一万行");
            var raw = Path.of(document.path("raw").asText());
            String sourceSha = document.path("sourceSha256").asText();
            if (!sha(Files.readAllBytes(raw)).equals(sourceSha))
                throw new IllegalStateException("原来源文件变化");
            requireSchema(d);
            var ledger = SyncRunLedger.openExisting(ledgerPath);
            var original = ledger.getRun(run);
            var request = JSON.readTree(original.frozenJson());
            var entries = ledger.entries(run, null, 1000);
            if (!original.targetId().equals(identity(d))
                    || !receipt.path("targetId").asText().equals(original.targetId())
                    || !request.path("parameters")
                            .path("prepared_sha256")
                            .asText()
                            .equals(preparedSha)
                    || !request.path("parameters").path("source_sha256").asText().equals(sourceSha)
                    || entries.size() != 3
                    || entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE).count()
                            != 1
                    || entries.stream()
                            .anyMatch(
                                    e ->
                                            !Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT)
                                                    .contains(e.state())))
                throw new IllegalStateException("原账本、物理目标或首切片恢复条件不符");
            var page = restoredPublicationPage(document, request);
            var locks = new DatasetIntervalLock(ledgerPath);
            var lease =
                    locks.findOwned(
                            run,
                            new IntervalLockStore.Scope(
                                    d.datasetId(),
                                    LocalDate.parse(request.path("from").asText()),
                                    LocalDate.parse(request.path("to").asText())));
            if (lease == null || !lease.inDoubt() || !settled(d.objectName()))
                throw new IllegalStateException("原未决锁缺失或 WAL 尚未结清");
            var before = compareExisting(d, rows, original.targetId());
            if (!repairMissing && !before.missing().isEmpty())
                throw new IllegalStateException(
                        "存在 " + before.missing().size() + " 个已证实缺失键；只读核对不会补写");
            Path folder = root.resolve("reconciliations/" + run + "-" + UUID.randomUUID());
            Files.createDirectories(folder);
            Files.write(
                    folder.resolve("intent.json"),
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    run,
                                    "preparedSha256",
                                    preparedSha,
                                    "targetId",
                                    original.targetId(),
                                    "stoppedEvidenceSha256",
                                    sha(stopBytes),
                                    "existingRows",
                                    before.actual().size(),
                                    "missingRows",
                                    before.missing(),
                                    "repairMissingAuthorized",
                                    repairMissing,
                                    "at",
                                    Instant.now().toString())),
                    StandardOpenOption.CREATE_NEW);
            for (int i = 0; i < before.missing().size(); i += 5000) {
                var batch =
                        before.missing().subList(i, Math.min(i + 5000, before.missing().size()));
                // 发送前再次核对；期间出现的新值不能被原冻结值覆盖。
                var latest = compareExisting(d, batch, original.targetId());
                if (latest.missing().isEmpty()) continue;
                if (!settled(d.objectName())
                        || !sha(Files.readAllBytes(prepared)).equals(preparedSha)
                        || !sha(Files.readAllBytes(raw)).equals(sourceSha))
                    throw new IllegalStateException("补缺前来源或 WAL 变化");
                sendRows(d, latest.missing(), run);
                long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
                while (!settled(d.objectName())) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("补缺 WAL 尚未结清，保留原锁");
                    Thread.sleep(100);
                }
            }
            var after = compareExisting(d, rows, original.targetId());
            if (!after.missing().isEmpty()
                    || !settled(d.objectName())
                    || !identity(d).equals(original.targetId()))
                throw new IllegalStateException("原冻结来源仍未完全回读，保留原锁");
            Path readback = folder.resolve("readback.json");
            Files.write(
                    readback,
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "rows",
                                    after.actual(),
                                    "sourceSha256",
                                    sourceSha,
                                    "targetId",
                                    original.targetId(),
                                    "walSettled",
                                    true)),
                    StandardOpenOption.CREATE_NEW);
            String reference = readback + "#" + sha(Files.readAllBytes(readback));
            for (var kind :
                    List.of(
                            SyncRunLedger.Kind.SLICE,
                            SyncRunLedger.Kind.ATTEMPT,
                            SyncRunLedger.Kind.RUN))
                for (var entry : entries)
                    if (entry.kind() == kind) {
                        int size =
                                kind == SyncRunLedger.Kind.SLICE
                                        ? Math.min(page.rows(), rows.size())
                                        : rows.size();
                        var proof =
                                Map.of(
                                        "passed",
                                        true,
                                        "expectedRows",
                                        size,
                                        "actualRows",
                                        size,
                                        "matchedRows",
                                        size,
                                        "mismatchedRows",
                                        0,
                                        "duplicateKeys",
                                        0,
                                        "missingKeys",
                                        0,
                                        "sourceFingerprint",
                                        sourceSha,
                                        "readbackEvidence",
                                        reference,
                                        "writerStopped",
                                        true);
                        ledger.transition(
                                entry.id(),
                                entry.revision(),
                                SyncRunState.VERIFIED,
                                JSON.writeValueAsString(
                                        Map.of(
                                                "verification",
                                                proof,
                                                "sourceComplete",
                                                true,
                                                "returnedRows",
                                                rows.size(),
                                                "supplementalFrozenRows",
                                                Math.max(0, rows.size() - page.rows()),
                                                "reconciliationIntent",
                                                folder.resolve("intent.json").toString())));
                    }
            locks.releaseAfterReconciliation(lease, true, true);
            var result =
                    Map.<String, Object>of(
                            "runId",
                            run,
                            "state",
                            "VERIFIED",
                            "rows",
                            rows.size(),
                            "repairedMissingRows",
                            before.missing().size(),
                            "reconciliation",
                            folder.toString());
            Files.write(
                    folder.resolve("receipt.json"),
                    JSON.writeValueAsBytes(result),
                    StandardOpenOption.CREATE_NEW);
            return result;
        }
    }

    @FunctionalInterface
    interface FrozenTailSender {
        void send(DatasetDefinition definition, List<Map<String, Object>> rows, String run)
                throws Exception;
    }

    /** 显式续接原冻结的未发送尾页；已提交前缀必须全部逐字段可见，不能重发或修补前缀。 */
    public Map<String, Object> reconcileFrozenTail(
            String run, String preparedSha, Path stoppedEvidence, boolean appendUnsubmitted)
            throws Exception {
        return reconcileFrozenTail(
                run, preparedSha, stoppedEvidence, appendUnsubmitted, this::sendRows);
    }

    Map<String, Object> reconcileFrozenTail(
            String run,
            String preparedSha,
            Path stoppedEvidence,
            boolean appendUnsubmitted,
            FrozenTailSender sender)
            throws Exception {
        if (!appendUnsubmitted) throw new IllegalArgumentException("必须显式授权续接原未发送尾部");
        try (var publisher =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            var context = frozenRecovery(run, preparedSha, stoppedEvidence);
            var d = context.definition();
            var rows = context.rows();
            var ledger = context.ledger();
            var page = context.page();
            String sourceSha = context.sourceSha(), target = context.original().targetId();
            var prefix =
                    CompleteFrozenSlices.requireSubmittedPrefix(
                            ledger,
                            run,
                            context.prepared(),
                            d,
                            rows,
                            sourceSha,
                            page,
                            context.entries());
            int submitted = prefix.stream().mapToInt(CompleteFrozenSlices.Slice::rows).sum();
            var locks = new DatasetIntervalLock(ledgerPath);
            var lease =
                    locks.findOwned(
                            run,
                            new IntervalLockStore.Scope(
                                    d.datasetId(),
                                    LocalDate.parse(context.request().path("from").asText()),
                                    LocalDate.parse(context.request().path("to").asText())));
            if (lease == null || !lease.inDoubt()) throw new IllegalStateException("原未决锁缺失");
            var frontier = reconciliationFrontier(d);
            var compared = compareExisting(d, rows, target);
            var codec = codec(d);
            var expectedMissing = new HashSet<String>();
            for (var row : rows.subList(submitted, rows.size()))
                expectedMissing.add(codec.key(row));
            var actualMissing = new HashSet<String>();
            for (var row : compared.missing()) actualMissing.add(codec.key(row));
            if (!expectedMissing.equals(actualMissing) || compared.actual().size() != submitted)
                throw new IllegalStateException("已提交前缀未齐或尾部已有数据；禁止续接或重发");
            requireFrozenRecoveryStable(context, preparedSha, frontier);
            Path folder = root.resolve("reconciliations/" + run + "-tail-" + UUID.randomUUID());
            Files.createDirectories(folder);
            var actual = new HashMap<String, Map<String, Object>>();
            for (var row : compared.actual()) actual.put(codec.key(row), row);
            String initialProof =
                    writeReconciliationReadback(
                            folder.resolve("submitted-prefix.json"),
                            rows.subList(0, submitted).stream()
                                    .map(r -> actual.get(codec.key(r)))
                                    .toList(),
                            sourceSha,
                            target);
            var intent = new LinkedHashMap<String, Object>();
            intent.put("runId", run);
            intent.put("preparedSha256", preparedSha);
            intent.put("sourceSha256", sourceSha);
            intent.put("targetId", target);
            intent.put("submittedRows", submitted);
            intent.put("unsubmittedRows", rows.size() - submitted);
            intent.put("stoppedEvidenceSha256", sha(context.stopBytes()));
            intent.put("submittedPrefixReadback", initialProof);
            intent.put("appendUnsubmittedAuthorized", true);
            intent.put("writerPid", ProcessHandle.current().pid());
            intent.put(
                    "writerStartedAt",
                    ProcessHandle.current().info().startInstant().orElseThrow().toString());
            intent.put("at", Instant.now().toString());
            Files.write(
                    folder.resolve("intent.json"),
                    JSON.writeValueAsBytes(intent),
                    StandardOpenOption.CREATE_NEW);
            requireFrozenRecoveryStable(context, preparedSha, frontier);
            // 只核对未决前缀页；此前成功页的条目、修订、事件和原证明全部保持。
            for (var slice : prefix)
                if (slice.entry().state() != SyncRunState.VERIFIED) {
                    String evidence =
                            writeReconciliationReadback(
                                    folder.resolve(slice.entry().id() + "-prefix.json"),
                                    rows
                                            .subList(slice.offset(), slice.offset() + slice.rows())
                                            .stream()
                                            .map(r -> actual.get(codec.key(r)))
                                            .toList(),
                                    sourceSha,
                                    target);
                    ledger.transition(
                            slice.entry().id(),
                            slice.entry().revision(),
                            SyncRunState.VERIFIED,
                            reconciliationProof(slice.rows(), sourceSha, evidence, folder));
                }
            String attempt =
                    context.entries().stream()
                            .filter(e -> e.kind() == SyncRunLedger.Kind.ATTEMPT)
                            .findFirst()
                            .orElseThrow()
                            .id();
            var completedTailProofs = new ArrayList<Map<String, Object>>();
            for (int offset = submitted; offset < rows.size(); offset += page.rows()) {
                var entries = ledger.entries(run, null, 1000);
                var verifiedPrefix =
                        CompleteFrozenSlices.requireSubmittedPrefix(
                                ledger, run, context.prepared(), d, rows, sourceSha, page, entries);
                if (verifiedPrefix.stream().mapToInt(CompleteFrozenSlices.Slice::rows).sum()
                        != offset) throw new IllegalStateException("原冻结尾部游标或已验收前缀变化");
                var tail = rows.subList(offset, Math.min(offset + page.rows(), rows.size()));
                if (compareExisting(d, tail, target).missing().size() != tail.size())
                    throw new IllegalStateException("原尾页已有数据，先核对，不能重复发送");
                if (!sha(Files.readAllBytes(context.prepared())).equals(preparedSha)
                        || !sha(Files.readAllBytes(context.raw())).equals(sourceSha)
                        || !identity(d).equals(target))
                    throw new IllegalStateException("尾部发送前原输入或物理目标变化");
                requireSchema(d);
                reconciliationFrontier(d);
                byte[] prefixBytes =
                        JSON.writeValueAsBytes(
                                Map.of(
                                        "initialReadback",
                                        initialProof,
                                        "completedTailPages",
                                        completedTailProofs,
                                        "verifiedPrefixRows",
                                        offset,
                                        "sourceSha256",
                                        sourceSha,
                                        "targetId",
                                        target));
                Path prefixPath = folder.resolve("verified-prefix-" + offset + ".json");
                Files.write(prefixPath, prefixBytes, StandardOpenOption.CREATE_NEW);
                var authorization = new LinkedHashMap<String, Object>();
                authorization.put("preparedSha256", preparedSha);
                authorization.put("sourceSha256", sourceSha);
                authorization.put("sourceRows", rows.size());
                authorization.put("submittedRows", offset);
                authorization.put("cursor", Integer.toString(offset));
                authorization.put("tailRows", tail.size());
                authorization.put("readbackEvidence", prefixPath + "#" + sha(prefixBytes));
                authorization.put("reconciliationIntent", folder.resolve("intent.json").toString());
                String slice = "slice-" + UUID.randomUUID();
                ledger.createFrozenTailSlice(
                        slice,
                        run,
                        attempt,
                        context.original().frozenJson(),
                        entries,
                        JSON.writeValueAsString(authorization));
                String source =
                        JSON.writeValueAsString(
                                Map.of(
                                        "returnedRows",
                                        tail.size(),
                                        "sourceFingerprint",
                                        sourceSha,
                                        "responseEvidence",
                                        context.prepared().toString(),
                                        "cursor",
                                        Integer.toString(offset)));
                ledger.transition(slice, 1, SyncRunState.FETCHED, source);
                ledger.transition(slice, 2, SyncRunState.VALIDATED, source);
                var wanted = new LinkedHashMap<String, Map<String, Object>>();
                for (var row : tail) wanted.put(codec.key(row), row);
                boolean[] sent = {false};
                String originalSubmission =
                        ledger.events(prefix.getLast().entry().id(), 3, 1).getFirst().payloadJson();
                var port =
                        new VerifiedBatchExecutor.Port<Map<String, Object>, String>() {
                            private volatile boolean senderStopped = true;

                            public void preflight() throws Exception {
                                ledger.requireUncancelledRevision4SubmissionEvent(
                                        run, prefix.getLast().entry().id(), originalSubmission);
                                if (!identity(d).equals(target)
                                        || !sha(Files.readAllBytes(context.prepared()))
                                                .equals(preparedSha)
                                        || !sha(Files.readAllBytes(context.raw()))
                                                .equals(sourceSha))
                                    throw new IllegalStateException("尾页输入或目标变化");
                                requireSchema(d);
                                reconciliationFrontier(d);
                            }

                            public void send(List<Map<String, Object>> batch) throws Exception {
                                preflight();
                                if (compareExisting(d, batch, target).missing().size()
                                        != batch.size())
                                    throw new IllegalStateException("尾页键在发送前已存在");
                                if (!sent[0]) {
                                    ledger.transition(slice, 3, SyncRunState.SUBMITTED, source);
                                    sent[0] = true;
                                }
                                ledger.requireUncancelledRevision4SubmissionEvent(
                                        run, slice, source);
                                senderStopped = false;
                                try {
                                    sender.send(d, batch, run);
                                } finally {
                                    senderStopped = true;
                                }
                            }

                            public List<Map<String, Object>> readback(List<String> keys)
                                    throws Exception {
                                return readbackRows(d, wanted, target, keys, page);
                            }

                            public boolean walSettled() throws Exception {
                                return identity(d).equals(target) && settled(d.objectName());
                            }

                            public boolean uncertainSenderStopped() {
                                return senderStopped;
                            }
                        };
                int capacity = Math.min(page.rows(), tail.size());
                var policy =
                        new VerifiedBatchExecutor.Policy(
                                capacity,
                                page.bytes(),
                                Math.min(100000 / capacity, tail.size()),
                                Duration.ofMinutes(2),
                                Duration.ofMillis(100));
                var result =
                        new VerifiedBatchExecutor<>(policy, codec, port).execute(tail.iterator());
                if (result.status() != VerifiedBatchExecutor.Status.VERIFIED) {
                    if (sent[0])
                        ledger.transition(
                                slice,
                                4,
                                SyncRunState.IN_DOUBT,
                                JSON.writeValueAsString(Map.of("writeResult", result)));
                    throw new IllegalStateException("原尾页尚未完整验收，保留原运行和锁，不重发");
                }
                boolean unknown =
                        result.receipts().stream()
                                .anyMatch(
                                        x ->
                                                x.delivery()
                                                        != VerifiedBatchExecutor.Delivery
                                                                .ACKNOWLEDGED);
                ledger.transition(
                        slice,
                        4,
                        unknown ? SyncRunState.IN_DOUBT : SyncRunState.ACKNOWLEDGED,
                        JSON.writeValueAsString(Map.of("writeResult", result)));
                var tailReadback = compareExisting(d, tail, target);
                if (!tailReadback.missing().isEmpty()) throw new IllegalStateException("尾页证明生成前缺键");
                var byKey = new HashMap<String, Map<String, Object>>();
                for (var row : tailReadback.actual()) byKey.put(codec.key(row), row);
                String proof =
                        writeReconciliationReadback(
                                folder.resolve(slice + "-readback.json"),
                                tail.stream().map(r -> byKey.get(codec.key(r))).toList(),
                                sourceSha,
                                target);
                ledger.transition(
                        slice,
                        5,
                        SyncRunState.VERIFIED,
                        reconciliationProof(tail.size(), sourceSha, proof, folder));
                completedTailProofs.add(
                        Map.of(
                                "sliceId",
                                slice,
                                "offset",
                                offset,
                                "rows",
                                tail.size(),
                                "readbackEvidence",
                                proof));
            }
            // 全部原冻结页现在均有真实原运行子页；继续沿原只读协议完成全量回读与父记录。
            var completed =
                    new LinkedHashMap<String, Object>(
                            reconcileCompleteFrozenLocked(run, preparedSha, stoppedEvidence));
            completed.put("appendedUnsubmittedRows", rows.size() - submitted);
            completed.put("replayedSubmittedRows", 0);
            completed.put("repairedMissingRows", rows.size() - submitted);
            completed.put("tailReconciliation", folder.toString());
            Files.write(
                    folder.resolve("receipt.json"),
                    JSON.writeValueAsBytes(completed),
                    StandardOpenOption.CREATE_NEW);
            return Collections.unmodifiableMap(completed);
        }
    }

    private void requireFrozenRecoveryStable(
            FrozenRecovery context, String preparedSha, Map<String, Object> frontier)
            throws Exception {
        if (!frontier.equals(reconciliationFrontier(context.definition()))
                || !context.original().targetId().equals(identity(context.definition()))
                || !preparedSha.equals(sha(Files.readAllBytes(context.prepared())))
                || !context.sourceSha().equals(sha(Files.readAllBytes(context.raw())))
                || !context.entries()
                        .equals(context.ledger().entries(context.original().id(), null, 1000)))
            throw new IllegalStateException("原前缀核对期间来源、账本、物理目标或 WAL 变化");
    }

    /** 全部分页都已提交时，只核对原运行；缺键或错值均保留原锁，不发送任何行情。 */
    public Map<String, Object> reconcileCompleteFrozen(
            String run, String preparedSha, Path stoppedEvidence) throws Exception {
        try (var publisher =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            return reconcileCompleteFrozenLocked(run, preparedSha, stoppedEvidence);
        }
    }

    private Map<String, Object> reconcileCompleteFrozenLocked(
            String run, String preparedSha, Path stoppedEvidence) throws Exception {
        var context = frozenRecovery(run, preparedSha, stoppedEvidence);
        Path prepared = context.prepared(), raw = context.raw();
        byte[] stopBytes = context.stopBytes();
        var d = context.definition();
        var rows = context.rows();
        String sourceSha = context.sourceSha();
        var ledger = context.ledger();
        var original = context.original();
        var request = context.request();
        var entries = context.entries();
        var page = context.page();
        var slices =
                CompleteFrozenSlices.require(
                        ledger, run, prepared, d, rows, sourceSha, page, entries);
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease =
                locks.findOwned(
                        run,
                        new IntervalLockStore.Scope(
                                d.datasetId(),
                                LocalDate.parse(request.path("from").asText()),
                                LocalDate.parse(request.path("to").asText())));
        if (lease == null || !lease.inDoubt()) throw new IllegalStateException("原未决锁缺失");
        var frontier = reconciliationFrontier(d);
        var compared = compareExisting(d, rows, original.targetId());
        if (!compared.missing().isEmpty()) throw new IllegalStateException("完整来源仍有缺失键；只读核对不会补写");
        if (!frontier.equals(reconciliationFrontier(d))
                || !original.targetId().equals(identity(d))
                || !preparedSha.equals(sha(Files.readAllBytes(prepared)))
                || !sourceSha.equals(sha(Files.readAllBytes(raw)))
                || !entries.equals(ledger.entries(run, null, 1000)))
            throw new IllegalStateException("核对期间来源、账本或 WAL 版本变化");
        Path folder = root.resolve("reconciliations/" + run + "-" + UUID.randomUUID());
        Files.createDirectories(folder);
        Files.write(
                folder.resolve("intent.json"),
                JSON.writeValueAsBytes(
                        Map.of(
                                "runId",
                                run,
                                "preparedSha256",
                                preparedSha,
                                "sourceSha256",
                                sourceSha,
                                "targetId",
                                original.targetId(),
                                "stoppedEvidenceSha256",
                                sha(stopBytes),
                                "readOnly",
                                true,
                                "walFrontier",
                                frontier,
                                "rows",
                                rows.size(),
                                "at",
                                Instant.now().toString())),
                StandardOpenOption.CREATE_NEW);
        var actual = new HashMap<String, Map<String, Object>>();
        var codec = codec(d);
        for (var row : compared.actual()) actual.put(codec.key(row), row);
        String full =
                writeReconciliationReadback(
                        folder.resolve("readback.json"),
                        rows.stream().map(r -> actual.get(codec.key(r))).toList(),
                        sourceSha,
                        original.targetId());
        var proofs = new HashMap<String, String>();
        for (var slice : slices)
            if (slice.entry().state() != SyncRunState.VERIFIED) {
                var part =
                        rows.subList(slice.offset(), slice.offset() + slice.rows()).stream()
                                .map(r -> actual.get(codec.key(r)))
                                .toList();
                proofs.put(
                        slice.entry().id(),
                        writeReconciliationReadback(
                                folder.resolve(slice.entry().id() + ".json"),
                                part,
                                sourceSha,
                                original.targetId()));
            }
        // 证明先落盘，最后再次检查稳定版本；已成功页保持原修订和原证明。
        if (!frontier.equals(reconciliationFrontier(d))
                || !original.targetId().equals(identity(d))
                || !entries.equals(ledger.entries(run, null, 1000))
                || !preparedSha.equals(sha(Files.readAllBytes(prepared)))
                || !sourceSha.equals(sha(Files.readAllBytes(raw))))
            throw new IllegalStateException("推进检查点前版本变化");
        for (var slice : slices)
            if (slice.entry().state() != SyncRunState.VERIFIED)
                ledger.transition(
                        slice.entry().id(),
                        slice.entry().revision(),
                        SyncRunState.VERIFIED,
                        reconciliationProof(
                                slice.rows(), sourceSha, proofs.get(slice.entry().id()), folder));
        for (var kind : List.of(SyncRunLedger.Kind.ATTEMPT, SyncRunLedger.Kind.RUN))
            for (var entry : entries)
                if (entry.kind() == kind && entry.state() != SyncRunState.VERIFIED)
                    ledger.transition(
                            entry.id(),
                            entry.revision(),
                            SyncRunState.VERIFIED,
                            reconciliationProof(rows.size(), sourceSha, full, folder));
        locks.releaseAfterReconciliation(lease, true, true);
        var result =
                Map.<String, Object>of(
                        "runId",
                        run,
                        "state",
                        "VERIFIED",
                        "rows",
                        rows.size(),
                        "pages",
                        slices.size(),
                        "repairedMissingRows",
                        0,
                        "reconciliation",
                        folder.toString());
        Files.write(
                folder.resolve("receipt.json"),
                JSON.writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
        return result;
    }

    private record FrozenRecovery(
            Path prepared,
            byte[] stopBytes,
            DatasetDefinition definition,
            List<Map<String, Object>> rows,
            Path raw,
            String sourceSha,
            SyncRunLedger ledger,
            SyncRunLedger.Run original,
            JsonNode request,
            List<SyncRunLedger.Entry> entries,
            PublicationPage page) {}

    /** 两种恢复共用原来源、停止证据、物理目标与冻结预算校验，不修改原请求。 */
    private FrozenRecovery frozenRecovery(String run, String preparedSha, Path stoppedEvidence)
            throws Exception {
        if (!run.matches("market-[a-f0-9-]{36}")) throw new IllegalArgumentException("原运行标识非法");
        Path prepared = root.resolve("prepared/" + run + ".json");
        byte[] bytes = Files.readAllBytes(prepared),
                stopBytes = Files.readAllBytes(stoppedEvidence);
        var document = JSON.readTree(bytes);
        var stop = JSON.readTree(stopBytes);
        var receipt = JSON.readTree(Files.readAllBytes(root.resolve("receipts/" + run + ".json")));
        Instant inspected = Instant.parse(stop.path("inspectedAt").asText());
        if (!sha(bytes).equals(preparedSha)
                || !receipt.path("preparedSha256").asText().equals(preparedSha)
                || !stop.path("writerStopped").asBoolean(false)
                || !run.equals(stop.path("runId").asText())
                || stop.path("evidence").asText().isBlank()
                || inspected.isBefore(Instant.now().minusSeconds(300))
                || inspected.isAfter(Instant.now().plusSeconds(5))
                || document.path("writerPid").asLong() <= 0)
            throw new IllegalArgumentException("原摘要或新鲜的原进程停止证据不符");
        Instant started = Instant.parse(document.path("writerStartedAt").asText());
        var process = ProcessHandle.of(document.path("writerPid").asLong());
        if (process.isPresent()
                && process.get().isAlive()
                && process.get().info().startInstant().map(started::equals).orElse(true))
            throw new IllegalStateException("原发送进程仍在运行");
        var d = JSON.treeToValue(document.path("definition"), DatasetDefinition.class);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : document.path("rows"))
            rows.add(normalize(d, JSON.convertValue(row, Map.class)));
        Path raw = Path.of(document.path("raw").asText());
        String sourceSha = document.path("sourceSha256").asText();
        if (!sha(Files.readAllBytes(raw)).equals(sourceSha))
            throw new IllegalStateException("原来源文件变化");
        requireSchema(d);
        var ledger = SyncRunLedger.openExisting(ledgerPath);
        var original = ledger.getRun(run);
        var request = JSON.readTree(original.frozenJson());
        var budget = request.path("definition").path("budget");
        var entries = ledger.entries(run, null, 1000);
        var page = restoredPublicationPage(document, request);
        if (rows.isEmpty()
                || rows.size() > 100000
                || rows.size() > budget.path("maxRows").asInt()
                || (rows.size() + page.rows() - 1) / page.rows()
                        > Math.min(
                                100,
                                Math.min(
                                        budget.path("maxPages").asInt(),
                                        budget.path("maxSlices").asInt())))
            throw new IllegalStateException("来源超出原冻结行数或分页预算");
        if (!d.datasetId().equals(request.path("definition").path("datasetId").asText())
                || d.schemaVersion() != request.path("definition").path("datasetVersion").asInt()
                || !original.targetId().equals(identity(d))
                || !receipt.path("targetId").asText().equals(original.targetId())
                || !request.path("parameters").path("prepared_sha256").asText().equals(preparedSha)
                || !request.path("parameters").path("source_sha256").asText().equals(sourceSha))
            throw new IllegalStateException("原账本、来源或物理目标不符");
        return new FrozenRecovery(
                prepared,
                stopBytes,
                d,
                List.copyOf(rows),
                raw,
                sourceSha,
                ledger,
                original,
                request,
                entries,
                page);
    }

    private Map<String, Object> reconciliationFrontier(DatasetDefinition d) throws Exception {
        if (!settled(d.objectName())) throw new IllegalStateException("原目标 WAL 尚未结清");
        var rows =
                query(
                        "SELECT writerTxn,sequencerTxn,suspended,bufferedTxnSize FROM wal_tables() WHERE name="
                                + literal(d.objectName()));
        if (rows.size() != 1
                || !Boolean.FALSE.equals(rows.getFirst().get("suspended"))
                || !(rows.getFirst().get("writerTxn") instanceof Number a)
                || !(rows.getFirst().get("sequencerTxn") instanceof Number b)
                || a.longValue() != b.longValue()
                || !(rows.getFirst().get("bufferedTxnSize") instanceof Number c)
                || c.longValue() != 0) throw new IllegalStateException("原目标 WAL 版本不稳定");
        return rows.getFirst();
    }

    private String writeReconciliationReadback(
            Path path, List<Map<String, Object>> rows, String sourceSha, String target)
            throws Exception {
        byte[] bytes =
                JSON.writeValueAsBytes(
                        Map.of(
                                "rows",
                                rows,
                                "sourceSha256",
                                sourceSha,
                                "targetId",
                                target,
                                "walSettled",
                                true));
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        return path + "#" + sha(bytes);
    }

    private String reconciliationProof(int rows, String sourceSha, String evidence, Path folder)
            throws Exception {
        var proof =
                Map.of(
                        "passed",
                        true,
                        "expectedRows",
                        rows,
                        "actualRows",
                        rows,
                        "matchedRows",
                        rows,
                        "mismatchedRows",
                        0,
                        "duplicateKeys",
                        0,
                        "missingKeys",
                        0,
                        "sourceFingerprint",
                        sourceSha,
                        "readbackEvidence",
                        evidence,
                        "writerStopped",
                        true);
        return JSON.writeValueAsString(
                Map.of(
                        "verification",
                        proof,
                        "sourceComplete",
                        true,
                        "returnedRows",
                        rows,
                        "reconciliationIntent",
                        folder.resolve("intent.json").toString()));
    }

    private record Comparison(
            List<Map<String, Object>> actual, List<Map<String, Object>> missing) {}

    private Set<String> presentKeys(DatasetDefinition d, List<Map<String, Object>> expected)
            throws Exception {
        var codec = codec(d);
        var found = new HashSet<String>();
        String ts = d.designatedTimestamp();
        for (int i = 0; i < expected.size(); i += 1000) {
            var part = expected.subList(i, Math.min(i + 1000, expected.size()));
            var times =
                    part.stream().map(r -> Instant.parse(r.get(ts).toString())).sorted().toList();
            var conditions = new ArrayList<String>();
            conditions.add(q(ts) + ">=" + literal(times.getFirst().toString()));
            conditions.add(q(ts) + "<=" + literal(times.getLast().toString()));
            for (String key : d.dedupKey())
                if (!key.equals(ts))
                    conditions.add(
                            q(key)
                                    + " IN ("
                                    + String.join(
                                            ",",
                                            part.stream()
                                                    .map(r -> r.get(key))
                                                    .distinct()
                                                    .map(
                                                            v ->
                                                                    v instanceof Number
                                                                            ? v.toString()
                                                                            : literal(v.toString()))
                                                    .toList())
                                    + ")");
            var wanted = new HashSet<String>();
            for (var r : part) wanted.add(codec.key(r));
            var visible =
                    query(
                            "SELECT "
                                    + String.join(
                                            ",",
                                            d.storageColumns().stream()
                                                    .map(VerifiedMarketDataStore::q)
                                                    .toList())
                                    + " FROM "
                                    + q(d.objectName())
                                    + " WHERE "
                                    + String.join(" AND ", conditions)
                                    + " LIMIT 10001");
            if (visible.size() >= 10001)
                throw new IllegalStateException("补缺前存在性查询达到边界，禁止用截断结果判定缺失");
            for (var row : visible) {
                String key = codec.key(normalize(d, row));
                if (wanted.contains(key)) found.add(key);
            }
        }
        return found;
    }

    private Comparison compareExisting(
            DatasetDefinition d, List<Map<String, Object>> expected, String target)
            throws Exception {
        var codec = codec(d);
        var wanted = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : expected)
            if (wanted.put(codec.key(r), r) != null)
                throw new IllegalArgumentException("冻结来源有重复业务键");
        var actual = new ArrayList<Map<String, Object>>();
        var found = new HashSet<String>();
        for (int start = 0; start < expected.size(); start += 1000) {
            var part = expected.subList(start, Math.min(start + 1000, expected.size()));
            if (!identity(d).equals(target)) throw new IllegalStateException("核对物理目标发生变化");
            String ts = d.designatedTimestamp();
            var times =
                    part.stream().map(r -> Instant.parse(r.get(ts).toString())).sorted().toList();
            var conditions = new ArrayList<String>();
            conditions.add(q(ts) + ">=" + literal(times.getFirst().toString()));
            conditions.add(q(ts) + "<=" + literal(times.getLast().toString()));
            for (String key : d.dedupKey())
                if (!key.equals(ts))
                    conditions.add(
                            q(key)
                                    + " IN ("
                                    + String.join(
                                            ",",
                                            part.stream()
                                                    .map(r -> r.get(key))
                                                    .distinct()
                                                    .map(
                                                            v ->
                                                                    v instanceof Number
                                                                            ? v.toString()
                                                                            : literal(v.toString()))
                                                    .toList())
                                    + ")");
            var requested = new HashSet<String>();
            for (var r : part) requested.add(codec.key(r));
            var fetched =
                    query(
                            "SELECT "
                                    + String.join(
                                            ",",
                                            d.storageColumns().stream()
                                                    .map(VerifiedMarketDataStore::q)
                                                    .toList())
                                    + " FROM "
                                    + q(d.objectName())
                                    + " WHERE "
                                    + String.join(" AND ", conditions)
                                    + " LIMIT 10001");
            if (fetched.size() > 10000) throw new IllegalStateException("核对范围超限");
            for (var raw : fetched) {
                var row = normalize(d, raw);
                String key = codec.key(row);
                if (!requested.contains(key)) continue;
                if (!found.add(key)
                        || !Arrays.equals(
                                codec.canonicalBytes(row), codec.canonicalBytes(wanted.get(key))))
                    throw new IllegalStateException("现有键有重复或不同值，禁止用冻结旧值覆盖");
                actual.add(row);
            }
        }
        return new Comparison(
                actual,
                wanted.entrySet().stream()
                        .filter(e -> !found.contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .toList());
    }

    public Map<String, Object> verifyExisting(
            String run,
            DatasetDefinition d,
            List<Map<String, Object>> expected,
            Path raw,
            String sourceSha,
            String queueTask)
            throws Exception {
        return verifyExistingSource(run, d, expected, raw, sourceSha, queueTask, false);
    }

    /** 来源组只能核对原组的成功运行，不能借用某个成员或另一次发布的回执。 */
    public Map<String, Object> verifyExistingBundle(
            String run,
            DatasetDefinition d,
            List<Map<String, Object>> expected,
            Path raw,
            String sourceSha,
            String bundle)
            throws Exception {
        return verifyExistingSource(run, d, expected, raw, sourceSha, bundle, true);
    }

    private Map<String, Object> verifyExistingSource(
            String run,
            DatasetDefinition d,
            List<Map<String, Object>> expected,
            Path raw,
            String sourceSha,
            String sourceId,
            boolean bundle)
            throws Exception {
        try (var publisher =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            var ledger = SyncRunLedger.openExisting(ledgerPath);
            var original = ledger.getRun(run);
            var request = JSON.readTree(original.frozenJson());
            byte[] prepared = Files.readAllBytes(root.resolve("prepared/" + run + ".json"));
            var document = JSON.readTree(prepared);
            boolean belongs =
                    bundle
                            ? document.path("evidence").path("bundle").asText().equals(sourceId)
                            : List.of(
                                            document.path("evidence").path("queueTask").asText(),
                                            document.path("evidence").path("task").asText())
                                    .contains(sourceId);
            if (sourceId == null
                    || sourceId.isBlank()
                    || !sha(prepared)
                            .equals(request.path("parameters").path("prepared_sha256").asText())
                    || !belongs) throw new IllegalStateException("原运行不属于本次来源队列");
            if (ledger.get(run).state() != SyncRunState.VERIFIED
                    || !original.targetId().equals(identity(d))
                    || !request.path("parameters").path("source_sha256").asText().equals(sourceSha)
                    || !sha(Files.readAllBytes(raw)).equals(sourceSha))
                throw new IllegalStateException("原运行或冻结来源尚未验收");
            var normalized = expected.stream().map(r -> normalize(d, r)).toList();
            var result = compareExisting(d, normalized, original.targetId());
            if (!result.missing().isEmpty() || !settled(d.objectName()))
                throw new IllegalStateException("队列来源尚未完全可见");
            Path folder = root.resolve("queue-verifications");
            Files.createDirectories(folder);
            Path proof = folder.resolve(UUID.randomUUID() + ".json");
            Files.write(
                    proof,
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    run,
                                    "dataset",
                                    d.datasetId(),
                                    "sourceSha256",
                                    sourceSha,
                                    "expectedRows",
                                    normalized.size(),
                                    "rows",
                                    result.actual(),
                                    "walSettled",
                                    true,
                                    "targetId",
                                    original.targetId())),
                    StandardOpenOption.CREATE_NEW);
            return Map.of(
                    "proof",
                    proof.toString(),
                    "proofSha256",
                    sha(Files.readAllBytes(proof)),
                    "rows",
                    normalized.size());
        }
    }

    public static Map<String, Object> normalize(DatasetDefinition d, Map<String, Object> row) {
        if (!row.keySet().equals(new HashSet<>(d.storageColumns())))
            throw new IllegalArgumentException("来源规范字段与数据定义不一致");
        var normalized = new LinkedHashMap<String, Object>();
        for (var c : d.columns()) {
            Object v = row.get(c.storageName());
            if (v == null) {
                if (!c.nullable()) throw new IllegalArgumentException("必填字段为空：" + c.storageName());
                normalized.put(c.storageName(), null);
                continue;
            }
            v =
                    switch (c.storageType()) {
                        case DOUBLE -> {
                            double n = ((Number) v).doubleValue();
                            if (!Double.isFinite(n)) throw new IllegalArgumentException("市场数值必须有限");
                            yield n;
                        }
                        case LONG -> new BigDecimal(v.toString()).longValueExact();
                        case INT -> new BigDecimal(v.toString()).intValueExact();
                        case BOOLEAN -> (Boolean) v;
                        case TIMESTAMP -> {
                            Instant t = Instant.parse(v.toString());
                            if (t.getNano() % 1000 != 0)
                                throw new IllegalArgumentException("不能静默丢失时间精度");
                            yield t.toString();
                        }
                        case TIMESTAMP_NS -> {
                            Instant t = Instant.parse(v.toString());
                            epochNanos(t);
                            yield t.toString();
                        }
                        case SYMBOL, VARCHAR, STRING -> v.toString();
                        default ->
                                throw new IllegalArgumentException("未支持的市场字段类型：" + c.storageType());
                    };
            normalized.put(c.storageName(), v);
        }
        return Collections.unmodifiableMap(normalized);
    }

    public static VerifiedBatchExecutor.Codec<Map<String, Object>, String> codec(
            DatasetDefinition d) {
        return new VerifiedBatchExecutor.Codec<>() {
            public String key(Map<String, Object> row) {
                try {
                    return JSON.writeValueAsString(d.businessKey().stream().map(row::get).toList());
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }

            public byte[] canonicalBytes(Map<String, Object> row) {
                try {
                    return JSON.writeValueAsBytes(normalize(d, row));
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }
        };
    }

    public static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long epochNanos(Instant time) {
        return Math.addExact(
                Math.multiplyExact(time.getEpochSecond(), 1_000_000_000L), time.getNano());
    }

    public static String q(String value) {
        DatasetDefinition.identifier(value);
        return "\"" + value + "\"";
    }

    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
