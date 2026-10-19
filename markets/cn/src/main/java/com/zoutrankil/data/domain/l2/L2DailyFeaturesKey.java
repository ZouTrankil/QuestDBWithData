package com.zoutrankil.data.domain.l2;

import java.time.LocalDate;
import java.util.Objects;

/** 日频 L2 的业务身份由 ts 承载的日历日期和证券代码共同确定。 */
public record L2DailyFeaturesKey(LocalDate tradeDate, String symbol) {
    public L2DailyFeaturesKey {
        Objects.requireNonNull(tradeDate, "trade date required");
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical L2 stock symbol required");
    }
}
