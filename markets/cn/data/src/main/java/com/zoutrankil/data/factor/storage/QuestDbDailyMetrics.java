package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.DailyMetricDelta;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded formal-date read. Existing rows outside the requested factor/pool matrix are preserved. */
public final class QuestDbDailyMetrics {
    private final JdbcTemplate jdbc;
    public QuestDbDailyMetrics(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(60);
    }
    public List<FactorDailyMetricRow> read(LocalDate date) {
        return readRange(date,date,10000).rows();
    }
    public record History(LocalDate from,LocalDate through,List<FactorDailyMetricRow> rows,List<Map<String,Object>> sourceState) {}
    /** One bounded history scan, with generation and WAL checks covering the entire result. */
    public History readRange(LocalDate from,LocalDate through,int maxRows) {
        return readRange(from,through,maxRows,null);
    }
    public History readRange(LocalDate from,LocalDate through,int maxRows,String canonicalLabel) {
        if(from==null||through==null||from.isAfter(through)
                ||java.time.temporal.ChronoUnit.DAYS.between(from,through)>1100||maxRows<1||maxRows>1_000_000)
            throw new IllegalArgumentException("Bounded metric history of at most 1100 days and 1000000 rows required");
        var before=state();
        var parameters=new ArrayList<Object>();parameters.add(from+"T00:00:00.000000Z");parameters.add(through.plusDays(1)+"T00:00:00.000000Z");
        String selection="";
        if(canonicalLabel!=null) {
            if(canonicalLabel.isBlank())throw new IllegalArgumentException("Explicit canonical label required");
            selection=" AND horizon=1 AND evaluation_profile='base_ic' AND label_definition=?";
            parameters.add(canonicalLabel);
        }
        var rows=jdbc.query("SELECT "+DailyMetricStorageMapping.projection()+
                " FROM factor_daily_metric WHERE trade_date>=? AND trade_date<?"+selection+" ORDER BY trade_date LIMIT "+(maxRows+1),
                (rs,n)->DailyMetricStorageMapping.read(rs),parameters.toArray());
        if(rows.size()>maxRows)throw new IllegalStateException("Daily metric row budget exceeded");
        var unique=new HashSet<DailyMetricDelta.Key>();
        for(var row:rows)if(!unique.add(DailyMetricDelta.key(row)))throw new IllegalStateException("Duplicate stored metric business key");
        if(!before.equals(state()))throw new IllegalStateException("Metric target changed during read");
        return new History(from,through,List.copyOf(rows),before.stream().map(Map::copyOf).toList());
    }
    private List<Map<String,Object>> state() {
        if(!QuestDbWriteChecks.walSettled(jdbc,"factor_daily_metric"))throw new IllegalStateException("Metric WAL unsettled");
        var rows=jdbc.queryForList("SELECT id,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='factor_daily_metric'");
        if(rows.size()!=1)throw new IllegalStateException("Formal metric target missing");
        return rows;
    }
}
