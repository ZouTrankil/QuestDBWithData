-- CBOE 原收盘与 FRED 分列展示；不替换原值，不按股票日历删改来源日期。
SELECT c.trade_date,c.symbol,c.provider,c.close,c.value_unit,
       c.source_sha256,c.observed_at,
       f.close AS fred_close,f.source_id AS fred_series,f.update_time AS fred_observed_at
FROM us_index_daily c
LEFT JOIN (
    SELECT trade_date,close,source_id,update_time
    FROM us_market_daily
    WHERE ts_code='VIXCLS.US'
) f ON c.trade_date=f.trade_date
WHERE c.symbol='VIX' AND c.provider='CBOE'
  AND c.trade_date>='2006-01-01' AND c.trade_date<='2026-10-20'
ORDER BY c.trade_date,c.symbol,c.provider;
