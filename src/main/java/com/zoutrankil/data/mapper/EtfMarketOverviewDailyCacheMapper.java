package com.zoutrankil.data.mapper;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfMarketOverviewDailyCache;
import com.zoutrankil.data.domain.table.EtfMarketOverviewDailyCacheRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Five physical cache columns pass through without rescaling shares, amounts, nulls or count types. */
public final class EtfMarketOverviewDailyCacheMapper {
    public EtfMarketOverviewDailyCache fromStorage(EtfMarketOverviewDailyCacheRow row) {
        Objects.requireNonNull(row, "ETF cache row required");
        return new EtfMarketOverviewDailyCache(TemporalValues.CalendarTimestamp.fromStorage(
                Objects.requireNonNull(row.tradeDate(), "trade date required")).date(),
                Objects.requireNonNull(row.etfCount(), "etf_count required"),
                row.totalShare(), row.totalSizeYi(), row.sourceVersion());
    }

    public DatasetValues values(EtfMarketOverviewDailyCache row) {
        Objects.requireNonNull(row, "ETF cache row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate()); values.put("etf_count", row.etfCount());
        values.put("total_share", row.totalShare()); values.put("total_size_yi", row.totalSizeYi());
        values.put("source_version", row.sourceVersion());
        return new DatasetValues(values);
    }

    public EtfMarketOverviewDailyCache fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "ETF cache values required");
        return new EtfMarketOverviewDailyCache(values.get("trade_date", LocalDate.class),
                Objects.requireNonNull(values.get("etf_count", Long.class), "etf_count required"),
                values.get("total_share", Double.class), values.get("total_size_yi", Double.class),
                values.get("source_version", String.class));
    }
}
