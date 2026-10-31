-- 原八个产品单个 UTC 日，200 行十三列，含 192 根小时线和 8 根日线。
SELECT * FROM crypto_ohlcv WHERE exchange IN ('BINANCE','BYBIT')
  AND symbol IN ('BTCUSDT','ETHUSDT') AND instrument IN ('SPOT','USDT_PERPETUAL')
  AND interval_minutes IN (60,1440) AND ts>='2026-10-30' AND ts<'2026-10-31'
ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 每格小时 24 行、日线 1 行；完整性仍须结合原文、完整键值与 Native。
SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv
WHERE exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
  AND ts>='2026-10-30' AND ts<'2026-10-31'
GROUP BY exchange,symbol,instrument,interval_minutes
ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 保留原两条停市缺口，不补价格。
SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;
