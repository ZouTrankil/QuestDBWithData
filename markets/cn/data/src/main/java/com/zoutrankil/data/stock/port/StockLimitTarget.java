package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.StockLimit;
import com.zoutrankil.data.domain.stock.market.StockLimitKey;
import com.zoutrankil.data.stock.domain.StockTargetRange;

public interface StockLimitTarget {
    String tableName();

    String targetId();

    StockTargetRange range();

    StockDateWriteSession<StockLimit, StockLimitKey> newWriter(String frozenTargetId);
}
