package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.mapper.*;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfMarketOverviewDailyView;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Explicit four-column direct-view mapping, with no rounding, scaling or null filling. */
public final class EtfMarketOverviewDailyViewMapper {
    public EtfMarketOverviewDailyView fromStorage(
            com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView row) {
        Objects.requireNonNull(row, "ETF overview view row required");
        return new EtfMarketOverviewDailyView(TemporalValues.CalendarTimestamp.fromStorage(
                Objects.requireNonNull(row.tradeDate(), "trade date required")).date(),
                Objects.requireNonNull(row.etfCount(), "etf_count required"),
                row.totalShare(), row.totalSizeYi());
    }

    public DatasetValues values(EtfMarketOverviewDailyView row) {
        Objects.requireNonNull(row, "ETF overview view row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("etf_count", row.etfCount());
        values.put("total_share", row.totalShare());
        values.put("total_size_yi", row.totalSizeYi());
        return new DatasetValues(values);
    }

    public EtfMarketOverviewDailyView fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "ETF overview view values required");
        return new EtfMarketOverviewDailyView(values.get("trade_date", LocalDate.class),
                Objects.requireNonNull(values.get("etf_count", Long.class), "etf_count required"),
                values.get("total_share", Double.class), values.get("total_size_yi", Double.class));
    }
}
