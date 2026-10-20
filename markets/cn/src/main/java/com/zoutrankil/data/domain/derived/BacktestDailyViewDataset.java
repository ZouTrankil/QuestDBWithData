package com.zoutrankil.data.domain.derived;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.NumericContract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** The native automatically refreshed backtest MV, built directly from the four raw sources. */
public final class BacktestDailyViewDataset {
    private BacktestDailyViewDataset() {}

    private static final String NUMERIC_EVIDENCE =
            "markets/cn/data/src/main/resources/sql/mv_backtest_daily.sql";

    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "Exchange business date; UTC midnight is only the QuestDB storage carrier");

    public static final DatasetDefinition DEFINITION =
            new DatasetDefinition(
                    "mv_backtest_daily",
                    1,
                    "derived.questdb.backtest_daily",
                    "questdb_native_mv",
                    "mv_backtest_daily",
                    ObjectKind.MATERIALIZED_VIEW,
                    List.of(
                            new Column(
                                    "trade_date",
                                    "trade_date",
                                    "trade_date",
                                    StorageType.TIMESTAMP,
                                    false,
                                    "Exchange business date stored at UTC midnight; not an event instant",
                                    TRADE_DATE),
                            new Column(
                                    "ts_code",
                                    "ts_code",
                                    "ts_code",
                                    StorageType.SYMBOL,
                                    false,
                                    "Exchange-qualified mainland equity code",
                                    null),
                            metric(
                                    "open",
                                    "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                            metric(
                                    "high",
                                    "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                            metric(
                                    "low",
                                    "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                            metric(
                                    "close",
                                    "Daily unadjusted close in CNY/share; suspended rows carry the previous close"),
                            metric(
                                    "vol",
                                    "Tushare volume in lots (手); synthesized suspended rows use zero"),
                            metric(
                                    "amount",
                                    "Tushare turnover in thousand CNY (千元); synthesized suspended rows use zero"),
                            metric(
                                    "adj_factor",
                                    "Dimensionless adjustment factor copied from the source price row"),
                            metric(
                                    "up_limit",
                                    "Upper daily price limit in CNY/share, copied from stk_limit when available"),
                            metric(
                                    "down_limit",
                                    "Lower daily price limit in CNY/share, copied from stk_limit when available"),
                            new Column(
                                            "derived:stk_suspend",
                                            "is_suspended",
                                            "is_suspended",
                                            StorageType.LONG,
                                            true,
                                            "Suspension indicator; coalesced to zero by the current view for normal rows",
                                            null)
                                    .withNumeric(indicator("缺少停牌记录时按原 SQL coalesce 为零")),
                            new Column(
                                            "derived:stk_st_daily",
                                            "is_st",
                                            "is_st",
                                            StorageType.INT,
                                            true,
                                            "Special-treatment indicator; coalesced to zero by the current view when absent",
                                            null)
                                    .withNumeric(indicator("缺少 ST 记录时按原 SQL coalesce 为零"))),
                    List.of("trade_date", "ts_code"),
                    List.of(),
                    "trade_date",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ),
                    List.of("stk_factor", "stk_limit", "stk_suspend", "stk_st_daily"),
                    "QuestDB owns the 13-column materialized result, including prior-price suspension rows. "
                            + "REFRESH IMMEDIATE listens to stk_limit; ingest stk_factor, stk_suspend and stk_st_daily first, then stk_limit. "
                            + "Applications only ingest raw tables and read mv_backtest_daily; no backtest result table, staging, publication or refresh job.");

    private static Column metric(String name, String meaning) {
        return new Column(
                        "derived:mv_backtest_daily." + name,
                        name,
                        name,
                        StorageType.DOUBLE,
                        true,
                        meaning,
                        null)
                .withNumeric(numeric(name));
    }

    private static NumericContract numeric(String name) {
        // 这里描述当前 MV 的合成停牌规则，不把原始来源的缺失规则套到派生行上。
        return switch (name) {
            case "amount" ->
                    new NumericContract(
                            "千元",
                            "元",
                            new BigDecimal("1000"),
                            "CNY",
                            "不适用",
                            "stk_factor.amount 原值，不复权",
                            "当日来源行保留原值和 null；原 SQL 的停牌补行金额为零",
                            NUMERIC_EVIDENCE);
            case "vol" ->
                    new NumericContract(
                            "手",
                            "手",
                            BigDecimal.ONE,
                            "NONE",
                            "不适用",
                            "stk_factor.vol 原值，不推断每手股数",
                            "当日来源行保留原值和 null；原 SQL 的停牌补行成交量为零",
                            NUMERIC_EVIDENCE);
            case "adj_factor" ->
                    new NumericContract(
                            "因子值",
                            "因子值",
                            BigDecimal.ONE,
                            "NONE",
                            "来源复权因子基准，不重新基准化",
                            "stk_factor.adj_factor 原值",
                            "按原 ASOF 行保留因子和 null，不用一补缺失",
                            NUMERIC_EVIDENCE);
            case "up_limit", "down_limit" ->
                    new NumericContract(
                            "元",
                            "元",
                            BigDecimal.ONE,
                            "CNY",
                            "一股",
                            "stk_limit 当日未复权价格上限或下限",
                            "原 LEFT JOIN 缺失时保持 null，不填前一日涨跌停价",
                            NUMERIC_EVIDENCE);
            case "open", "high", "low", "close" ->
                    new NumericContract(
                            "元",
                            "元",
                            BigDecimal.ONE,
                            "CNY",
                            "一股",
                            "stk_factor 未复权价格；原 SQL 的停牌补行沿用 ASOF 收盘价",
                            "原始 null 保留；停牌沿用价格仅由原 MV SQL 决定",
                            NUMERIC_EVIDENCE);
            default -> throw new IllegalArgumentException("未登记的回测 MV 数值字段：" + name);
        };
    }

    private static NumericContract indicator(String missingPolicy) {
        return new NumericContract(
                "标志", "标志", BigDecimal.ONE, "NONE", "不适用", "不适用", missingPolicy, NUMERIC_EVIDENCE);
    }
}
