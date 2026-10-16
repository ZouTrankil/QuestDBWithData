package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Complete bounded source reads, stable schema/WAL identities and trading-calendar evidence. */
public final class QuestDbStrategyInputs {
    public record Snapshot(LocalDate start,LocalDate end,List<LocalDate> signalDates,
            Map<String,List<Map<String,Object>>> tables,Map<String,String> sourceVersions,
            Map<String,Object> proof) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> TABLES=List.of("mv_backtest_daily","daily_basic","stock_detail_info","l2_daily_features","etf_basic","etf_factor","etf_daily","etf_portfolio","market_sentiment_daily","regime_features_monitor_daily","exchange_calendar");
    private static final List<String> L2_SCORE_COLUMNS=List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q2_accumulation_ratio","active_buy_ratio","gmm_main_force_net_inflow","obi_top5_std","orderbook_replenish_speed","depth_slope","continuous_auction_ratio","close_auction_net_inflow","open_close_vol_ratio","q3_count","q4_count","q4_distribution","q4_distribution_ratio","active_sell_ratio","large_active_sell_amount","depth_top5_mean","ofi_persistence","ofi_std","mfi_escort","mean_obi_top5","main_funds_buy_amount","daily_amount","amount","turnover_rate");
    private final JdbcTemplate jdbc;
    private final long rowBudget;
    private final ThreadLocal<ReadBudget> activeRead=new ThreadLocal<>();
    private static final class ReadBudget {
        final long maximum;long rows;
        ReadBudget(long maximum){this.maximum=maximum;}
        void accept(){if(++rows>maximum)throw new IllegalStateException("Aggregate strategy source row budget exceeded before materialization");}
    }
    public QuestDbStrategyInputs(JdbcTemplate jdbc,long rowBudget) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setFetchSize(2048);this.jdbc.setQueryTimeout(120);
        if(rowBudget<10_000||rowBudget>5_000_000)throw new IllegalArgumentException("Strategy source budget must be 10000..5000000");this.rowBudget=rowBudget;
    }
    public Snapshot read(LocalDate start,LocalDate end,int warmupDays)throws Exception {
        if(activeRead.get()!=null)throw new IllegalStateException("Nested source snapshot read is forbidden");
        activeRead.set(new ReadBudget(Math.multiplyExact(rowBudget,3)));
        try{return readSnapshot(start,end,warmupDays);}finally{activeRead.remove();}
    }
    private Snapshot readSnapshot(LocalDate start,LocalDate end,int warmupDays)throws Exception {
        if(start==null||end==null||end.isBefore(start)||end.toEpochDay()-start.toEpochDay()>730||warmupDays<460||warmupDays>730)throw new IllegalArgumentException("Bounded signal span and >=460 calendar-day warmup required");
        LocalDate warmup=start.minusDays(warmupDays);
        var before=clocks();var backtestPort=new BacktestDailyMaterializedViewPort(jdbc);Object backtest=backtestPort.sourceState();
        List<LocalDate> dates=calendar(start,end),warmupGrid=calendar(warmup,end);
        if(dates.isEmpty()||!dates.getLast().equals(end))throw new IllegalStateException("Score target must be an open calendar date");
        var data=new LinkedHashMap<String,List<Map<String,Object>>>();
        calendar(start,end.plusDays(14));
        data.put("calendar",readSql("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date",new Object[]{micros(start),micros(end.plusDays(15))},"calendar",1500));
        data.put("market_context",readRows("SELECT * FROM regime_features_monitor_daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",start,end,"market_context"));
        data.put("stock_daily",readRows("""
            SELECT b.trade_date,b.ts_code,b.close AS price,b.open AS open_price,b.close AS raw_close,
                   b.amount,b.up_limit,b.down_limit,b.is_suspended,b.is_st,
                   d.pb,d.turnover_rate,d.total_mv,d.ts_code AS basic_present
            FROM mv_backtest_daily b LEFT JOIN daily_basic d ON b.trade_date=d.trade_date AND b.ts_code=d.ts_code
            WHERE b.trade_date>=cast(? AS TIMESTAMP) AND b.trade_date<cast(? AS TIMESTAMP)
            ORDER BY b.trade_date,b.ts_code
            """,warmup,end,"stock_daily"));
        data.put("l2_raw",readRows("SELECT "+l2Projection(before)+" FROM l2_daily_features WHERE ts>=cast(? AS TIMESTAMP) AND ts<cast(? AS TIMESTAMP) ORDER BY ts,symbol",start,end,"l2_raw"));
        data.put("stock_metadata",readStatic("SELECT ts_code,name,industry,market FROM stock_detail_info ORDER BY ts_code","stock_metadata",10_000));
        data.put("etf_reference",readStatic("SELECT * FROM etf_basic ORDER BY ts_code","etf_reference",10_000));
        data.put("etf_factor",readRows("SELECT * FROM etf_factor WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code",warmup,end,"etf_factor"));
        data.put("etf_daily",readRows("SELECT * FROM etf_daily WHERE timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP) ORDER BY timestamp,ts_code",warmup.minusDays(5),end,"etf_daily"));
        // Keep the last disclosure before the signal span and every subsequent announcement.
        // Comparing ann_date <= signalDate is deferred to the exact PIT scorer.
        data.put("etf_portfolio",readSql("""
            SELECT * FROM (SELECT p.* FROM etf_portfolio p
            JOIN (SELECT ts_code,max(ann_date) AS a FROM etf_portfolio WHERE ann_date<cast(? AS TIMESTAMP) GROUP BY ts_code) prior
            ON p.ts_code=prior.ts_code AND p.ann_date=prior.a
            UNION ALL
            SELECT * FROM etf_portfolio WHERE ann_date>=cast(? AS TIMESTAMP) AND ann_date<cast(? AS TIMESTAMP))
            ORDER BY ts_code,ann_date,end_date,symbol
            """,new Object[]{micros(start),micros(start),micros(end.plusDays(1))},"etf_portfolio",rowBudget));
        data.put("sentiment",readRows("SELECT * FROM market_sentiment_daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",start,end,"sentiment"));
        requireGrid(data.get("stock_daily"),"trade_date",warmupGrid,"stock daily warmup");
        requireGrid(data.get("l2_raw"),"ts",dates,"L2 daily features");
        requireGrid(data.get("etf_factor"),"trade_date",warmupGrid,"ETF factor warmup");
        requireGrid(data.get("etf_daily"),"timestamp",warmupGrid,"ETF daily warmup",warmup);
        requireGrid(data.get("sentiment"),"trade_date",dates,"market sentiment");
        requireGrid(data.get("market_context"),"trade_date",dates,"market regime context");
        unique(data.get("stock_daily"),List.of("trade_date","ts_code"),"stock_daily");
        unique(data.get("l2_raw"),List.of("ts","symbol"),"l2_raw");
        unique(data.get("etf_factor"),List.of("trade_date","ts_code"),"etf_factor");
        unique(data.get("etf_daily"),List.of("timestamp","ts_code"),"etf_daily");
        unique(data.get("etf_portfolio"),List.of("ann_date","ts_code","end_date","symbol"),"etf_portfolio");
        unique(data.get("sentiment"),List.of("trade_date"),"sentiment");
        unique(data.get("market_context"),List.of("trade_date"),"market_context");
        for(var row:data.get("stock_daily")) {
            if(row.get("basic_present")==null)throw new IllegalStateException("daily_basic lacks a stock business key: "+row.get("ts_code")+" "+row.get("trade_date"));
            for(String field:List.of("price","open_price","amount","up_limit","down_limit"))if(!(row.get(field) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Stock critical value missing: "+field);
        }
        if(!before.equals(clocks())||!backtest.equals(backtestPort.sourceState()))throw new IllegalStateException("Strategy sources changed during the complete read");
        var versions=new LinkedHashMap<String,String>();
        var proofs=new LinkedHashMap<String,Object>();proofs.put("producer","java.main_strategy.inputs");proofs.put("start",start.toString());proofs.put("end",end.toString());proofs.put("warmupStart",warmup.toString());proofs.put("signalDates",dates.stream().map(LocalDate::toString).toList());proofs.put("sourceClocks",before);proofs.put("backtestState",backtest);
        var rows=new LinkedHashMap<String,Object>();
        for(var e:data.entrySet()){String hash=hashRows(e.getValue());versions.put(e.getKey(),hash);rows.put(e.getKey(),Map.of("rows",e.getValue().size(),"sha256",hash));}
        proofs.put("sources",rows);proofs.put("sourceComplete",true);
        return new Snapshot(start,end,List.copyOf(dates),Collections.unmodifiableMap(data),Map.copyOf(versions),Collections.unmodifiableMap(proofs));
    }
    public Map<String,Object> clocks()throws Exception {
        var result=new TreeMap<String,Object>();
        for(String table:TABLES) {
            var rows=jdbc.queryForList("SELECT t.id,t.directoryName,t.walEnabled,t.table_txn,t.table_suspended,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t LEFT JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",table);
            if(rows.size()!=1)throw new IllegalStateException("Exact strategy table absent: "+table);
            var r=rows.getFirst();verifyClock(table,r);
            var card=new TreeMap<String,Object>(r);card.put("schema",jdbc.queryForList("SELECT \"column\",\"type\" FROM table_columns('"+table+"') LIMIT 257"));result.put(table,card);
        }return result;
    }
    static void verifyClock(String table,Map<String,Object> r) {
        if(!(r.get("id") instanceof Number n)||n.longValue()<0||Objects.toString(r.get("directoryName"),"").isBlank())throw new IllegalStateException("Physical strategy source identity absent: "+table);
        if(Boolean.TRUE.equals(r.get("walEnabled"))){
            if(!Boolean.FALSE.equals(r.get("suspended"))||!(r.get("writerTxn") instanceof Number a)||!(r.get("sequencerTxn") instanceof Number b)||a.longValue()!=b.longValue()||!(r.get("bufferedTxnSize") instanceof Number pending)||pending.longValue()!=0)throw new IllegalStateException("Strategy source WAL unsettled: "+table);
        }else if(!Boolean.FALSE.equals(r.get("walEnabled"))||!(r.get("table_txn") instanceof Number txn)||txn.longValue()<0||Boolean.TRUE.equals(r.get("table_suspended"))||r.get("writerTxn")!=null||r.get("sequencerTxn")!=null)throw new IllegalStateException("Non-WAL source lacks a committed physical transaction: "+table);
    }
    private List<LocalDate> calendar(LocalDate from,LocalDate to)throws Exception {
        long days=to.toEpochDay()-from.toEpochDay()+1;if(days<1||days>1500)throw new IllegalArgumentException("Bounded natural day calendar required");
        var raw=readSql("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date",new Object[]{micros(from),micros(to.plusDays(1))},"calendar",1500);
        if(raw.size()!=days)throw new IllegalStateException("Calendar has missing natural days: "+from+".."+to);
        var result=new ArrayList<LocalDate>();LocalDate current=from;
        for(var row:raw){LocalDate d=date(row.get("cal_date"));if(!d.equals(current))throw new IllegalStateException("Calendar dates duplicate or incomplete");Object open=row.get("is_open");if(!(open instanceof Number n)||n.intValue()<0||n.intValue()>1)throw new IllegalStateException("Typed open flag required");if(n.intValue()==1)result.add(d);current=current.plusDays(1);}return result;
    }
    private List<Map<String,Object>> readRows(String sql,LocalDate from,LocalDate to,String name)throws Exception{return readSql(sql,new Object[]{micros(from),micros(to.plusDays(1))},name,rowBudget);}
    private List<Map<String,Object>> readStatic(String sql,String name,long budget)throws Exception{return readSql(sql,new Object[0],name,budget);}
    private List<Map<String,Object>> readSql(String sql,Object[] params,String name,long budget)throws Exception {
        var rows=new ArrayList<Map<String,Object>>();jdbc.query(sql+" LIMIT "+(budget+1),(RowCallbackHandler)rs->{
            if(rows.size()>=budget)throw new IllegalStateException("Complete source exceeds row budget: "+name);
            ReadBudget aggregate=activeRead.get();if(aggregate!=null)aggregate.accept();
            var row=new LinkedHashMap<String,Object>();ResultSetMetaData metadata=rs.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) {String column=metadata.getColumnLabel(i);Object value=rs.getObject(i);if(value instanceof Timestamp ts){var instant=ts.toInstant();if(Set.of("trade_date","ts","timestamp","ann_date","end_date","cal_date","pretrade_date").contains(column)){if(!instant.atZone(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))throw new IllegalStateException("Business date must be UTC midnight: "+name+"."+column);value=instant.atZone(ZoneOffset.UTC).toLocalDate();}else value=instant.toString();}else if(value instanceof java.sql.Date d)value=d.toLocalDate();else if(value instanceof Double d&&Double.isNaN(d))value=null;else if(value instanceof Float f&&Float.isNaN(f))value=null;else if(value instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Infinite source number: "+name);row.put(column,value);}
            rows.add(Collections.unmodifiableMap(row));
        },params);return List.copyOf(rows);
    }
    public Object backtestState(){return new BacktestDailyMaterializedViewPort(jdbc).sourceState();}
    static String l2Projection(Map<String,Object> clocks) {
        if(!(clocks.get("l2_daily_features") instanceof Map<?,?> card)||!(card.get("schema") instanceof List<?> schema))throw new IllegalStateException("Pinned L2 schema required");
        var names=new HashSet<String>();for(Object column:schema)if(column instanceof Map<?,?> row)names.add(Objects.toString(row.get("column"),""));
        if(!names.containsAll(List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q3_count","q4_count","main_funds_buy_amount")))throw new IllegalStateException("Required formal L2 scoring schema is missing");
        return String.join(",",L2_SCORE_COLUMNS.stream().filter(names::contains).toList());
    }
    private static void requireGrid(List<Map<String,Object>> rows,String column,List<LocalDate> expected,String name)throws Exception {requireGrid(rows,column,expected,name,expected.getFirst());}
    private static void requireGrid(List<Map<String,Object>> rows,String column,List<LocalDate> expected,String name,LocalDate lower)throws Exception {
        var actual=new TreeSet<LocalDate>();for(var row:rows){LocalDate d=date(row.get(column));if(!d.isBefore(lower))actual.add(d);}
        if(!actual.equals(new TreeSet<>(expected)))throw new IllegalStateException("Source open-day grid incomplete: "+name+"; expected "+expected.size()+", observed "+actual.size());
    }
    private static void unique(List<Map<String,Object>> rows,List<String> keys,String name) {var found=new HashSet<List<Object>>();for(var row:rows){var key=new ArrayList<Object>();for(String k:keys){Object v=row.get(k);if(v==null)throw new IllegalStateException("Null source key: "+name+"."+k);key.add(v);}if(!found.add(key))throw new IllegalStateException("Duplicate source key: "+name+" "+key);}}
    private static String hashRows(List<Map<String,Object>> rows)throws Exception {var md=MessageDigest.getInstance("SHA-256");for(var row:rows){var values=new TreeMap<String,Object>();row.forEach((k,v)->values.put(k,v instanceof LocalDate d?d.toString():v));byte[] b=JSON.writeValueAsBytes(values);md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());md.update(b);}return HexFormat.of().formatHex(md.digest());}
    public static LocalDate date(Object value) {if(value instanceof LocalDate d)return d;if(value instanceof Timestamp ts)return ts.toInstant().atZone(ZoneOffset.UTC).toLocalDate();if(value==null)throw new IllegalStateException("Typed source date is null");return LocalDate.parse(value.toString().substring(0,10));}
    private static long micros(LocalDate day){return Math.multiplyExact(day.toEpochDay(),86_400_000_000L);}
}
