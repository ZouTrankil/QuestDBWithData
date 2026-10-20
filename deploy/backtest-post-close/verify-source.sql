SELECT * FROM (
    SELECT trade_date,
           cast(ts_code AS SYMBOL) AS ts_code,
           last(open) AS open,
           last(high) AS high,
           last(low) AS low,
           last(close) AS close,
           last(vol) AS vol,
           last(amount) AS amount,
           last(adj_factor) AS adj_factor,
           last(up_limit) AS up_limit,
           last(down_limit) AS down_limit,
           cast(last(is_suspended) AS LONG) AS is_suspended,
           last(is_st) AS is_st
    FROM (
        SELECT /*+ asof_dense(x f) */
               x.trade_date,
               x.ts_code,
               CASE WHEN f.trade_date=x.trade_date THEN f.open ELSE f.close END open,
               CASE WHEN f.trade_date=x.trade_date THEN f.high ELSE f.close END high,
               CASE WHEN f.trade_date=x.trade_date THEN f.low ELSE f.close END low,
               CASE WHEN f.trade_date=x.trade_date THEN f.close ELSE f.close END close,
               CASE WHEN f.trade_date=x.trade_date THEN f.vol ELSE 0.0 END vol,
               CASE WHEN f.trade_date=x.trade_date THEN f.amount ELSE 0.0 END amount,
               CASE WHEN f.trade_date=x.trade_date THEN f.adj_factor ELSE f.adj_factor END adj_factor,
               l.up_limit,
               l.down_limit,
               coalesce(s.is_suspended,0) is_suspended,
               coalesce(st.is_st,0) is_st
        FROM ((
            SELECT d.trade_date,u.ts_code
            FROM ((
                SELECT DISTINCT trade_date FROM stk_limit ORDER BY trade_date
            ) TIMESTAMP(trade_date)) d
            CROSS JOIN (SELECT DISTINCT ts_code FROM stk_factor) u
            ORDER BY d.trade_date
        ) TIMESTAMP(trade_date)) x
        ASOF JOIN stk_factor f ON (x.ts_code=f.ts_code)
        LEFT JOIN stk_suspend s ON x.trade_date=s.timestamp AND x.ts_code=s.ts_code
        LEFT JOIN stk_limit l ON x.trade_date=l.trade_date AND x.ts_code=l.ts_code
        LEFT JOIN stk_st_daily st ON x.trade_date=st.timestamp AND x.ts_code=st.ts_code
        JOIN (SELECT DISTINCT trade_date FROM stk_factor) fd ON x.trade_date=fd.trade_date
        WHERE f.trade_date=x.trade_date
           OR (s.timestamp IS NOT NULL AND f.trade_date<x.trade_date)
    ) SAMPLE BY 1d
) WHERE trade_date >= '{from}' AND trade_date < '{until}'
