package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.BacktestDailyViewValue;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.view.BacktestDailyView;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit mapping of the view's 13 columns, including promoted LONG suspension flag. */
public final class BacktestDailyViewMapper {
    public BacktestDailyViewValue fromStorage(BacktestDailyView row) {
        return new BacktestDailyViewValue(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(), row.tsCode(),
                row.open(), row.high(), row.low(), row.close(), row.vol(), row.amount(), row.adjFactor(),
                row.upLimit(), row.downLimit(), row.isSuspended(), row.isSt());
    }

    public DatasetValues values(BacktestDailyViewValue row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("ts_code", row.tsCode());
        values.put("open", row.open());
        values.put("high", row.high());
        values.put("low", row.low());
        values.put("close", row.close());
        values.put("vol", row.vol());
        values.put("amount", row.amount());
        values.put("adj_factor", row.adjFactor());
        values.put("up_limit", row.upLimit());
        values.put("down_limit", row.downLimit());
        values.put("is_suspended", row.isSuspended());
        values.put("is_st", row.isSt());
        return new DatasetValues(values);
    }

    public BacktestDailyViewValue fromValues(DatasetValues values) {
        return new BacktestDailyViewValue(values.get("trade_date", LocalDate.class), values.get("ts_code", String.class),
                values.get("open", Double.class), values.get("high", Double.class),
                values.get("low", Double.class), values.get("close", Double.class),
                values.get("vol", Double.class), values.get("amount", Double.class),
                values.get("adj_factor", Double.class), values.get("up_limit", Double.class),
                values.get("down_limit", Double.class), values.get("is_suspended", Long.class),
                values.get("is_st", Integer.class));
    }
}
