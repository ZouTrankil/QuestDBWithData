package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarketBreadthDailyCache;
import com.zoutrankil.data.domain.table.MarketBreadthDailyCacheRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Explicit mapping of all eight versioned cache fields without unit or null coercion. */
public final class MarketBreadthDailyCacheMapper {
    public MarketBreadthDailyCache fromStorage(MarketBreadthDailyCacheRow row) {
        Objects.requireNonNull(row, "cache row required");
        return new MarketBreadthDailyCache(
                TemporalValues.CalendarTimestamp.fromStorage(Objects.requireNonNull(row.tradeDate(), "trade date required")).date(),
                Objects.requireNonNull(row.stockCount(), "stock count required"),
                Objects.requireNonNull(row.upCount(), "up count required"),
                Objects.requireNonNull(row.downCount(), "down count required"),
                Objects.requireNonNull(row.flatCount(), "flat count required"),
                row.avgPctChange(), row.totalAmountYi(), row.sourceVersion());
    }

    public DatasetValues values(MarketBreadthDailyCache row) {
        Objects.requireNonNull(row, "cache row required");
        var result = new LinkedHashMap<String, Object>();
        result.put("trade_date", row.tradeDate());
        result.put("stock_count", row.stockCount());
        result.put("up_count", row.upCount());
        result.put("down_count", row.downCount());
        result.put("flat_count", row.flatCount());
        result.put("avg_pct_change", row.avgPctChange());
        result.put("total_amount_yi", row.totalAmountYi());
        result.put("source_version", row.sourceVersion());
        return new DatasetValues(result);
    }

    public MarketBreadthDailyCache fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "cache values required");
        return new MarketBreadthDailyCache(values.get("trade_date", LocalDate.class),
                Objects.requireNonNull(values.get("stock_count", Long.class), "stock count required"),
                Objects.requireNonNull(values.get("up_count", Long.class), "up count required"),
                Objects.requireNonNull(values.get("down_count", Long.class), "down count required"),
                Objects.requireNonNull(values.get("flat_count", Long.class), "flat count required"),
                values.get("avg_pct_change", Double.class), values.get("total_amount_yi", Double.class),
                values.get("source_version", String.class));
    }
}
