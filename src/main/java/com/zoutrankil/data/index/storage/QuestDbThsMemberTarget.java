package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.index.port.ThsMemberTarget;
import com.zoutrankil.data.index.port.ThsMemberTables;
import org.springframework.jdbc.core.JdbcTemplate;

public final class QuestDbThsMemberTarget extends QuestDbThsMemberTables implements ThsMemberTarget {
    private final String table;

    public QuestDbThsMemberTarget(String table, JdbcTemplate source) {
        super(source, false);
        DatasetDefinition.identifier(table);
        this.table = table;
    }

    @Override public String tableName() { return table; }
    @Override public StageWriter newStaging() { return new ThsMemberBoardStaging(jdbc); }
    @Override public ThsMemberTables publicationTables() { return new QuestDbThsMemberTables(jdbc); }
}
