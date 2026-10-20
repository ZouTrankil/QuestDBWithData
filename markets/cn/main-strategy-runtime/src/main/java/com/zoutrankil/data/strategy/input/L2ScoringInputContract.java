package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 读取投影和本次字段审计共用清单；保留原评分器的缺失、关联和派生行为。 */
final class L2ScoringInputContract {
    private L2ScoringInputContract() {}

    private static final List<String> FIELDS =
            List.of(
                    "ts",
                    "symbol",
                    "clean_amount",
                    "q2_count",
                    "q2_accumulation",
                    "q2_accumulation_ratio",
                    "active_buy_ratio",
                    "gmm_main_force_net_inflow",
                    "obi_top5_std",
                    "orderbook_replenish_speed",
                    "depth_slope",
                    "continuous_auction_ratio",
                    "close_auction_net_inflow",
                    "open_close_vol_ratio",
                    "q3_count",
                    "q4_count",
                    "q4_distribution",
                    "q4_distribution_ratio",
                    "active_sell_ratio",
                    "large_active_sell_amount",
                    "depth_top5_mean",
                    "ofi_persistence",
                    "ofi_std",
                    "mfi_escort",
                    "mean_obi_top5",
                    "main_funds_buy_amount",
                    "daily_amount",
                    "amount",
                    "turnover_rate");

    private static final Set<String> REQUIRED =
            Set.of(
                    "ts",
                    "symbol",
                    "clean_amount",
                    "q2_count",
                    "q2_accumulation",
                    "q3_count",
                    "q4_count",
                    "main_funds_buy_amount");

    private static Set<String> schema(Map<String, Object> clocks) {
        if (!(clocks.get("l2_daily_features") instanceof Map<?, ?> card)
                || !(card.get("schema") instanceof List<?> columns))
            throw new IllegalStateException("Pinned L2 schema required");
        var names = new HashSet<String>();
        for (Object column : columns)
            if (column instanceof Map<?, ?> row) names.add(Objects.toString(row.get("column"), ""));
        if (!names.containsAll(REQUIRED))
            throw new IllegalStateException("Required formal L2 scoring schema is missing");
        return names;
    }

    static String projection(Map<String, Object> clocks) {
        var names = schema(clocks);
        return String.join(",", FIELDS.stream().filter(names::contains).toList());
    }

    /** 只统计原始有界快照，不把 NaN 当作零，不用结构审计代替批准模型的验收。 */
    static Map<String, Object> inspect(Map<String, Object> clocks, List<Map<String, Object>> rows) {
        var names = schema(clocks);
        var definitions =
                L2DailyFeaturesDataset.DEFINITION.columns().stream()
                        .collect(
                                Collectors.toMap(
                                        column -> column.storageName(), Function.identity()));
        var fields =
                FIELDS.stream()
                        .map(
                                field -> {
                                    long missing = 0,
                                            nulls = 0,
                                            finite = 0,
                                            nonFinite = 0,
                                            nonNumeric = 0;
                                    boolean identity = field.equals("ts") || field.equals("symbol");
                                    for (var row : rows) {
                                        if (!row.containsKey(field)) missing++;
                                        else if (row.get(field) == null) nulls++;
                                        else if (!identity) {
                                            if (!(row.get(field) instanceof Number number))
                                                nonNumeric++;
                                            else if (Double.isFinite(number.doubleValue()))
                                                finite++;
                                            else nonFinite++;
                                        }
                                    }
                                    var item = new LinkedHashMap<String, Object>();
                                    item.put("sourceField", field);
                                    item.put(
                                            "logicalField",
                                            field.equals("ts")
                                                    ? "trade_date"
                                                    : field.equals("symbol")
                                                            ? "instrument_code"
                                                            : field);
                                    item.put("requiredSourceColumn", REQUIRED.contains(field));
                                    item.put("schemaPresent", names.contains(field));
                                    var definition = definitions.get(field);
                                    item.put(
                                            "meaning",
                                            definition == null
                                                    ? "历史评分候选列，正式 L2 契约未登记"
                                                    : definition.meaning());
                                    item.put("missingKeyRows", missing);
                                    item.put("nullRows", nulls);
                                    item.put("finiteNumericRows", finite);
                                    item.put("nonFiniteNumericRows", nonFinite);
                                    item.put("nonNumericRows", nonNumeric);
                                    item.put("scoringRule", rule(field));
                                    item.put(
                                            "numericUnitStatus",
                                            identity ? "不适用" : "原值保留；单位、币种及比例尺度尚未完成生产验收");
                                    return Map.copyOf(item);
                                })
                        .toList();
        return Map.of(
                "schemaVersion",
                1,
                "sourceRows",
                rows.size(),
                "fields",
                fields,
                "valuesChanged",
                false,
                "sourceCoverageVerifiedByThisAudit",
                false,
                "modelAcceptanceVerified",
                false);
    }

    private static String rule(String field) {
        return switch (field) {
            case "ts", "symbol" -> "业务键；v2 显式映射日期与完整证券代码，不作为数值特征";
            case "daily_amount" -> "基础评分使用关联行情 amount；最终增强列仅在原始 L2 存在时保留原值";
            case "amount" -> "基础评分在关联行情提供 amount 时覆盖原值；缺行情保留原左连接行为；最终增强列仅在原始 L2 存在时保留原值，不凭同名认定单位相同";
            case "turnover_rate" -> "基础评分只接受原始 L2 同名列；不从日行情补入；缺失沿用原 NaN 和排名规则";
            case "main_funds_buy_amount" ->
                    "原值进入增强特征；基础 buy_aggressiveness=原值/关联行情 amount，分母为零时 NaN；量纲仍待独立验收";
            case "q2_accumulation", "gmm_main_force_net_inflow", "close_auction_net_inflow" ->
                    "原值参与现行评分；增强特征另按原公式除以 clean_amount，不在读取时缩放";
            default -> "仅投影实际存在的列；缺失沿用原 NaN/排名及特征逻辑，不补零；meanRank 无有效排名时原默认值为 0.5";
        };
    }
}
