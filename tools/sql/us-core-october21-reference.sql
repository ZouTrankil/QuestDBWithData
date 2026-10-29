-- 分别执行三个只读查询；原表和视图是同一批分钟值，不叠加计算新增行数。
-- 日线保留未复权、调整价、分红、拆股及来源共二十列。
SELECT * FROM us_equity_daily
WHERE provider='TIINGO' AND trade_date='2026-10-21'
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
ORDER BY trade_date,symbol,provider;

-- ts 是真实 UTC 分钟起点；本日 6240 根一分钟与1248根五分钟均来自 IEX。
SELECT * FROM us_equity_intraday
WHERE provider='TIINGO_IEX' AND ts>='2026-10-21' AND ts<'2026-10-22'
  AND trade_date='2026-10-21' AND interval_minutes IN (1,5)
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
ORDER BY ts,symbol,provider,interval_minutes;

-- 研究读取正常交易时段视图，额外保留 calendar_version，不改原值。
SELECT * FROM us_equity_intraday_regular
WHERE provider='TIINGO_IEX' AND trade_date='2026-10-21'
  AND interval_minutes IN (1,5)
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
ORDER BY ts,symbol,provider,interval_minutes;
