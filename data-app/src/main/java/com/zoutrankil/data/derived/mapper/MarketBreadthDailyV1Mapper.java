package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarketBreadthDailyV1;
import com.zoutrankil.data.domain.materializedview.MarketBreadthDailyV1MaterializedView;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit mapping of every physical daily breadth MV column. */
public final class MarketBreadthDailyV1Mapper {
    public MarketBreadthDailyV1 fromStorage(MarketBreadthDailyV1MaterializedView row) {
        return new MarketBreadthDailyV1(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(),
                row.stockCount(), row.upCount(), row.downCount(), row.flatCount(),
                row.avgPctChange(), row.totalAmountYi());
    }

    public DatasetValues values(MarketBreadthDailyV1 row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("trade_date", row.tradeDate());
        result.put("stock_count", row.stockCount());
        result.put("up_count", row.upCount());
        result.put("down_count", row.downCount());
        result.put("flat_count", row.flatCount());
        result.put("avg_pct_change", row.avgPctChange());
        result.put("total_amount_yi", row.totalAmountYi());
        return new DatasetValues(result);
    }

    public MarketBreadthDailyV1 fromValues(DatasetValues values) {
        return new MarketBreadthDailyV1(values.get("trade_date", LocalDate.class),
                values.get("stock_count", Long.class), values.get("up_count", Long.class),
                values.get("down_count", Long.class), values.get("flat_count", Long.class),
                values.get("avg_pct_change", Double.class), values.get("total_amount_yi", Double.class));
    }
}
