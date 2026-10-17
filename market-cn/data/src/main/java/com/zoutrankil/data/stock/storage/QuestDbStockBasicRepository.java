package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.stock.port.StockBasicLatestRepository;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.StockBasicLatest;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetReadPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Typed stock-basic reads and the configured JDBC connection probe. */
@Repository
public class QuestDbStockBasicRepository implements StockBasicLatestRepository, DatasetImplementation {
    public static final String TABLE = StockBasicDataset.DEFINITION.objectName();
    public static final String LATEST_VIEW = StockBasicDataset.LATEST.objectName();

    private final JdbcTemplate jdbcTemplate;
    private final QuestDbBoundedReader boundedReader;

    public QuestDbStockBasicRepository(JdbcTemplate jdbcTemplate, QuestDbBoundedReader boundedReader) {
        this.jdbcTemplate=jdbcTemplate; this.boundedReader=boundedReader;
    }

    @Override
    public List<StockBasicLatest> findLatest() {
        var page = findLatestPage(new DatasetReadQuery(StockBasicDataset.LATEST.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList(), Map.of(), null, null, null, 10000, null));
        if (page.hasMore()) throw new IllegalStateException("Latest result exceeds 10000 rows; use findLatestPage with its cursor");
        return page.rows();
    }

    @Override
    public DatasetDefinition definition() { return StockBasicDataset.DEFINITION; }

    @Override
    public DatasetReadPage<StockBasicLatest> findLatestPage(DatasetReadQuery query) {
        return boundedReader.read(StockBasicDataset.LATEST, query, null, row -> new StockBasicLatest(
                row.get("snapshot_ts", Instant.class), row.get("ts_code", String.class), row.get("symbol", String.class),
                row.get("name", String.class), row.get("area", String.class), row.get("industry", String.class),
                row.get("list_date", LocalDate.class)));
    }

    public void verifyConnection() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (result == null || result != 1) {
            throw new IllegalStateException("QuestDB JDBC probe returned an unexpected result");
        }
    }

}
