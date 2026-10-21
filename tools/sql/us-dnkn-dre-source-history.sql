-- 原普通股只按永久价格键、原文摘要及已验收窗口读取。
-- 并购现金和换股对价不记作分红或拆股；DRE 本批末日不独立证明官方最后交易日。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name,
       CASE WHEN i.source_ticker = 'DNKN'
              THEN '原普通股并购前行情；并购现金不记作分红或拆股'
            ELSE '原普通股并购前行情；换股对价不记作分红或拆股；本批末日不独立证明官方最后交易日'
       END AS scope_note
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000001521', 'US000000001529')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
