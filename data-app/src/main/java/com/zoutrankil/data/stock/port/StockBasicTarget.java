package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
public interface StockBasicTarget {
    String tableName();
    String targetId() throws Exception;
    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newWriter();
    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newConfiguredWriter(int maxBatchBytes, java.time.Duration timeout);
    void verifyConnection();
}
