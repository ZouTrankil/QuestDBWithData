-- 原表OHLC冲突逐路由统计：700路、3512行；保留原值，不表示这些路由的全部日期均不可用。
SELECT symbol,provider,count() AS rows FROM us_equity_daily WHERE low>open OR low>close OR high<open OR high<close GROUP BY symbol,provider ORDER BY symbol,provider LIMIT 100001;

-- 固定首批六个路由的七条冲突，全部原字段保留，作为明确例外读取。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND ((symbol='CIG.C' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='HUBB' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='SHEL' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='SITC' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='UA' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='WLY' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16')) AND (low>open OR low>close OR high<open OR high<close) ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 六个原窗口筛除七条冲突后的15077行；筛选不代表窗口日期完整。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND ((symbol='CIG.C' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='HUBB' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='SHEL' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='SITC' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='UA' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16') OR (symbol='WLY' AND trade_date>='2016-10-17' AND trade_date<='2026-10-16')) AND low<=open AND low<=close AND high>=open AND high>=close ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 五个原路由的完整五年段6275行；SHEL仍有2023年冲突，另行留缺口。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND symbol IN ('CIG.C', 'HUBB', 'SITC', 'UA', 'WLY') AND trade_date>='2021-10-18' AND trade_date<='2026-10-16' AND low<=open AND low<=close AND high>=open AND high>=close ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 本次实际新增的25行，五个原首批路由的五日尾段。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND symbol IN ('CIG.C', 'SHEL', 'SITC', 'UA', 'WLY') AND trade_date>='2026-10-19' AND trade_date<='2026-10-23' ORDER BY trade_date,symbol,provider LIMIT 100001;

-- WAL须与来源、完整字段和Native证据一起核对。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('us_equity_daily','us_equity_intraday') ORDER BY name;
