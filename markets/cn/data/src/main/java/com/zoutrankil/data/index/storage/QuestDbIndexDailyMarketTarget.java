package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.index.IndexDailyMarketDataset;
import com.zoutrankil.data.index.port.IndexDailyMarketTarget;
import com.zoutrankil.data.index.port.IndexDailyMarketWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Concrete storage assembly; construction does not query or mutate the target. */
public final class QuestDbIndexDailyMarketTarget implements IndexDailyMarketTarget {
    private final int version;
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbIndexDailyMarketTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, jdbc, questdb, 1);
    }

    public QuestDbIndexDailyMarketTarget(
            String table, JdbcTemplate jdbc, QuestDB questdb, int version) {
        IndexDailyMarketDataset.requireTarget(table, version);
        this.version = version;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.table = table;
    }

    @Override
    public int datasetVersion() {
        return version;
    }

    @Override
    public IndexDailyMarketTarget forVersion(int version) {
        return new QuestDbIndexDailyMarketTarget(table, jdbc, questdb, version);
    }

    @Override
    public String tableName() {
        return table;
    }

    @Override
    public String targetId() {
        IndexDailyMarketDataset.requireTarget(table, version);
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated D019 QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override
    public IndexDailyMarketWriteSession newWriter(String frozenTargetId) {
        return new IndexDailyMarketWritePort(table, frozenTargetId, jdbc, questdb, version);
    }
}
