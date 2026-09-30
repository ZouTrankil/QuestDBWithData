CREATE TABLE java_d008_daily_basic (
    ts_code SYMBOL,
    trade_date TIMESTAMP,
    close DOUBLE,
    turnover_rate DOUBLE,
    turnover_rate_f DOUBLE,
    volume_ratio DOUBLE,
    pe DOUBLE,
    pe_ttm DOUBLE,
    pb DOUBLE,
    ps DOUBLE,
    ps_ttm DOUBLE,
    dv_ratio DOUBLE,
    dv_ttm DOUBLE,
    total_share DOUBLE,
    float_share DOUBLE,
    free_share DOUBLE,
    total_mv DOUBLE,
    circ_mv DOUBLE
) TIMESTAMP(trade_date) PARTITION BY YEAR WAL
  DEDUP UPSERT KEYS(ts_code, trade_date);
