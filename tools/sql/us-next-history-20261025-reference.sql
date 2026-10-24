-- 本批来源在2026年的全部二十列，预期 12973 行。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2026-01-01' AND trade_date<='2026-10-16' AND symbol IN ('ICOA', 'ICOSF', 'ICPVF', 'ICRD', 'ICTEY', 'ICTSF', 'ICTY', 'IDAM', 'IDBHF', 'IDCBF', 'IDCBY', 'IDCN', 'IDDTF', 'IDDWF', 'IDEA', 'IDEXF', 'IDEXY', 'IDGAF', 'IDGC', 'IDGR', 'IDGXF', 'IDIG', 'IDKFF', 'IDKOY', 'IDLM', 'IDPUF', 'IDRSF', 'IDTA', 'IDTRY', 'IDTVF', 'IDWM', 'IDXAF', 'IDXG', 'IDXMF', 'IDYLF', 'IEGCF', 'IEHC', 'IENT', 'IEQWF', 'IESFY', 'IEXA', 'IFABF', 'IFBC', 'IFCNF', 'IFCZF', 'IFJPY', 'IFLM', 'IFNNF', 'IFNNY', 'IFPJF', 'IFRTF', 'IFSPF', 'IFSUF', 'IFTBF', 'IFUS', 'IFXY', 'IGCRF', 'IGDFF', 'IGEN', 'IGESF', 'IGEX', 'IGFFF', 'IGGHY', 'IGGRF', 'IGIFF', 'IGNE') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 本批明确历史窗口逐来源计数，预期 66 行，合计 190756。
SELECT symbol,provider,count() AS rows FROM us_equity_daily WHERE provider='YAHOO' AND trade_date<='2026-10-16' AND ((trade_date>='2006-01-01' AND symbol IN ('ICOSF', 'ICPVF', 'ICRD', 'ICTEY', 'ICTSF', 'IDAM', 'IDBHF', 'IDCBF', 'IDCBY', 'IDCN', 'IDDTF', 'IDDWF', 'IDEA', 'IDEXF', 'IDEXY', 'IDGAF', 'IDGR', 'IDKFF', 'IDKOY', 'IDLM', 'IDPUF', 'IDRSF', 'IDTA', 'IDTRY', 'IDTVF', 'IDXAF', 'IDXG', 'IDXMF', 'IDYLF', 'IEGCF', 'IEHC', 'IENT', 'IEQWF', 'IESFY', 'IEXA', 'IFABF', 'IFBC', 'IFCNF', 'IFCZF', 'IFJPY', 'IFNNF', 'IFNNY', 'IFPJF', 'IFRTF', 'IFSPF', 'IFSUF', 'IFTBF', 'IFUS', 'IGCRF', 'IGDFF', 'IGESF', 'IGGHY', 'IGGRF', 'IGIFF')) OR (trade_date>='2021-10-18' AND symbol IN ('ICOA', 'ICTY', 'IDGC', 'IDGXF', 'IDIG', 'IDWM', 'IFLM', 'IFXY', 'IGEN', 'IGEX', 'IGFFF', 'IGNE'))) GROUP BY symbol,provider ORDER BY symbol,provider LIMIT 100001;

-- 十二个独立五年段，预期15060行；原全历史任务保留待核或失败。
SELECT * FROM us_equity_daily WHERE provider='YAHOO' AND trade_date>='2021-10-18' AND trade_date<='2026-10-16' AND symbol IN ('ICOA', 'ICTY', 'IDGC', 'IDGXF', 'IDIG', 'IDWM', 'IFLM', 'IFXY', 'IGEN', 'IGEX', 'IGFFF', 'IGNE') ORDER BY trade_date,symbol,provider LIMIT 100001;

-- 结合原文、Native与全部键值证明；WAL不独立代表完成。
SELECT name,writerTxn,sequencerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name IN ('us_equity_daily','us_equity_intraday') ORDER BY name;
