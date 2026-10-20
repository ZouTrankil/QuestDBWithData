-- 分别读取原BHI、STJ、SIAL的已验收美国股票窗口，不拼接收购方或新代码。
-- BHI特别分红保留来源2017-07-03标记，公告支付日为2017-07-06。
-- SIAL拆股保留来源2007-01-03标记；收购现金不补成分红。
-- LLTC短来源仍待补，本查询不把其298行当作五年历史。
SELECT
    trade_date,
    CASE symbol
        WHEN 'US000000000378' THEN 'BHI'
        WHEN 'US000000002692' THEN 'STJ'
        WHEN 'US000000002595' THEN 'SIAL'
    END AS source_ticker,
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
WHERE provider = 'TIINGO'
    AND trade_date >= '2006-01-03'
    AND (
        (symbol = 'US000000000378' AND trade_date <= '2017-07-03')
        OR (symbol = 'US000000002692' AND trade_date <= '2017-01-04')
        OR (symbol = 'US000000002595' AND trade_date <= '2015-11-17')
    )
ORDER BY price_symbol, trade_date;
