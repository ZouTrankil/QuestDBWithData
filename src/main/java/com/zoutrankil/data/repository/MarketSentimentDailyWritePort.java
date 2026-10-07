package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Typed facade over the shared daily-window staging port. */
public final class MarketSentimentDailyWritePort implements VerifiedBatchExecutor.Port<MarketSentimentDailyRow,Instant> {
    public static final List<String> COLUMNS=List.of("trade_date","month","sentiment_score","sentiment_score_core","sentiment_score_enhanced","sentiment_state",
            "heat_score","breadth_score","limit_score","profit_effect_score","leverage_score","moneyflow_score","structure_score","divergence_score",
            "pc1_heat","pc2_divergence","pc3_structure","total_amount","amount_to_circ_mv","avg_turnover_rate","median_turnover_rate","high_turnover_stock_ratio",
            "top_amount_share","up_ratio","down_ratio","up_down_spread","limit_up_ratio","limit_down_ratio","limit_net_ratio","max_limit_streak","limit_promotion_rate",
            "pct_above_ma20","median_distance_to_ma20","pct_distance_to_ma20_gt_5","pct_distance_to_ma20_lt_minus_5","small_large_ret","growth_value_ret",
            "margin_buy_sell_ratio","margin_buy_amount_ratio","margin_balance_change_5d","moneyflow_net_amount_ratio","moneyflow_large_net_ratio",
            "index_up_breadth_down","amount_up_limit_down","small_up_large_down","sentiment_up_return_down","is_false_boom","is_limit_collapse","is_leverage_warning","is_crowded",
            "data_quality_flag","model_version","updated_at");
    private static final NativeDailyWindowWritePort.Projection<MarketSentimentDailyRow> PROJECTION=new NativeDailyWindowWritePort.Projection<>(MarketSentimentDailyRow.class,COLUMNS);
    public static final VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> CODEC=PROJECTION;
    public record Snapshot(String targetId,long tableId,String directory,boolean wal,List<MarketSentimentDailyRow> rows,String fingerprint) {
        public Snapshot { rows=List.copyOf(rows); }
    }
    private final NativeDailyWindowWritePort<MarketSentimentDailyRow> delegate;
    public MarketSentimentDailyWritePort(JdbcTemplate source,String table) {
        delegate=new NativeDailyWindowWritePort<>(source,table,"market_sentiment_daily","java_d121_market_sentiment_daily",MarketSentimentDailyRow.class,COLUMNS);
    }
    public static void requireTarget(String table){NativeDailyWindowWritePort.requireTarget(table,"market_sentiment_daily","java_d121_market_sentiment_daily");}
    public NativeDailyWindowWritePort<MarketSentimentDailyRow> nativePort(){return delegate;}
    public String table(){return delegate.table();}
    public String stage(){return delegate.stage();}
    public Snapshot snapshot(String table){return typed(delegate.snapshot(table));}
    public Snapshot formalSnapshot(){return typed(delegate.formalSnapshot());}
    public void requireSame(Snapshot expected){delegate.requireSame(nativeSnapshot(expected));}
    public Snapshot prepare(Snapshot before,LocalDate from,LocalDate to)throws Exception{return typed(delegate.prepare(nativeSnapshot(before),from,to));}
    public static boolean outside(MarketSentimentDailyRow row,LocalDate from,LocalDate to){return PROJECTION.outside(row,from,to);}
    @Override public void preflight(){delegate.preflight();}
    @Override public void send(List<MarketSentimentDailyRow> rows)throws Exception{delegate.send(rows);}
    @Override public List<MarketSentimentDailyRow> readback(List<Instant> keys){return delegate.readback(keys);}
    @Override public boolean walSettled(){return delegate.walSettled();}
    @Override public boolean uncertainSenderStopped(){return delegate.uncertainSenderStopped();}
    public List<MarketSentimentDailyRow> readAll(String table){return delegate.readAll(table);}
    public static MarketSentimentDailyRow row(Map<String,?> map){return PROJECTION.row(map);}
    public static Map<String,Object> values(MarketSentimentDailyRow row){return PROJECTION.values(row);}
    public void requireSchema(String table){delegate.requireSchema(table);}
    public static long micros(Instant time){return NativeDailyWindowWritePort.micros(time);}
    public static Instant fromMicros(long time){return NativeDailyWindowWritePort.fromMicros(time);}
    public static String quotedColumns(){return PROJECTION.quotedColumns();}
    public static String digest(List<MarketSentimentDailyRow> rows){return PROJECTION.digest(rows);}
    private static Snapshot typed(NativeDailyWindowWritePort.Snapshot<MarketSentimentDailyRow> snapshot) {
        return new Snapshot(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
    private static NativeDailyWindowWritePort.Snapshot<MarketSentimentDailyRow> nativeSnapshot(Snapshot snapshot) {
        return new NativeDailyWindowWritePort.Snapshot<>(snapshot.targetId(),snapshot.tableId(),snapshot.directory(),snapshot.wal(),snapshot.rows(),snapshot.fingerprint());
    }
}