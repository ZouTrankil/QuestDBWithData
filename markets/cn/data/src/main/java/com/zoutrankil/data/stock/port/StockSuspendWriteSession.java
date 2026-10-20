package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** One independently owned writer, with explicit admission for a private replacement stage. */
public interface StockSuspendWriteSession
        extends VerifiedWriteSession<StockSuspend, StockSuspendKey> {
    /** 显式声明物理布局；未声明的旧会话固定 v1，行编码继续沿用原逻辑 trade_date。 */
    interface Versioned extends StockSuspendWriteSession {
        int datasetVersion();
    }

    StockSuspendWriteSession forTarget(String stageTable, String stagePhysicalTargetId);
}
