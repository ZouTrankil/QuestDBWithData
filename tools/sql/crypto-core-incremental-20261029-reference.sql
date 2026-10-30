-- 原八个产品四个 UTC 日，800 行十三列，含 768 根小时线和 32 根日线。
SELECT * FROM crypto_ohlcv WHERE exchange IN ('BINANCE','BYBIT')
  AND symbol IN ('BTCUSDT','ETHUSDT') AND instrument IN ('SPOT','USDT_PERPETUAL')
  AND interval_minutes IN (60,1440) AND ts>='2026-10-26' AND ts<'2026-10-30'
ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 每格小时 96 行、日线 4 行；完整性仍须结合原文、完整键值与 Native。
SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv
WHERE exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
  AND ts>='2026-10-26' AND ts<'2026-10-30'
GROUP BY exchange,symbol,instrument,interval_minutes
ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 保留原两条停市缺口，不补价格。
SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;
