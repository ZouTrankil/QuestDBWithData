-- 原水位与上次验收不一致；不能作为新发布已就绪。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('us_equity_daily','crypto_ohlcv') ORDER BY name;

-- 原16个核心的26日尚无新增记录。
SELECT * FROM us_equity_daily WHERE provider='TIINGO' AND trade_date>='2026-10-26' AND trade_date<'2026-10-27' AND symbol IN ('AAPL','AMZN','AVGO','GLD','GOOGL','IWM','JPM','META','MSFT','NVDA','QQQ','SPY','TLT','TSLA','USO','WMT') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 原八产品的UTC26日尚无新增记录。
SELECT * FROM crypto_ohlcv WHERE ts>='2026-10-26' AND ts<'2026-10-27' ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 只读记录当前服务端版本，不据此推断异常原因。
SELECT build();
