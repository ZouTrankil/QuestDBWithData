-- 本批来源在2026年的全部二十列，预期 12615 行。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2026-01-01' AND trade_date<='2026-10-16' AND symbol IN ('MTYFF', 'MUEL', 'MUNMF', 'MUPQF', 'MURCF', 'MURGF', 'MURGY', 'MURMF', 'MUSLF', 'MVCO', 'MVES', 'MVLA', 'MVLY', 'MVMDF', 'MVNC', 'MVNCD', 'MVNT', 'MVPT', 'MVVYF', 'MWAI', 'MWTCF', 'MWTCY', 'MWWC', 'MXCHF', 'MXCHY', 'MXGBF', 'MXGFF', 'MXLGF', 'MXMGF', 'MXROF', 'MXTLF', 'MXTRF', 'MXUBY', 'MXXNF', 'MYCB', 'MYCOF', 'MYFT', 'MYIDF', 'MYLKF', 'MYMX', 'MYNDF', 'MYRLF', 'MYRUF', 'MYRX', 'MYSL', 'MYTEF', 'MYTKY', 'MZDAF', 'MZDAY', 'MZHOF', 'MZPS', 'MZTFF', 'NABZY', 'NACB', 'NADA', 'NAHD', 'NAKAW', 'NALG', 'NAPD', 'NAPRF', 'NASB', 'NASC', 'NASO', 'NATBF', 'NATKY', 'NATUF', 'NAUBF', 'NAUFF') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 本批明确历史窗口逐来源计数，预期 68 行，合计 202268。
SELECT symbol,provider,count() AS rows FROM us_equity_daily WHERE provider='YAHOO' AND trade_date<='2026-10-16' AND ((trade_date>='2006-01-01' AND symbol IN ('MTYFF', 'MUEL', 'MUNMF', 'MUPQF', 'MURCF', 'MURGF', 'MURGY', 'MURMF', 'MUSLF', 'MVCO', 'MVES', 'MVLA', 'MVLY', 'MVMDF', 'MVNC', 'MVNCD', 'MVPT', 'MVVYF', 'MWAI', 'MWTCF', 'MWTCY', 'MWWC', 'MXCHF', 'MXCHY', 'MXGBF', 'MXGFF', 'MXMGF', 'MXROF', 'MXTLF', 'MXTRF', 'MXUBY', 'MXXNF', 'MYCB', 'MYCOF', 'MYFT', 'MYIDF', 'MYLKF', 'MYMX', 'MYNDF', 'MYRUF', 'MYRX', 'MYTEF', 'MYTKY', 'MZDAF', 'MZDAY', 'MZHOF', 'MZPS', 'MZTFF', 'NABZY', 'NACB', 'NAKAW', 'NAPD', 'NAPRF', 'NASB', 'NASC', 'NASO', 'NATBF', 'NATKY', 'NATUF', 'NAUBF', 'NAUFF')) OR (trade_date>='2021-10-18' AND symbol IN ('MVNT', 'MXLGF', 'MYRLF', 'MYSL', 'NADA', 'NAHD', 'NALG'))) GROUP BY symbol,provider ORDER BY symbol,provider LIMIT 100001;

-- 本批7个独立五年段，预期8785行；原全历史任务保留待核或失败。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2021-10-18' AND trade_date<='2026-10-16' AND symbol IN ('MVNT', 'MXLGF', 'MYRLF', 'MYSL', 'NADA', 'NAHD', 'NALG') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 结合原文、Native与全部键值证明；WAL不独立代表完成。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('us_equity_daily','us_equity_intraday') ORDER BY name;
