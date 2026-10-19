package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.EtfConstituentWeights;
import com.zoutrankil.data.factor.engine.EtfCsiHoldings;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Latest disclosed report, matching Python's extract_latest_etf_constituents SQL. */
public final class QuestDbEtfConstituents {
    public record HistorySnapshot(LocalDate asOf,List<EtfConstituentWeights.Input> rows,List<Map<String,Object>> sourceState) {
        public EtfCsiHoldings csiHoldings(Set<String> validEtfs) {
            return new EtfCsiHoldings(rows,validEtfs,asOf);
        }
    }
    public record Snapshot(LocalDate asOf,List<EtfConstituentWeights.Input> rows,List<Map<String,Object>> sourceState) {
        public List<EtfConstituentWeights.Member> normalize(Set<String> validEtfs) {
            return EtfConstituentWeights.normalize(rows,validEtfs);
        }
    }
    private final JdbcTemplate jdbc;
    public QuestDbEtfConstituents(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public Snapshot read(LocalDate asOf,int maxRows) {
        return read(asOf,maxRows,false);
    }
    /** One source read for all disclosure versions used by a historical CSI output interval. */
    public HistorySnapshot readHistory(LocalDate asOf,int maxRows) {
        var snapshot=read(asOf,maxRows,true);
        return new HistorySnapshot(snapshot.asOf(),snapshot.rows(),snapshot.sourceState());
    }
    public record WindowSnapshot(HistorySnapshot history,EtfCsiHoldings.Refresh refresh) {}
    /** Only the last pre-window disclosure and in-window disclosures need constituent payloads. */
    public WindowSnapshot readWindow(LocalDate from,LocalDate asOf,LocalDate latestTradeDate,Set<String> validEtfs,int maxRows) {
        if(from==null || asOf==null || from.isAfter(asOf) || latestTradeDate.isAfter(asOf)
                || maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded holdings window required");
        var before=state();
        String versions="SELECT ts_code,ann_date,max(end_date) AS report_end FROM etf_portfolio WHERE ann_date<='"+asOf
                +"' AND end_date IS NOT NULL AND (ts_code LIKE '%.SH' OR ts_code LIKE '%.SZ') GROUP BY ts_code,ann_date";
        var catalog=jdbc.queryForList(versions+" LIMIT 100001");
        if(catalog.size()>100000)throw new IllegalStateException("Holdings version directory exceeds budget");
        LocalDate latestAnn=null,latestEnd=null;int versionCount=0;var etfs=new HashSet<String>();
        for(var row:catalog)if(validEtfs.contains((String)row.get("ts_code"))) {
            var ann=((java.sql.Timestamp)row.get("ann_date")).toLocalDateTime().toLocalDate();
            var end=((java.sql.Timestamp)row.get("report_end")).toLocalDateTime().toLocalDate();
            if(latestAnn==null || ann.isAfter(latestAnn))latestAnn=ann;
            if(latestEnd==null || end.isAfter(latestEnd))latestEnd=end;
            etfs.add((String)row.get("ts_code"));versionCount++;
        }
        var rows=new ArrayList<EtfConstituentWeights.Input>();
        String sql="WITH versions AS ("+versions+"), prior AS (SELECT ts_code,max(ann_date) AS prior_ann FROM versions WHERE ann_date<'"+from
                +"' GROUP BY ts_code), selected AS (SELECT v.ts_code,v.ann_date,v.report_end FROM versions v LEFT JOIN prior b ON v.ts_code=b.ts_code"
                +" WHERE v.ann_date>='"+from+"' OR v.ann_date=b.prior_ann)"
                +" SELECT p.ts_code,p.symbol,p.stk_mkv_ratio,p.mkv,p.ann_date,p.end_date FROM etf_portfolio p"
                +" JOIN selected s ON (p.ts_code=s.ts_code AND p.ann_date=s.ann_date AND p.end_date=s.report_end)"
                +" ORDER BY p.ts_code,p.ann_date,p.end_date,p.symbol LIMIT "+(maxRows+1);
        jdbc.query(sql,(RowCallbackHandler)rs->{
            if(rows.size()>=maxRows)throw new IllegalStateException("Window holdings exceed row budget");
            rows.add(new EtfConstituentWeights.Input(rs.getString(1),rs.getString(2),number(rs,3),number(rs,4),
                    rs.getTimestamp(5).toLocalDateTime().toLocalDate(),rs.getTimestamp(6).toLocalDateTime().toLocalDate()));
        });
        if(!before.equals(state()))throw new IllegalStateException("Holdings changed during window read");
        Long stale=latestAnn==null?null:Math.max(0,java.time.temporal.ChronoUnit.DAYS.between(latestAnn,latestTradeDate));
        var refresh=new EtfCsiHoldings.Refresh(latestAnn,latestEnd,etfs.size(),versionCount,latestTradeDate,stale,
                stale==null?"empty":stale>120?"stale":"ready");
        return new WindowSnapshot(new HistorySnapshot(asOf,List.copyOf(rows),before),refresh);
    }
    private Snapshot read(LocalDate asOf,int maxRows,boolean history) {
        Objects.requireNonNull(asOf);
        if(maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded constituent row budget required");
        var before=state();var rows=new ArrayList<EtfConstituentWeights.Input>();
        String cutoff="to_date('"+asOf+"','yyyy-MM-dd')";
        String sql="WITH latest AS (SELECT ts_code,max(end_date) AS latest_end_date FROM etf_portfolio"
                +" WHERE ann_date<="+cutoff+" AND (ts_code LIKE '%.SH' OR ts_code LIKE '%.SZ') GROUP BY ts_code)"
                +" SELECT p.ts_code,p.symbol,p.stk_mkv_ratio,p.mkv,p.ann_date,p.end_date FROM etf_portfolio p"
                +" JOIN latest ON (p.ts_code=latest.ts_code AND p.end_date=latest.latest_end_date)"
                +" WHERE p.ann_date<="+cutoff+" AND (p.ts_code LIKE '%.SH' OR p.ts_code LIKE '%.SZ')"
                +" ORDER BY p.ts_code,p.symbol LIMIT "+(maxRows+1);
        if(history)sql="SELECT ts_code,symbol,stk_mkv_ratio,mkv,ann_date,end_date FROM etf_portfolio"
                +" WHERE ann_date<="+cutoff+" AND (ts_code LIKE '%.SH' OR ts_code LIKE '%.SZ')"
                +" ORDER BY ts_code,ann_date,end_date,symbol LIMIT "+(maxRows+1);
        jdbc.query(sql,(RowCallbackHandler)rs->{
            if(rows.size()>=maxRows)throw new IllegalStateException("ETF constituent snapshot exceeds row budget");
            var announced=rs.getTimestamp(5);var period=rs.getTimestamp(6);
            if(announced==null || period==null)throw new IllegalStateException("Missing constituent disclosure dates");
            rows.add(new EtfConstituentWeights.Input(rs.getString(1),rs.getString(2),number(rs,3),number(rs,4),
                    announced.toLocalDateTime().toLocalDate(),period.toLocalDateTime().toLocalDate()));
        });
        if(!before.equals(state()))throw new IllegalStateException("ETF constituents changed during read");
        return new Snapshot(asOf,List.copyOf(rows),before);
    }
    public void verifyUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("ETF constituents changed after snapshot");
    }
    public void verifyUnchanged(HistorySnapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("ETF constituents changed after history snapshot");
    }
    private static double number(java.sql.ResultSet rs,int column)throws java.sql.SQLException {
        double value=rs.getDouble(column);return rs.wasNull()?Double.NaN:value;
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='etf_portfolio'");
        if(rows.size()!=1 || !QuestDbWriteChecks.walSettled(jdbc,"etf_portfolio"))
            throw new IllegalStateException("ETF constituent source missing or WAL unsettled");
        return rows;
    }
}
