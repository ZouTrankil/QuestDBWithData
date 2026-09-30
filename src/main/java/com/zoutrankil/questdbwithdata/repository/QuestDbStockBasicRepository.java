package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import com.zoutrankil.questdbwithdata.domain.StockBasicLatest;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshot;
import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.StockBasicDataset;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import com.zoutrankil.questdbwithdata.domain.temporal.BusinessTime;
import java.util.List;
import java.util.Map;

/** Writes through QWP and reads through PGWire/JDBC. */
@Repository
public class QuestDbStockBasicRepository implements StockBasicLatestRepository, DatasetImplementation {
    public static final String TABLE = StockBasicDataset.DEFINITION.objectName();
    public static final String LATEST_VIEW = StockBasicDataset.LATEST.objectName();

    private final JdbcTemplate jdbcTemplate;
    private final QuestDB questDB;
    private final QuestDbProperties properties;
    private final QuestDbBoundedReader boundedReader;
    private final BusinessTime businessTime;

    public QuestDbStockBasicRepository(
            JdbcTemplate jdbcTemplate,
            @Lazy QuestDB questDB,
            QuestDbProperties properties, QuestDbBoundedReader boundedReader, BusinessTime businessTime) {
        this.jdbcTemplate = jdbcTemplate;
        this.questDB = questDB;
        this.properties = properties;
        this.boundedReader = boundedReader;
        this.businessTime = businessTime;
    }

    public StockBasicSyncReport storeAndVerify(List<StockBasic> stocks) throws InterruptedException {
        definition().requireCapability(DatasetDefinition.Capability.WRITE);
        Instant snapshot = businessTime.todaySnapshotMarker();
        var result = writeDetailed(stocks, snapshot);
        if (result.status() != VerifiedBatchExecutor.Status.VERIFIED
                && result.status() != VerifiedBatchExecutor.Status.EMPTY) {
            throw new IllegalStateException("QuestDB batch not verified: " + result.status() + "; " + result.reason()
                    + "; submitted=" + result.submittedRows() + "; verified=" + result.verifiedRows()
                    + "; do not replay an in-doubt batch");
        }
        return new StockBasicSyncReport(result.submittedRows(), result.verifiedRows(), snapshot);
    }

    public VerifiedBatchExecutor.Result writeDetailed(List<StockBasic> stocks, Instant snapshot) {
        definition().requireCapability(DatasetDefinition.Capability.WRITE);
        var rows = stocks.stream().map(stock -> new StockBasicSnapshot(snapshot, stock)).iterator();
        var policy = new VerifiedBatchExecutor.Policy(properties.getWriteBatchRows(),
                properties.getWriteBatchBytes() / 4, properties.getWriteMaxBatches(),
                properties.getVisibilityTimeout(), properties.getPollInterval());
        var port = new StockBasicWritePort(TABLE, jdbcTemplate, questDB,
                properties.getWriteBatchBytes(), properties.getVisibilityTimeout());
        return new VerifiedBatchExecutor<>(policy, StockBasicWritePort.CODEC, port).execute(rows);
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
