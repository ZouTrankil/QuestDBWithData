package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** One independently owned writer, with explicit admission for a private replacement stage. */
public interface StockSuspendWriteSession
        extends VerifiedWriteSession<StockSuspend, StockSuspendKey> {
    StockSuspendWriteSession forTarget(String stageTable, String stagePhysicalTargetId);
}
