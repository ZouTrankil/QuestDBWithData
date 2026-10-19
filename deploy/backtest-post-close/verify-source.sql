SELECT b.trade_date, b.ts_code, b.open, b.high, b.low, b.close,
       b.vol, b.amount, b.adj_factor, l.up_limit, l.down_limit,
       cast(coalesce(s.is_suspended, 0) AS LONG) is_suspended, coalesce(st.is_st, 0) is_st
FROM (SELECT * FROM stk_factor WHERE trade_date >= '{from}' AND trade_date < '{until}') b
LEFT JOIN (SELECT * FROM stk_limit WHERE trade_date >= '{from}' AND trade_date < '{until}') l
  ON (b.trade_date = l.trade_date AND b.ts_code = l.ts_code)
LEFT JOIN (SELECT timestamp, ts_code, max(is_suspended) is_suspended FROM stk_suspend
           WHERE timestamp >= '{from}' AND timestamp < '{until}' GROUP BY timestamp, ts_code) s
  ON (b.trade_date = s.timestamp AND b.ts_code = s.ts_code)
LEFT JOIN (SELECT * FROM stk_st_daily WHERE timestamp >= '{from}' AND timestamp < '{until}') st
  ON (b.trade_date = st.timestamp AND b.ts_code = st.ts_code)
UNION ALL
SELECT s.trade_date, s.ts_code, f.close AS open, f.close AS high, f.close AS low, f.close AS close,
       0.0 AS vol, 0.0 AS amount, f.adj_factor, l.up_limit, l.down_limit,
       cast(s.is_suspended AS LONG) is_suspended, coalesce(st.is_st, 0) is_st
FROM ((SELECT timestamp AS trade_date, ts_code, max(is_suspended) is_suspended FROM stk_suspend
       WHERE timestamp >= '{from}' AND timestamp < '{until}' GROUP BY timestamp, ts_code ORDER BY trade_date) TIMESTAMP(trade_date)) s
ASOF JOIN stk_factor f ON (ts_code)
LEFT JOIN (SELECT * FROM stk_limit WHERE trade_date >= '{from}' AND trade_date < '{until}') l
  ON (s.trade_date = l.trade_date AND s.ts_code = l.ts_code)
LEFT JOIN (SELECT * FROM stk_st_daily WHERE timestamp >= '{from}' AND timestamp < '{until}') st
  ON (s.trade_date = st.timestamp AND s.ts_code = st.ts_code)
JOIN (SELECT DISTINCT trade_date FROM stk_factor WHERE trade_date >= '{from}' AND trade_date < '{until}') fd
  ON s.trade_date = fd.trade_date
WHERE f.trade_date < s.trade_date
