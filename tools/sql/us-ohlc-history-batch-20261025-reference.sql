-- 本批来源在2026年的全部二十列，预期 12951 行。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2026-01-01' AND trade_date<='2026-10-16' AND symbol IN ('IGNT', 'IGPG', 'IGPK', 'IGPYF', 'IGRU', 'IGRW', 'IGSC', 'IGTA', 'IGTAU', 'IHAI', 'IHCPF', 'IHGP', 'IHHHF', 'IHICF', 'IHICY', 'IHLDF', 'IHRTB', 'IHSWF', 'IHTI', 'IIDDY', 'IIJIY', 'IITSF', 'IJJP', 'IKTO', 'IKTSF', 'IKTSY', 'ILAL', 'ILDO', 'ILHMF', 'ILIKF', 'ILIM', 'ILKAF', 'ILKAY', 'ILLMF', 'ILLR', 'ILPMF', 'ILPMY', 'ILST', 'IMAHF', 'IMAQ', 'IMAQU', 'IMBBF', 'IMBBY', 'IMCDY', 'IMCI', 'IMDXF', 'IMDZF', 'IMEXF', 'IMHDY', 'IMIAF', 'IMIMF', 'IMITF', 'IMIUY', 'IMJX', 'IMMB', 'IMMFF', 'IMMG', 'IMMQF', 'IMMVF', 'IMPJY', 'IMPTF', 'IMPUF', 'IMPUY', 'IMPZY', 'IMQCF', 'IMRFF') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 本批明确历史窗口逐来源计数，预期 66 行，合计 188795。
SELECT symbol,provider,count() AS rows FROM us_equity_daily WHERE provider='YAHOO' AND trade_date<='2026-10-16' AND ((trade_date>='2006-01-01' AND symbol IN ('IGPG', 'IGPK', 'IGPYF', 'IGRU', 'IGSC', 'IGTA', 'IGTAU', 'IHAI', 'IHCPF', 'IHGP', 'IHHHF', 'IHICF', 'IHICY', 'IHLDF', 'IHRTB', 'IHSWF', 'IHTI', 'IIDDY', 'IIJIY', 'IITSF', 'IJJP', 'IKTO', 'IKTSF', 'IKTSY', 'ILAL', 'ILDO', 'ILHMF', 'ILIKF', 'ILIM', 'ILKAF', 'ILKAY', 'ILLMF', 'ILLR', 'ILPMF', 'ILPMY', 'IMAQ', 'IMAQU', 'IMBBF', 'IMBBY', 'IMCDY', 'IMCI', 'IMDXF', 'IMDZF', 'IMHDY', 'IMIAF', 'IMIMF', 'IMITF', 'IMIUY', 'IMMB', 'IMMFF', 'IMMG', 'IMMQF', 'IMMVF', 'IMPJY', 'IMPTF', 'IMPUF', 'IMPUY', 'IMPZY', 'IMQCF', 'IMRFF')) OR (trade_date>='2021-10-18' AND symbol IN ('IGNT', 'IGRW', 'ILST', 'IMAHF', 'IMEXF', 'IMJX'))) GROUP BY symbol,provider ORDER BY symbol,provider LIMIT 100001;

-- 本批6个独立五年段，预期7530行；原全历史任务保留待核或失败。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2021-10-18' AND trade_date<='2026-10-16' AND symbol IN ('IGNT', 'IGRW', 'ILST', 'IMAHF', 'IMEXF', 'IMJX') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 结合原文、Native与全部键值证明；WAL不独立代表完成。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('us_equity_daily','us_equity_intraday') ORDER BY name;
