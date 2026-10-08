package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.StockFactor;
import com.zoutrankil.data.domain.StockFactorKey;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

public interface StockFactorTarget {
    String tableName();
    String targetId();
    StockTargetRange range(String tsCode);
    VerifiedWriteSession<StockFactor, StockFactorKey> newWriter(String frozenTargetId);
}
