-- 逐源核对实际宏观日期和完整字段。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-07' ORDER BY ts_code,trade_date LIMIT 100001;
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'
 AND trade_date>='2026-10-22' AND trade_date<'2026-11-07' ORDER BY trade_date,symbol,provider LIMIT 100001;
