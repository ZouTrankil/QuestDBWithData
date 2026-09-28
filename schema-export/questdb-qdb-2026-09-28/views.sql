CREATE VIEW 'v_backtest_daily' AS ( 
SELECT b.trade_date, b.ts_code, b.open, b.high, b.low, b.close,
       b.vol, b.amount, b.adj_factor, l.up_limit, l.down_limit,
       coalesce(s.is_suspended, 0) is_suspended, coalesce(st.is_st, 0) is_st
FROM stk_factor b
LEFT JOIN stk_limit l ON (b.trade_date = l.trade_date AND b.ts_code = l.ts_code)
LEFT JOIN (SELECT timestamp, ts_code, max(is_suspended) is_suspended
           FROM stk_suspend GROUP BY timestamp, ts_code) s ON (b.trade_date = s.timestamp AND b.ts_code = s.ts_code)
LEFT JOIN stk_st_daily st ON (b.trade_date = st.timestamp AND b.ts_code = st.ts_code)
UNION ALL
SELECT s.trade_date, s.ts_code, f.close AS open, f.close AS high,
       f.close AS low, f.close AS close, 0.0 AS vol, 0.0 AS amount,
       f.adj_factor, l.up_limit, l.down_limit, s.is_suspended,
       coalesce(st.is_st, 0) is_st
FROM ((SELECT timestamp AS trade_date, ts_code, max(is_suspended) is_suspended
      FROM stk_suspend GROUP BY timestamp, ts_code ORDER BY trade_date) TIMESTAMP(trade_date)) s
ASOF JOIN stk_factor f ON (ts_code)
LEFT JOIN stk_limit l ON (s.trade_date = l.trade_date AND s.ts_code = l.ts_code)
LEFT JOIN stk_st_daily st ON (s.trade_date = st.timestamp AND s.ts_code = st.ts_code)
JOIN (SELECT DISTINCT trade_date FROM stk_factor) fd ON s.trade_date = fd.trade_date
WHERE f.trade_date < s.trade_date
);

CREATE VIEW 'v_etf_market_overview_daily' AS ( 
SELECT
    s.timestamp AS trade_date,
    count_distinct(s.ts_code) AS etf_count,
    sum(s.fd_share) AS total_share,
    sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
FROM etf_share s
JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp

SAMPLE BY 1d ALIGN TO CALENDAR
);

CREATE VIEW 'v_macro_core_monthly' AS ( 
SELECT * FROM macro_core_monthly
);

CREATE VIEW 'v_macro_liquidity_credit_monthly' AS ( 
SELECT * FROM macro_liquidity_credit_monthly
);

CREATE VIEW 'v_market_breadth_daily' AS ( 
SELECT * FROM mv_market_breadth_daily_v1
);

CREATE VIEW 'v_market_breadth_monthly' AS ( 
SELECT * FROM market_breadth_monthly
);

CREATE VIEW 'v_regime_features_monitor_daily' AS ( 
SELECT * FROM regime_features_monitor_daily
);

CREATE VIEW 'v_regime_features_monthly' AS ( 
SELECT * FROM regime_features_monthly
);

CREATE VIEW 'v_regime_market_monthly' AS ( 
SELECT * FROM regime_market_monthly
);

CREATE VIEW 'v_retail_sentiment_daily' AS ( 
SELECT * FROM mv_retail_sentiment_daily_v1
);
