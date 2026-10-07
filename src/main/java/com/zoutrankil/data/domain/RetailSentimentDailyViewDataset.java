package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D099 contract for the public SELECT * alias of the D098 native materialized view. */
public final class RetailSentimentDailyViewDataset {
    private RetailSentimentDailyViewDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Pass-through native SAMPLE BY 1d calendar bucket; TIMESTAMP carries the date at UTC midnight exactly");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "v_retail_sentiment_daily", 1, "python.derived.market_barometer_views",
            "retail_sentiment_daily_owner", "v_retail_sentiment_daily", ObjectKind.VIEW,
            List.of(
                    new Column("mv_retail_sentiment_daily_v1.trade_date", "trade_date", "trade_date",
                            StorageType.TIMESTAMP, false, "Calendar-day identity; no event-time conversion", TRADE_DATE),
                    new Column("mv_retail_sentiment_daily_v1.avg_retail_ratio", "avg_retail_ratio", "avg_retail_ratio",
                            StorageType.DOUBLE, true, "Mean GMM retail volume ratio; dimensionless, already aggregated", null),
                    new Column("mv_retail_sentiment_daily_v1.avg_retail_entropy", "avg_retail_entropy", "avg_retail_entropy",
                            StorageType.DOUBLE, true, "Mean retail Shannon time entropy in bits", null),
                    new Column("mv_retail_sentiment_daily_v1.total_retail_amount_yi", "total_retail_amount_yi", "total_retail_amount_yi",
                            StorageType.DOUBLE, true, "Retail turnover in yi yuan; D098 converts source yuan exactly once", null),
                    new Column("mv_retail_sentiment_daily_v1.total_retail_net_inflow_yi", "total_retail_net_inflow_yi", "total_retail_net_inflow_yi",
                            StorageType.DOUBLE, true, "Signed retail net inflow in yi yuan; no second conversion", null),
                    new Column("mv_retail_sentiment_daily_v1.avg_rel_aggro", "avg_rel_aggro", "avg_rel_aggro",
                            StorageType.DOUBLE, true, "Mean relative aggression; source metric scale retained", null),
                    new Column("mv_retail_sentiment_daily_v1.total_q1", "total_q1", "total_q1",
                            StorageType.LONG, true, "Sum of Q1 windows with net inflow and price rise; null remains null", null),
                    new Column("mv_retail_sentiment_daily_v1.total_q3", "total_q3", "total_q3",
                            StorageType.LONG, true, "Sum of Q3 windows with net outflow and price decline; null remains null", null),
                    new Column("mv_retail_sentiment_daily_v1.avg_wash_trade_ratio", "avg_wash_trade_ratio", "avg_wash_trade_ratio",
                            StorageType.DOUBLE, true, "Mean wash trade ratio; dimensionless", null),
                    new Column("mv_retail_sentiment_daily_v1.total_spoof_count", "total_spoof_count", "total_spoof_count",
                            StorageType.LONG, true, "Sum of severe spoofing windows; null remains null", null),
                    new Column("mv_retail_sentiment_daily_v1.total_manipulation_count", "total_manipulation_count", "total_manipulation_count",
                            StorageType.LONG, true, "Pass-through sum of rowwise fake support plus fake pressure; either-null row is omitted by SQL SUM", null),
                    new Column("mv_retail_sentiment_daily_v1.avg_mfi_score", "avg_mfi_score", "avg_mfi_score",
                            StorageType.DOUBLE, true, "Mean main force intensity; source score scale retained", null),
                    new Column("mv_retail_sentiment_daily_v1.total_main_net_yi", "total_main_net_yi", "total_main_net_yi",
                            StorageType.DOUBLE, true, "Signed main net inflow in yi yuan; no second conversion", null)),
            List.of("trade_date"), List.of(), "trade_date", Partition.NONE, false,
            Set.of(Capability.READ), List.of("mv_retail_sentiment_daily_v1"),
            "Ordinary SELECT * alias with one native calendar-day bucket per date. No physical partition, WAL or UPSERT key. "
                    + "Direct writes and replacements are forbidden; D098 is the single canonical refresh job. "
                    + "Read verifies the exact alias binding and its native MV validity, catch-up and stable physical source/output versions. "
                    + "All nullable SUM/AVG outputs retain their units and null values without zero filling or clamping.");
}
