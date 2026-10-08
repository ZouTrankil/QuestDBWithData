package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.domain.StockSuspend;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.stock.domain.StockSuspendState.*;
import com.zoutrankil.data.stock.port.StockSuspendTables;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
/** D011 publication queries use their original independent JDBC settings. */
public final class QuestDbStockSuspendTables implements StockSuspendTables {
    private final JdbcTemplate jdbc;
    public QuestDbStockSuspendTables(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(20);
    }
    @Override public Table openTable(String table){return new StockSuspendStorage(jdbc,table);}
    @Override public String logicalTargetId(String table){return StockSuspendTargetIdentity.logical(jdbc,table);}
    @Override public String physicalTargetId(String table,Identity identity){return StockSuspendStorage.physicalTargetId(jdbc,table,identity);}
    @Override public Prepared prepare(Snapshot before,Snapshot current,List<StockSuspend> source,LocalDate from,LocalDate to)throws Exception {
        return StockSuspendStaging.prepare(before,current,source,from,to);
    }
    @Override public Snapshot snapshotIfPresent(String tableName)throws Exception {
        if(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",tableName).isEmpty())return null;
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,tableName)) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Renamed stk_suspend WAL unresolved; keep publication journal");
            Thread.sleep(50);
        }
        return new StockSuspendStorage(jdbc,tableName).snapshot();
    }
    @Override public void rename(String from,String to){jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\"");}
}
