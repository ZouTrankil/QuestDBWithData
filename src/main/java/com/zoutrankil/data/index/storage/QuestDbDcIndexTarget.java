package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;
import java.util.*;
public final class QuestDbDcIndexTarget implements DcIndexTarget {
private final String table;private final JdbcTemplate jdbc;private final QuestDB questdb;
public QuestDbDcIndexTarget(String table,JdbcTemplate jdbc,QuestDB questdb){DcIndexDataset.requireIsolatedTable(table);this.table=table;this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);}
public String tableName(){return table;}
public String targetId(){return newPublicationTables().logicalTargetId(table);}
public String physicalTargetId(){var identity=new DcIndexStorage(jdbc,table).preflight();return DcIndexStorage.physicalTargetId(jdbc,table,identity);}
public DcIndexWriteSession newWriter(String physicalId){return new DcIndexWritePort(table,physicalId,jdbc,questdb);}
public DcIndexTables newPublicationTables(){return new QuestDbDcIndexTables(jdbc);}
public DcIndexStagingPort newStaging(){return new DcIndexStaging(jdbc,table);}
public DcIndexWriteSession stageWriter(String stage,String physicalId){return new DcIndexWritePort(stage,physicalId,jdbc,questdb);}
public DcIndexState.DateRange range(){return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros FROM \""+table+"\"",rs->{if(!rs.next())throw new IllegalStateException("D023 target range unavailable");Object min=rs.getObject(1),max=rs.getObject(2);if(min==null&&max==null)return new DcIndexState.DateRange(null,null);if(!(min instanceof Number a)||!(max instanceof Number b))throw new IllegalStateException("D023 target date is not timestamp");return new DcIndexState.DateRange(com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp.fromStorageEpoch(a.longValue(),com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date(),com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp.fromStorageEpoch(b.longValue(),com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date());});}
}
