CREATE MATERIALIZED VIEW 'mv_market_breadth_daily_v1' WITH BASE 'stk_factor' REFRESH EVERY 1m START '2026-09-18T15:32:22.866888Z' AS (
SELECT
    trade_date,
    count() AS stock_count,
    sum(CASE WHEN pct_change > 0 THEN 1 ELSE 0 END) AS up_count,
    sum(CASE WHEN pct_change < 0 THEN 1 ELSE 0 END) AS down_count,
    sum(CASE WHEN pct_change = 0 THEN 1 ELSE 0 END) AS flat_count,
    avg(pct_change) AS avg_pct_change,
    sum(amount) / 100000.0 AS total_amount_yi
FROM stk_factor

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;

CREATE MATERIALIZED VIEW 'mv_retail_sentiment_daily_v1' WITH BASE 'l2_daily_features' REFRESH EVERY 1m START '2026-09-18T15:32:22.955712Z' AS (
SELECT
    ts AS trade_date,
    avg(gmm_retail_ratio) AS avg_retail_ratio,
    avg(mean_retail_entropy) AS avg_retail_entropy,
    sum(retail_total_amount) / 100000000.0 AS total_retail_amount_yi,
    sum(retail_funds_net_inflow) / 100000000.0 AS total_retail_net_inflow_yi,
    avg(mean_rel_aggro) AS avg_rel_aggro,
    sum(q1_count) AS total_q1,
    sum(q3_count) AS total_q3,
    avg(wash_trade_ratio) AS avg_wash_trade_ratio,
    sum(spoof_count) AS total_spoof_count,
    sum(fake_support_count + fake_pressure_count) AS total_manipulation_count,
    avg(mfi_score) AS avg_mfi_score,
    sum(main_net_inflow) / 100000000.0 AS total_main_net_yi
FROM l2_daily_features

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;
