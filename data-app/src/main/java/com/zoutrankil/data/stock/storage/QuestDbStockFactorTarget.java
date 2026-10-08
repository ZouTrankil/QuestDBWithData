package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.port.StockFactorTarget;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.Objects;

public final class QuestDbStockFactorTarget implements StockFactorTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbStockFactorTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        DatasetDefinition.identifier(table); this.table = table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        var port=new StockFactorWritePort(table,jdbc,questdb);port.preflight();
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact stk_factor QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);
    }
    @Override public StockTargetRange range(String tsCode) {
        String sql="SELECT cast(min(trade_date) AS long) AS min_micros,"
                +"cast(max(trade_date) AS long) AS max_micros FROM \""+table+"\""
                +(tsCode==null?"":" WHERE ts_code=?");
        return jdbc.query(sql,rs->{
            if(!rs.next()) throw new IllegalStateException("QuestDB did not return the stk_factor date range aggregate");
            Object min=rs.getObject("min_micros"),max=rs.getObject("max_micros");
            if(min==null && max==null) return checkedRange(null,null);
            if(!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_factor range aggregate is not a timestamp epoch");
            var minDate=com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(),com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var maxDate=com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(),com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(minDate,maxDate);
        },tsCode==null?new Object[]{}:new Object[]{tsCode});
    }
    private static StockTargetRange checkedRange(LocalDate min, LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid stk_factor target date range");
        return new StockTargetRange(min, max);
    }
    @Override public VerifiedWriteSession<StockFactor, StockFactorKey> newWriter(String frozenTargetId) {
        return new StockFactorWritePort(table,jdbc,questdb,1024*1024,java.time.Duration.ofSeconds(10),frozenTargetId);
    }
}
