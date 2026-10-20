package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.strategy.score.FrozenScalarValues;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 实际输入映射：v2 快照用标准身份，原模型特征在明确的 v1 边界适配。 */
public final class StrategyInputRows {
    private StrategyInputRows() {}

    private static final Map<String, Map<String, String>> FORWARD = mappings();
    private static final Map<String, Map<String, String>> REVERSE = inverse();

    static String standardFieldName(String role, String field) {
        return names(FORWARD, role).getOrDefault(field, field);
    }

    public static Map<String, List<Map<String, Object>>> standardTables(
            StrategyInputSchema schema, Map<String, List<Map<String, Object>>> tables) {
        if (Objects.requireNonNull(schema) == StrategyInputSchema.V1) return tables;
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        tables.forEach((role, rows) -> result.put(role, standard(role, rows)));
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, List<Map<String, Object>>> legacyModelTables(
            StrategyInputSchema schema, Map<String, List<Map<String, Object>>> tables) {
        if (Objects.requireNonNull(schema) == StrategyInputSchema.V1) return tables;
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        tables.forEach((role, rows) -> result.put(role, legacyModel(role, rows)));
        return Collections.unmodifiableMap(result);
    }

    public static List<Map<String, Object>> standard(String role, List<Map<String, Object>> rows) {
        var names = names(FORWARD, role);
        return rows.stream()
                .map(
                        row -> {
                            var changed = new LinkedHashMap<String, Object>();
                            row.forEach(
                                    (field, value) -> {
                                        Object normalized = canonicalValue(role, field, value);
                                        if (!Objects.equals(value, normalized))
                                            changed.put(field, normalized);
                                    });
                            var values =
                                    changed.isEmpty()
                                            ? row
                                            : FrozenScalarValues.merging(row, changed);
                            return FrozenScalarValues.renaming(values, names);
                        })
                .toList();
    }

    public static List<Map<String, Object>> legacyModel(
            String role, List<Map<String, Object>> rows) {
        var names = names(REVERSE, role);
        return rows.stream()
                .map(
                        row -> {
                            var renamed = FrozenScalarValues.renaming(row, names);
                            var changed = new LinkedHashMap<String, Object>();
                            renamed.forEach(
                                    (field, value) -> {
                                        Object legacy = legacyValue(role, field, value);
                                        if (!Objects.equals(value, legacy))
                                            changed.put(field, legacy);
                                    });
                            return changed.isEmpty()
                                    ? renamed
                                    : FrozenScalarValues.merging(renamed, changed);
                        })
                .toList();
    }

    private static Object canonicalValue(String role, String field, Object value) {
        if (value == null) return null;
        Column column = StrategyInputTemporalMappings.binding(role).columns().get(field);
        if (column == null || column.temporal() == null) return value;
        var kind = column.temporal().kind();
        if (kind == TemporalKind.TECHNICAL) {
            if (!role.equals("etf_reference")
                    || !field.equals("timestamp")
                    || !LocalDate.ofEpochDay(0).equals(value))
                throw new IllegalStateException("技术占位不能推算为业务日期：" + role + "." + field);
            return Instant.EPOCH.toString();
        }
        if (kind == TemporalKind.INSTANT) {
            if (!(value instanceof String text))
                throw new IllegalStateException("v2 真实时刻须来自已解码的 UTC 时刻：" + role + "." + field);
            return Instant.parse(text).toString();
        }
        if (value instanceof LocalDate day) return day;
        if (!(value instanceof String text))
            throw new IllegalStateException("业务日期缺少明确编码：" + role + "." + field);
        // 仅接受合同或已核对的历史空值，不把未知文本和数字猜成日期。
        if (column.nullable()
                && (text.isBlank()
                        || column.legacyNullSentinels().contains(text)
                        || role.equals("calendar")
                                && field.equals("pretrade_date")
                                && text.equals("None"))) return null;
        try {
            return switch (column.temporal().sourceFormat()) {
                case "BASIC", "YYYYMMDD" -> LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
                case "ISO_DATE", "YYYY-MM-DD" -> LocalDate.parse(text);
                default -> throw new IllegalStateException("未支持的来源日期编码：" + role + "." + field);
            };
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("来源日期不符合合同：" + role + "." + field, invalid);
        }
    }

    private static Object legacyValue(String role, String field, Object value) {
        if (value == null) return null;
        Column column = StrategyInputTemporalMappings.binding(role).columns().get(field);
        if (column == null || column.temporal() == null) return value;
        if (column.temporal().kind() == TemporalKind.TECHNICAL) {
            if (!role.equals("etf_reference")
                    || !field.equals("timestamp")
                    || !Instant.EPOCH.toString().equals(value))
                throw new IllegalStateException("v1 适配不能把任意时刻变成技术占位");
            return LocalDate.ofEpochDay(0);
        }
        if (column.temporal().kind() == TemporalKind.BUSINESS_DATE
                && column.storageType() == StorageType.STRING
                && value instanceof LocalDate day) {
            return switch (column.temporal().sourceFormat()) {
                case "BASIC", "YYYYMMDD" -> day.format(DateTimeFormatter.BASIC_ISO_DATE);
                case "ISO_DATE", "YYYY-MM-DD" -> day.toString();
                default -> throw new IllegalStateException("未支持的模型日期适配：" + role + "." + field);
            };
        }
        return value;
    }

    private static Map<String, String> names(
            Map<String, Map<String, String>> registry, String role) {
        var names = registry.get(role);
        if (names == null) throw new IllegalArgumentException("未登记的主策略输入：" + role);
        return names;
    }

    private static Map<String, Map<String, String>> mappings() {
        var result = new HashMap<String, Map<String, String>>();
        for (var role : StrategyInputSchema.V1.keys().keySet()) {
            var names = new LinkedHashMap<String, String>();
            for (var column : StrategyInputTemporalMappings.binding(role).definition().columns()) {
                if (!column.storageName().equals(column.logicalName()))
                    names.put(column.storageName(), column.logicalName());
            }
            var oldKeys = StrategyInputSchema.V1.keys().get(role);
            var newKeys = StrategyInputSchema.V2.keys().get(role);
            for (int i = 0; i < oldKeys.size(); i++)
                if (!oldKeys.get(i).equals(newKeys.get(i)))
                    names.put(oldKeys.get(i), newKeys.get(i));
            if (role.equals("etf_reference"))
                names.putAll(
                        Map.of(
                                "timestamp",
                                "technical_partition_marker",
                                "found_date",
                                "establishment_date",
                                "due_date",
                                "maturity_date",
                                "list_date",
                                "listing_date",
                                "delist_date",
                                "delisting_date",
                                "purc_startdate",
                                "subscription_start_date",
                                "redm_startdate",
                                "redemption_start_date"));
            if (role.equals("stock_metadata")) names.put("symbol", "local_code");
            if (role.equals("etf_portfolio"))
                names.putAll(
                        Map.of(
                                "amount",
                                "holding_quantity",
                                "mkv",
                                "holding_market_value",
                                "stk_mkv_ratio",
                                "holding_market_value_ratio"));
            result.put(role, Map.copyOf(names));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Map<String, String>> inverse() {
        var result = new HashMap<String, Map<String, String>>();
        FORWARD.forEach(
                (role, names) -> {
                    var inverse = new HashMap<String, String>();
                    names.forEach(
                            (source, target) -> {
                                if (inverse.put(target, source) != null)
                                    throw new IllegalStateException(
                                            "字段映射不可逆：" + role + "." + target);
                            });
                    result.put(role, Map.copyOf(inverse));
                });
        return Map.copyOf(result);
    }
}
