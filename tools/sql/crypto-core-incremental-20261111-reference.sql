-- 本轮已验收范围；完整字段及原缺口见对应清单。
SELECT * FROM crypto_ohlcv WHERE exchange IN ('BINANCE','BYBIT')
  AND symbol IN ('BTCUSDT','ETHUSDT') AND instrument IN ('SPOT','USDT_PERPETUAL')
  AND interval_minutes IN (60,1440) AND ts>='2026-11-10' AND ts<'2026-11-12'
ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv
WHERE exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
  AND ts>='2026-11-10' AND ts<'2026-11-12'
GROUP BY exchange,symbol,instrument,interval_minutes
ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;
