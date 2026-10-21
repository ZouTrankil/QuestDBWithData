-- ARRXF、BARUF 的明确五年 Yahoo 报价；保留零量行，不把报价网格当作每日成交。
-- 原始及调整价均沿来源基准；来源未报现金分红不证明实物分派或总回报历史完整。
SELECT trade_date, symbol, provider, venue,
       open, high, low, close, volume,
       adj_open, adj_high, adj_low, adj_close, adj_volume,
       dividend, split_factor, currency, raw_price_basis, source_sha256, observed_at,
       volume > 0 AS has_positive_volume
FROM us_equity_daily
WHERE symbol IN ('ARRXF', 'BARUF')
  AND provider = 'YAHOO'
  AND trade_date >= '2021-10-16'
  AND trade_date <= '2026-10-16'
ORDER BY trade_date, symbol, provider;
