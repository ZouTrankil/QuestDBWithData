package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;

public final class MarketSentimentDailyRows {
    private MarketSentimentDailyRows() {}
    public static final List<String> COLUMNS=List.of("trade_date","month","sentiment_score","sentiment_score_core","sentiment_score_enhanced","sentiment_state",
            "heat_score","breadth_score","limit_score","profit_effect_score","leverage_score","moneyflow_score","structure_score","divergence_score",
            "pc1_heat","pc2_divergence","pc3_structure","total_amount","amount_to_circ_mv","avg_turnover_rate","median_turnover_rate","high_turnover_stock_ratio",
            "top_amount_share","up_ratio","down_ratio","up_down_spread","limit_up_ratio","limit_down_ratio","limit_net_ratio","max_limit_streak","limit_promotion_rate",
            "pct_above_ma20","median_distance_to_ma20","pct_distance_to_ma20_gt_5","pct_distance_to_ma20_lt_minus_5","small_large_ret","growth_value_ret",
            "margin_buy_sell_ratio","margin_buy_amount_ratio","margin_balance_change_5d","moneyflow_net_amount_ratio","moneyflow_large_net_ratio",
            "index_up_breadth_down","amount_up_limit_down","small_up_large_down","sentiment_up_return_down","is_false_boom","is_limit_collapse","is_leverage_warning","is_crowded",
            "data_quality_flag","model_version","updated_at");
    public static final String FACTOR_SOURCE_ENDPOINT="stk_factor";
    private static final NativeDailyProjection<MarketSentimentDailyRow> PROJECTION=new NativeDailyProjection<>(MarketSentimentDailyRow.class,COLUMNS);
    public static NativeDailyProjection<MarketSentimentDailyRow> projection(){return PROJECTION;}
    public static void requireTarget(String table){NativeDailyWindowRules.requireTarget(table,"market_sentiment_daily","java_d121_market_sentiment_daily");}
    public static boolean outside(MarketSentimentDailyRow row,LocalDate from,LocalDate to){return PROJECTION.outside(row,from,to);}
    public static MarketSentimentDailyRow row(Map<String,?> map){return PROJECTION.row(map);}
    public static Map<String,Object> values(MarketSentimentDailyRow row){return PROJECTION.values(row);}
    public static long micros(Instant time){return NativeDailyWindowRules.micros(time);}
    public static Instant fromMicros(long time){return NativeDailyWindowRules.fromMicros(time);}
    public static String quotedColumns(){return PROJECTION.quotedColumns();}
    public static String digest(List<MarketSentimentDailyRow> rows){return PROJECTION.digest(rows);}
}
