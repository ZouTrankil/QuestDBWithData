package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.stock.domain.StockStDailyState;
import com.zoutrankil.data.stock.port.StockStDailyTables;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.Objects;

public final class QuestDbStockStDailyTables implements StockStDailyTables {
    private final JdbcTemplate jdbc;
    public QuestDbStockStDailyTables(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(120);
    }
    @Override public StockStDailyState.Snapshot snapshot(String table) throws Exception {
        return new StockStDailyStorage(jdbc, table).snapshot();
    }
    @Override public String targetId(String table, StockStDailyState.Identity identity) {
        return StockStDailyStorage.targetId(jdbc, table, identity);
    }
    @Override public StockStDailyState.Snapshot snapshotIfPresent(String table) throws Exception {
        var tables = jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table);
        if (tables.isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("D012 renamed table WAL did not settle; publication remains locked");
            Thread.sleep(50);
        }
        return new StockStDailyStorage(jdbc, table).snapshot();
    }
    @Override public void rename(String from, String to) {
        DatasetDefinition.identifier(from); DatasetDefinition.identifier(to);
        jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\"");
    }
}
