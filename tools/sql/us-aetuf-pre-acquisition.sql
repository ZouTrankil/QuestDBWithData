-- ARC Resources 并购前的美国 OTC 来源，按已核对的 Tiingo 永久代码读取。
-- 仅接受截至 2026-09-01 的窗口；此后32条原来源报价仍待核对。
-- 166条零量报价原样保留；2006年缺31个SPY参照日，不据此推断无成交。
-- 供应商搜索中的isActive不证明当前挂牌；不混入原Yahoo休市日记录。
SELECT
    trade_date,
    'AETUF' AS source_ticker,
    symbol AS price_symbol,
    provider,
    venue,
    open,
    high,
    low,
    close,
    volume,
    adj_open,
    adj_high,
    adj_low,
    adj_close,
    adj_volume,
    dividend,
    split_factor,
    currency,
    raw_price_basis,
    source_sha256,
    observed_at,
    volume > 0 AS source_reports_positive_volume
FROM us_equity_daily
WHERE symbol = 'US000000004783'
    AND provider = 'TIINGO'
    AND trade_date >= '2006-01-03'
    AND trade_date <= '2026-09-01'
ORDER BY trade_date;
