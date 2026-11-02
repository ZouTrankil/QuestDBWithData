-- 本轮FRED请求窗口；只读六列，未提供的日期不补造。
SELECT * FROM us_market_daily WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
 AND trade_date>='2026-10-22' AND trade_date<'2026-10-31'
ORDER BY ts_code,trade_date LIMIT 100001;

-- CBOE已有30日；没有新请求，与FRED保持独立。
SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX' AND trade_date='2026-10-30'
ORDER BY trade_date,symbol,provider LIMIT 100001;

SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables()
WHERE name IN ('us_equity_daily','us_equity_intraday','us_market_daily','us_index_daily','crypto_ohlcv','crypto_data_gap_events') ORDER BY name;
