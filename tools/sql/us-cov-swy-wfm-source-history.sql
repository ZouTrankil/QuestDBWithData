-- 三家历史证券使用永久键、原价格摘要和已验收窗口读取。
-- COV 的一比一迁册不记为数量拆股；COV、SWY 的非现金派股价值需另算。
-- WFM 并购日仍有正量平价报价，本查询保留并购边界前窗口。
SELECT p.trade_date, p.symbol AS price_symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at, i.source_ticker, i.security_name,
       CASE WHEN i.source_ticker = 'COV'
              THEN '一比一迁册；非现金MNK派股价值未另计；来源首日不等于普通挂牌首日'
            WHEN i.source_ticker = 'SWY'
              THEN '非现金Blackhawk派股价值未另计；并购日零量报价留存'
            ELSE '拆股和现金分红按来源；并购日正量平价报价留存'
       END AS scope_note
FROM us_equity_daily p
JOIN us_security_identifiers i
  ON p.symbol = i.price_symbol AND p.provider = i.provider
WHERE i.security_id IN ('US000000000356', 'US000000000219', 'US000000000406')
  AND i.provider = 'TIINGO'
  AND p.source_sha256 = i.price_source_sha256
  AND p.trade_date >= i.price_from
  AND p.trade_date <= i.price_through
ORDER BY price_symbol, trade_date;
