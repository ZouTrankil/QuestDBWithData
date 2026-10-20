package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.index.IndexCatalogEntry;
import com.zoutrankil.data.index.domain.IndexCatalogState.Prepared;
import com.zoutrankil.data.index.domain.IndexCatalogState.Snapshot;
import com.zoutrankil.data.index.port.IndexCatalogStagingPort;
import com.zoutrankil.data.index.port.IndexCatalogTables;
import com.zoutrankil.data.index.port.IndexCatalogTarget;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

public final class QuestDbIndexCatalogTarget implements IndexCatalogTarget {
    private final JdbcTemplate jdbc;
    private final String table;

    public QuestDbIndexCatalogTarget(JdbcTemplate jdbc, String table) {
        this.jdbc = Objects.requireNonNull(jdbc);
        DatasetDefinition.identifier(table);
        this.table = table;
    }

    public String tableName() {
        return table;
    }

    public Table open(String table) {
        return new IndexCatalogStorage(jdbc, table);
    }

    public String identify(String table, long id, String directory) {
        return StaticTargetIdentity.identify(jdbc, table, id, directory);
    }

    public boolean exists(String table) {
        return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty();
    }

    public boolean walSettled(String table) {
        return QuestDbWriteChecks.walSettled(jdbc, table);
    }

    public void rename(String from, String to) {
        jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\"");
    }

    public IndexCatalogTables publicationTables() {
        var copy = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        copy.setQueryTimeout(20);
        return new QuestDbIndexCatalogTarget(copy, table);
    }

    public Prepared prepare(Snapshot before, List<IndexCatalogEntry> source) throws Exception {
        return IndexCatalogStaging.prepare(before, source);
    }

    public IndexCatalogStagingPort newStaging() {
        return new IndexCatalogStaging(jdbc);
    }
}
