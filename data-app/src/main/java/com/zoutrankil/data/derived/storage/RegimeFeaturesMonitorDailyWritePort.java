package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.RegimeFeaturesMonitorDailyTargetSnapshot;
import com.zoutrankil.data.domain.NativeDailyWindowSnapshot;

import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Typed facade over the shared daily-window staging port. */
public final class RegimeFeaturesMonitorDailyWritePort implements RegimeFeaturesMonitorDailyWriteSession {
    public static final List<String> COLUMNS=RegimeFeaturesMonitorDailyRows.COLUMNS;
    public static final VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow,Instant> CODEC=RegimeFeaturesMonitorDailyWriteSession.CODEC;
    private final NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> delegate;
    public RegimeFeaturesMonitorDailyWritePort(JdbcTemplate source,String table) {
        delegate=new NativeDailyWindowWritePort<>(source,table,"regime_features_monitor_daily","java_regime_features_monitor_daily",RegimeFeaturesMonitorDailyRow.class,COLUMNS);
    }
    public static void requireTarget(String table){RegimeFeaturesMonitorDailyRows.requireTarget(table);}
    public NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> nativePort(){return delegate;}
    public String table(){return delegate.table();}
    public String stage(){return delegate.stage();}
    public RegimeFeaturesMonitorDailyTargetSnapshot snapshot(String table){return typed(delegate.snapshot(table));}
    public RegimeFeaturesMonitorDailyTargetSnapshot formalSnapshot(){return typed(delegate.formalSnapshot());}
    public void requireSame(RegimeFeaturesMonitorDailyTargetSnapshot expected){delegate.requireSame(nativeSnapshot(expected));}
    public RegimeFeaturesMonitorDailyTargetSnapshot prepare(RegimeFeaturesMonitorDailyTargetSnapshot before,LocalDate from,LocalDate to)throws Exception{return typed(delegate.prepare(nativeSnapshot(before),from,to));}
    public static boolean outside(RegimeFeaturesMonitorDailyRow row,LocalDate from,LocalDate to){return RegimeFeaturesMonitorDailyRows.outside(row,from,to);}
    @Override public void preflight(){delegate.preflight();}
    @Override public void send(List<RegimeFeaturesMonitorDailyRow> rows)throws Exception{delegate.send(rows);}
    @Override public List<RegimeFeaturesMonitorDailyRow> readback(List<Instant> keys){return delegate.readback(keys);}
    @Override public boolean walSettled(){return delegate.walSettled();}
    @Override public boolean uncertainSenderStopped(){return delegate.uncertainSenderStopped();}
    public List<RegimeFeaturesMonitorDailyRow> readAll(String table){return delegate.readAll(table);}
    public static RegimeFeaturesMonitorDailyRow row(Map<String,?> map){return RegimeFeaturesMonitorDailyRows.row(map);}
    public static Map<String,Object> values(RegimeFeaturesMonitorDailyRow row){return RegimeFeaturesMonitorDailyRows.values(row);}
    public void requireSchema(String table){delegate.requireSchema(table);}
    public static long micros(Instant time){return RegimeFeaturesMonitorDailyRows.micros(time);}
    public static Instant fromMicros(long time){return RegimeFeaturesMonitorDailyRows.fromMicros(time);}
    public static String quotedColumns(){return RegimeFeaturesMonitorDailyRows.quotedColumns();}
    public static String digest(List<RegimeFeaturesMonitorDailyRow> rows){return RegimeFeaturesMonitorDailyRows.digest(rows);}
    private static RegimeFeaturesMonitorDailyTargetSnapshot typed(NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> snapshot) {
        return new RegimeFeaturesMonitorDailyTargetSnapshot(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
    private static NativeDailyWindowSnapshot<RegimeFeaturesMonitorDailyRow> nativeSnapshot(RegimeFeaturesMonitorDailyTargetSnapshot snapshot) {
        return new NativeDailyWindowSnapshot<>(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
}
