-- 固定十二段已裁定原报价，原长区间失败任务继续保留。
-- 零量报价不证明当日无交易；当前代码与名称不证明历史发行人连续。
SELECT p.trade_date, p.symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at,
       p.volume > 0 AS source_positive_volume,
       'OTC供应商原报价；零量未证明每日成交；当前代码未证明历史发行人连续' AS source_scope,
       '窗口内来源未报告分红和拆股；不证明所有公司行动均已齐备' AS action_scope
FROM us_equity_daily p
WHERE p.provider='YAHOO'
  AND p.symbol IN ('ETBI','EWKS','EXRG','FORZ','FRAZ','FYNN','GARQF','GCEI','GEAT','GETH','GFOO','GHAV')
  AND p.trade_date>='2011-10-16' AND p.trade_date<'2026-10-17'
  AND (
    (p.symbol='ETBI' AND p.source_sha256='bba1bd64cb992d9c7c4a4d30b6228d2ca5cb991140159f3d47b73aa187b980fc' AND p.trade_date>='2016-10-16')
    OR
    (p.symbol='EWKS' AND p.source_sha256='45e95fbe21f9eec65b9219b8c583f3b90cd7ffdf06fec19748ac33078f90c36c' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='EXRG' AND p.source_sha256='ebf2b745fa80a363307f43470b79227a7fabcdf54d353b2168e74f08f5939071' AND p.trade_date>='2011-10-16')
    OR
    (p.symbol='FORZ' AND p.source_sha256='435b77338990c0083e92f557ae73624a94995890049e9d08ba1cafda95c9470c' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='FRAZ' AND p.source_sha256='165b0c98e2925128dd9ea98e981267ff1506f06e7952d0c87c9c797462b6be69' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='FYNN' AND p.source_sha256='df3d5be30e4f52b6e3c11b0ae050f1382bb2e523178828e424b1586160398140' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='GARQF' AND p.source_sha256='e31e5ef7e461c835a5b5decc9a9b42aafe2a73d9f9ed60ac685fe85a87073582' AND p.trade_date>='2011-10-16')
    OR
    (p.symbol='GCEI' AND p.source_sha256='60fe0ef0f9bf62ebc75084ab2eb9c96c1ca3dba5be3256426a0f956f36601291' AND p.trade_date>='2016-10-16')
    OR
    (p.symbol='GEAT' AND p.source_sha256='fafcd11c4ac2ecd977d6e21264e8eaa371ed8b95215d61b88b00a5e454ab9bb0' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='GETH' AND p.source_sha256='216990cc53cb9c82e831c3d66968bc5dc4bc71607b30c6dad0fff0a7b9f8f58a' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='GFOO' AND p.source_sha256='c25a6ea112514ab16806f711f16f79c4716ff464ed0ceb70522bf6ead10195d5' AND p.trade_date>='2021-10-16')
    OR
    (p.symbol='GHAV' AND p.source_sha256='95fbd78bebea7add5f69a9356183a4c3c00e7fcf3cc24c7e7667dd00378c7713' AND p.trade_date>='2016-10-16')
  )
ORDER BY p.trade_date,p.symbol,p.provider
LIMIT 30001;
