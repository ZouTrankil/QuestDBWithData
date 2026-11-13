-- 原八产品UTC十一月十二日完整十三列。
SELECT * FROM crypto_ohlcv WHERE exchange IN ('BINANCE','BYBIT')
  AND symbol IN ('BTCUSDT','ETHUSDT') AND instrument IN ('SPOT','USDT_PERPETUAL')
  AND interval_minutes IN (60,1440) AND ts>='2026-11-12' AND ts<'2026-11-13'
ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv
WHERE exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
  AND ts>='2026-11-12' AND ts<'2026-11-13'
GROUP BY exchange,symbol,instrument,interval_minutes
ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;
