package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.RetailSentimentDailyView;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit mapping of all thirteen public alias columns, preserving aggregate units and nulls. */
public final class RetailSentimentDailyViewMapper {
    public RetailSentimentDailyView fromStorage(com.zoutrankil.data.domain.view.RetailSentimentDailyView row) {
        return new RetailSentimentDailyView(
                TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(),
                row.avgRetailRatio(), row.avgRetailEntropy(), row.totalRetailAmountYi(),
                row.totalRetailNetInflowYi(), row.avgRelAggro(), row.totalQ1(), row.totalQ3(),
                row.avgWashTradeRatio(), row.totalSpoofCount(), row.totalManipulationCount(),
                row.avgMfiScore(), row.totalMainNetYi());
    }

    public DatasetValues values(RetailSentimentDailyView row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("avg_retail_ratio", row.avgRetailRatio());
        values.put("avg_retail_entropy", row.avgRetailEntropy());
        values.put("total_retail_amount_yi", row.totalRetailAmountYi());
        values.put("total_retail_net_inflow_yi", row.totalRetailNetInflowYi());
        values.put("avg_rel_aggro", row.avgRelAggro());
        values.put("total_q1", row.totalQ1());
        values.put("total_q3", row.totalQ3());
        values.put("avg_wash_trade_ratio", row.avgWashTradeRatio());
        values.put("total_spoof_count", row.totalSpoofCount());
        values.put("total_manipulation_count", row.totalManipulationCount());
        values.put("avg_mfi_score", row.avgMfiScore());
        values.put("total_main_net_yi", row.totalMainNetYi());
        return new DatasetValues(values);
    }

    public RetailSentimentDailyView fromValues(DatasetValues values) {
        return new RetailSentimentDailyView(values.get("trade_date", LocalDate.class),
                values.get("avg_retail_ratio", Double.class), values.get("avg_retail_entropy", Double.class),
                values.get("total_retail_amount_yi", Double.class), values.get("total_retail_net_inflow_yi", Double.class),
                values.get("avg_rel_aggro", Double.class), values.get("total_q1", Long.class), values.get("total_q3", Long.class),
                values.get("avg_wash_trade_ratio", Double.class), values.get("total_spoof_count", Long.class),
                values.get("total_manipulation_count", Long.class), values.get("avg_mfi_score", Double.class),
                values.get("total_main_net_yi", Double.class));
    }
}
