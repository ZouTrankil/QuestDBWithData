package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.Snapshot;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Shared configuration only; each mutable session and table-operation adapter is new. */
public final class QuestDbMoneyflowHsgtTarget implements MoneyflowHsgtTarget {
 private final String table;private final JdbcTemplate jdbc;private final QuestDB questdb;
 public QuestDbMoneyflowHsgtTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
  MoneyflowHsgtDataset.requireAdmittedTable(table);this.table=table;this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
 }
 @Override public String tableName(){return table;}
 @Override public String targetId(){com.zoutrankil.data.domain.DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbc,table,0L,"d027-logical-target-v1");}
 @Override public String physicalTargetId()throws Exception {var snapshot=new MoneyflowHsgtStorage(jdbc,table).snapshot();return MoneyflowHsgtStorage.physicalTargetId(jdbc,table,snapshot.identity());}
 @Override public Snapshot snapshot()throws Exception{return new MoneyflowHsgtStorage(jdbc,table).snapshot();}
 @Override public MoneyflowHsgtWriteSession newWriter(String physicalTargetId){return new MoneyflowHsgtWritePort(table,physicalTargetId,jdbc,questdb);}
 @Override public MoneyflowHsgtStagingPort newTables(){return new QuestDbMoneyflowHsgtTables(jdbc);}
}
