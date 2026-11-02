-- 原八产品两日400行十三列：384根小时线和16根UTC日线。
SELECT * FROM crypto_ohlcv WHERE exchange IN ('BINANCE','BYBIT')
 AND symbol IN ('BTCUSDT','ETHUSDT') AND instrument IN ('SPOT','USDT_PERPETUAL')
 AND interval_minutes IN (60,1440) AND ts>='2026-10-31' AND ts<'2026-11-02'
ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 十六格小时每格48行，日线每格2行；完整性仍需原文、Native与全部字段证明。
SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv
WHERE exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
 AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
 AND ts>='2026-10-31' AND ts<'2026-11-02'
GROUP BY exchange,symbol,instrument,interval_minutes
ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 原两条官方停市小时缺口保持，不补价格。
SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;
