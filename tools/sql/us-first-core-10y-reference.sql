-- 首批核心十六个标的，复用已验收的 TIINGO 来源，保留原始二十列。
-- 日期是交易日标记，范围固定到 2026-10-21；保留来源零量报价。
-- 供应商调整口径不证明每天可交易，也不证明当时可见信息。
SELECT
    trade_date,
    symbol,
    provider,
    venue,
    open,
    high,
    low,
    close,
    volume,
    adj_open,
    adj_high,
    adj_low,
    adj_close,
    adj_volume,
    dividend,
    split_factor,
    currency,
    raw_price_basis,
    source_sha256,
    observed_at
FROM us_equity_daily
WHERE provider = 'TIINGO'
  AND symbol IN (
      'SPY', 'QQQ', 'IWM', 'TLT', 'GLD', 'USO',
      'AAPL', 'MSFT', 'NVDA', 'AMZN', 'GOOGL', 'META',
      'TSLA', 'AVGO', 'JPM', 'WMT'
  )
  AND trade_date >= '2016-10-16'
  AND trade_date < '2026-10-22'
ORDER BY trade_date, symbol, provider
LIMIT 100001;
