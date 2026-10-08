package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.MarketSentimentDailyTargetSnapshot;
import com.zoutrankil.data.domain.NativeDailyWindowSnapshot;

import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Typed facade over the shared daily-window staging port. */
public final class MarketSentimentDailyWritePort implements MarketSentimentDailyWriteSession {
    public static final List<String> COLUMNS=MarketSentimentDailyRows.COLUMNS;
    public static final VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> CODEC=MarketSentimentDailyWriteSession.CODEC;
    private final NativeDailyWindowWritePort<MarketSentimentDailyRow> delegate;
    public MarketSentimentDailyWritePort(JdbcTemplate source,String table) {
        delegate=new NativeDailyWindowWritePort<>(source,table,"market_sentiment_daily","java_d121_market_sentiment_daily",MarketSentimentDailyRow.class,COLUMNS);
    }
    public static void requireTarget(String table){MarketSentimentDailyRows.requireTarget(table);}
    public NativeDailyWindowSession<MarketSentimentDailyRow> nativePort(){return delegate;}
    public String table(){return delegate.table();}
    public String stage(){return delegate.stage();}
    public MarketSentimentDailyTargetSnapshot snapshot(String table){return typed(delegate.snapshot(table));}
    public MarketSentimentDailyTargetSnapshot formalSnapshot(){return typed(delegate.formalSnapshot());}
    public void requireSame(MarketSentimentDailyTargetSnapshot expected){delegate.requireSame(nativeSnapshot(expected));}
    public MarketSentimentDailyTargetSnapshot prepare(MarketSentimentDailyTargetSnapshot before,LocalDate from,LocalDate to)throws Exception{return typed(delegate.prepare(nativeSnapshot(before),from,to));}
    public static boolean outside(MarketSentimentDailyRow row,LocalDate from,LocalDate to){return MarketSentimentDailyRows.outside(row,from,to);}
    @Override public void preflight(){delegate.preflight();}
    @Override public void send(List<MarketSentimentDailyRow> rows)throws Exception{delegate.send(rows);}
    @Override public List<MarketSentimentDailyRow> readback(List<Instant> keys){return delegate.readback(keys);}
    @Override public boolean walSettled(){return delegate.walSettled();}
    @Override public boolean uncertainSenderStopped(){return delegate.uncertainSenderStopped();}
    public List<MarketSentimentDailyRow> readAll(String table){return delegate.readAll(table);}
    public static MarketSentimentDailyRow row(Map<String,?> map){return MarketSentimentDailyRows.row(map);}
    public static Map<String,Object> values(MarketSentimentDailyRow row){return MarketSentimentDailyRows.values(row);}
    public void requireSchema(String table){delegate.requireSchema(table);}
    public static long micros(Instant time){return MarketSentimentDailyRows.micros(time);}
    public static Instant fromMicros(long time){return MarketSentimentDailyRows.fromMicros(time);}
    public static String quotedColumns(){return MarketSentimentDailyRows.quotedColumns();}
    public static String digest(List<MarketSentimentDailyRow> rows){return MarketSentimentDailyRows.digest(rows);}
    private static MarketSentimentDailyTargetSnapshot typed(NativeDailyWindowSnapshot<MarketSentimentDailyRow> snapshot) {
        return new MarketSentimentDailyTargetSnapshot(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
    private static NativeDailyWindowSnapshot<MarketSentimentDailyRow> nativeSnapshot(MarketSentimentDailyTargetSnapshot snapshot) {
        return new NativeDailyWindowSnapshot<>(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
}
