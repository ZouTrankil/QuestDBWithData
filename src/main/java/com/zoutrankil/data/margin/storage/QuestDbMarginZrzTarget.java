package com.zoutrankil.data.margin.storage;
import com.zoutrankil.data.domain.MarginZrzDataset;
import com.zoutrankil.data.margin.port.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
/** No-I/O configured target with fresh writer and independent protocol clients. */
public final class QuestDbMarginZrzTarget extends QuestDbMarginZrzTables implements MarginZrzTarget {
    private final String table;
    private final QuestDB questdb;
    public QuestDbMarginZrzTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        super(jdbc,false);this.questdb=Objects.requireNonNull(questdb);MarginZrzDataset.requireIsolatedTable(table);this.table=table;
    }
    @Override public String tableName() {return table;}
    @Override public MarginZrzWriteSession newWriter(String frozenPhysicalTargetId) {return new MarginZrzWritePort(table,frozenPhysicalTargetId,jdbc,questdb);}
    @Override public MarginZrzStagingPort newStaging() {return new QuestDbMarginZrzTables(jdbc);}
    @Override public MarginZrzTables newPublicationTables() {return new QuestDbMarginZrzTables(jdbc);}
}
