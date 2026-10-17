package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.margin.domain.MarginSecsState.Snapshot;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Configured D030 target with operation-scoped writer state. */
public final class QuestDbMarginSecsTarget implements MarginSecsTarget {
 private final String table;private final JdbcTemplate jdbc;private final QuestDB questdb;
 public QuestDbMarginSecsTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
  MarginSecsDataset.requireIsolatedTable(table);this.table=table;this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
 }
 @Override public String tableName(){return table;}
 @Override public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
  if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir))
   throw new IllegalStateException("Exact isolated D030 target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir);}
 @Override public Snapshot snapshot(){return new MarginSecsStorage(jdbc,table).snapshot();}
 @Override public MarginSecsWriteSession newWriter(String physicalTargetId){return new MarginSecsWritePort(table,physicalTargetId,jdbc,questdb);}
}
