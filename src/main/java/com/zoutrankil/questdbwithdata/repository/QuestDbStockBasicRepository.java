package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import com.zoutrankil.questdbwithdata.domain.StockBasicLatest;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshot;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshotKey;
import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Writes through QWP and reads through PGWire/JDBC. */
@Repository
public class QuestDbStockBasicRepository implements StockBasicLatestRepository {
    public static final String TABLE = "java_tushare_stock_basic_qwp_test";
    public static final String LATEST_VIEW = "java_tushare_stock_basic_latest_qwp_test";
    private static final DateTimeFormatter TUSHARE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String COUNT_SNAPSHOT_SQL =
            "SELECT count() FROM " + TABLE + " WHERE snapshot_ts = ?";
    private static final String SELECT_LATEST_SQL = """
            SELECT snapshot_ts, ts_code, symbol, name, area, industry, list_date
            FROM java_tushare_stock_basic_latest_qwp_test
            ORDER BY ts_code
            """;

    private final JdbcTemplate jdbcTemplate;
    private final QuestDB questDB;
    private final QuestDbProperties properties;

    public QuestDbStockBasicRepository(
            JdbcTemplate jdbcTemplate,
            @Lazy QuestDB questDB,
            QuestDbProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.questDB = questDB;
        this.properties = properties;
    }

    public StockBasicSyncReport storeAndVerify(List<StockBasic> stocks) throws InterruptedException {
        Instant snapshot = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

        try (Sender sender = questDB.borrowSender()) {
            for (StockBasic stock : stocks) {
                StockBasicSnapshot snapshotRow = new StockBasicSnapshot(snapshot, stock);
                StockBasicSnapshotKey key = snapshotRow.key();
                var row = sender.table(TABLE)
                        .symbol("ts_code", key.tsCode())
                        .symbol("symbol", stock.symbol())
                        .stringColumn("name", stock.name())
                        .symbol("area", stock.area())
                        .symbol("industry", stock.industry());
                // The Java QWP Sender has no DATE setter; retain YYYYMMDD as STRING in storage.
                if (stock.listDate() != null) {
                    row.stringColumn("list_date", TUSHARE_DATE_FORMAT.format(stock.listDate()));
                }
                row.at(key.snapshotTimestamp());
            }
        }

        long visibleRows = awaitSnapshotVisibility(snapshot, stocks.size());
        return new StockBasicSyncReport(stocks.size(), visibleRows, snapshot);
    }

    @Override
    public List<StockBasicLatest> findLatest() {
        return jdbcTemplate.query(SELECT_LATEST_SQL, this::mapSnapshotRows);
    }

    private List<StockBasicLatest> mapSnapshotRows(ResultSet resultSet) throws SQLException {
        List<StockBasicLatest> rows = new ArrayList<>();
        while (resultSet.next()) {
            String listDate = resultSet.getString("list_date");
            Instant snapshotTimestamp = resultSet.getTimestamp("snapshot_ts").toInstant();
            rows.add(new StockBasicLatest(
                    snapshotTimestamp,
                    resultSet.getString("ts_code"),
                    resultSet.getString("symbol"),
                    resultSet.getString("name"),
                    resultSet.getString("area"),
                    resultSet.getString("industry"),
                    listDate == null || listDate.isBlank()
                            ? null : LocalDate.parse(listDate, TUSHARE_DATE_FORMAT)));
        }
        return List.copyOf(rows);
    }

    public void verifyConnection() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (result == null || result != 1) {
            throw new IllegalStateException("QuestDB JDBC probe returned an unexpected result");
        }
    }

    private long awaitSnapshotVisibility(Instant snapshot, int expectedRows)
            throws InterruptedException {
        long deadline = System.nanoTime() + properties.getVisibilityTimeout().toNanos();
        RuntimeException lastQueryFailure = null;
        while (true) {
            try {
                Long visibleRows = jdbcTemplate.queryForObject(
                        COUNT_SNAPSHOT_SQL, Long.class, Timestamp.from(snapshot));
                if (visibleRows != null && visibleRows >= expectedRows) {
                    return visibleRows;
                }
                lastQueryFailure = null;
            } catch (RuntimeException queryFailure) {
                lastQueryFailure = queryFailure;
            }

            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for QuestDB snapshot visibility; expected "
                                + expectedRows + " rows", lastQueryFailure);
            }
            Thread.sleep(properties.getPollInterval().toMillis());
        }
    }
}
