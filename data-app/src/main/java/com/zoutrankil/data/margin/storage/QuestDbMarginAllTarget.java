package com.zoutrankil.data.margin.storage;
import com.zoutrankil.data.domain.MarginAllDataset;
import com.zoutrankil.data.margin.port.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
/** No-I/O configured target with fresh writer and independent protocol clients. */
public final class QuestDbMarginAllTarget extends QuestDbMarginAllTables implements MarginAllTarget {
    private final String table;
    private final QuestDB questdb;
    public QuestDbMarginAllTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        super(jdbc,false);this.questdb=Objects.requireNonNull(questdb);MarginAllDataset.requireIsolatedTable(table);this.table=table;
    }
    @Override public String tableName() {return table;}
    @Override public MarginAllWriteSession newWriter(String frozenPhysicalTargetId) {return new MarginAllWritePort(table,frozenPhysicalTargetId,jdbc,questdb);}
    @Override public MarginAllStagingPort newStaging() {return new QuestDbMarginAllTables(jdbc);}
    @Override public MarginAllTables newPublicationTables() {return new QuestDbMarginAllTables(jdbc);}
}
