package com.zoutrankil.data.mapper;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.RetailSentimentDailyCache;
import com.zoutrankil.data.domain.table.RetailSentimentDailyCacheRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

/** All fourteen physical fields pass through; invalid historical null counts are never filled with zero. */
public final class RetailSentimentDailyCacheMapper {
    public RetailSentimentDailyCache fromStorage(RetailSentimentDailyCacheRow row) {
        Objects.requireNonNull(row, "cache row required");
        return new RetailSentimentDailyCache(TemporalValues.CalendarTimestamp.fromStorage(
                Objects.requireNonNull(row.tradeDate(), "trade date required")).date(),
                row.avgRetailRatio(), row.avgRetailEntropy(), row.totalRetailAmountYi(), row.totalRetailNetInflowYi(),
                row.avgRelAggro(), Objects.requireNonNull(row.totalQ1(), "total_q1 required"),
                Objects.requireNonNull(row.totalQ3(), "total_q3 required"), row.avgWashTradeRatio(),
                Objects.requireNonNull(row.totalSpoofCount(), "total_spoof_count required"),
                Objects.requireNonNull(row.totalManipulationCount(), "total_manipulation_count required"),
                row.avgMfiScore(), row.totalMainNetYi(), row.sourceVersion());
    }

    public DatasetValues values(RetailSentimentDailyCache row) {
        Objects.requireNonNull(row, "cache row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate()); values.put("avg_retail_ratio", row.avgRetailRatio());
        values.put("avg_retail_entropy", row.avgRetailEntropy()); values.put("total_retail_amount_yi", row.totalRetailAmountYi());
        values.put("total_retail_net_inflow_yi", row.totalRetailNetInflowYi()); values.put("avg_rel_aggro", row.avgRelAggro());
        values.put("total_q1", row.totalQ1()); values.put("total_q3", row.totalQ3());
        values.put("avg_wash_trade_ratio", row.avgWashTradeRatio()); values.put("total_spoof_count", row.totalSpoofCount());
        values.put("total_manipulation_count", row.totalManipulationCount()); values.put("avg_mfi_score", row.avgMfiScore());
        values.put("total_main_net_yi", row.totalMainNetYi()); values.put("source_version", row.sourceVersion());
        return new DatasetValues(values);
    }

    public RetailSentimentDailyCache fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "cache values required");
        return new RetailSentimentDailyCache(values.get("trade_date", LocalDate.class),
                values.get("avg_retail_ratio", Double.class), values.get("avg_retail_entropy", Double.class),
                values.get("total_retail_amount_yi", Double.class), values.get("total_retail_net_inflow_yi", Double.class),
                values.get("avg_rel_aggro", Double.class), Objects.requireNonNull(values.get("total_q1", Long.class), "total_q1 required"),
                Objects.requireNonNull(values.get("total_q3", Long.class), "total_q3 required"), values.get("avg_wash_trade_ratio", Double.class),
                Objects.requireNonNull(values.get("total_spoof_count", Long.class), "total_spoof_count required"),
                Objects.requireNonNull(values.get("total_manipulation_count", Long.class), "total_manipulation_count required"),
                values.get("avg_mfi_score", Double.class), values.get("total_main_net_yi", Double.class), values.get("source_version", String.class));
    }
}
