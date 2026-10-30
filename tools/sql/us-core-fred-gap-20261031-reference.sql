-- 29 日目前只有 VIXCLS 一行、六列；SP500 尚未提供，不补造。
SELECT * FROM us_market_daily
WHERE ts_code IN ('SP500.US','VIXCLS.US') AND trade_date='2026-10-29'
ORDER BY ts_code,trade_date LIMIT 100001;

-- 冻结来源窗口：SP500 五日、VIXCLS 六日，共十一行。
SELECT * FROM us_market_daily
WHERE ts_code IN ('SP500.US','VIXCLS.US')
  AND trade_date>='2026-10-22' AND trade_date<'2026-10-30'
ORDER BY ts_code,trade_date LIMIT 100001;

-- WAL 结合原文、六列键值和 Native 使用，不能单独证明完整。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables()
WHERE name IN ('us_market_daily','us_equity_daily','us_equity_intraday','crypto_ohlcv') ORDER BY name;
