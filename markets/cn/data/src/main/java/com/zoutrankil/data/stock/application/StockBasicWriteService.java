package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.BusinessTime;
import com.zoutrankil.data.stock.port.StockBasicTarget;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Applies snapshot timing, bounded execution and verification to the stock-basic write target. */
@Service
public final class StockBasicWriteService {
    private final StockBasicTarget target;
    private final QuestDbProperties properties;
    private final BusinessTime businessTime;
    public StockBasicWriteService(StockBasicTarget target, QuestDbProperties properties, BusinessTime businessTime) {
        this.target=target; this.properties=properties; this.businessTime=businessTime;
    }
    public StockBasicSyncReport storeAndVerify(List<StockBasic> stocks) throws InterruptedException {
        StockBasicDataset.DEFINITION.requireCapability(DatasetDefinition.Capability.WRITE);
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
        StockBasicDataset.DEFINITION.requireCapability(DatasetDefinition.Capability.WRITE);
        var rows = stocks.stream().map(stock -> new StockBasicSnapshot(snapshot, stock)).iterator();
        var policy = new VerifiedBatchExecutor.Policy(properties.getWriteBatchRows(),
                properties.getWriteBatchBytes() / 4, properties.getWriteMaxBatches(),
                properties.getVisibilityTimeout(), properties.getPollInterval());
        var port = target.newConfiguredWriter(properties.getWriteBatchBytes(), properties.getVisibilityTimeout());
        return new VerifiedBatchExecutor<>(policy, port.codec(), port).execute(rows);
    }

    public void verifyConnection() { target.verifyConnection(); }
}
