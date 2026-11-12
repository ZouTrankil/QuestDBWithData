-- 本轮已验收范围；完整字段及原缺口见对应清单。
SELECT * FROM us_equity_daily WHERE provider='TIINGO' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND trade_date>='2026-11-10' AND trade_date<'2026-11-12'
ORDER BY trade_date,symbol,provider LIMIT 100001;

SELECT * FROM us_equity_intraday WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-11-10' AND ts<'2026-11-12' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;

SELECT * FROM us_equity_intraday_regular WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-11-10' AND ts<'2026-11-12' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;
