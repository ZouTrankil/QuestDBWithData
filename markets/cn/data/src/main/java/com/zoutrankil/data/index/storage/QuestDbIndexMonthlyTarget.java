package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;
import java.util.*;
public final class QuestDbIndexMonthlyTarget implements IndexMonthlyTarget {
private final String table;private final JdbcTemplate jdbc;private final QuestDB questdb;
public QuestDbIndexMonthlyTarget(String table,JdbcTemplate jdbc,QuestDB questdb){IndexMonthlyDataset.requireIsolatedTableName(table);this.table=table;this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);}
public String tableName(){return table;}
public String targetId(){return newPublicationTables().logicalTargetId(table);}
public String physicalTargetId(){var identity=new IndexMonthlyStorage(jdbc,table).preflight();return IndexMonthlyStorage.physicalTargetId(jdbc,table,identity);}
public IndexMonthlyWriteSession newWriter(String physicalId){return new IndexMonthlyWritePort(table,physicalId,jdbc,questdb);}
public IndexMonthlyTables newPublicationTables(){return new QuestDbIndexMonthlyTables(jdbc);}
public IndexMonthlyStagingPort newStaging(){return new QuestDbIndexMonthlyTables(jdbc);}
}
