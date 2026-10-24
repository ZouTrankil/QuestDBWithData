-- 核心八产品的 UTC 两日增量，预期 400 行十三列：384 根小时线、16 根日线。
SELECT * FROM crypto_ohlcv WHERE ts>='2026-10-22' AND ts<'2026-10-24' ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 十六格逐项计数，小时每格 48 行、日线每格 2 行。
SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv WHERE ts>='2026-10-22' AND ts<'2026-10-24' GROUP BY exchange,symbol,instrument,interval_minutes ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 两条既有官方停市小时缺口；本次没有新增或补造价格。
SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 结合原文、Native 和全部键值证明核对；WAL 不能独立代表完整。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('crypto_ohlcv','crypto_data_gap_events') ORDER BY name;
