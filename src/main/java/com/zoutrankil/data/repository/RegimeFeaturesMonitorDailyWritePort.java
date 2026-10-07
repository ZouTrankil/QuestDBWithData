package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.RegimeFeaturesMonitorDailyTargetSnapshot;
import com.zoutrankil.data.domain.NativeDailyWindowSnapshot;

import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Typed facade over the shared daily-window staging port. */
public final class RegimeFeaturesMonitorDailyWritePort implements VerifiedBatchExecutor.Port<RegimeFeaturesMonitorDailyRow,Instant> {
    public static final List<String> COLUMNS=List.of("trade_date","month","hs300_ret_mtd","zz500_ret_mtd","all_a_ret_mtd","cs1000_ret_mtd","small_large_ret_mtd","growth_value_ret_mtd","avg_up_down_ratio_5d","avg_limit_up_count_5d","avg_limit_down_count_5d","avg_turnover_rate_5d","avg_pct_positive_ratio_5d","avg_pct_negative_ratio_5d","northbound_net_buy_mtd","margin_balance_change_mtd","all_a_pe_ttm_percentile_latest","all_a_pb_percentile_latest","erp_latest","data_quality_flag","updated_at");
    private static final NativeDailyWindowWritePort.Projection<RegimeFeaturesMonitorDailyRow> PROJECTION=new NativeDailyWindowWritePort.Projection<>(RegimeFeaturesMonitorDailyRow.class,COLUMNS);
    public static final VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow,Instant> CODEC=PROJECTION;
    private final NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> delegate;
    public RegimeFeaturesMonitorDailyWritePort(JdbcTemplate source,String table) {
        delegate=new NativeDailyWindowWritePort<>(source,table,"regime_features_monitor_daily","java_regime_features_monitor_daily",RegimeFeaturesMonitorDailyRow.class,COLUMNS);
    }
    public static void requireTarget(String table){NativeDailyWindowWritePort.requireTarget(table,"regime_features_monitor_daily","java_regime_features_monitor_daily");}
    public NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> nativePort(){return delegate;}
    public String table(){return delegate.table();}
    public String stage(){return delegate.stage();}
    public RegimeFeaturesMonitorDailyTargetSnapshot snapshot(String table){return typed(delegate.snapshot(table));}
    public RegimeFeaturesMonitorDailyTargetSnapshot formalSnapshot(){return typed(delegate.formalSnapshot());}
    public void requireSame(RegimeFeaturesMonitorDailyTargetSnapshot expected){delegate.requireSame(nativeSnapshot(expected));}
    public RegimeFeaturesMonitorDailyTargetSnapshot prepare(RegimeFeaturesMonitorDailyTargetSnapshot before,LocalDate from,LocalDate to)throws Exception{return typed(delegate.prepare(nativeSnapshot(before),from,to));}
    public static boolean outside(RegimeFeaturesMonitorDailyRow row,LocalDate from,LocalDate to){return PROJECTION.outside(row,from,to);}
    @Override public void preflight(){delegate.preflight();}
    @Override public void send(List<RegimeFeaturesMonitorDailyRow> rows)throws Exception{delegate.send(rows);}
    @Override public List<RegimeFeaturesMonitorDailyRow> readback(List<Instant> keys){return delegate.readback(keys);}
    @Override public boolean walSettled(){return delegate.walSettled();}
    @Override public boolean uncertainSenderStopped(){return delegate.uncertainSenderStopped();}
    public List<RegimeFeaturesMonitorDailyRow> readAll(String table){return delegate.readAll(table);}
    public static RegimeFeaturesMonitorDailyRow row(Map<String,?> map){return PROJECTION.row(map);}
    public static Map<String,Object> values(RegimeFeaturesMonitorDailyRow row){return PROJECTION.values(row);}
    public void requireSchema(String table){delegate.requireSchema(table);}
    public static long micros(Instant time){return NativeDailyWindowWritePort.micros(time);}
    public static Instant fromMicros(long time){return NativeDailyWindowWritePort.fromMicros(time);}
    public static String quotedColumns(){return PROJECTION.quotedColumns();}
    public static String digest(List<RegimeFeaturesMonitorDailyRow> rows){return PROJECTION.digest(rows);}
    private static RegimeFeaturesMonitorDailyTargetSnapshot typed(NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> snapshot) {
        return new RegimeFeaturesMonitorDailyTargetSnapshot(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
    private static NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> nativeSnapshot(RegimeFeaturesMonitorDailyTargetSnapshot snapshot) {
        return new NativeDailyWindowSnapshot<>(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
}