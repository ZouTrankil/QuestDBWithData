package com.zoutrankil.market.cn.observation.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只读检查整个旧表；候选 SQL 不是执行许可，也不能补回旧键已吞掉的来源行。 */
public final class ContractKeyRepairPlan {
    private static final Map<String, Set<String>> LEGACY =
            Map.of(
                    "sge_daily", Set.of(),
                    "broker_recommend", Set.of("month", "ts_code"),
                    "stk_alert", Set.of("ts_code", "start_date", "end_date", "type"));

    public record Plan(
            String dataset,
            String targetId,
            Map<String, Object> walVersion,
            Set<String> actualKeys,
            List<String> requiredKeys,
            long invalidKeyRows,
            List<Map<String, Object>> collisions,
            List<Map<String, Object>> sourceScopes,
            String status,
            String candidateSql,
            boolean productionWritten) {}

    private final JdbcTemplate jdbc;

    public ContractKeyRepairPlan(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(30);
    }

    public Plan inspect(String dataset) {
        if (!LEGACY.containsKey(dataset)) throw new IllegalArgumentException("该表不支持旧键修复计划");
        var definition = ContractTableDefinition.from(dataset);
        var target = new QuestDbContractTableTarget(dataset, jdbc, null);
        String identity = target.targetId();
        var before = version(dataset);
        var columns =
                jdbc.queryForList(
                        "SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"
                                + dataset
                                + "')");
        var keys = new HashSet<String>();
        var actualTypes = new HashMap<String, String>();
        for (var column : columns) {
            actualTypes.put(column.get("column").toString(), column.get("type").toString());
            if (Boolean.TRUE.equals(column.get("upsertKey")))
                keys.add(column.get("column").toString());
        }
        var expectedTypes = new HashMap<String, String>();
        definition
                .columns()
                .forEach(c -> expectedTypes.put(c.storageName(), c.storageType().sqlType()));
        if (!actualTypes.equals(expectedTypes)
                || !(keys.equals(LEGACY.get(dataset))
                        || keys.equals(new HashSet<>(definition.dedupKey()))))
            throw new IllegalStateException("未知列布局或旧键，不能生成修复 SQL");
        var metadata =
                jdbc.queryForList(
                        "SELECT designatedTimestamp,partitionBy FROM tables() WHERE table_name=?",
                        dataset);
        if (metadata.size() != 1
                || !definition
                        .designatedTimestamp()
                        .equals(metadata.getFirst().get("designatedTimestamp"))
                || !definition.partition().name().equals(metadata.getFirst().get("partitionBy")))
            throw new IllegalStateException("旧表日期或分区与原契约不符");
        String keySql =
                String.join(",", definition.dedupKey().stream().map(n -> "\"" + n + "\"").toList());
        // null 和空白文本不能当作身份；日期仍核对原 UTC 午夜载体。
        String invalid =
                String.join(
                        " OR ",
                        definition.columns().stream()
                                .filter(c -> definition.dedupKey().contains(c.storageName()))
                                .map(
                                        c -> {
                                            String n = "\"" + c.storageName() + "\"";
                                            return c.storageType()
                                                            == DatasetDefinition.StorageType
                                                                    .TIMESTAMP
                                                    ? "("
                                                            + n
                                                            + " IS NULL OR cast("
                                                            + n
                                                            + " AS long)%86400000000!=0)"
                                                    : "(" + n + " IS NULL OR trim(" + n + ")='')";
                                        })
                                .toList());
        long invalidRows =
                jdbc.queryForObject(
                        "SELECT count() FROM " + dataset + " WHERE " + invalid, Long.class);
        var collisions =
                jdbc.queryForList(
                        "SELECT * FROM (SELECT "
                                + keySql
                                + ",count() AS copies FROM "
                                + dataset
                                + " GROUP BY "
                                + keySql
                                + ") WHERE copies>1 LIMIT 101");
        // 返回完整来源作用域，而不是 MAX(date)。超过预算就不生成不完整计划。
        String scope =
                dataset.equals("sge_daily")
                        ? "cast(trade_date AS long) AS trade_date_us"
                        : dataset.equals("broker_recommend")
                                ? "cast(month AS long) AS month_us"
                                : "ts_code,cast(start_date AS long) AS start_date_us";
        String order =
                dataset.equals("sge_daily")
                        ? "trade_date_us"
                        : dataset.equals("broker_recommend") ? "month_us" : "ts_code,start_date_us";
        var scopes =
                jdbc.queryForList(
                        "SELECT DISTINCT "
                                + scope
                                + " FROM "
                                + dataset
                                + " ORDER BY "
                                + order
                                + " LIMIT 100001");
        if (scopes.size() > 100000) throw new IllegalStateException("补数范围超过只读计划预算，需显式分段");
        if (!identity.equals(target.targetId()) || !before.equals(version(dataset)))
            throw new IllegalStateException("生成计划时物理对象或 WAL 已变化");
        boolean corrected = keys.equals(new HashSet<>(definition.dedupKey()));
        boolean rebuild = invalidRows != 0 || !collisions.isEmpty();
        return new Plan(
                dataset,
                identity,
                before,
                Set.copyOf(keys),
                definition.dedupKey(),
                invalidRows,
                List.copyOf(collisions),
                List.copyOf(scopes),
                rebuild
                        ? "REBUILD_REQUIRED"
                        : corrected
                                ? "KEYS_MATCH_BACKFILL_REQUIRED"
                                : "KEY_CHANGE_AND_BACKFILL_REQUIRED",
                rebuild || corrected
                        ? null
                        : "ALTER TABLE " + dataset + " DEDUP ENABLE UPSERT KEYS(" + keySql + ");",
                false);
    }

    private Map<String, Object> version(String dataset) {
        if (!QuestDbWriteChecks.walSettled(jdbc, dataset))
            throw new IllegalStateException("旧表 WAL 尚未稳定");
        var rows =
                jdbc.queryForList(
                        "SELECT writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=?",
                        dataset);
        if (rows.size() != 1) throw new IllegalStateException("旧表 WAL 版本缺失");
        return Map.copyOf(rows.getFirst());
    }
}
