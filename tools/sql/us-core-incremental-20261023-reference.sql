-- 核心 16 个标的 10 月 22、23 日新增日线，共 32 行；保留全部二十列。
SELECT * FROM us_equity_daily
WHERE provider = 'TIINGO'
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND trade_date >= '2026-10-22' AND trade_date < '2026-10-24'
ORDER BY trade_date, symbol, provider
LIMIT 100001;

-- 正常时段 1/5 分钟共 14976 行；十四列，成交量仅代表 IEX 场所。
SELECT * FROM us_equity_intraday_regular
WHERE provider = 'TIINGO_IEX'
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND ts >= '2026-10-22' AND ts < '2026-10-24'
  AND interval_minutes IN (1,5)
ORDER BY ts, symbol, provider, interval_minutes
LIMIT 100001;

-- WAL 结清只是验收的一部分，完整来源与键值证明另见范围清单。
SELECT name, writerTxn, sequencerTxn, bufferedTxnSize, suspended
FROM wal_tables()
WHERE name IN ('us_equity_daily','us_equity_intraday')
ORDER BY name;
