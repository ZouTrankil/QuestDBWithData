package com.zoutrankil.data.maintenance.application;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 在本次创建的隔离表上演练六个日期别名，不更改正式表或恢复账本。 */
public final class CnTimeColumnRehearsal {
    private static final Map<String, String> SOURCES =
            Map.of(
                    "etf_adj",
                    "timestamp",
                    "etf_daily",
                    "timestamp",
                    "etf_share",
                    "timestamp",
                    "stk_st_daily",
                    "timestamp",
                    "stk_suspend",
                    "timestamp",
                    "l2_daily_features",
                    "ts");
    private final JdbcTemplate jdbc;

    public CnTimeColumnRehearsal(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public Map<String, Object> run() {
        String runId = UUID.randomUUID().toString().replace("-", "");
        var results = new ArrayList<Map<String, Object>>();
        for (String source : SOURCES.keySet().stream().sorted().toList()) {
            results.add(
                    rehearse(
                            source,
                            SOURCES.get(source),
                            "java_cn_time_rehearsal_" + runId + "_" + results.size()));
        }
        boolean verified =
                results.stream()
                        .allMatch(
                                r ->
                                        List.of("RENAMED_AND_RESTORED", "REBUILT_AND_VERIFIED")
                                                        .contains(r.get("status"))
                                                && Boolean.TRUE.equals(r.get("isolatedRemoved"))
                                                && Boolean.TRUE.equals(
                                                        r.get("replacementRemoved")));
        return Map.of(
                "isolatedRehearsalVerified",
                verified,
                "runId",
                runId,
                "checkedAt",
                Instant.now().toString(),
                "formalTablesChanged",
                false,
                "scope",
                "六组隔离副本，每组最多两行；不证明正式迁移或MV验收完成",
                "objects",
                results);
    }

    private Map<String, Object> rehearse(String source, String originalDate, String isolated) {
        var result = new LinkedHashMap<String, Object>();
        result.put("source", source);
        result.put("isolated", isolated);
        result.put("isolatedCreated", false);
        Long ownedId = null;
        Long replacementId = null;
        String replacement = isolated + "_v2";
        try {
            var sourceMetadata = metadata(source);
            if (!originalDate.equals(sourceMetadata.get("designatedTimestamp"))) {
                throw new IllegalStateException("来源指定时间列已变化，停止使用旧迁移基线");
            }
            var columns = columns(source);
            String ddl =
                    String.valueOf(
                            jdbc.queryForList("SHOW CREATE TABLE " + quote(source))
                                    .getFirst()
                                    .values()
                                    .iterator()
                                    .next());
            String isolatedDdl = isolatedDdl(source, isolated, ddl);
            result.put("sourceMetadata", sourceMetadata);
            result.put("sourceColumns", columns);
            result.put("sourceDdl", ddl);
            jdbc.execute(isolatedDdl);
            result.put("isolatedCreated", true);
            ownedId = number(metadata(isolated).get("id"));
            result.put("ownedId", ownedId);
            jdbc.execute(
                    "INSERT INTO "
                            + quote(isolated)
                            + " SELECT * FROM "
                            + quote(source)
                            + " LIMIT 2");
            awaitWal(isolated);
            var before = sample(isolated, columns, originalDate, originalDate);
            result.put("sampleBefore", before);
            result.put("sampleRows", before.size());
            try {
                jdbc.execute(renameSql(isolated, originalDate, "trade_date"));
            } catch (UncategorizedSQLException rejected) {
                // 仅在服务端明确拒绝且原布局未变时转入新表方案；未知 DDL 结果不重试。
                if (!rejected.getMessage().contains("cannot rename designated timestamp column"))
                    throw rejected;
                requireShape(sourceMetadata, metadata(isolated), ownedId, originalDate);
                result.put("directRenameSupported", false);
                result.put("directRenameError", rejected.getMessage());
                jdbc.execute(replacementDdl(source, replacement, ddl, originalDate));
                replacementId = number(metadata(replacement).get("id"));
                result.put("replacement", replacement);
                result.put("replacementId", replacementId);
                // 两个对象都只属于本次演练。新对象身份不复用旧账本或覆盖凭证。
                jdbc.execute(
                        "INSERT INTO " + quote(replacement) + " SELECT * FROM " + quote(isolated));
                awaitWal(replacement);
                requireShape(sourceMetadata, metadata(replacement), replacementId, "trade_date");
                var rebuiltColumns = columns(replacement);
                result.put("replacementColumns", rebuiltColumns);
                requireColumns(columns, rebuiltColumns, originalDate, "trade_date");
                if (!before.equals(sample(replacement, columns, originalDate, "trade_date"))
                        || !before.equals(sample(isolated, columns, originalDate, originalDate))) {
                    throw new IllegalStateException("新布局复制或旧布局保留样本不一致");
                }
                result.put("replacementMetadata", metadata(replacement));
                result.put("status", "REBUILT_AND_VERIFIED");
                return result;
            }
            result.put("directRenameSupported", true);
            awaitWal(isolated);
            var renamed = metadata(isolated);
            requireShape(sourceMetadata, renamed, ownedId, "trade_date");
            if (!before.equals(sample(isolated, columns, originalDate, "trade_date"))) {
                throw new IllegalStateException("改名后完整样本键值不一致");
            }
            result.put("renamedMetadata", renamed);
            var renamedColumns = columns(isolated);
            requireColumns(columns, renamedColumns, originalDate, "trade_date");
            result.put("renamedColumns", renamedColumns);
            // 实际执行逆操作，验证失败恢复所需的原列名仍可恢复；不假设 DDL 事务回滚。
            jdbc.execute(renameSql(isolated, "trade_date", originalDate));
            awaitWal(isolated);
            requireShape(sourceMetadata, metadata(isolated), ownedId, originalDate);
            if (!before.equals(sample(isolated, columns, originalDate, originalDate))) {
                throw new IllegalStateException("逆向改名后完整样本键值不一致");
            }
            result.put("status", "RENAMED_AND_RESTORED");
        } catch (Exception failure) {
            result.put("status", "FAILED");
            if (ownedId != null) {
                try {
                    result.put("failedMetadata", metadata(isolated));
                    result.put(
                            "failedWal",
                            jdbc.queryForList("SELECT * FROM wal_tables() WHERE name=?", isolated));
                } catch (Exception unavailable) {
                    result.put("failedStateCaptureError", unavailable.getClass().getSimpleName());
                }
            }
            if (replacementId != null) {
                try {
                    result.put("failedReplacementMetadata", metadata(replacement));
                    result.put(
                            "failedReplacementWal",
                            jdbc.queryForList(
                                    "SELECT * FROM wal_tables() WHERE name=?", replacement));
                } catch (Exception unavailable) {
                    result.put(
                            "failedReplacementStateCaptureError",
                            unavailable.getClass().getSimpleName());
                }
            }
            result.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            // 只清理本次成功创建且身份未被替换的对象；未知或替换后的对象留给人工核对。
            result.put("replacementRemoved", cleanup(replacement, replacementId, result));
            result.put("isolatedRemoved", cleanup(isolated, ownedId, result));
        }
        return result;
    }

    boolean cleanup(String table, Long ownedId, Map<String, Object> result) {
        try {
            if (!table.matches("java_cn_time_rehearsal_[0-9a-f]{32}_[0-5](?:_v2)?"))
                throw new IllegalArgumentException("禁止清理非本轮隔离对象");
            if (ownedId == null)
                return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table)
                        .isEmpty();
            if (number(metadata(table).get("id")) != ownedId.longValue())
                throw new IllegalStateException("隔离身份变化，禁止删除");
            jdbc.execute("DROP TABLE " + quote(table));
            return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty();
        } catch (Exception failure) {
            result.put(
                    "cleanupError:" + table,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    static String replacementDdl(String source, String target, String ddl, String originalDate) {
        if (!Objects.equals(SOURCES.get(source), originalDate) || !target.endsWith("_v2"))
            throw new IllegalArgumentException("迁移来源和列名不符");
        String result = isolatedDdl(source, target, ddl);
        String column = "\n\t" + originalDate + " ";
        String designated = ") timestamp(" + originalDate + ")";
        if (!result.contains(column) || !result.contains(designated))
            throw new IllegalStateException("不能识别指定列 DDL，停止改写");
        result =
                result.replace(column, "\n\ttrade_date ")
                        .replace(designated, ") timestamp(trade_date)");
        String keysPrefix = "DEDUP UPSERT KEYS(";
        int keysStart = result.indexOf(keysPrefix);
        if (keysStart >= 0) {
            int contentStart = keysStart + keysPrefix.length();
            int contentEnd = result.indexOf(')', contentStart);
            var keys =
                    Arrays.stream(result.substring(contentStart, contentEnd).split(","))
                            .map(String::trim)
                            .map(k -> k.equals(originalDate) ? "trade_date" : k)
                            .toList();
            result =
                    result.substring(0, contentStart)
                            + String.join(",", keys)
                            + result.substring(contentEnd);
        }
        return result;
    }

    static String isolatedDdl(String source, String isolated, String ddl) {
        if (!SOURCES.containsKey(source)
                || !isolated.matches("java_cn_time_rehearsal_[0-9a-f]{32}_[0-5](?:_v2)?")) {
            throw new IllegalArgumentException("只允许固定来源与本轮隔离目标");
        }
        // SHOW CREATE 的表定义部分原样复用，不能顺带改精度、分区或去重键。
        String prefix = "CREATE TABLE '" + source + "' ";
        if (!ddl.startsWith(prefix) || ddl.charAt(prefix.length()) != '(') {
            throw new IllegalArgumentException("不支持的 SHOW CREATE 格式，停止演练");
        }
        return "CREATE TABLE " + quote(isolated) + " " + ddl.substring(prefix.length());
    }

    static String renameSql(String isolated, String from, String to) {
        if (!isolated.matches("java_cn_time_rehearsal_[0-9a-f]{32}_[0-5]")
                || !List.of("timestamp", "ts", "trade_date").contains(from)
                || !List.of("timestamp", "ts", "trade_date").contains(to)
                || from.equals(to)) {
            throw new IllegalArgumentException("只允许固定日期列的隔离改名");
        }
        return "ALTER TABLE "
                + quote(isolated)
                + " RENAME COLUMN "
                + quote(from)
                + " TO "
                + quote(to);
    }

    private List<Map<String, Object>> columns(String table) {
        DatasetDefinition.identifier(table);
        // QuestDB 的此系统函数要求字符串常量，不能使用 PGWire 绑定参数。
        return jdbc.queryForList("SELECT * FROM table_columns('" + table + "')");
    }

    private Map<String, Object> metadata(String table) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,table_name,designatedTimestamp,partitionBy,walEnabled,dedup FROM tables() WHERE table_name=?",
                        table);
        if (rows.size() != 1) throw new IllegalStateException("必须存在唯一隔离或来源对象");
        return rows.getFirst();
    }

    private List<Map<String, Object>> sample(
            String table,
            List<Map<String, Object>> columns,
            String originalDate,
            String actualDate) {
        var projection = new ArrayList<String>();
        var keys = new ArrayList<String>();
        keys.add(quote(actualDate));
        for (var column : columns) {
            String name = String.valueOf(column.get("column"));
            if (name.equals("null")) name = String.valueOf(column.get("name"));
            String actual = name.equals(originalDate) ? actualDate : name;
            String expression = quote(actual);
            if (List.of("TIMESTAMP", "TIMESTAMP_NS", "DATE")
                    .contains(String.valueOf(column.get("type")))) {
                // 直接比较存储整数，避免 PGWire 时间显示精度掩盖纳秒变化。
                expression = "cast(" + expression + " AS long)";
            }
            projection.add(expression + " AS " + quote(name));
            if (Boolean.TRUE.equals(column.get("upsertKey")) && !actual.equals(actualDate))
                keys.add(quote(actual));
        }
        return jdbc.queryForList(
                "SELECT "
                        + String.join(",", projection)
                        + " FROM "
                        + quote(table)
                        + " ORDER BY "
                        + String.join(",", keys)
                        + " LIMIT 3");
    }

    private void awaitWal(String table) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            if (QuestDbWriteChecks.walSettled(jdbc, table)) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("隔离目标 WAL 未稳定");
    }

    private static void requireShape(
            Map<String, Object> source, Map<String, Object> actual, long id, String date) {
        if (number(actual.get("id")) != id || !date.equals(actual.get("designatedTimestamp"))) {
            throw new IllegalStateException("隔离身份或指定列不符");
        }
        for (String field : List.of("partitionBy", "walEnabled", "dedup")) {
            if (!Objects.equals(source.get(field), actual.get(field)))
                throw new IllegalStateException("隔离存储布局变化：" + field);
        }
    }

    static void requireColumns(
            List<Map<String, Object>> before,
            List<Map<String, Object>> after,
            String oldName,
            String newName) {
        if (before.size() != after.size()) throw new IllegalStateException("字段数量变化");
        for (var original : before) {
            String key = original.containsKey("column") ? "column" : "name";
            String name = String.valueOf(original.get(key));
            String expected = name.equals(oldName) ? newName : name;
            var matching = after.stream().filter(c -> expected.equals(c.get(key))).toList();
            if (matching.size() != 1) throw new IllegalStateException("改名后字段不存在或重名：" + expected);
            // symbolCapacity 会随字典增长；未启用索引的块容量不参与业务布局判断。
            if (Boolean.TRUE.equals(original.get("indexed"))
                    && !Objects.equals(
                            original.get("indexBlockCapacity"),
                            matching.getFirst().get("indexBlockCapacity"))) {
                throw new IllegalStateException("改名后索引块容量变化：" + expected);
            }
            for (String field :
                    List.of(
                            "type",
                            "designated",
                            "upsertKey",
                            "indexed",
                            "symbolCached",
                            "indexType",
                            "indexInclude")) {
                if (!Objects.equals(original.get(field), matching.getFirst().get(field))) {
                    throw new IllegalStateException("改名后字段布局变化：" + expected + "." + field);
                }
            }
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("缺少物理对象身份");
        return number.longValue();
    }

    private static String quote(String name) {
        DatasetDefinition.identifier(name);
        return "\"" + name + "\"";
    }
}
