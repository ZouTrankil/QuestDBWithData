package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.EtfComponentPanel;
import com.zoutrankil.data.factor.engine.EtfSelfL2;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Read seven shared L2 fields once for ETF-self and constituent consumers. */
public final class QuestDbEtfL2 {
    public record Snapshot(Map<LocalDate,List<EtfComponentPanel.L2>> dates,long rows,List<Map<String,Object>> sourceState) {
        public List<EtfSelfL2.Input> selfInputs() {
            var result=new ArrayList<EtfSelfL2.Input>();
            dates.forEach((date,rows)->rows.forEach(row->result.add(new EtfSelfL2.Input(date,row.symbol(),row.obi1(),row.ofi(),row.spread(),row.depth()))));
            return List.copyOf(result);
        }
    }
    private final JdbcTemplate jdbc;
    public QuestDbEtfL2(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public Snapshot read(LocalDate from,LocalDate through,int maxRows) {
        if(from==null || through==null || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>30
                || maxRows<1 || maxRows>1_000_000)throw new IllegalArgumentException("Bounded ETF L2 window required");
        var before=state();var dates=new TreeMap<LocalDate,List<EtfComponentPanel.L2>>();
        var keys=new HashSet<List<Object>>();long[] count={0};
        jdbc.query("SELECT ts,symbol,feature_version,parser_version,main_net_inflow,mfi_score,obi_top1_mean,mean_obi_top5,ofi_mean,mean_spread,depth_top5_mean"
                +" FROM l2_daily_features WHERE ts>='"+from+"' AND ts<'"+through.plusDays(1)+"' ORDER BY ts,symbol LIMIT "+(maxRows+1),
                (RowCallbackHandler)rs->{
                    if(++count[0]>maxRows)throw new IllegalStateException("ETF L2 source exceeds row budget");
                    var stamp=rs.getTimestamp(1);String symbol=rs.getString(2);
                    if(stamp==null || symbol==null || symbol.isBlank())throw new IllegalStateException("ETF L2 key missing");
                    var date=stamp.toLocalDateTime().toLocalDate();
                    if(!QuestDbL2FactorPanel.FEATURE_VERSION.equals(rs.getString(3)) || !QuestDbL2FactorPanel.PARSER_VERSION.equals(rs.getString(4)))
                        throw new IllegalStateException("ETF L2 source version mismatch");
                    if(!keys.add(List.of(date,symbol)))throw new IllegalStateException("Duplicate ETF L2 date/symbol");
                    double[] values=new double[7];
                    for(int i=0;i<values.length;i++){values[i]=rs.getDouble(i+5);if(rs.wasNull())values[i]=Double.NaN;}
                    dates.computeIfAbsent(date,ignored->new ArrayList<>()).add(new EtfComponentPanel.L2(symbol,
                            values[0],values[1],values[2],values[3],values[4],values[5],values[6]));
                });
        if(!before.equals(state()))throw new IllegalStateException("ETF L2 source changed during read");
        dates.replaceAll((date,rows)->List.copyOf(rows));
        return new Snapshot(Collections.unmodifiableMap(dates),count[0],before);
    }
    public void verifyUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("ETF L2 source changed after read");
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='l2_daily_features'");
        if(rows.size()!=1 || !QuestDbWriteChecks.walSettled(jdbc,"l2_daily_features"))
            throw new IllegalStateException("ETF L2 table missing or WAL unsettled");
        return rows;
    }
}
