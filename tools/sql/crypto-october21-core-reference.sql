-- 只读已验收的 UTC 单日；按交易所、币对、现货/永续及频率分开，保留原值和来源。
SELECT ts,exchange,symbol,instrument,interval_minutes,
       open,high,low,close,volume,quote_volume,source_sha256,observed_at
FROM crypto_ohlcv
WHERE ts>='2026-10-21' AND ts<'2026-10-22'
  AND exchange IN ('BINANCE','BYBIT')
  AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL')
  AND interval_minutes IN (60,1440)
ORDER BY ts,exchange,symbol,instrument,interval_minutes;
