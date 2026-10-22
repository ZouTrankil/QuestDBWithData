-- 固定首批的末日与 ITCH 末段查询。全范围、行数和限制见对应 JSON 清单。
-- 分钟研究读取正常时段视图；批量读取按交易日切分，保留全部字段。
-- 这里的 7488 行仅代表 IEX 场所，零量报价不等于这一分钟有成交。
SELECT ts, trade_date, symbol, provider, venue, interval_minutes,
       open, high, low, close, volume, source_sha256, observed_at, calendar_version
FROM us_equity_intraday_regular
WHERE trade_date = '2026-10-21'
  AND provider = 'TIINGO_IEX'
  AND symbol IN ('SPY','QQQ','IWM','TLT','GLD','USO','AAPL','MSFT',
                 'NVDA','AMZN','GOOGL','META','TSLA','AVGO','JPM','WMT')
  AND interval_minutes IN (1,5)
ORDER BY ts, symbol, provider, interval_minutes
LIMIT 10001;

-- 八个产品各 24 根小时及一根日线，共 200 行；时间按 UTC。
SELECT ts, exchange, symbol, instrument, interval_minutes,
       open, high, low, close, volume, quote_volume, source_sha256, observed_at
FROM crypto_ohlcv
WHERE ts >= '2026-10-21' AND ts < '2026-10-22'
  AND exchange IN ('BINANCE','BYBIT') AND symbol IN ('BTCUSDT','ETHUSDT')
  AND instrument IN ('SPOT','USDT_PERPETUAL') AND interval_minutes IN (60,1440)
ORDER BY ts, exchange, symbol, instrument, interval_minutes
LIMIT 10001;

-- ITCH 最后 836 条；ts 返回 INT64 纳秒，客户端应保留整数或字符串精度。
-- 原片段为盘前部分来源，source_complete=false；不代表全日或完整订单簿。
SELECT cast(ts AS long) AS ts, message_index, message_type, stock_locate,
       tracking_number, symbol, order_ref, new_order_ref, side, shares, price_4,
       raw_hex, decompressed_offset, source_sha256, source_complete, observed_at
FROM us_nasdaq_itch_messages
WHERE source_sha256 = 'fdf8133e8aa56e888a57dcdc9048142890a7bac346dee158f16fee233c2c396e'
  AND message_index >= 659047 AND message_index < 659883
ORDER BY ts, source_sha256, message_index
LIMIT 10001;

-- FRED 三条序列的固定业务日期标记，共三行。
SELECT * FROM us_market_daily
WHERE trade_date = '2026-10-21'
  AND ts_code IN ('NASDAQCOM.US','SP500.US','VIXCLS.US')
ORDER BY ts_code, trade_date
LIMIT 10001;

-- CBOE 原 VIX 收盘值，保持供应商身份，共一行。
SELECT * FROM us_index_daily
WHERE trade_date = '2026-10-21' AND symbol = 'VIX' AND provider = 'CBOE'
ORDER BY trade_date, symbol, provider
LIMIT 10001;
