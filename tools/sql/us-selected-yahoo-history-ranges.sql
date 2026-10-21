-- 只读四段已逐字段验收的原报价；不代表历史时点名称或全市场覆盖。
-- 保留来源零量，不能由此推定无成交；Yahoo 原价由拆股调整价重建。
SELECT p.trade_date, p.symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at,
       p.volume > 0 AS source_positive_volume,
       CASE WHEN p.symbol = 'CBIH'
              THEN '2011-10-16起原报价；包含原CHNC；最新代码不代表各日历史名称'
            WHEN p.symbol = 'DBMM'
              THEN '2021-10-16起原报价；较长窗口仍有非法字段'
            WHEN p.symbol = 'CUEN'
              THEN '2011-10-16起原报价；包含前身及四次合股；源零量不能推定无成交'
            ELSE '2016-10-16起原报价；业务及注册地变化单列，非历史时点名称证明'
       END AS source_scope
FROM us_equity_daily p
WHERE p.provider = 'YAHOO'
  AND p.trade_date <= '2026-10-16'
  AND ((p.symbol = 'CBIH' AND p.trade_date >= '2011-10-16'
        AND p.source_sha256 = '294a78d4a02e5f37acf9ea7aa9d671dc6e9969f14bdb61d13f6e4b30a02f8798')
    OR (p.symbol = 'DBMM' AND p.trade_date >= '2021-10-16'
        AND p.source_sha256 = 'e1459c1b3defa4c42e49ba81efff1311f9387e6d79de7185dde6e43a585e8923')
    OR (p.symbol = 'CUEN' AND p.trade_date >= '2011-10-16'
        AND p.source_sha256 = '5278caa9c3d2de025881895f1e7c47cb319d9d38f3a08402682a676f24d1563f')
    OR (p.symbol = 'CSOC' AND p.trade_date >= '2016-10-16'
        AND p.source_sha256 = '4b80f549e44140ec2a1b51bb930bbb3c2632ca51b2b30ff44bd2ca590792ae5f'))
ORDER BY trade_date, symbol, provider;
