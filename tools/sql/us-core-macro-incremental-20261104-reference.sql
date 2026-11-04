-- 原六/七列独立来源窗口；缺口按来源原文保留，不跨源补齐。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-04' ORDER BY ts_code,trade_date LIMIT 100001;
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-04' ORDER BY trade_date,symbol,provider LIMIT 100001;
