-- 2026-10-16 SPY/QQQ 股票持仓参照；按已验收来源读取正量报价。
-- 四个已知美国挂牌前窗口排除，原表与零量报价保持原样。
-- FISV 使用已验收的永久价格键；price_symbol 保留原表实际请求代码。
-- 这是固定持仓参照和供应商调整口径，不是历史指数成分或当时可见信息证明。
SELECT
    d.trade_date,
    d.symbol,
    d.symbol AS price_symbol,
    d.provider,
    d.venue,
    d.open,
    d.high,
    d.low,
    d.close,
    d.volume,
    d.adj_open,
    d.adj_high,
    d.adj_low,
    d.adj_close,
    d.adj_volume,
    d.dividend,
    d.split_factor,
    d.currency,
    d.raw_price_basis,
    d.source_sha256,
    d.observed_at
FROM us_equity_daily d
JOIN (
    SELECT DISTINCT symbol
    FROM us_etf_equity_holdings
    WHERE asof_date = '2026-10-16' AND fund IN ('SPY', 'QQQ')
) h ON d.symbol = h.symbol
WHERE d.provider = 'YAHOO'
  AND d.symbol <> 'FISV'
  AND d.trade_date >= '2006-01-01'
  AND d.trade_date <= '2026-10-16'
  AND d.volume > 0
  AND (d.symbol <> 'AMCR' OR d.trade_date >= '2019-06-11')
  AND (d.symbol <> 'SW' OR d.trade_date >= '2024-07-08')
  AND (d.symbol <> 'FER' OR d.trade_date >= '2024-05-09')
  AND (d.symbol <> 'FERG' OR d.trade_date >= '2021-03-08')
UNION ALL
SELECT
    d.trade_date,
    'FISV' AS symbol,
    d.symbol AS price_symbol,
    d.provider,
    d.venue,
    d.open,
    d.high,
    d.low,
    d.close,
    d.volume,
    d.adj_open,
    d.adj_high,
    d.adj_low,
    d.adj_close,
    d.adj_volume,
    d.dividend,
    d.split_factor,
    d.currency,
    d.raw_price_basis,
    d.source_sha256,
    d.observed_at
FROM us_equity_daily d
WHERE d.provider = 'TIINGO'
  AND d.symbol = 'US000000000280'
  AND d.trade_date >= '2006-01-01'
  AND d.trade_date <= '2026-10-16'
  AND d.volume > 0
