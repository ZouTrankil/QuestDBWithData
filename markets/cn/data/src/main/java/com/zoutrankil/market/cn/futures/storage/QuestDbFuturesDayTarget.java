package com.zoutrankil.market.cn.futures.storage;

import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.futures.mapper.FuturesDayDefinition;
import com.zoutrankil.market.cn.futures.port.FuturesDayTarget;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 原期货表的类型化标量读写；完整键配对，回读前后核对同一 WAL 版本。 */
public final class QuestDbFuturesDayTarget implements FuturesDayTarget {
    private final DatasetDefinition definition;
    private final SourceContract source;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbFuturesDayTarget(String dataset, JdbcTemplate jdbc, QuestDB questdb) {
        this.definition = FuturesDayDefinition.from(dataset);
        this.source = SourceContract.load(dataset);
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(30);
        this.questdb = questdb;
    }

    public String targetId() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?",
                        definition.objectName());
        if (rows.size() != 1) throw new IllegalStateException("期货正式表不存在");
        var r = rows.getFirst();
        return StaticTargetIdentity.identify(
                jdbc,
                definition.objectName(),
                ((Number) r.get("id")).longValue(),
                r.get("directoryName").toString());
    }

    public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, definition.objectName(), definition);
    }

    public boolean walSettled() {
        return QuestDbWriteChecks.walSettled(jdbc, definition.objectName());
    }

    private Instant marker(Object value) {
        Instant t = Instant.parse(value.toString());
        if (t.getNano() != 0 || Math.floorMod(t.getEpochSecond(), 86400) != 0)
            throw new IllegalArgumentException("期货交易日须保留 UTC 午夜日期载体");
        return t;
    }

    private void requireCode(String code, Instant date) {
        new SourceCollector.Request(
                source.dataset(),
                date.atOffset(ZoneOffset.UTC).toLocalDate(),
                Set.of(code),
                source.version(),
                code);
    }

    public void send(List<Map<String, Object>> rows) throws Exception {
        if (rows.isEmpty() || rows.size() > 100)
            throw new IllegalArgumentException("期货写入批次须为 1..100 行");
        var values =
                rows.stream().map(r -> VerifiedMarketDataStore.normalize(definition, r)).toList();
        for (var row : values)
            requireCode(row.get("ts_code").toString(), marker(row.get("trade_date")));
        try (Sender sender = questdb.borrowSender()) {
            for (var row : values) {
                sender.table(definition.objectName());
                // ILP 的 SYMBOL 必须先于普通字段，不能按来源列顺序混发。
                for (var c : definition.columns())
                    if (c.storageType() == StorageType.SYMBOL && row.get(c.storageName()) != null)
                        sender.symbol(c.storageName(), row.get(c.storageName()).toString());
                for (var c : definition.columns()) {
                    String name = c.storageName();
                    Object value = row.get(name);
                    if (value == null
                            || name.equals(definition.designatedTimestamp())
                            || c.storageType() == StorageType.SYMBOL) continue;
                    switch (c.storageType()) {
                        case DOUBLE -> sender.doubleColumn(name, ((Number) value).doubleValue());
                        case TIMESTAMP ->
                                sender.timestampColumn(name, Instant.parse(value.toString()));
                        default -> throw new IllegalArgumentException("未接入的期货字段类型");
                    }
                }
                sender.at(marker(row.get("trade_date")));
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000))
                throw new IllegalStateException("期货发送结果未决，保留原运行及锁");
        }
    }

    private Map<String, Object> version() {
        var rows =
                jdbc.queryForList(
                        "SELECT writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=?",
                        definition.objectName());
        if (rows.size() != 1) throw new IllegalStateException("期货 WAL 版本缺失");
        var r = rows.getFirst();
        if (!(r.get("writerTxn") instanceof Number w)
                || !(r.get("sequencerTxn") instanceof Number s)
                || w.longValue() < 0
                || w.longValue() != s.longValue()
                || !(r.get("bufferedTxnSize") instanceof Number b)
                || b.longValue() != 0
                || !Boolean.FALSE.equals(r.get("suspended")))
            throw new IllegalStateException("期货 WAL 未稳定");
        return Map.copyOf(r);
    }

    public List<Map<String, Object>> readback(List<String> keys) throws Exception {
        if (keys.isEmpty() || keys.size() > 100 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("期货回读需 1..100 个唯一完整键");
        var params = new ArrayList<Object>();
        var clauses = new ArrayList<String>();
        for (String key : keys) {
            var n = JobDefinitionJson.mapper().readTree(key);
            if (!n.isArray() || n.size() != 2 || !n.get(0).isTextual() || !n.get(1).isTextual())
                throw new IllegalArgumentException("期货键需合约和交易日");
            Instant date = marker(n.get(1).textValue());
            requireCode(n.get(0).textValue(), date);
            params.add(n.get(0).textValue());
            params.add(Math.multiplyExact(date.getEpochSecond(), 1_000_000L));
            clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");
        }
        var before = version();
        // 真实技术时刻和日期载体都按微秒整数回读，避免 JDBC 时区转换或精度丢失。
        String columns =
                String.join(
                        ",",
                        definition.columns().stream()
                                .map(
                                        c ->
                                                c.storageType() == StorageType.TIMESTAMP
                                                        ? "cast(\""
                                                                + c.storageName()
                                                                + "\" AS long) AS \""
                                                                + c.storageName()
                                                                + "\""
                                                        : "\"" + c.storageName() + "\"")
                                .toList());
        var result =
                jdbc.query(
                        "SELECT "
                                + columns
                                + " FROM "
                                + definition.objectName()
                                + " WHERE "
                                + String.join(" OR ", clauses)
                                + " LIMIT "
                                + (keys.size() + 1),
                        (rs, index) -> {
                            var row = new TreeMap<String, Object>();
                            for (var c : definition.columns()) {
                                String name = c.storageName();
                                Object value;
                                switch (c.storageType()) {
                                    case SYMBOL -> value = rs.getString(name);
                                    case DOUBLE -> {
                                        Double d = rs.getObject(name, Double.class);
                                        value = d != null && Double.isNaN(d) ? null : d;
                                    }
                                    case TIMESTAMP -> {
                                        long us = rs.getLong(name);
                                        value =
                                                rs.wasNull()
                                                        ? null
                                                        : Instant.ofEpochSecond(
                                                                        Math.floorDiv(
                                                                                us, 1_000_000L),
                                                                        Math.floorMod(
                                                                                        us,
                                                                                        1_000_000L)
                                                                                * 1000)
                                                                .toString();
                                        if (name.equals("trade_date") && value != null)
                                            marker(value);
                                    }
                                    default -> throw new IllegalArgumentException("未接入的期货读取类型");
                                }
                                row.put(name, value);
                            }
                            return VerifiedMarketDataStore.normalize(definition, row);
                        },
                        params.toArray());
        if (!before.equals(version())) throw new IllegalStateException("期货回读期间 WAL 版本变化");
        return result;
    }
}
