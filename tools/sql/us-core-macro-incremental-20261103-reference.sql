-- FRED窗口22行六列；SP500、VIXCLS尚未提供11月2日，缺口保留。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-03' ORDER BY ts_code,trade_date LIMIT 100001;
-- 独立CBOE八行七列，不补成FRED。
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-03' ORDER BY trade_date,symbol,provider LIMIT 100001;
