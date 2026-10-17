package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailySourceData.*;
import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.policy.RegimeFeaturesMonitorDailyCalculation.*;
import static com.zoutrankil.data.domain.policy.MarketSentimentDailyCalculation.finite;

/** Bounded streaming reads and physical target construction for the native regime product. */
public final class RegimeFeaturesMonitorDailyStorage implements RegimeFeaturesMonitorDailySourceReadPort,RegimeFeaturesMonitorDailyTarget {
    private static final List<String> SOURCES=List.of("stk_factor","daily_basic","stk_limit","stk_suspend","stk_st_daily","margin_detail","moneyflow_hsgt","cn_bond_yield_curve","exchange_calendar");
    private final JdbcTemplate jdbc;
    public RegimeFeaturesMonitorDailyStorage(JdbcTemplate source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(120);jdbc.setFetchSize(2048);
    }
    public NativeDailyWindowWritePort<RegimeFeaturesMonitorDailyRow> writer(String table) {
        return new NativeDailyWindowWritePort<>(jdbc,table,"regime_features_monitor_daily","java_regime_features_monitor_daily",RegimeFeaturesMonitorDailyRow.class,COLUMNS);
    }


    public void readPanelMonth(LocalDate lower,LocalDate upper,Set<LocalDate> calendar,long[] panelRows,MessageDigest hash,BooleanSupplier cancelled,PanelDayConsumer complete) {
            LocalDate[] date={null};var stocks=new ArrayList<Stock>();var codes=new HashSet<String>();int[] basicCount={0},limitCount={0},count={0};
            String sql="SELECT cast(sf.trade_date AS long) AS trade_micros,sf.ts_code,sf.close,sf.pre_close,sf.amount,db.turnover_rate,db.total_mv,db.pb,db.pe_ttm,sl.up_limit,sl.down_limit,ss.is_suspended,st.is_st,db.ts_code AS basic_code,sl.ts_code AS limit_code"
                    +" FROM "+bounded("stk_factor","trade_date,ts_code,close,pre_close,amount","trade_date")+" sf"
                    +" LEFT JOIN "+bounded("daily_basic","trade_date,ts_code,turnover_rate,total_mv,pb,pe_ttm","trade_date")+" db ON sf.ts_code=db.ts_code AND sf.trade_date=db.trade_date"
                    +" LEFT JOIN "+bounded("stk_limit","trade_date,ts_code,up_limit,down_limit","trade_date")+" sl ON sf.ts_code=sl.ts_code AND sf.trade_date=sl.trade_date"
                    +" LEFT JOIN "+bounded("stk_suspend","timestamp,ts_code,is_suspended","timestamp")+" ss ON sf.ts_code=ss.ts_code AND sf.trade_date=ss.timestamp"
                    +" LEFT JOIN "+bounded("stk_st_daily","timestamp,ts_code,is_st","timestamp")+" st ON sf.ts_code=st.ts_code AND sf.trade_date=st.timestamp"
                    +" ORDER BY sf.trade_date,sf.ts_code LIMIT 200001";
            jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                if(++count[0]>200000)throw new IllegalStateException("Stock source month exceeds 200000-row budget");if(count[0]==1||(count[0]&1023)==0)check(cancelled);panelRows[0]++;
                LocalDate current=date(rs);if(!calendar.contains(current))throw new IllegalStateException("Stock panel contains non-SSE-trading date "+current);
                if(date[0]!=null&&!date[0].equals(current)){complete.accept(date[0],stocks,codes,basicCount[0],limitCount[0]);stocks.clear();codes.clear();basicCount[0]=limitCount[0]=0;}
                date[0]=current;String code=rs.getString("ts_code");if(code==null||!codes.add(code))throw new IllegalStateException("Duplicate stock panel business key on "+current);
                if(rs.getString("basic_code")!=null)basicCount[0]++;if(rs.getString("limit_code")!=null)limitCount[0]++;
                var stock=new Stock(code,number(rs,"close"),number(rs,"pre_close"),number(rs,"amount"),number(rs,"turnover_rate"),number(rs,"total_mv"),number(rs,"pb"),number(rs,"pe_ttm"),number(rs,"up_limit"),number(rs,"down_limit"));
                boolean suspended=flag(rs.getObject("is_suspended")),st=flag(rs.getObject("is_st"));hashLine(hash,current+"|"+stock+"|"+suspended+"|"+st);
                if(!suspended&&!st)stocks.add(stock);
            },micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper));
            if(date[0]!=null)complete.accept(date[0],stocks,codes,basicCount[0],limitCount[0]);
    }

    public void readValuations(LocalDate from,LocalDate upper,Set<LocalDate> calendar,Map<LocalDate,Valuation> result,long[] rawRows,MessageDigest hash,BooleanSupplier cancelled){
        LocalDate[] date={null};var codes=new HashSet<String>();var pe=new ArrayList<Double>();var pb=new ArrayList<Double>();int[] count={0};
        jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,ts_code,pe_ttm,pb FROM daily_basic WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT 200001",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    if(++count[0]>200000)throw new IllegalStateException("Valuation source month exceeds 200000-row budget");if(count[0]==1||(count[0]&1023)==0)check(cancelled);rawRows[0]++;
                    LocalDate current=date(rs);if(!calendar.contains(current))throw new IllegalStateException("Valuation contains a non-SSE-trading date "+current);
                    if(date[0]!=null&&!date[0].equals(current)){finishValuation(date[0],pe,pb,result);pe.clear();pb.clear();codes.clear();}date[0]=current;
                    String code=rs.getString("ts_code");if(code==null||!codes.add(code))throw new IllegalStateException("Duplicate daily_basic valuation key on "+current);
                    double p=number(rs,"pe_ttm"),b=number(rs,"pb");hashLine(hash,"valuation|"+current+"|"+code+"|"+p+"|"+b);
                    // The reference first keeps (pe>0 OR pb>0); a date with no
                    // such rows does not enter its rank/ffill valuation grid.
                    if(p>0||b>0){pe.add(p);pb.add(b);}
                },micros(from),micros(upper));
        if(date[0]!=null)finishValuation(date[0],pe,pb,result);
    }
    private static void finishValuation(LocalDate date,List<Double> pe,List<Double> pb,Map<LocalDate,Valuation> result){
        if(pe.isEmpty())return;var medians=positiveMedians(pe.stream().mapToDouble(Double::doubleValue).toArray(),pb.stream().mapToDouble(Double::doubleValue).toArray());
        if(result.put(date,medians)!=null)throw new IllegalStateException("Duplicate valuation aggregate date "+date);
    }
    public NavigableSet<LocalDate> calendar(LocalDate from,LocalDate to,MessageDigest hash){
        var all=new TreeSet<LocalDate>();var open=new TreeSet<LocalDate>();int[] rows={0};
        jdbc.query("SELECT cast(cal_date AS long) AS trade_micros,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{if(++rows[0]>400)throw new IllegalStateException("SSE calendar exceeds 400-date budget");var date=date(rs);
                    if(!all.add(date))throw new IllegalStateException("Duplicate SSE calendar key "+date);Object value=rs.getObject("is_open");
                    if(!(value instanceof Number n)||n.intValue()!=0&&n.intValue()!=1)throw new IllegalStateException("Invalid SSE is_open on "+date);
                    if(n.intValue()==1)open.add(date);hashLine(hash,"calendar|"+date+"|"+n.intValue());},micros(from),micros(to.plusDays(1)));
        for(var date=from;!date.isAfter(to);date=date.plusDays(1))if(!all.contains(date))throw new IllegalStateException("Missing authoritative calendar coverage on "+date);
        return open;
    }
    public SortedMap<LocalDate,Double> readNorthbound(LocalDate from,LocalDate to,MessageDigest hash){
        var rows=new TreeMap<LocalDate,Double>();jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,north_money FROM moneyflow_hsgt WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double value=number(rs,"north_money");if(rows.put(date,value)!=null)throw new IllegalStateException("Duplicate northbound date "+date);
                    if(rows.size()>400)throw new IllegalStateException("Northbound exceeds 400-date budget");hashLine(hash,"northbound|"+date+"|"+value);},micros(from),micros(to.plusDays(1)));return rows;
    }
    public Margins readMargins(LocalDate from,LocalDate to,MessageDigest hash){
        var duplicates=jdbc.queryForList("SELECT * FROM (SELECT trade_date,ts_code,count() AS n FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,ts_code) WHERE n>1 LIMIT 1",micros(from),micros(to.plusDays(1)));
        if(!duplicates.isEmpty())throw new IllegalStateException("Duplicate margin_detail business key");
        var balances=new TreeMap<LocalDate,Double>();var exchanges=new TreeMap<LocalDate,Map<String,Long>>();
        jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(rzye) balance,count() source_rows,sum(CASE WHEN ts_code LIKE '%.SH' THEN 1 ELSE 0 END) exchange_sh,sum(CASE WHEN ts_code LIKE '%.SZ' THEN 1 ELSE 0 END) exchange_sz,sum(CASE WHEN ts_code LIKE '%.BJ' THEN 1 ELSE 0 END) exchange_bj FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double balance=number(rs,"balance");long sh=rs.getLong("exchange_sh"),sz=rs.getLong("exchange_sz"),bj=rs.getLong("exchange_bj");
                    if(sh+sz+bj!=rs.getLong("source_rows"))throw new IllegalStateException("Unrecognized margin exchange code on "+date);
                    if(balances.put(date,balance)!=null)throw new IllegalStateException("Duplicate margin aggregate date "+date);if(balances.size()>400)throw new IllegalStateException("Margin exceeds 400-date budget");
                    exchanges.put(date,Map.of("SH",sh,"SZ",sz,"BJ",bj));hashLine(hash,"margin|"+date+"|"+balance+"|"+sh+"|"+sz+"|"+bj);},micros(from),micros(to.plusDays(1)));
        return new Margins(balances,exchanges);
    }
    public Map<LocalDate,Double> readGov(LocalDate from,LocalDate to,MessageDigest hash){
        var rows=new TreeMap<LocalDate,Double>();jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,yield_value FROM cn_bond_yield_curve WHERE curve_code='gov' AND tenor='10Y' AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT 401",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{var date=date(rs);double yield=number(rs,"yield_value");if(rows.put(date,yield)!=null)throw new IllegalStateException("Duplicate gov/10Y source key on "+date);
                    if(rows.size()>400)throw new IllegalStateException("Gov curve exceeds 400-date budget");hashLine(hash,"gov10Y|"+date+"|"+yield);},micros(from),micros(to.plusDays(1)));return rows;
    }
    public String sourcePin()throws Exception{
        var pins=new ArrayList<Object>();for(String source:SOURCES){var metadata=jdbc.queryForList("SELECT id,directoryName,walEnabled,table_txn,table_row_count FROM tables() WHERE table_name=?",source);
            if(metadata.size()!=1)throw new IllegalStateException("Required existing source table missing: "+source);var item=new LinkedHashMap<String,Object>(metadata.getFirst());item.put("table",source);
            if(Boolean.TRUE.equals(item.get("walEnabled"))){var frontier=jdbc.queryForList("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name=?",source);
                if(frontier.size()!=1||!QuestDbWriteChecks.walSettled(jdbc,source))throw new IllegalStateException("Source WAL is unsettled: "+source);item.put("wal",frontier.getFirst());}
            pins.add(item);}
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(pins)));
    }
    private static String bounded(String table,String columns,String timestamp){return "(SELECT "+columns+" FROM "+table+" WHERE "+timestamp+">=cast(? AS TIMESTAMP) AND "+timestamp+"<cast(? AS TIMESTAMP))";}
    private static long micros(LocalDate date){return MarketSentimentDailyWritePort.micros(date.atStartOfDay().toInstant(ZoneOffset.UTC));}
    private static LocalDate date(ResultSet rs)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("Explicit source epoch microseconds required");
        var utc=MarketSentimentDailyWritePort.fromMicros(n.longValue()).atOffset(ZoneOffset.UTC);if(!utc.toLocalTime().equals(LocalTime.MIDNIGHT))throw new SQLException("Source business date is not UTC midnight");return utc.toLocalDate();}
    private static double number(ResultSet rs,String field)throws SQLException{double value=rs.getDouble(field);if(rs.wasNull()||Double.isNaN(value))return Double.NaN;if(!finite(value))throw new SQLException("Nonfinite source field "+field);return value;}
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.doubleValue()==1;}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Native regime-monitor calculation cancelled");}
    private static void hashLine(MessageDigest hash,String line){hash.update((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(value);}
}
