package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.application.DailyFactorEvaluation;
import com.zoutrankil.data.factor.engine.StockCodeAliases;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** One immutable daily exposure map shared by every pool and factor in an evaluation. */
public final class QuestDbDailyEvaluationExposures {
    private final JdbcTemplate jdbc;
    public QuestDbDailyEvaluationExposures(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(60);
    }
    public record Snapshot(LocalDate date,Map<String,DailyFactorEvaluation.Exposure> values,
            List<Map<String,Object>> sourceState) {
        public Snapshot {
            values=Map.copyOf(values);
            sourceState=sourceState.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
        }
    }
    public Snapshot read(LocalDate date) {
        Objects.requireNonNull(date);var before=state();
        var memberships=new QuestDbStockMembership(jdbc).read(date,1_000_000);
        var rows=jdbc.query("SELECT ts_code,circ_mv,total_mv FROM daily_basic WHERE trade_date=? ORDER BY ts_code LIMIT 10001",
                (rs,n)->new StockCodeAliases.Row(rs.getString(1),date,new double[]{
                    rs.getObject(2)==null?Double.NaN:rs.getDouble(2),rs.getObject(3)==null?Double.NaN:rs.getDouble(3)}),
                date+"T00:00:00.000000Z");
        if(rows.isEmpty() || rows.size()>10000)throw new IllegalStateException("Daily exposure source missing or exceeds budget");
        var normalized=StockCodeAliases.load().normalize(rows,new int[]{0,1});
        String[] symbols=normalized.stream().map(StockCodeAliases.Row::symbol).toArray(String[]::new);
        double[] circ=normalized.stream().mapToDouble(r->r.values()[0]).toArray();
        double[] total=normalized.stream().mapToDouble(r->r.values()[1]).toArray();
        var resolved=memberships.exposures().resolve(date,symbols,circ,total);
        var values=new LinkedHashMap<String,DailyFactorEvaluation.Exposure>();
        for(int i=0;i<symbols.length;i++)values.put(symbols[i],new DailyFactorEvaluation.Exposure(resolved.logMarketCap()[i],resolved.industry()[i]));
        if(!before.equals(state()))throw new IllegalStateException("Exposure sources changed during read");
        return new Snapshot(date,values,before);
    }
    public void requireUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("Exposure sources changed after calculation");
    }
    private List<Map<String,Object>> state() {
        for(String table:List.of("daily_basic","index_member","index_weight"))
            if(!QuestDbWriteChecks.walSettled(jdbc,table))throw new IllegalStateException("Exposure source WAL unsettled: "+table);
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name IN ('daily_basic','index_member','index_weight') ORDER BY table_name");
        if(rows.size()!=3)throw new IllegalStateException("Exposure source table missing");
        return rows;
    }
}
