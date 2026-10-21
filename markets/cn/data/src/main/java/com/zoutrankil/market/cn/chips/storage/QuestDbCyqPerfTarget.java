package com.zoutrankil.market.cn.chips.storage;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.chips.mapper.CyqPerfDefinition;
import com.zoutrankil.market.cn.chips.port.CyqPerfTarget;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 使用完整证券和交易日键回读全部列；不建表、不改列、不刷新下游。 */
public final class QuestDbCyqPerfTarget implements CyqPerfTarget {
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbCyqPerfTarget(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(30);
        this.questdb = questdb;
    }

    public String targetId() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?", "cyq_perf");
        if (rows.size() != 1) throw new IllegalStateException("筹码正式表缺失");
        var row = rows.getFirst();
        return StaticTargetIdentity.identify(
                jdbc,
                "cyq_perf",
                ((Number) row.get("id")).longValue(),
                row.get("directoryName").toString());
    }

    public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, "cyq_perf", CyqPerfDefinition.DEFINITION);
    }

    public boolean walSettled() {
        return QuestDbWriteChecks.walSettled(jdbc, "cyq_perf");
    }

    private static Instant dateMarker(Object value) {
        Instant date = Instant.parse(value.toString());
        if (date.getNano() != 0 || Math.floorMod(date.getEpochSecond(), 86400) != 0)
            throw new IllegalArgumentException("筹码交易日必须为原 UTC 午夜日期标记");
        return date;
    }

    public void send(List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty() || rows.size() > 100)
            throw new IllegalArgumentException("筹码发送批次须为 1..100 行");
        var normalized =
                rows.stream()
                        .map(
                                row ->
                                        VerifiedMarketDataStore.normalize(
                                                CyqPerfDefinition.DEFINITION, row))
                        .toList();
        for (var row : normalized) {
            dateMarker(row.get("trade_date"));
            if (!row.get("ts_code").toString().matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                throw new IllegalArgumentException("筹码证券必须保留完整代码和交易所后缀");
        }
        try (Sender sender = questdb.borrowSender()) {
            for (var row : normalized) {
                sender.table("cyq_perf").symbol("ts_code", row.get("ts_code").toString());
                for (var column : CyqPerfDefinition.DEFINITION.columns()) {
                    Object value = row.get(column.storageName());
                    if (column.storageType() == StorageType.DOUBLE && value != null)
                        sender.doubleColumn(column.storageName(), ((Number) value).doubleValue());
                }
                sender.at(dateMarker(row.get("trade_date")));
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000))
                throw new IllegalStateException("筹码发送结果未决，保留原运行和锁");
        }
    }

    public List<Map<String, Object>> readback(List<String> keys) throws Exception {
        if (keys.isEmpty() || keys.size() > 100 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("筹码回读须有 1..100 个唯一完整键");
        var params = new ArrayList<Object>();
        var clauses = new ArrayList<String>();
        for (String key : keys) {
            var node = JobDefinitionJson.mapper().readTree(key);
            if (!node.isArray()
                    || node.size() != 2
                    || !node.get(0).isTextual()
                    || !node.get(1).isTextual()
                    || !node.get(0).textValue().matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                throw new IllegalArgumentException("筹码业务键必须包含证券和交易日");
            params.add(node.get(0).textValue());
            params.add(
                    Math.multiplyExact(
                            dateMarker(node.get(1).textValue()).getEpochSecond(), 1_000_000L));
            clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");
        }
        // 以微秒整数读日期载体，避免 JDBC/JVM 时区把业务日期移到前一天。
        String columns =
                String.join(
                        ",",
                        CyqPerfDefinition.DEFINITION.columns().stream()
                                .map(
                                        c ->
                                                c.storageName().equals("trade_date")
                                                        ? "cast(trade_date AS long) AS trade_date"
                                                        : "\"" + c.storageName() + "\"")
                                .toList());
        return jdbc.query(
                "SELECT "
                        + columns
                        + " FROM cyq_perf WHERE "
                        + String.join(" OR ", clauses)
                        + " LIMIT "
                        + (keys.size() + 1),
                (rs, index) -> {
                    var row = new TreeMap<String, Object>();
                    row.put("ts_code", rs.getString("ts_code"));
                    long micros = rs.getLong("trade_date");
                    if (rs.wasNull()) throw new IllegalStateException("筹码交易日为空");
                    Instant date =
                            Instant.ofEpochSecond(
                                    Math.floorDiv(micros, 1_000_000L),
                                    Math.floorMod(micros, 1_000_000L) * 1000);
                    row.put("trade_date", dateMarker(date).toString());
                    for (var c : CyqPerfDefinition.DEFINITION.columns()) {
                        if (c.storageType() != StorageType.DOUBLE) continue;
                        Double value = rs.getObject(c.storageName(), Double.class);
                        row.put(
                                c.storageName(),
                                value != null && Double.isNaN(value) ? null : value);
                    }
                    return VerifiedMarketDataStore.normalize(CyqPerfDefinition.DEFINITION, row);
                },
                params.toArray());
    }
}
