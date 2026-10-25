-- 原八产品UTC单日200行十三列，含192根小时线和8根日线。
SELECT * FROM crypto_ohlcv WHERE ts>='2026-10-24' AND ts<'2026-10-25' ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 十六格逐项计数：小时每格24行，日线每格1行。
SELECT exchange,symbol,instrument,interval_minutes,count() AS rows FROM crypto_ohlcv WHERE ts>='2026-10-24' AND ts<'2026-10-25' GROUP BY exchange,symbol,instrument,interval_minutes ORDER BY exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 两条原官方停市小时缺口保持，不补价格。
SELECT * FROM crypto_data_gap_events ORDER BY ts,exchange,symbol,instrument,interval_minutes LIMIT 100001;

-- 须结合原文、完整字段和Native；WAL不能单独证明完成。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('crypto_ohlcv','crypto_data_gap_events') ORDER BY name;
