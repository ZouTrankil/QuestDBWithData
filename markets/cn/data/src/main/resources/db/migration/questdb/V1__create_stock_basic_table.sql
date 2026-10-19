CREATE TABLE java_tushare_stock_basic_qwp_test (
    snapshot_ts TIMESTAMP,
    ts_code SYMBOL,
    symbol SYMBOL,
    name STRING,
    area SYMBOL,
    industry SYMBOL,
    list_date STRING
) TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL
  DEDUP UPSERT KEYS(snapshot_ts, ts_code);
