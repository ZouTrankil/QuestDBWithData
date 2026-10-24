-- 三段已验收原报价；按原摘要和明确窗口读取，零量不证明无成交。
-- 最新名称、场所或市场层级不能代表各日历史身份与可交易性。
SELECT p.trade_date, p.symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at,
       p.volume > 0 AS source_positive_volume,
       CASE WHEN p.symbol = 'CIWV'
              THEN '2016-10-16起原报价；普通股现金口径，非Class A；零量不证明无成交'
            WHEN p.symbol = 'CATV'
              THEN '2021-10-16起原报价；名称及业务变化单列；不能证明连续公开做市'
            ELSE '2021-10-16起原报价；含Expert Market阶段；零量不证明无成交'
       END AS source_scope
FROM us_equity_daily p
WHERE p.provider = 'YAHOO'
  AND p.trade_date <= '2026-10-16'
  AND ((p.symbol = 'CIWV' AND p.trade_date >= '2016-10-16'
        AND p.source_sha256 = '0b5cdd6edff6c714a2175b1b7df1f7f8733a4f40c041f55188f5807f10d84346')
    OR (p.symbol = 'CATV' AND p.trade_date >= '2021-10-16'
        AND p.source_sha256 = '1dfcd467a6f51cf629d083585bf2ec43c843e696ba1d5c7688104ffa71c86213')
    OR (p.symbol = 'BLFR' AND p.trade_date >= '2021-10-16'
        AND p.source_sha256 = 'd1ae790091972469f41f2136b0898dbd08f382ac28848a9c61c0144377bebdc5'))
ORDER BY trade_date, symbol, provider;
