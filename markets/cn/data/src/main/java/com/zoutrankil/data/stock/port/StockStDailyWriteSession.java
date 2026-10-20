package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;

/** 旧泛型会话固定 v1；新版 ST 会话显式声明布局与行编码版本。 */
public interface StockStDailyWriteSession
        extends StockDateWriteSession<StockStDaily, StockStDailyKey> {
    interface Versioned extends StockStDailyWriteSession {
        int datasetVersion();
    }
}
