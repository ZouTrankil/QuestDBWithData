-- 来源窗口 10 月 22—29 日：FRED 16 行，含本轮新增 13 行。
-- NASDAQCOM 至 29 日，SP500、VIXCLS 至 28 日，29 日缺口保留。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
  AND trade_date>='2026-10-22' AND trade_date<'2026-10-30'
ORDER BY ts_code,trade_date LIMIT 100001;

-- CBOE 独立七列，共 6 行，含新增 4 行；不能当作 FRED 记录。
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'
  AND trade_date>='2026-10-22' AND trade_date<'2026-10-30'
ORDER BY trade_date,symbol,provider LIMIT 100001;
