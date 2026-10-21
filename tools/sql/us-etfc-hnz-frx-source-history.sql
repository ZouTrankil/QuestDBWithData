-- 三家原发行人的已核对来源窗口；价格键使用供应商永久代码。
-- HNZ 的 2006 年仍缺九个参照日；最近五年日期完整，不补造旧缺日。
-- ETFC 的 2020-10-02、FRX 的 2014-07-01 停牌后原报价留在来源中。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000000090', 'US000000009599', 'US000000001760')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
