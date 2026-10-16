CREATE MATERIALIZED VIEW mv_backtest_daily WITH BASE stk_limit REFRESH IMMEDIATE
AS (
SELECT trade_date,cast(ts_code AS SYMBOL) AS ts_code,last(open) AS open,last(high) AS high,last(low) AS low,last(close) AS close,last(vol) AS vol,last(amount) AS amount,last(adj_factor) AS adj_factor,last(up_limit) AS up_limit,last(down_limit) AS down_limit,cast(last(is_suspended) AS LONG) AS is_suspended,last(is_st) AS is_st FROM (SELECT j.trade_date,j.ts_code,CASE WHEN j.factor_date IS NULL THEN f.close ELSE j.open END open,CASE WHEN j.factor_date IS NULL THEN f.close ELSE j.high END high,CASE WHEN j.factor_date IS NULL THEN f.close ELSE j.low END low,CASE WHEN j.factor_date IS NULL THEN f.close ELSE j.close END close,CASE WHEN j.factor_date IS NULL THEN 0.0 ELSE j.vol END vol,CASE WHEN j.factor_date IS NULL THEN 0.0 ELSE j.amount END amount,CASE WHEN j.factor_date IS NULL THEN f.adj_factor ELSE j.adj_factor END adj_factor,l.up_limit,l.down_limit,j.is_suspended,coalesce(st.is_st,0) is_st
 FROM (((SELECT coalesce(b.trade_date,s.timestamp) trade_date,coalesce(b.ts_code,s.ts_code) ts_code,b.trade_date factor_date,b.open,b.high,b.low,b.close,b.vol,b.amount,b.adj_factor,coalesce(s.is_suspended,0) is_suspended
 FROM (SELECT * FROM stk_factor WHERE trade_date >= (SELECT min(trade_date) FROM stk_limit) AND trade_date <= (SELECT max(trade_date) FROM stk_limit)) b FULL OUTER JOIN (SELECT timestamp,ts_code,max(is_suspended) is_suspended FROM stk_suspend WHERE timestamp >= (SELECT min(trade_date) FROM stk_limit) AND timestamp <= (SELECT max(trade_date) FROM stk_limit) GROUP BY timestamp,ts_code) s ON b.trade_date=s.timestamp AND b.ts_code=s.ts_code) ORDER BY trade_date) TIMESTAMP(trade_date)) j ASOF JOIN stk_factor f ON (ts_code)
 LEFT JOIN stk_limit l ON j.trade_date=l.trade_date AND j.ts_code=l.ts_code
 LEFT JOIN stk_st_daily st ON j.trade_date=st.timestamp AND j.ts_code=st.ts_code
 JOIN (SELECT DISTINCT trade_date FROM stk_factor WHERE trade_date >= (SELECT min(trade_date) FROM stk_limit) AND trade_date <= (SELECT max(trade_date) FROM stk_limit)) fd ON j.trade_date=fd.trade_date
 JOIN (SELECT DISTINCT trade_date FROM stk_limit) trigger_dates ON j.trade_date=trigger_dates.trade_date
 WHERE j.factor_date IS NOT NULL OR f.trade_date<j.trade_date) SAMPLE BY 1d
)
PARTITION BY MONTH;
