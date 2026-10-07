package com.zoutrankil.data.margin.storage;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.margin.domain.MarginAllTargetIdentity;
import com.zoutrankil.data.margin.domain.MarginAllState.Identity;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
/** QuestDB-only operations; receipt, ledger and recovery decisions remain in the application. */
public class QuestDbMarginAllTables implements MarginAllStagingPort {
    protected final JdbcTemplate jdbc;
    public QuestDbMarginAllTables(JdbcTemplate source) {this(source,true);}
    protected QuestDbMarginAllTables(JdbcTemplate source,boolean clone) {
        if(clone){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);}
        else jdbc=Objects.requireNonNull(source);
    }
    @Override public Table open(String table) {return new MarginAllStorage(jdbc,table);}
    @Override public String logicalTargetId(String table) {
        DatasetDefinition.identifier(table);
        String url=jdbc.execute((ConnectionCallback<String>) connection->connection.getMetaData().getURL());
        return MarginAllTargetIdentity.logical(url,table);
    }
    @Override public String physicalTargetId(String table,Identity identity) {return MarginAllStorage.physicalTargetId(jdbc,table,identity);}
    @Override public int tableCount(String table) {return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).size();}
    @Override public void rename(String source,String target) {jdbc.execute("RENAME TABLE \""+source+"\" TO \""+target+"\"");}
    @Override public DiscardSession newDiscardSession() {
        var client=new JdbcTemplate(jdbc.getDataSource());
        return new DiscardSession() {
            @Override public int namedStageCount(String stage) {return client.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",stage).size();}
            @Override public void dropStage(String stage) {client.execute("DROP TABLE \""+stage+"\"");}
            @Override public boolean stageExists(String stage) {return !client.queryForList("SELECT id FROM tables() WHERE table_name=?",stage).isEmpty();}
        };
    }
    @Override public void createOutsideStage(String stage,String target,String lower,String upper) {
        String sql="CREATE TABLE \""+stage+"\" AS (SELECT trade_date,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl FROM \""+target
                +"\" WHERE trade_date<cast('"+lower+"' AS TIMESTAMP) OR trade_date>=cast('"+upper+"' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
        jdbc.execute(sql);
    }
@Override public void awaitWal(String table,BooleanSupplier cancelled)throws Exception {
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();while(!QuestDbWriteChecks.walSettled(jdbc,table)){
            check(cancelled);if(System.nanoTime()>deadline)throw new IllegalStateException("D028 stage WAL unresolved; retain it for recovery");Thread.sleep(50);}
    }
private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D028 stage operation cancelled; retain artifacts");}
}
