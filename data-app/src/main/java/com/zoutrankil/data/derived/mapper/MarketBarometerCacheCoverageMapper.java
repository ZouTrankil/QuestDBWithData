package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarketBarometerCacheCoverage;
import com.zoutrankil.data.domain.table.MarketBarometerCacheCoverageRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit mapping for all five barometer cache coverage fields. */
public final class MarketBarometerCacheCoverageMapper {
    public MarketBarometerCacheCoverage fromStorage(MarketBarometerCacheCoverageRow row) {
        return new MarketBarometerCacheCoverage(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(), row.datasetId(),
                row.sourceVersion(), row.rowCount(), row.contentDigest());
    }

    public DatasetValues values(MarketBarometerCacheCoverage row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("dataset_id", row.datasetId());
        values.put("source_version", row.sourceVersion());
        values.put("row_count", row.rowCount());
        values.put("content_digest", row.contentDigest());
        return new DatasetValues(values);
    }

    public MarketBarometerCacheCoverage fromValues(DatasetValues values) {
        return new MarketBarometerCacheCoverage(values.get("trade_date", LocalDate.class),
                values.get("dataset_id", String.class), values.get("source_version", String.class),
                values.get("row_count", Long.class), values.get("content_digest", String.class));
    }
}
