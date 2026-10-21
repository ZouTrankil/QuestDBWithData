-- 固定已验收的供应商DTV1原文；两项提示来自官方转板及重组公告，不改写原价格字段。
-- 原目录场所为空，未建立永久证券映射；US_COMPOSITE 不等于每笔交易真实成交场所。
SELECT p.*,
       CASE WHEN p.trade_date < '2007-12-03' THEN 'NYSE'
            ELSE 'NASDAQ' END AS listing_market_hint,
       CASE WHEN p.trade_date < '2009-11-20' THEN 'GROUP_COMMON'
            ELSE 'HOLDCO_CLASS_A' END AS issuer_phase
FROM us_equity_daily p
WHERE p.provider = 'TIINGO'
  AND p.symbol = 'US000000012569'
  AND p.source_sha256 = '3665c7efe20eb6c16a90dbe26a3801ff275105705b1aaa70f5e5085c504883ca'
  AND p.trade_date >= '2006-01-03'
  AND p.trade_date <= '2015-07-24'
ORDER BY p.trade_date, p.symbol, p.provider;
