-- FRED 本次只有三行：NASDAQCOM 两行、VIXCLS 一行；SP500 不补造。
SELECT * FROM us_market_daily
WHERE ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
  AND trade_date >= '2026-10-22' AND trade_date < '2026-10-24'
ORDER BY ts_code, trade_date
LIMIT 100001;

-- CBOE 原来源的 VIX 两条收盘点位，与 FRED 保持分开。
SELECT * FROM us_index_daily
WHERE provider = 'CBOE' AND symbol = 'VIX'
  AND trade_date >= '2026-10-22' AND trade_date < '2026-10-24'
ORDER BY trade_date, symbol, provider
LIMIT 100001;

-- WAL 与全量业务键值、真实来源及缺口证明一起使用。
SELECT name, writerTxn, sequencerTxn, bufferedTxnSize, suspended
FROM wal_tables()
WHERE name IN ('us_market_daily','us_index_daily')
ORDER BY name;
