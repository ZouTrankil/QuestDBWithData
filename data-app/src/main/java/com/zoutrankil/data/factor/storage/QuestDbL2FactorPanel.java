package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.engine.L2FactorPanel;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Bounded diagnostic reader. Version checks are not archive/source completeness certificates. */
public final class QuestDbL2FactorPanel {
    public static final String FEATURE_VERSION="v1.3",PARSER_VERSION="dfcf_csv_v1.2";
    public record Snapshot(L2FactorPanel.Builder panel,long sourceRows,List<String> columns,
            List<Map<String,Object>> sourceState,Map<LocalDate,Long> outputDateRows) {}
    private final JdbcTemplate jdbc;
    public QuestDbL2FactorPanel(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(90);
    }
    public Snapshot read(LocalDate historyFrom,LocalDate from,LocalDate through,List<FactorDefinition> definitions,int maxRows) {
        if(historyFrom.isAfter(from) || through.isBefore(from) || ChronoUnit.DAYS.between(historyFrom,through)>460
                || ChronoUnit.DAYS.between(from,through)>30 || maxRows<1 || maxRows>1_000_000 || definitions.isEmpty())
            throw new IllegalArgumentException("Bounded L2 window required");
        var columns=new TreeSet<String>();
        for(var definition:definitions) {
            if(!definition.enabled() || !definition.sourceGroup().equals("l2_microstructure")
                    || !definition.sourceColumn().matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Invalid L2 source binding");
            columns.add(definition.sourceColumn());
            if(definition.transform().equals("to_clean_amount"))columns.add("clean_amount");
        }
        var selected=List.copyOf(columns);var before=state();
        var builder=new L2FactorPanel.Builder(from,through,selected);long[] count={0};
        var coverage=new TreeMap<LocalDate,Long>();
        String sql="SELECT ts,symbol,feature_version,parser_version,"+String.join(",",selected)
                +" FROM l2_daily_features WHERE ts>='"+historyFrom+"' AND ts<'"+through.plusDays(1)
                +"' ORDER BY ts,symbol,feature_version,parser_version LIMIT "+(maxRows+1);
        jdbc.query(sql,(RowCallbackHandler) rs -> {
            if(++count[0]>maxRows)throw new IllegalStateException("L2 source exceeds row budget");
            var timestamp=rs.getTimestamp(1);
            if(timestamp==null)throw new IllegalStateException("Missing L2 timestamp");
            var date=timestamp.toLocalDateTime().toLocalDate();
            if(!FEATURE_VERSION.equals(rs.getString(3)) || !PARSER_VERSION.equals(rs.getString(4)))
                throw new IllegalStateException("L2 feature/parser version mismatch on "+date);
            double[] values=new double[selected.size()];
            for(int i=0;i<values.length;i++) {
                Object value=rs.getObject(i+5);
                values[i]=value==null?Double.NaN:value instanceof Boolean flag?(flag?1:0):((Number)value).doubleValue();
            }
            builder.add(rs.getString(2),date,values);
            if(!date.isBefore(from))coverage.merge(date,1L,Long::sum);
        });
        if(!before.equals(state()))throw new IllegalStateException("L2 source changed during read");
        if(coverage.isEmpty())throw new IllegalStateException("No L2 output rows in requested window");
        return new Snapshot(builder,count[0],selected,before,Collections.unmodifiableMap(coverage));
    }
    private List<Map<String,Object>> state() {
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='l2_daily_features'");
        if(rows.size()!=1 || Boolean.TRUE.equals(rows.getFirst().get("table_suspended"))
                || !QuestDbWriteChecks.walSettled(jdbc,"l2_daily_features"))
            throw new IllegalStateException("L2 table missing or WAL not settled");
        return rows;
    }
}
