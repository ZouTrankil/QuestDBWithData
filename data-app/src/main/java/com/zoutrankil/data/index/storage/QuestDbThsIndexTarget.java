package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.ThsIndex;
import com.zoutrankil.data.index.domain.ThsIndexState.*;
import com.zoutrankil.data.index.port.ThsIndexTarget;
import com.zoutrankil.data.index.port.ThsIndexTables;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class QuestDbThsIndexTarget extends QuestDbThsIndexTables implements ThsIndexTarget {
    private final String table;

    public QuestDbThsIndexTarget(String table, JdbcTemplate source) {
        super(source, false);
        DatasetDefinition.identifier(table);
        this.table = table;
    }

    @Override public String tableName() { return table; }
    @Override public Prepared prepare(Snapshot before, List<ThsIndex> source, Scope scope) throws Exception {
        return ThsIndexStaging.prepare(before, source, scope);
    }
    @Override public Prepared preparePrepared(Snapshot before, List<ThsIndex> rows) throws Exception {
        return ThsIndexStaging.preparePrepared(before, rows);
    }
    @Override public StageWriter newStaging() { return new ThsIndexStaging(jdbc); }
    @Override public ThsIndexTables publicationTables() { return new QuestDbThsIndexTables(jdbc); }
}
