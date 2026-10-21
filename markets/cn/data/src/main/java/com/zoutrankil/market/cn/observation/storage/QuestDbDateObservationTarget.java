package com.zoutrankil.market.cn.observation.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.observation.port.DateObservationTarget;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 只写明确日期键和标量字段的已有观测表；结构、去重键或分区不符就停，不自动改表。 */
public class QuestDbDateObservationTarget implements DateObservationTarget {
    private final DatasetDefinition definition;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbDateObservationTarget(
            DatasetDefinition definition, JdbcTemplate jdbc, QuestDB questdb) {
        if (definition.businessKey().size() != 1
                || definition.columns().stream()
                        .noneMatch(
                                column ->
                                        column.logicalName()
                                                        .equals(definition.businessKey().getFirst())
                                                && column.storageName()
                                                        .equals(definition.designatedTimestamp()))
                || definition.columns().stream()
                        .anyMatch(
                                column ->
                                        column.storageName()
                                                        .equals(definition.designatedTimestamp())
                                                ? column.storageType()
                                                        != DatasetDefinition.StorageType.TIMESTAMP
                                                : column.storageType()
                                                                != DatasetDefinition.StorageType
                                                                        .DOUBLE
                                                        && column.storageType()
                                                                != DatasetDefinition.StorageType
                                                                        .STRING))
            throw new IllegalArgumentException("日期观测端口只支持单个微秒日期键及 DOUBLE/STRING 字段");
        this.definition = definition;
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(30);
        this.questdb = questdb;
    }

    public String targetId() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?",
                        definition.objectName());
        if (rows.size() != 1) throw new IllegalStateException("日期观测正式表缺失");
        var row = rows.getFirst();
        return StaticTargetIdentity.identify(
                jdbc,
                definition.objectName(),
                ((Number) row.get("id")).longValue(),
                row.get("directoryName").toString());
    }

    public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, definition.objectName(), definition);
    }

    public boolean walSettled() {
        return QuestDbWriteChecks.walSettled(jdbc, definition.objectName());
    }

    public void send(List<Map<String, Object>> rows) throws Exception {
        definition.requireCapability(DatasetDefinition.Capability.WRITE);
        if (rows.isEmpty() || rows.size() > 100)
            throw new IllegalArgumentException("日期观测发送批次需为 1..100 行");
        try (Sender sender = questdb.borrowSender()) {
            for (var row : rows) {
                sender.table(definition.objectName());
                for (var column : definition.columns()) {
                    String name = column.storageName();
                    Object value = row.get(name);
                    if (value == null || name.equals(definition.designatedTimestamp())) continue;
                    switch (column.storageType()) {
                        case DOUBLE -> sender.doubleColumn(name, ((Number) value).doubleValue());
                        case STRING -> sender.stringColumn(name, value.toString());
                        default -> throw new IllegalArgumentException("日期观测字段类型不在已验收范围内");
                    }
                }
                sender.at(Instant.parse(row.get(definition.designatedTimestamp()).toString()));
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000))
                throw new IllegalStateException("日期观测发送结果未决，保留原运行");
        }
    }

    public List<Map<String, Object>> readback(List<String> keys) throws Exception {
        if (keys.isEmpty() || keys.size() > 100 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("日期观测回读需要唯一完整统计期键");
        var params = new ArrayList<Object>();
        var clauses = new ArrayList<String>();
        for (var key : keys) {
            var value = JobDefinitionJson.mapper().readTree(key);
            if (!value.isArray() || value.size() != 1 || !value.get(0).isTextual())
                throw new IllegalArgumentException("日期观测业务键结构不符");
            Instant time = Instant.parse(value.get(0).textValue());
            if (time.getNano() % 1000 != 0) throw new IllegalArgumentException("日期观测时间精度不能丢失");
            params.add(
                    Math.addExact(
                            Math.multiplyExact(time.getEpochSecond(), 1_000_000L),
                            time.getNano() / 1000));
            clauses.add("\"" + definition.designatedTimestamp() + "\"=cast(? AS TIMESTAMP)");
        }
        String columns =
                String.join(
                        ",",
                        definition.columns().stream()
                                .map(c -> "\"" + c.storageName() + "\"")
                                .toList());
        return jdbc.query(
                "SELECT "
                        + columns
                        + " FROM "
                        + definition.objectName()
                        + " WHERE "
                        + String.join(" OR ", clauses)
                        + " LIMIT "
                        + (keys.size() + 1),
                (rs, index) -> {
                    var values = new TreeMap<String, Object>();
                    for (var column : definition.columns()) {
                        String name = column.storageName();
                        Object value =
                                switch (column.storageType()) {
                                    case TIMESTAMP ->
                                            rs.getTimestamp(name) == null
                                                    ? null
                                                    : rs.getTimestamp(name).toInstant().toString();
                                    case DOUBLE -> rs.getObject(name, Double.class);
                                    case STRING -> rs.getString(name);
                                    default -> throw new IllegalArgumentException("未登记日期观测读取类型");
                                };
                        if (value instanceof Double number && Double.isNaN(number)) value = null;
                        values.put(name, value);
                    }
                    return VerifiedMarketDataStore.normalize(definition, values);
                },
                params.toArray());
    }
}
