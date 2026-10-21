package com.zoutrankil.data.maintenance.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier.RowDigest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** 显式 FULL 的原请求保持原字节；未决期间不自动重发，也不借当前配置换目标。 */
public final class BacktestRefreshJournal {
    // 协议 v1 固定字段；端口以后增加观测字段时，不能据此改写旧输入版本。
    public static final List<String> VERSION_FIELDS =
            List.of(
                    "table_name",
                    "id",
                    "directoryName",
                    "table_type",
                    "walEnabled",
                    "table_suspended",
                    "dedup",
                    "writerTxn",
                    "sequencerTxn",
                    "bufferedTxnSize",
                    "suspended");

    public record Request(
            int protocol,
            BacktestDailyScope scope,
            String definitionJson,
            String definitionSql,
            String refreshSql,
            String inputVersions,
            String outputIdentity,
            String beforeVersion,
            long priorFinished,
            long priorOutputTxn,
            LocalDate from,
            LocalDate to,
            RowDigest expected) {
        public Request {
            if (protocol != 1
                    || scope == null
                    || from == null
                    || to == null
                    || !from.isBefore(to)
                    || ChronoUnit.DAYS.between(from, to) > 36_600
                    || priorFinished < 0
                    || priorOutputTxn < 0
                    || expected == null
                    || expected.rows() < 0
                    || !expected.sha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("原回测 FULL 请求协议、窗口或摘要不完整");
            if (!definitionJson.equals(json(scope.definition()))
                    || !definitionSql.equals(BacktestDailyMaterializedViewPort.createSql(scope))
                    || !refreshSql.equals(
                            "REFRESH MATERIALIZED VIEW " + scope.outputTable() + " FULL"))
                throw new IllegalArgumentException("原回测 FULL 定义或 SQL 不符，不按当前版本替换");
            if (inputVersions == null
                    || inputVersions.isBlank()
                    || outputIdentity == null
                    || outputIdentity.isBlank()
                    || beforeVersion == null
                    || beforeVersion.isBlank())
                throw new IllegalArgumentException("原回测 FULL 缺物理身份与输入版本");
            validateVersions(
                    scope,
                    inputVersions,
                    outputIdentity,
                    beforeVersion,
                    priorFinished,
                    priorOutputTxn);
        }
    }

    private static void validateVersions(
            BacktestDailyScope scope,
            String input,
            String identity,
            String before,
            long finished,
            long outputTxn) {
        try {
            var inputs = JobDefinitionJson.mapper().readTree(input);
            var prior = JobDefinitionJson.mapper().readTree(before);
            var output = JobDefinitionJson.mapper().readTree(identity);
            var tables = prior.path("tables");
            if (!fields(inputs).equals(new HashSet<>(scope.sources()))
                    || !fields(tables)
                            .equals(
                                    new HashSet<>(
                                            Stream.concat(
                                                            scope.sources().stream(),
                                                            Stream.of(scope.outputTable()))
                                                    .toList())))
                throw new IllegalArgumentException("原回测 FULL 来源集合不完整");
            var ids = new HashSet<Long>();
            for (String name : scope.sources()) {
                var row = inputs.path(name);
                if (!fields(row).equals(new HashSet<>(VERSION_FIELDS))
                        || !name.equals(row.path("table_name").asText())
                        || !"T".equals(row.path("table_type").asText())
                        || !row.path("walEnabled").asBoolean()
                        || !row.path("dedup").asBoolean()
                        || !row.path("table_suspended").isBoolean()
                        || row.path("table_suspended").asBoolean()
                        || !row.path("suspended").isBoolean()
                        || row.path("suspended").asBoolean()
                        || counter(row, "writerTxn") != counter(row, "sequencerTxn")
                        || counter(row, "bufferedTxnSize") != 0
                        || counter(row, "id") < 1
                        || !ids.add(counter(row, "id"))
                        || row.path("directoryName").asText().isBlank())
                    throw new IllegalArgumentException("原回测 FULL 来源物理版本无效");
                for (String field : VERSION_FIELDS)
                    if (!row.get(field).equals(tables.path(name).path(field)))
                        throw new IllegalArgumentException("原回测 FULL 输入与发送前版本不符");
            }
            var target = tables.path(scope.outputTable());
            if (!scope.outputTable().equals(output.path("table").asText())
                    || !"M".equals(target.path("table_type").asText())
                    || counter(output, "id") != counter(target, "id")
                    || ids.contains(counter(output, "id"))
                    || !output.path("directory")
                            .asText()
                            .equals(target.path("directoryName").asText())
                    || output.path("directory").asText().isBlank()
                    || counter(prior.path("view"), "finished") != finished
                    || counter(target, "sequencerTxn") != outputTxn)
                throw new IllegalArgumentException("原回测 FULL 输出身份或基线不符");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("原回测 FULL 版本结构无法读取", failure);
        }
    }

    private static Set<String> fields(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static long counter(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("原回测 FULL 计数无效：" + field);
        return value.longValue();
    }

    public record Operation(
            String id,
            Request request,
            String requestJson,
            String requestSha256,
            String state,
            String resultJson) {}

    private final Path file;

    public BacktestRefreshJournal(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static String json(Object value) {
        try {
            return JobDefinitionJson.canonicalMapper().writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("回测维护原件无法编码", failure);
        }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Connection writable() throws Exception {
        Files.createDirectories(file.getParent());
        var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS native_backtest_refresh_ops(id TEXT PRIMARY KEY,output_table TEXT NOT NULL,request_json TEXT NOT NULL,request_sha256 TEXT NOT NULL,state TEXT NOT NULL,created_at TEXT NOT NULL,result_json TEXT)");
            s.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS native_backtest_refresh_active ON native_backtest_refresh_ops(output_table) WHERE state='IN_DOUBT'");
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
        return db;
    }

    public Operation begin(Request request) throws Exception {
        String original = json(request),
                checksum = sha(original),
                id = UUID.randomUUID().toString();
        try (var db = writable();
                var q =
                        db.prepareStatement(
                                "INSERT INTO native_backtest_refresh_ops(id,output_table,request_json,request_sha256,state,created_at) VALUES(?,?,?,?,'IN_DOUBT',?)")) {
            q.setString(1, id);
            q.setString(2, request.scope().outputTable());
            q.setString(3, original);
            q.setString(4, checksum);
            q.setString(5, Instant.now().toString());
            q.executeUpdate();
        }
        return new Operation(id, request, original, checksum, "IN_DOUBT", null);
    }

    public Optional<Operation> pending(String output) {
        var matches = read("output_table=? AND state='IN_DOUBT'", output);
        if (matches.size() > 1) throw new IllegalStateException("回测 FULL 原未决意图不唯一");
        return matches.stream().findFirst();
    }

    public Operation get(String id) {
        return read("id=?", id).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("原回测 FULL 意图不存在"));
    }

    public List<Operation> pendingOperations() {
        return read("state='IN_DOUBT'", null);
    }

    private List<Operation> read(String predicate, String value) {
        if (!Files.isRegularFile(file)) return List.of();
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + file.toUri().toASCIIString() + "?mode=ro");
                var s = db.createStatement()) {
            s.execute("PRAGMA query_only=ON");
            s.execute("PRAGMA busy_timeout=5000");
            try (var r =
                    s.executeQuery(
                            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='native_backtest_refresh_ops'")) {
                if (!r.next() || r.getInt(1) == 0) return List.of();
            }
            var result = new ArrayList<Operation>();
            try (var q =
                    db.prepareStatement(
                            "SELECT id,output_table,request_json,request_sha256,state,result_json FROM native_backtest_refresh_ops WHERE "
                                    + predicate
                                    + " ORDER BY created_at LIMIT 201")) {
                if (value != null) q.setString(1, value);
                try (var r = q.executeQuery()) {
                    while (r.next()) {
                        String original = r.getString(3),
                                checksum = r.getString(4),
                                state = r.getString(5);
                        if (!sha(original).equals(checksum)
                                || !List.of("IN_DOUBT", "VERIFIED").contains(state))
                            throw new IllegalStateException("原回测 FULL 请求摘要或状态不符");
                        var request = JobDefinitionJson.mapper().readValue(original, Request.class);
                        if (!json(request).equals(original))
                            throw new IllegalStateException("原回测 FULL 请求字节不符合固定编码");
                        if (state.equals("VERIFIED")
                                && (r.getString(6) == null || r.getString(6).isBlank()))
                            throw new IllegalStateException("原回测 FULL 完成证明缺失");
                        if (!request.scope().outputTable().equals(r.getString(2)))
                            throw new IllegalStateException("原回测 FULL 输出归属不符");
                        result.add(
                                new Operation(
                                        r.getString(1),
                                        request,
                                        original,
                                        checksum,
                                        state,
                                        r.getString(6)));
                    }
                }
            }
            if (result.size() > 200) throw new IllegalStateException("回测 FULL 原件超过查询预算");
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("回测 FULL 原账本无法读取", failure);
        }
    }

    /** 只补执行结果；原 JSON、校验和、scope 与窗口不更新。 */
    public void complete(Operation operation, Object result) throws Exception {
        try (var db = writable();
                var q =
                        db.prepareStatement(
                                "UPDATE native_backtest_refresh_ops SET state='VERIFIED',result_json=? WHERE id=? AND state='IN_DOUBT' AND request_json=? AND request_sha256=?")) {
            q.setString(1, json(result));
            q.setString(2, operation.id());
            q.setString(3, operation.requestJson());
            q.setString(4, operation.requestSha256());
            if (q.executeUpdate() != 1) throw new IllegalStateException("原回测 FULL 意图已变化，不补写完成");
        }
    }
}
