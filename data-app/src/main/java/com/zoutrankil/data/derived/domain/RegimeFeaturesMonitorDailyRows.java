package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;

public final class RegimeFeaturesMonitorDailyRows {
    private RegimeFeaturesMonitorDailyRows() {}
    public static final List<String> COLUMNS=List.of("trade_date","month","hs300_ret_mtd","zz500_ret_mtd","all_a_ret_mtd","cs1000_ret_mtd","small_large_ret_mtd","growth_value_ret_mtd","avg_up_down_ratio_5d","avg_limit_up_count_5d","avg_limit_down_count_5d","avg_turnover_rate_5d","avg_pct_positive_ratio_5d","avg_pct_negative_ratio_5d","northbound_net_buy_mtd","margin_balance_change_mtd","all_a_pe_ttm_percentile_latest","all_a_pb_percentile_latest","erp_latest","data_quality_flag","updated_at");
    private static final NativeDailyProjection<RegimeFeaturesMonitorDailyRow> PROJECTION=new NativeDailyProjection<>(RegimeFeaturesMonitorDailyRow.class,COLUMNS);
    public static NativeDailyProjection<RegimeFeaturesMonitorDailyRow> projection(){return PROJECTION;}
    public static void requireTarget(String table){NativeDailyWindowRules.requireTarget(table,"regime_features_monitor_daily","java_regime_features_monitor_daily");}
    public static boolean outside(RegimeFeaturesMonitorDailyRow row,LocalDate from,LocalDate to){return PROJECTION.outside(row,from,to);}
    public static RegimeFeaturesMonitorDailyRow row(Map<String,?> map){return PROJECTION.row(map);}
    public static Map<String,Object> values(RegimeFeaturesMonitorDailyRow row){return PROJECTION.values(row);}
    public static long micros(Instant time){return NativeDailyWindowRules.micros(time);}
    public static Instant fromMicros(long time){return NativeDailyWindowRules.fromMicros(time);}
    public static String quotedColumns(){return PROJECTION.quotedColumns();}
    public static String digest(List<RegimeFeaturesMonitorDailyRow> rows){return PROJECTION.digest(rows);}
}
