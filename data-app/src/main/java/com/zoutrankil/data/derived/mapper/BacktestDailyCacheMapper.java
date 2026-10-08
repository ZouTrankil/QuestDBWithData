package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.BacktestDaily;
import com.zoutrankil.data.domain.BacktestDailyCache;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.table.BacktestDailyCacheRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.util.LinkedHashMap;

/** Explicit mapping of all 14 physical cache columns. */
public final class BacktestDailyCacheMapper {
    private final BacktestDailyMapper dailyMapper = new BacktestDailyMapper();

    public BacktestDailyCache fromStorage(BacktestDailyCacheRow row) {
        return new BacktestDailyCache(new BacktestDaily(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(), row.tsCode(),
                row.open(), row.high(), row.low(), row.close(), row.vol(), row.amount(), row.adjFactor(),
                row.upLimit(), row.downLimit(), row.isSuspended(), row.isSt()), row.sourceVersion());
    }

    public DatasetValues values(BacktestDailyCache row) {
        var values = new LinkedHashMap<>(dailyMapper.values(row.daily()).asMap());
        values.put("source_version", row.sourceVersion());
        return new DatasetValues(values);
    }

    public BacktestDailyCache fromValues(DatasetValues values) {
        return new BacktestDailyCache(dailyMapper.fromValues(values),
                values.get("source_version", String.class));
    }
}
