-- FRED 原六列；仅查询两个已结束的纽约日期，来源缺值不补零。
SELECT ts_code,trade_date,close,source_id,source_url,update_time
FROM us_market_daily
WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
  AND trade_date >= '2026-10-20T00:00:00Z'
  AND trade_date < '2026-10-22T00:00:00Z'
ORDER BY ts_code,trade_date
LIMIT 101;

-- CBOE 独立七列；保留每行实际原件摘要及取得时刻，不覆盖 FRED。
SELECT trade_date,symbol,provider,close,value_unit,source_sha256,observed_at
FROM us_index_daily
WHERE symbol = 'VIX' AND provider = 'CBOE'
  AND trade_date >= '2026-10-20T00:00:00Z'
  AND trade_date < '2026-10-22T00:00:00Z'
ORDER BY trade_date,symbol,provider
LIMIT 101;
