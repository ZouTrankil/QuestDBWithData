-- 16 核心 10 月 30 日日线，共 16 行、二十列。
SELECT * FROM us_equity_daily WHERE provider='TIINGO' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND trade_date>='2026-10-30' AND trade_date<'2026-10-31'
ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 原 1/5 分钟共 7488 行、十三列；IEX 成交量不代表全市场。
SELECT * FROM us_equity_intraday WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-10-30' AND ts<'2026-10-31' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;

-- 正常时段视图同为 7488 行、十四列。
SELECT * FROM us_equity_intraday_regular WHERE provider='TIINGO_IEX' AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT','NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts>='2026-10-30' AND ts<'2026-10-31' AND interval_minutes IN (1,5)
ORDER BY ts,symbol,provider,interval_minutes LIMIT 100001;
