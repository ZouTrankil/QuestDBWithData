package com.zoutrankil.data.domain.derived;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.derived.table.MarketSentimentDailyRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 复用现有日频合同；交易日与真实计算时刻分开，不改变旧布局版本。 */
public final class MarketSentimentDailyDataset {
    private MarketSentimentDailyDataset() {}

    public static final List<String> COLUMNS =
            List.of(
                    "trade_date",
                    "month",
                    "sentiment_score",
                    "sentiment_score_core",
                    "sentiment_score_enhanced",
                    "sentiment_state",
                    "heat_score",
                    "breadth_score",
                    "limit_score",
                    "profit_effect_score",
                    "leverage_score",
                    "moneyflow_score",
                    "structure_score",
                    "divergence_score",
                    "pc1_heat",
                    "pc2_divergence",
                    "pc3_structure",
                    "total_amount",
                    "amount_to_circ_mv",
                    "avg_turnover_rate",
                    "median_turnover_rate",
                    "high_turnover_stock_ratio",
                    "top_amount_share",
                    "up_ratio",
                    "down_ratio",
                    "up_down_spread",
                    "limit_up_ratio",
                    "limit_down_ratio",
                    "limit_net_ratio",
                    "max_limit_streak",
                    "limit_promotion_rate",
                    "pct_above_ma20",
                    "median_distance_to_ma20",
                    "pct_distance_to_ma20_gt_5",
                    "pct_distance_to_ma20_lt_minus_5",
                    "small_large_ret",
                    "growth_value_ret",
                    "margin_buy_sell_ratio",
                    "margin_buy_amount_ratio",
                    "margin_balance_change_5d",
                    "moneyflow_net_amount_ratio",
                    "moneyflow_large_net_ratio",
                    "index_up_breadth_down",
                    "amount_up_limit_down",
                    "small_up_large_down",
                    "sentiment_up_return_down",
                    "is_false_boom",
                    "is_limit_collapse",
                    "is_leverage_warning",
                    "is_crowded",
                    "data_quality_flag",
                    "model_version",
                    "updated_at");
    public static final DatasetDefinition DEFINITION = definition("market_sentiment_daily");

    public static DatasetDefinition definition(String table) {
        var columns = new ArrayList<Column>();
        var components = MarketSentimentDailyRow.class.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            String name = COLUMNS.get(i);
            Class<?> type = components[i].getType();
            var storage =
                    type == Instant.class
                            ? StorageType.TIMESTAMP
                            : type == Double.class
                                    ? StorageType.DOUBLE
                                    : type == Boolean.class
                                            ? StorageType.BOOLEAN
                                            : StorageType.SYMBOL;
            var temporal =
                    type != Instant.class
                            ? null
                            : new TemporalContract(
                                    name.equals("trade_date")
                                            ? TemporalKind.BUSINESS_DATE
                                            : TemporalKind.INSTANT,
                                    "epochMicros",
                                    name.equals("trade_date") ? "calendar" : "UTC",
                                    "MICROS",
                                    name.equals("trade_date")
                                            ? "Trading date encoded at exact UTC midnight"
                                            : "Java calculation observation instant");
            columns.add(
                    new Column(
                            "derived:" + name,
                            name,
                            name,
                            storage,
                            !name.equals("trade_date"),
                            "Native v2 rule/PCA field " + name,
                            temporal));
        }
        return new DatasetDefinition(
                "market_sentiment_daily",
                1,
                "derived.questdb",
                "java.market_sentiment_daily",
                table,
                ObjectKind.TABLE,
                columns,
                List.of("trade_date"),
                List.of(),
                "trade_date",
                Partition.MONTH,
                true,
                Set.of(Capability.READ, Capability.WAL_REPLACE),
                List.of(
                        "stk_factor",
                        "daily_basic",
                        "stk_limit",
                        "stk_suspend",
                        "stk_st_daily",
                        "margin_detail",
                        "moneyflow"),
                "53 legacy fields; date-keyed bounded full-row window replacement retaining outside rows and backup. 1400-day warmup; shifted 756/120 rolling scores; full-window winsorization/PCA are inherited rebuild semantics, not a historical point-in-time PCA promise.");
    }
}
