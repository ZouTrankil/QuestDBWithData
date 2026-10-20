package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;
import com.zoutrankil.data.domain.stock.market.DailyBasicDataset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 实际 SELECT 和字段证明共用映射；保留左连接，不因单位说明填值或缩放。 */
final class StrategyStockDailyProjection {
    private StrategyStockDailyProjection() {}

    private static final String JOIN =
            "LEFT JOIN daily_basic d ON b.trade_date=d.trade_date AND b.ts_code=d.ts_code";

    private record Binding(
            String sourceAlias, DatasetDefinition dataset, Column column, String output) {
        String sql() {
            String source = sourceAlias + "." + column.storageName();
            return column.storageName().equals(output) ? source : source + " AS " + output;
        }

        Map<String, Object> evidence() {
            var result = new LinkedHashMap<String, Object>();
            result.put("outputField", output);
            result.put("logicalField", StrategyInputRows.standardFieldName("stock_daily", output));
            result.put("sourceDataset", dataset.datasetId());
            result.put("datasetVersion", dataset.schemaVersion());
            result.put("sourcePhysicalField", column.storageName());
            result.put("contractSourceField", column.sourceName());
            result.put("sqlExpression", sql());
            result.put("nullableAfterJoin", column.nullable() || sourceAlias.equals("d"));
            if (column.numeric() != null) result.put("numeric", column.numeric());
            if (column.temporal() != null) result.put("temporal", column.temporal());
            return Map.copyOf(result);
        }
    }

    private static final List<Binding> FIELDS =
            List.of(
                    bind("b", "trade_date", "trade_date"), bind("b", "ts_code", "ts_code"),
                    bind("b", "close", "price"), bind("b", "open", "open_price"),
                    bind("b", "close", "raw_close"), bind("b", "amount", "amount"),
                    bind("b", "up_limit", "up_limit"), bind("b", "down_limit", "down_limit"),
                    bind("b", "is_suspended", "is_suspended"), bind("b", "is_st", "is_st"),
                    bind("d", "pb", "pb"), bind("d", "turnover_rate", "turnover_rate"),
                    bind("d", "total_mv", "total_mv"), bind("d", "ts_code", "basic_present"));

    private static Binding bind(String sourceAlias, String field, String output) {
        var dataset =
                switch (sourceAlias) {
                    case "b" -> BacktestDailyViewDataset.DEFINITION;
                    case "d" -> DailyBasicDataset.DEFINITION;
                    default -> throw new IllegalArgumentException("未登记的股票输入来源别名");
                };
        DatasetDefinition.identifier(output);
        var column =
                dataset.columns().stream()
                        .filter(value -> value.storageName().equals(field))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("股票投影字段不在来源契约中：" + field));
        return new Binding(sourceAlias, dataset, column, output);
    }

    static String querySql() {
        // 只改变投影的维护位置；日期边界、完整连接键和排序仍与原查询相同。
        return "SELECT "
                + FIELDS.stream().map(Binding::sql).collect(Collectors.joining(","))
                + " FROM mv_backtest_daily b "
                + JOIN
                + " WHERE b.trade_date>=cast(? AS TIMESTAMP) AND b.trade_date<cast(? AS TIMESTAMP)"
                + " ORDER BY b.trade_date,b.ts_code";
    }

    static Map<String, Object> evidence() {
        return Map.of(
                "schemaVersion",
                1,
                "role",
                "stock_daily",
                "join",
                JOIN,
                "rightMissingPolicy",
                "只允许原校验已确认的停牌行缺 daily_basic；不补右侧数值",
                "fields",
                FIELDS.stream().map(Binding::evidence).toList(),
                "valuesConverted",
                false,
                "modelAcceptanceVerified",
                false);
    }
}
