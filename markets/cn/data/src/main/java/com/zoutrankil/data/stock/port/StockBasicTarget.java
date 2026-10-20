package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshot;
import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshotKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.time.Duration;

public interface StockBasicTarget {
    String tableName();

    String targetId() throws Exception;

    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newWriter();

    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newConfiguredWriter(
            int maxBatchBytes, Duration timeout);

    void verifyConnection();
}
