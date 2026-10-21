-- 本批新增FDO、HSP；只关联已验收的原价格摘要和窗口。
-- 截止日表示本批已核对的来源窗口，不冒充官方最后交易日；窗口后原报价留在原文。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000000292', 'US000000000475')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
