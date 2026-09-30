package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.domain.BacktestDailyCacheCoverage;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.table.BacktestDailyCacheCoverageRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Typed persistence mapping for all four fields in the Python coverage receipt. */
public final class BacktestDailyCacheCoverageMapper {
    public BacktestDailyCacheCoverage fromStorage(BacktestDailyCacheCoverageRow row) {
        return new BacktestDailyCacheCoverage(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(),
                row.sourceVersion(), row.rowCount(), row.contentDigest());
    }

    public DatasetValues values(BacktestDailyCacheCoverage row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("source_version", row.sourceVersion());
        values.put("row_count", row.rowCount());
        values.put("content_digest", row.contentDigest());
        return new DatasetValues(values);
    }

    public BacktestDailyCacheCoverage fromValues(DatasetValues values) {
        return new BacktestDailyCacheCoverage(values.get("trade_date", LocalDate.class),
                values.get("source_version", String.class), values.get("row_count", Long.class),
                values.get("content_digest", String.class));
    }
}
