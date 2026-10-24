-- Panera 与旧 SanDisk 的已核对历史，使用永久价格键隔离发行人。
-- PNRA 保留至2017-07-17，旧 SNDK 保留至2016-05-11；两条停牌后报价留在原文。
-- 当前 SNDK 的2025年起报价继续使用原 SNDK/YAHOO，不与旧证券拼接。
-- PETM 的最后一次分红与复权因子存在冲突，本查询不包含该待核来源。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000007315', 'US000000000217')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
