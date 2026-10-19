CREATE VIEW java_tushare_stock_basic_latest_qwp_test AS (
    SELECT snapshot_ts, ts_code, symbol, name, area, industry, list_date
    FROM (
        SELECT snapshot_ts, ts_code, symbol, name, area, industry, list_date,
               ROW_NUMBER() OVER (
                   PARTITION BY ts_code ORDER BY snapshot_ts DESC
               ) AS row_num
        FROM java_tushare_stock_basic_qwp_test
    ) ranked
    WHERE row_num = 1
);
