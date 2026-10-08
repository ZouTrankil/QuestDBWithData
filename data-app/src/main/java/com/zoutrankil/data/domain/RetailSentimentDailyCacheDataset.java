package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D100 raw historical cache compatibility; Python remains the only data and coverage publisher. */
public final class RetailSentimentDailyCacheDataset {
    private RetailSentimentDailyCacheDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Python SAMPLE BY daily aggregation date; exact UTC-midnight TIMESTAMP carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "retail_sentiment_daily_cache", 1, "python.derived.market_barometer_cache",
            "python.MarketBarometerReadThroughCache._publish", "retail_sentiment_daily_cache", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Daily calendar bucket in the complete date/source-generation key", TRADE_DATE),
                    new Column("avg_retail_ratio", "avg_retail_ratio", "avg_retail_ratio", StorageType.DOUBLE, true,
                            "Mean GMM retail volume ratio; dimensionless", null),
                    new Column("avg_retail_entropy", "avg_retail_entropy", "avg_retail_entropy", StorageType.DOUBLE, true,
                            "Mean retail Shannon time entropy in bits", null),
                    new Column("total_retail_amount_yi", "total_retail_amount_yi", "total_retail_amount_yi", StorageType.DOUBLE, true,
                            "Yi yuan; source yuan converted by Python aggregate exactly once", null),
                    new Column("total_retail_net_inflow_yi", "total_retail_net_inflow_yi", "total_retail_net_inflow_yi", StorageType.DOUBLE, true,
                            "Signed retail net inflow in yi yuan; no second conversion", null),
                    new Column("avg_rel_aggro", "avg_rel_aggro", "avg_rel_aggro", StorageType.DOUBLE, true,
                            "Mean relative aggression; source metric scale retained", null),
                    new Column("total_q1", "total_q1", "total_q1", StorageType.LONG, false,
                            "Required exact sum of Q1 windows with inflow and price rise", null),
                    new Column("total_q3", "total_q3", "total_q3", StorageType.LONG, false,
                            "Required exact sum of Q3 windows with outflow and price decline", null),
                    new Column("avg_wash_trade_ratio", "avg_wash_trade_ratio", "avg_wash_trade_ratio", StorageType.DOUBLE, true,
                            "Mean wash trade ratio; dimensionless", null),
                    new Column("total_spoof_count", "total_spoof_count", "total_spoof_count", StorageType.LONG, false,
                            "Required exact severe spoofing count", null),
                    new Column("total_manipulation_count", "total_manipulation_count", "total_manipulation_count", StorageType.LONG, false,
                            "Required rowwise fake-support plus fake-pressure sum; no COALESCE or split SUM", null),
                    new Column("avg_mfi_score", "avg_mfi_score", "avg_mfi_score", StorageType.DOUBLE, true,
                            "Mean main force intensity; source score scale retained", null),
                    new Column("total_main_net_yi", "total_main_net_yi", "total_main_net_yi", StorageType.DOUBLE, true,
                            "Signed main net inflow in yi yuan; no second conversion", null),
                    new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                            "Complete lowercase SHA-256 of Python product SQL and relevant source identity/partition state", null)),
            List.of("trade_date", "source_version"), List.of("trade_date", "source_version"), "trade_date",
            Partition.MONTH, true, Set.of(Capability.READ), List.of(),
            "Retained compatibility for Python's MONTH/WAL/DEDUP historical cache. Active native alias bypasses this publisher. "
                    + "Python remains the sole cache and coverage owner; Java adds no competing writer or job. "
                    + "Four required LONG counts follow the actual Python model, while eight DOUBLE fields retain nulls. "
                    + "Raw historical rows alone do not certify a published receipt, current freshness or production latest. "
                    + "Pagination binds actual table identity and physical/WAL frontier, independently of business source_version.");
}
