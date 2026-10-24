package com.zoutrankil.market.cn.observation.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.observation.mapper.SnapshotEventDefinition;
import com.zoutrankil.market.cn.observation.port.SnapshotEventTables;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** 读取完整有界表，stage 使用无去重 WAL，保留每一条重复记录。 */
public final class QuestDbSnapshotEventTables implements SnapshotEventTables {
    private final String dataset;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbSnapshotEventTables(String dataset, JdbcTemplate jdbc, QuestDB questdb) {
        SnapshotEventDefinition.dateField(dataset);
        this.dataset = dataset;
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(120);
        this.questdb = questdb;
    }

    public void requireNoNativeDependencies() {
        var dependency = Pattern.compile("(?i)(?<![a-z0-9_])" + dataset + "(?![a-z0-9_])");
        for (var row :
                jdbc.queryForList("SELECT base_table_name,view_sql FROM materialized_views()"))
            if (dataset.equals(row.get("base_table_name"))
                    || dependency.matcher(Objects.toString(row.get("view_sql"), "")).find())
                throw new IllegalStateException("原生MV依赖原表，须先制定显式切换及刷新方案");
    }

    private Identity identity(String table) {
        DatasetDefinition.identifier(table);
        var info =
                jdbc.queryForList(
                        "SELECT id,directoryName,designatedTimestamp,partitionBy FROM tables() WHERE table_name=?",
                        table);
        if (info.size() != 1) throw new IllegalStateException("快照表不存在");
        var definition = SnapshotEventDefinition.definition(dataset, table);
        var columns =
                jdbc.queryForList(
                        "SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"
                                + table
                                + "')");
        var types = new HashMap<String, String>();
        var keys = new HashSet<String>();
        for (var c : columns) {
            types.put(c.get("column").toString(), c.get("type").toString());
            if (Boolean.TRUE.equals(c.get("upsertKey"))) keys.add(c.get("column").toString());
        }
        boolean v2 = types.containsKey(SnapshotEventDefinition.ORDINAL);
        var expected = new HashMap<String, String>();
        definition.columns().stream()
                .filter(c -> v2 || !c.storageName().equals(SnapshotEventDefinition.ORDINAL))
                .forEach(c -> expected.put(c.storageName(), c.storageType().sqlType()));
        Set<String> legacyKeys =
                dataset.equals("repurchase")
                        ? Set.of("ts_code", "ann_date", "end_date", "proc")
                        : Set.of("ts_code", "trade_date", "price", "vol", "buyer", "seller");
        var row = info.getFirst();
        if (!types.equals(expected)
                || !keys.equals(v2 ? Set.of() : legacyKeys)
                || !definition.designatedTimestamp().equals(row.get("designatedTimestamp"))
                || !"YEAR".equals(row.get("partitionBy"))
                || !QuestDbWriteChecks.walSettled(jdbc, table))
            throw new IllegalStateException("快照表布局或 WAL 不符合明确原版本");
        long id = ((Number) row.get("id")).longValue();
        String directory = row.get("directoryName").toString();
        return new Identity(
                id,
                directory,
                StaticTargetIdentity.identify(jdbc, table, id, directory),
                v2 ? 2 : 1);
    }

    private long version(String table) {
        var rows = jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("writerTxn") instanceof Number n))
            throw new IllegalStateException("快照 WAL 版本缺失");
        return n.longValue();
    }

    /** 公开页只需固定物理对象和已应用WAL版本，完整数据验收仍由发布器执行。 */
    public String readVersion() {
        var before = identity(dataset);
        if (before.schemaVersion() != 2) throw new IllegalStateException("旧事件表尚未显式切换v2");
        long txn = version(dataset);
        if (!before.equals(identity(dataset)) || txn != version(dataset))
            throw new IllegalStateException("读取版本期间目标改变");
        return before.targetId() + ":wal:" + txn;
    }

    public Snapshot snapshotIfPresent(String table) throws Exception {
        DatasetDefinition.identifier(table);
        var rows = jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table);
        if (rows.isEmpty()) return null;
        return snapshot(table);
    }

    public Snapshot snapshot(String table) throws Exception {
        Identity before = identity(table);
        long txn = version(table);
        var definition = SnapshotEventDefinition.definition(dataset, table);
        var columns =
                definition.columns().stream()
                        .filter(
                                c ->
                                        before.schemaVersion() == 2
                                                || !c.storageName()
                                                        .equals(SnapshotEventDefinition.ORDINAL))
                        .toList();
        String projection =
                String.join(
                        ",",
                        columns.stream()
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
        var rows =
                jdbc.query(
                        "SELECT "
                                + projection
                                + " FROM \""
                                + table
                                + "\" LIMIT "
                                + (SnapshotEventDefinition.MAX_TARGET_ROWS + 1),
                        (rs, index) -> {
                            var values = new TreeMap<String, Object>();
                            for (var c : columns) {
                                String n = c.storageName();
                                Object v;
                                switch (c.storageType()) {
                                    case SYMBOL, STRING -> v = rs.getString(n);
                                    case DOUBLE -> {
                                        Double d = rs.getObject(n, Double.class);
                                        v = d != null && Double.isNaN(d) ? null : d;
                                    }
                                    case LONG -> v = rs.getObject(n, Long.class);
                                    case TIMESTAMP -> {
                                        Long us = rs.getObject(n, Long.class);
                                        v =
                                                us == null
                                                        ? null
                                                        : Instant.ofEpochSecond(
                                                                        Math.floorDiv(us, 1000000L),
                                                                        Math.floorMod(us, 1000000L)
                                                                                * 1000L)
                                                                .toString();
                                    }
                                    default -> throw new IllegalStateException("未接入的快照类型");
                                }
                                values.put(n, v);
                            }
                            return (Map<String, Object>) values;
                        });
        if (rows.size() > SnapshotEventDefinition.MAX_TARGET_ROWS)
            throw new IllegalStateException("完整快照超过百万行预算，须先设计分段发布");
        List<Map<String, Object>> normalized =
                before.schemaVersion() == 1
                        ? SnapshotEventDefinition.assignOrdinals(dataset, rows)
                        : normalizeV2(rows);
        String canonical = SnapshotEventDefinition.canonical(normalized);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > SnapshotEventDefinition.MAX_BYTES)
            throw new IllegalStateException("完整快照超过128MiB预算");
        if (!before.equals(identity(table)) || txn != version(table))
            throw new IllegalStateException("完整快照读取时物理对象或 WAL 改变");
        return new Snapshot(
                before, normalized, SnapshotEventDefinition.fingerprint(normalized), txn);
    }

    private List<Map<String, Object>> normalizeV2(List<Map<String, Object>> rows) {
        var definition = SnapshotEventDefinition.definition(dataset);
        var codec = VerifiedMarketDataStore.codec(definition);
        var keys = new HashSet<String>();
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var v = VerifiedMarketDataStore.normalize(definition, row);
            SnapshotEventDefinition.date(dataset, v);
            if (((Number) v.get(SnapshotEventDefinition.ORDINAL)).longValue() < 1
                    || !keys.add(codec.key(v))) throw new IllegalStateException("快照序号为空、重复或非正数");
            result.add(v);
        }
        result.sort(Comparator.comparing(SnapshotEventDefinition::canonical));
        return List.copyOf(result);
    }

    public Snapshot stage(
            String table,
            List<Map<String, Object>> expected,
            Path evidence,
            BooleanSupplier cancelled)
            throws Exception {
        if (!table.matches("java_" + dataset + "_stage_[a-f0-9]{32}"))
            throw new IllegalArgumentException("stage 名称不属于本数据集");
        var rows = normalizeV2(expected);
        var definition = SnapshotEventDefinition.definition(dataset, table);
        if (rows.size() > SnapshotEventDefinition.MAX_TARGET_ROWS
                || SnapshotEventDefinition.canonical(rows).getBytes(StandardCharsets.UTF_8).length
                        > SnapshotEventDefinition.MAX_BYTES)
            throw new IllegalArgumentException("stage 超出完整快照预算");
        // 创建前保存意图；异常后不自动重新发送到这个无去重 stage。
        FileEvidenceStore.writeNewDurable(
                evidence,
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "table",
                                        table,
                                        "rows",
                                        rows.size(),
                                        "fingerprint",
                                        SnapshotEventDefinition.fingerprint(rows))));
        check(cancelled);
        String ddl =
                String.join(
                        ",",
                        definition.columns().stream()
                                .map(
                                        c ->
                                                "\""
                                                        + c.storageName()
                                                        + "\" "
                                                        + c.storageType().sqlType())
                                .toList());
        jdbc.execute(
                "CREATE TABLE \""
                        + table
                        + "\" ("
                        + ddl
                        + ") TIMESTAMP("
                        + definition.designatedTimestamp()
                        + ") PARTITION BY YEAR WAL");
        check(cancelled);
        try (Sender sender = questdb.borrowSender()) {
            int count = 0;
            for (var row : rows) {
                check(cancelled);
                sender.table(table);
                for (var c : definition.columns())
                    if (c.storageType() == StorageType.SYMBOL && row.get(c.storageName()) != null)
                        sender.symbol(c.storageName(), row.get(c.storageName()).toString());
                for (var c : definition.columns()) {
                    String n = c.storageName();
                    Object v = row.get(n);
                    if (v == null
                            || c.storageType() == StorageType.SYMBOL
                            || n.equals(definition.designatedTimestamp())) continue;
                    switch (c.storageType()) {
                        case STRING -> sender.stringColumn(n, v.toString());
                        case LONG -> sender.longColumn(n, ((Number) v).longValue());
                        case DOUBLE -> sender.doubleColumn(n, ((Number) v).doubleValue());
                        default -> throw new IllegalArgumentException("stage 字段类型不支持");
                    }
                }
                sender.at(Instant.parse(row.get(definition.designatedTimestamp()).toString()));
                if (++count % 1000 == 0) flush(sender);
            }
            if (count % 1000 != 0) flush(sender);
        }
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            check(cancelled);
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("stage WAL 等待超时，保留原件核对");
            Thread.sleep(50);
        }
        var actual = snapshot(table);
        if (!actual.fingerprint().equals(SnapshotEventDefinition.fingerprint(rows)))
            throw new IllegalStateException("stage 全部字段或重复行计数不符");
        return actual;
    }

    private static void flush(Sender sender) {
        long sequence = sender.flushAndGetSequence();
        if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000))
            throw new IllegalStateException("stage 发送未决，不自动重发");
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("快照发布取消，保留原件");
    }

    public void rename(String from, String to) {
        DatasetDefinition.identifier(from);
        DatasetDefinition.identifier(to);
        jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\"");
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, to)) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("重命名后 WAL 未稳定，保留原意图核对");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("重命名后等待被中断，保留原意图", e);
            }
        }
    }
}
