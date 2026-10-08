package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.domain.MarketSentimentDailySourceData.*;
import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.JobDefinitionJson;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Physical reads and publication table operations for the native sentiment owner. */
public final class MarketSentimentDailyStorage implements MarketSentimentDailySourceReadPort,MarketSentimentDailyTarget {



    private final JdbcTemplate jdbc;
    private final String table;
    public MarketSentimentDailyStorage(JdbcTemplate jdbc,String table) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(120);this.jdbc.setFetchSize(2048);
        this.table=table;
    }
    public String table(){return table;}
    public void requireTarget(){MarketSentimentDailyWritePort.requireTarget(table);}
    public MarketSentimentDailyWritePort newWriter(){return new MarketSentimentDailyWritePort(jdbc,table);}
    public int readPanelMonth(LocalDate lower,LocalDate upper,Runnable checkCancellation,PanelConsumer consumer) {
        LocalDate[] date={null};var rawCodes=new HashSet<String>();int[] basicCount={0},limitCount={0},batchRows={0};
        String sql="SELECT cast(sf.trade_date AS long) AS trade_micros,sf.ts_code,sf.close,sf.pre_close,sf.amount,db.turnover_rate,db.circ_mv,db.total_mv,db.pb,sl.up_limit,sl.down_limit,ss.is_suspended,st.is_st,db.ts_code AS basic_code,sl.ts_code AS limit_code"
                +" FROM "+bounded("stk_factor","trade_date,ts_code,close,pre_close,amount","trade_date")+" sf"
                +" LEFT JOIN "+bounded("daily_basic","trade_date,ts_code,turnover_rate,circ_mv,total_mv,pb","trade_date")+" db ON sf.ts_code=db.ts_code AND sf.trade_date=db.trade_date"
                +" LEFT JOIN "+bounded("stk_limit","trade_date,ts_code,up_limit,down_limit","trade_date")+" sl ON sf.ts_code=sl.ts_code AND sf.trade_date=sl.trade_date"
                +" LEFT JOIN "+bounded("stk_suspend","timestamp,ts_code,is_suspended","timestamp")+" ss ON sf.ts_code=ss.ts_code AND sf.trade_date=ss.timestamp"
                +" LEFT JOIN "+bounded("stk_st_daily","timestamp,ts_code,is_st","timestamp")+" st ON sf.ts_code=st.ts_code AND sf.trade_date=st.timestamp"
                +" ORDER BY sf.trade_date,sf.ts_code LIMIT 200001";
        jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            if(++batchRows[0]>200000)throw new IllegalStateException("Historical month exceeds 200000-row budget");
            // The shared cancellation supplier consults SQLite; keep it outside the per-row hot path.
            if(batchRows[0]==1 || (batchRows[0]&1023)==0)checkCancellation.run();
            LocalDate current=date(rs);
            if(date[0]!=null&&!date[0].equals(current)){consumer.completeDay(date[0],rawCodes,basicCount[0],limitCount[0]);rawCodes.clear();basicCount[0]=limitCount[0]=0;}
            date[0]=current;String code=rs.getString("ts_code");if(code==null||!rawCodes.add(code))throw new IllegalStateException("Duplicate stock panel business key on "+current);
            if(rs.getString("basic_code")!=null)basicCount[0]++;if(rs.getString("limit_code")!=null)limitCount[0]++;
            double close=number(rs,"close"),previous=number(rs,"pre_close"),amount=number(rs,"amount"),turnover=number(rs,"turnover_rate"),circ=number(rs,"circ_mv"),mv=number(rs,"total_mv"),pb=number(rs,"pb"),up=number(rs,"up_limit"),down=number(rs,"down_limit");
            boolean suspended=flag(rs.getObject("is_suspended")),st=flag(rs.getObject("is_st"));
            consumer.accept(new PanelRow(current,code,close,previous,amount,turnover,circ,mv,pb,up,down,suspended,st));
        },micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper),micros(lower),micros(upper));
        if(date[0]!=null)consumer.completeDay(date[0],rawCodes,basicCount[0],limitCount[0]);
        return batchRows[0];
    }
    public Map<LocalDate,Set<String>> expectedDates(LocalDate from,LocalDate to){
        var result=new TreeMap<LocalDate,Set<String>>();int[] count={0};jdbc.query("SELECT cast(trade_date AS long) AS trade_micros,ts_code FROM daily WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT 2200001",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{if(++count[0]>2200000)throw new IllegalStateException("Expected daily universe exceeds bounded row budget");var date=date(rs);
                    if(!result.computeIfAbsent(date,d->new HashSet<>()).add(rs.getString("ts_code")))throw new IllegalStateException("Duplicate daily source key");},micros(from),micros(to.plusDays(1)));
        if(result.isEmpty())throw new IllegalStateException("No authoritative daily trading dates in requested window");return result;
    }
    public MarginData margins(LocalDate from,LocalDate to) {
        var margins=new TreeMap<LocalDate,double[]>();var exchanges=new TreeMap<LocalDate,Map<String,Long>>();
        jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(rzye) balance,sum(rzmre) buy,sum(rzche) repay,count() source_rows,"
                +"sum(CASE WHEN ts_code LIKE '%.SH' THEN 1 ELSE 0 END) exchange_sh,"
                +"sum(CASE WHEN ts_code LIKE '%.SZ' THEN 1 ELSE 0 END) exchange_sz,"
                +"sum(CASE WHEN ts_code LIKE '%.BJ' THEN 1 ELSE 0 END) exchange_bj"
                +" FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 1801",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    LocalDate date=date(rs);long sh=rs.getLong("exchange_sh"),sz=rs.getLong("exchange_sz"),bj=rs.getLong("exchange_bj");
                    if(sh+sz+bj!=rs.getLong("source_rows"))throw new IllegalStateException("Margin source contains unrecognized exchange codes on "+date);
                    if(margins.put(date,new double[]{number(rs,"balance"),number(rs,"buy"),number(rs,"repay")})!=null)throw new IllegalStateException("Duplicate margin aggregate date");
                    exchanges.put(date,Map.of("SH",sh,"SZ",sz,"BJ",bj));if(margins.size()>1800)throw new IllegalStateException("Margin aggregate exceeds bounded date budget");
                },micros(from),micros(to.plusDays(1)));
        return new MarginData(margins,exchanges);
    }
    public Map<LocalDate,double[]> flows(LocalDate from,LocalDate to) {
        var flows=new TreeMap<LocalDate,double[]>();jdbc.query("SELECT trade_date,cast(trade_date AS long) AS trade_micros,sum(net_mf_amount) net,sum(buy_lg_amount+buy_elg_amount-sell_lg_amount-sell_elg_amount) large_net FROM moneyflow WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,trade_micros ORDER BY trade_date LIMIT 1801",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{flows.put(date(rs),new double[]{number(rs,"net"),number(rs,"large_net")});},micros(from),micros(to.plusDays(1)));
        return flows;
    }
    public String sourcePin(List<String> sources)throws Exception{
        var pins=new ArrayList<Object>();for(String source:sources){var metadata=jdbc.queryForList("SELECT id,directoryName,walEnabled,table_txn,table_row_count FROM tables() WHERE table_name=?",source);
            if(metadata.size()!=1)throw new IllegalStateException("Required source table missing: "+source);var item=new LinkedHashMap<String,Object>(metadata.getFirst());item.put("table",source);
            if(Boolean.TRUE.equals(item.get("walEnabled"))){var frontier=jdbc.queryForList("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name=?",source);
                if(frontier.size()!=1)throw new IllegalStateException("Source WAL metadata missing: "+source);var wal=frontier.getFirst();
                if(!com.zoutrankil.data.repository.QuestDbWriteChecks.walSettled(jdbc,source))
                    throw new IllegalStateException("Source WAL is unsettled: "+source);item.put("wal",wal);}
            pins.add(item);}
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(pins)));
    }
    public void requireNoPendingPublication(Path ledgerPath)throws Exception{
        if(!Files.isRegularFile(ledgerPath))return;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath);var exists=db.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reference_publications'")){try(var r=exists.executeQuery()){if(!r.next()||r.getInt(1)==0)return;}
            try(var query=db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset='market_sentiment_daily' AND state<>'VERIFIED' LIMIT 1");var r=query.executeQuery()){
                if(r.next())throw new IllegalStateException("Sentiment publication requires explicit reconciliation before another run: "+r.getString(1));}}
    }
    public boolean tableExists(String name){return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",name).isEmpty();}
    public boolean identityMatches(String name,long id){var found=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",name);return found.size()==1&&found.getFirst().get("id") instanceof Number n&&n.longValue()==id;}
    public void rename(String from,String to){jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\"");}
    private static long micros(LocalDate date){return MarketSentimentDailyWritePort.micros(date.atStartOfDay().toInstant(ZoneOffset.UTC));}
    private static String bounded(String table,String columns,String timestamp){return "(SELECT "+columns+" FROM "+table+" WHERE "+timestamp+">=cast(? AS TIMESTAMP) AND "+timestamp+"<cast(? AS TIMESTAMP))";}
    private static LocalDate date(ResultSet rs)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("Explicit trading date epoch required");
        var carrier=MarketSentimentDailyWritePort.fromMicros(n.longValue());var utc=carrier.atOffset(ZoneOffset.UTC);if(!utc.toLocalTime().equals(LocalTime.MIDNIGHT))throw new SQLException("Trading date is not an exact calendar carrier");return utc.toLocalDate();}
    private static double number(ResultSet rs,String field)throws SQLException{double value=rs.getDouble(field);if(rs.wasNull()||Double.isNaN(value))return Double.NaN;if(!Double.isFinite(value))throw new SQLException("Nonfinite source value: "+field);return value;}
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.doubleValue()==1;}
    private static byte[] canonicalBytes(Object value)throws Exception{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(value);}
}
