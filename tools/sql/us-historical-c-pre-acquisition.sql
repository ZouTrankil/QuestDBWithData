-- 三个原发行人的来源历史；永久价格键避免把旧 CA 与后来的 ETF 合并。
-- CA、CTXS 截至明确停牌边界前；COL 保留 2018-11-26 的供应商报价，不推定当日停牌时刻。
-- 只取已验收的原摘要与窗口；并购对价不填入分红或拆股。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000000517', 'US000000000260', 'US000000001367')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
