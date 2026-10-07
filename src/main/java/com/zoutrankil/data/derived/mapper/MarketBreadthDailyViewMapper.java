package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.mapper.*;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarketBreadthDailyView;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Explicit mapping of all seven public alias columns; no aggregate or unit conversion. */
public final class MarketBreadthDailyViewMapper {
    public MarketBreadthDailyView fromStorage(com.zoutrankil.data.domain.view.MarketBreadthDailyView row) {
        Objects.requireNonNull(row, "view row required");
        return new MarketBreadthDailyView(
                TemporalValues.CalendarTimestamp.fromStorage(Objects.requireNonNull(row.tradeDate(), "trade date required")).date(),
                Objects.requireNonNull(row.stockCount(), "stock count required"),
                Objects.requireNonNull(row.upCount(), "up count required"),
                Objects.requireNonNull(row.downCount(), "down count required"),
                Objects.requireNonNull(row.flatCount(), "flat count required"),
                row.avgPctChange(), row.totalAmountYi());
    }

    public DatasetValues values(MarketBreadthDailyView row) {
        Objects.requireNonNull(row, "view row required");
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

    public MarketBreadthDailyView fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "view values required");
        return new MarketBreadthDailyView(values.get("trade_date", LocalDate.class),
                Objects.requireNonNull(values.get("stock_count", Long.class), "stock count required"),
                Objects.requireNonNull(values.get("up_count", Long.class), "up count required"),
                Objects.requireNonNull(values.get("down_count", Long.class), "down count required"),
                Objects.requireNonNull(values.get("flat_count", Long.class), "flat count required"),
                values.get("avg_pct_change", Double.class), values.get("total_amount_yi", Double.class));
    }
}
