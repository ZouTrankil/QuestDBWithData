package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockSuspendState.*;
import com.zoutrankil.data.stock.domain.policy.StockSuspendTablePolicy;
import com.zoutrankil.data.stock.port.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;
/** D011 physical adapter; construction performs no database or sender I/O. */
public final class QuestDbStockSuspendTarget implements StockSuspendTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    public QuestDbStockSuspendTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        StockSuspendTablePolicy.requireExecutionTarget(table);this.table=table;
    }
    @Override public String tableName(){return table;}
    @Override public String targetId(){return StockSuspendTargetIdentity.logical(jdbc,table);}
    @Override public String physicalTargetId() {
        QuestDbWriteChecks.preflight(jdbc, table, StockSuspendDataset.definition(table));
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated stk_suspend QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }
    @Override public Range range() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros,cast(max(timestamp) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return stk_suspend date range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new Range(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_suspend range is not a timestamp epoch");
            return new Range(com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date(),
                    com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                            .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date());
        });
    }
    @Override public StockSuspendWriteSession newWriter(String frozenPhysicalTargetId){return new StockSuspendWritePort(table,jdbc,questdb,frozenPhysicalTargetId);}
    @Override public StockSuspendTables.Table openTable(String table){return new StockSuspendStorage(jdbc,table);}
    @Override public String physicalTargetId(String table,Identity identity){return StockSuspendStorage.physicalTargetId(jdbc,table,identity);}
    @Override public Prepared prepare(Snapshot before,Snapshot current,List<StockSuspend> source,LocalDate from,LocalDate to)throws Exception {
        return StockSuspendStaging.prepare(before,current,source,from,to);
    }
    @Override public StockSuspendStagingPort newStaging(){return new StockSuspendStaging(jdbc,table);}
    @Override public StockSuspendTables newPublicationTables(){return new QuestDbStockSuspendTables(jdbc);}
}
