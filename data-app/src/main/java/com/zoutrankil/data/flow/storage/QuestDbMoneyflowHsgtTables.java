package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.Identity;
import com.zoutrankil.data.flow.port.MoneyflowHsgtStagingPort;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Physical table operations; intent validation and publication transitions stay in application. */
public final class QuestDbMoneyflowHsgtTables implements MoneyflowHsgtStagingPort {
 private final JdbcTemplate jdbc;
 public QuestDbMoneyflowHsgtTables(JdbcTemplate source) {
  jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);
 }
 @Override public Table open(String table) {return new MoneyflowHsgtStorage(jdbc,table);}
 @Override public String logicalTargetId(String table) {
  DatasetDefinition.identifier(table);
  return StaticTargetIdentity.identify(jdbc,table,0L,"d027-logical-target-v1");
 }
 @Override public String physicalTargetId(String table,Identity identity) {return MoneyflowHsgtStorage.physicalTargetId(jdbc,table,identity);}
 @Override public int tableCount(String table) {return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).size();}
 @Override public void createOutsideStage(String target,String stage,LocalDate from,LocalDate to) {
  String lower=from+"T00:00:00.000000Z",upper=to.plusDays(1)+"T00:00:00.000000Z";
  String sql="CREATE TABLE \""+stage+"\" AS (SELECT trade_date,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \""+target
   +"\" WHERE trade_date<cast('"+lower+"' AS TIMESTAMP) OR trade_date>=cast('"+upper+"' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY DAY WAL";
  jdbc.execute(sql);
 }
 @Override public int discardTableCount(String table) {return new JdbcTemplate(jdbc.getDataSource()).queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table).size();}
 @Override public void drop(String table) {new JdbcTemplate(jdbc.getDataSource()).execute("DROP TABLE \""+table+"\"");}
 @Override public boolean dropSettled(String table) {return new JdbcTemplate(jdbc.getDataSource()).queryForList("SELECT id FROM tables() WHERE table_name=?",table).isEmpty();}
 @Override public void rename(String source,String target) {
  if(tableCount(target)==1)throw new IllegalStateException("D027 publication destination already exists: "+target);
  if(tableCount(source)!=1)throw new IllegalStateException("D027 publication source table is absent: "+source);
  jdbc.execute("RENAME TABLE \""+source+"\" TO \""+target+"\"");
 }
 public void awaitWal(String table,BooleanSupplier cancelled)throws Exception {
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();while(!QuestDbWriteChecks.walSettled(jdbc,table)){
            check(cancelled);if(System.nanoTime()>deadline)throw new IllegalStateException("D027 stage WAL unresolved; retain it for recovery");Thread.sleep(50);}
    }
 private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D027 stage operation cancelled; retain artifacts");}
}
