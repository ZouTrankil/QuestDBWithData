-- 每条源的实际提供日期以冻结原文为准；不把股票日历当作宏观完整证明。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
 AND trade_date>='2026-10-22' AND trade_date<'2026-10-31' ORDER BY ts_code,trade_date LIMIT 100001;
-- CBOE独立保存，不能补进FRED。
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'
 AND trade_date>='2026-10-22' AND trade_date<'2026-10-31' ORDER BY trade_date,symbol,provider LIMIT 100001;
