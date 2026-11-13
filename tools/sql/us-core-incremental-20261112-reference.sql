-- 纽约十一月十二日16核心完整字段。
SELECT * FROM us_equity_daily WHERE provider='TIINGO' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND trade_date>='2026-11-12' AND trade_date<'2026-11-13'
ORDER BY trade_date,symbol,provider LIMIT 100001;

SELECT * FROM us_equity_intraday WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-11-12' AND ts<'2026-11-13' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;

SELECT * FROM us_equity_intraday_regular WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-11-12' AND ts<'2026-11-13' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;
