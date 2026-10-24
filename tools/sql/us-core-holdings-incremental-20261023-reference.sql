-- 固定 2026-10-16 的 SPY/QQQ 股票持仓参照，沿清单实际价格键读取。
-- 五日窗口预期 2590 行；只代表固定参照，不能当作 10 月 23 日的指数成分表。
-- 各行保留来源摘要及取得时间，跨观察的调整价不直接拼成总回报。
SELECT * FROM us_equity_daily
WHERE trade_date >= '2026-10-19' AND trade_date < '2026-10-24'
  AND ((provider = 'YAHOO' AND symbol IN (
      'A', 'ABBV', 'ABNB', 'ABT', 'ACGL', 'ACN', 'ADBE', 'ADI', 'ADM', 'ADP',
      'ADSK', 'AEE', 'AEP', 'AES', 'AFL', 'AIG', 'AIZ', 'AJG', 'AKAM', 'ALAB',
      'ALB', 'ALGN', 'ALL', 'ALLE', 'ALNY', 'AMAT', 'AMCR', 'AMD', 'AME', 'AMGN',
      'AMP', 'AMT', 'ANET', 'AON', 'AOS', 'APA', 'APD', 'APH', 'APO', 'APP',
      'APTV', 'ARE', 'ARES', 'ARM', 'ASML', 'ATO', 'AVY', 'AWK', 'AXON', 'AXP',
      'AZO', 'BA', 'BAC', 'BALL', 'BAX', 'BBY', 'BDX', 'BE', 'BEN', 'BF.B',
      'BG', 'BIIB', 'BKNG', 'BKR', 'BLK', 'BMY', 'BNY', 'BR', 'BRK.B', 'BRO',
      'BSX', 'BX', 'BXP', 'C', 'CAH', 'CARR', 'CASY', 'CAT', 'CB', 'CBOE',
      'CBRE', 'CCEP', 'CCI', 'CCL', 'CDNS', 'CDW', 'CEG', 'CF', 'CFG', 'CHD',
      'CHRW', 'CHTR', 'CI', 'CIEN', 'CINF', 'CL', 'CLX', 'CMCSA', 'CME', 'CMG',
      'CMI', 'CMS', 'CNC', 'CNP', 'COF', 'COHR', 'COIN', 'COO', 'COP', 'COR',
      'COST', 'CPAY', 'CPRT', 'CPT', 'CRH', 'CRL', 'CRM', 'CRWD', 'CRWV', 'CSCO',
      'CSGP', 'CSX', 'CTAS', 'CTSH', 'CVNA', 'CVS', 'CVX', 'D', 'DAL', 'DASH',
      'DD', 'DDOG', 'DE', 'DECK', 'DELL', 'DG', 'DGX', 'DHI', 'DHR', 'DIS',
      'DLR', 'DLTR', 'DOC', 'DOV', 'DOW', 'DPZ', 'DRI', 'DTE', 'DUK', 'DVA',
      'DVN', 'DXCM', 'EBAY', 'ECHO', 'ECL', 'ED', 'EFX', 'EG', 'EIX', 'EL',
      'ELV', 'EME', 'EMR', 'EOG', 'EQIX', 'EQT', 'ERIE', 'ES', 'ESS', 'ETN',
      'ETR', 'EVRG', 'EW', 'EXC', 'EXE', 'EXPD', 'EXPE', 'EXR', 'F', 'FANG',
      'FAST', 'FCX', 'FDS', 'FDX', 'FDXF', 'FE', 'FER', 'FERG', 'FFIV', 'FICO',
      'FIS', 'FITB', 'FIX', 'FLEX', 'FOX', 'FOXA', 'FRT', 'FSLR', 'FTNT', 'FTV',
      'GD', 'GDDY', 'GE', 'GEHC', 'GEN', 'GEV', 'GILD', 'GIS', 'GL', 'GLW',
      'GM', 'GNRC', 'GOOG', 'GPC', 'GPN', 'GRMN', 'GS', 'GWW', 'HAL', 'HAS',
      'HBAN', 'HCA', 'HD', 'HIG', 'HII', 'HLT', 'HON', 'HONA', 'HOOD', 'HPE',
      'HPQ', 'HRL', 'HSIC', 'HST', 'HSY', 'HUBB', 'HUM', 'HWM', 'IBKR', 'IBM',
      'ICE', 'IDXX', 'IEX', 'IFF', 'ILMN', 'INCY', 'INTC', 'INTU', 'INVH', 'IP',
      'IQV', 'IR', 'IRM', 'ISRG', 'IT', 'ITW', 'IVZ', 'J', 'JBHT', 'JBL',
      'JCI', 'JKHY', 'JNJ', 'KDP', 'KEY', 'KEYS', 'KHC', 'KIM', 'KKR', 'KLAC',
      'KMB', 'KMI', 'KO', 'KR', 'KVUE', 'L', 'LDOS', 'LEN', 'LH', 'LHX',
      'LII', 'LIN', 'LITE', 'LLY', 'LMT', 'LNT', 'LOW', 'LRCX', 'LULU', 'LUV',
      'LVS', 'LYB', 'LYV', 'MA', 'MAA', 'MAR', 'MAS', 'MCD', 'MCHP', 'MCK',
      'MCO', 'MDLZ', 'MDT', 'MELI', 'MET', 'MGM', 'MKC', 'MLM', 'MMM', 'MNST',
      'MO', 'MOS', 'MPC', 'MPWR', 'MRK', 'MRNA', 'MRSH', 'MRVL', 'MS', 'MSCI',
      'MSI', 'MSTR', 'MTB', 'MTD', 'MU', 'NBIS', 'NCLH', 'NDAQ', 'NDSN', 'NEE',
      'NEM', 'NFLX', 'NI', 'NKE', 'NOC', 'NOW', 'NRG', 'NSC', 'NTAP', 'NTRS',
      'NUE', 'NVR', 'NWS', 'NWSA', 'NXPI', 'O', 'ODFL', 'OKE', 'OMC', 'ON',
      'ORCL', 'ORLY', 'OTIS', 'OXY', 'P', 'PANW', 'PAYX', 'PCAR', 'PCG', 'PDD',
      'PEG', 'PEP', 'PFE', 'PFG', 'PG', 'PGR', 'PH', 'PHM', 'PKG', 'PLD',
      'PLTR', 'PM', 'PNC', 'PNR', 'PNW', 'PODD', 'PPG', 'PPL', 'PRU', 'PSA',
      'PSX', 'PTC', 'PWR', 'PYPL', 'Q', 'QCOM', 'RCL', 'RDDT', 'REG', 'REGN',
      'RF', 'RJF', 'RKLB', 'RL', 'RMD', 'ROK', 'ROL', 'ROP', 'ROST', 'RSG',
      'RTX', 'RVTY', 'SBAC', 'SBUX', 'SCHW', 'SHOP', 'SHW', 'SJM', 'SKYD', 'SLB',
      'SMCI', 'SNA', 'SNDK', 'SNPS', 'SO', 'SOLV', 'SPCX', 'SPG', 'SPGI', 'SRE',
      'STE', 'STLD', 'STT', 'STX', 'STZ', 'SW', 'SWK', 'SWKS', 'SYF', 'SYK',
      'SYY', 'T', 'TDG', 'TDY', 'TECH', 'TEL', 'TER', 'TFC', 'TGT', 'TJX',
      'TKO', 'TMO', 'TMUS', 'TPL', 'TPR', 'TRGP', 'TRI', 'TRMB', 'TROW', 'TRV',
      'TSCO', 'TSN', 'TT', 'TTWO', 'TWLO', 'TXN', 'TXT', 'TYL', 'UAL', 'UBER',
      'UDR', 'UHS', 'ULTA', 'UNH', 'UNP', 'UPS', 'URI', 'USB', 'V', 'VEEV',
      'VICI', 'VLO', 'VLTO', 'VMC', 'VMRK', 'VRSK', 'VRSN', 'VRT', 'VRTX', 'VST',
      'VTR', 'VTRS', 'VYLR', 'VZ', 'WAB', 'WAT', 'WDAY', 'WDC', 'WEC', 'WELL',
      'WFC', 'WM', 'WMB', 'WRB', 'WSM', 'WST', 'WTW', 'WY', 'WYNN', 'XEL',
      'XOM', 'XYL', 'XYZ', 'YUM', 'ZBH', 'ZBRA', 'ZTS'
  ))
    OR (provider = 'TIINGO' AND symbol IN (
      'AAPL', 'AMZN', 'AVGO', 'US000000000280', 'GOOGL', 'JPM', 'META', 'MSFT', 'NVDA', 'TSLA',
      'WMT'
  )))
ORDER BY trade_date, symbol, provider
LIMIT 100001;

-- 按真实价格键看同一五日窗口，预期 518 行；计数须结合完整字段证明。
SELECT symbol, provider, count() AS rows, min(trade_date) AS first_date, max(trade_date) AS last_date
FROM us_equity_daily
WHERE trade_date >= '2026-10-19' AND trade_date < '2026-10-24'
  AND ((provider = 'YAHOO' AND symbol IN (
      'A', 'ABBV', 'ABNB', 'ABT', 'ACGL', 'ACN', 'ADBE', 'ADI', 'ADM', 'ADP',
      'ADSK', 'AEE', 'AEP', 'AES', 'AFL', 'AIG', 'AIZ', 'AJG', 'AKAM', 'ALAB',
      'ALB', 'ALGN', 'ALL', 'ALLE', 'ALNY', 'AMAT', 'AMCR', 'AMD', 'AME', 'AMGN',
      'AMP', 'AMT', 'ANET', 'AON', 'AOS', 'APA', 'APD', 'APH', 'APO', 'APP',
      'APTV', 'ARE', 'ARES', 'ARM', 'ASML', 'ATO', 'AVY', 'AWK', 'AXON', 'AXP',
      'AZO', 'BA', 'BAC', 'BALL', 'BAX', 'BBY', 'BDX', 'BE', 'BEN', 'BF.B',
      'BG', 'BIIB', 'BKNG', 'BKR', 'BLK', 'BMY', 'BNY', 'BR', 'BRK.B', 'BRO',
      'BSX', 'BX', 'BXP', 'C', 'CAH', 'CARR', 'CASY', 'CAT', 'CB', 'CBOE',
      'CBRE', 'CCEP', 'CCI', 'CCL', 'CDNS', 'CDW', 'CEG', 'CF', 'CFG', 'CHD',
      'CHRW', 'CHTR', 'CI', 'CIEN', 'CINF', 'CL', 'CLX', 'CMCSA', 'CME', 'CMG',
      'CMI', 'CMS', 'CNC', 'CNP', 'COF', 'COHR', 'COIN', 'COO', 'COP', 'COR',
      'COST', 'CPAY', 'CPRT', 'CPT', 'CRH', 'CRL', 'CRM', 'CRWD', 'CRWV', 'CSCO',
      'CSGP', 'CSX', 'CTAS', 'CTSH', 'CVNA', 'CVS', 'CVX', 'D', 'DAL', 'DASH',
      'DD', 'DDOG', 'DE', 'DECK', 'DELL', 'DG', 'DGX', 'DHI', 'DHR', 'DIS',
      'DLR', 'DLTR', 'DOC', 'DOV', 'DOW', 'DPZ', 'DRI', 'DTE', 'DUK', 'DVA',
      'DVN', 'DXCM', 'EBAY', 'ECHO', 'ECL', 'ED', 'EFX', 'EG', 'EIX', 'EL',
      'ELV', 'EME', 'EMR', 'EOG', 'EQIX', 'EQT', 'ERIE', 'ES', 'ESS', 'ETN',
      'ETR', 'EVRG', 'EW', 'EXC', 'EXE', 'EXPD', 'EXPE', 'EXR', 'F', 'FANG',
      'FAST', 'FCX', 'FDS', 'FDX', 'FDXF', 'FE', 'FER', 'FERG', 'FFIV', 'FICO',
      'FIS', 'FITB', 'FIX', 'FLEX', 'FOX', 'FOXA', 'FRT', 'FSLR', 'FTNT', 'FTV',
      'GD', 'GDDY', 'GE', 'GEHC', 'GEN', 'GEV', 'GILD', 'GIS', 'GL', 'GLW',
      'GM', 'GNRC', 'GOOG', 'GPC', 'GPN', 'GRMN', 'GS', 'GWW', 'HAL', 'HAS',
      'HBAN', 'HCA', 'HD', 'HIG', 'HII', 'HLT', 'HON', 'HONA', 'HOOD', 'HPE',
      'HPQ', 'HRL', 'HSIC', 'HST', 'HSY', 'HUBB', 'HUM', 'HWM', 'IBKR', 'IBM',
      'ICE', 'IDXX', 'IEX', 'IFF', 'ILMN', 'INCY', 'INTC', 'INTU', 'INVH', 'IP',
      'IQV', 'IR', 'IRM', 'ISRG', 'IT', 'ITW', 'IVZ', 'J', 'JBHT', 'JBL',
      'JCI', 'JKHY', 'JNJ', 'KDP', 'KEY', 'KEYS', 'KHC', 'KIM', 'KKR', 'KLAC',
      'KMB', 'KMI', 'KO', 'KR', 'KVUE', 'L', 'LDOS', 'LEN', 'LH', 'LHX',
      'LII', 'LIN', 'LITE', 'LLY', 'LMT', 'LNT', 'LOW', 'LRCX', 'LULU', 'LUV',
      'LVS', 'LYB', 'LYV', 'MA', 'MAA', 'MAR', 'MAS', 'MCD', 'MCHP', 'MCK',
      'MCO', 'MDLZ', 'MDT', 'MELI', 'MET', 'MGM', 'MKC', 'MLM', 'MMM', 'MNST',
      'MO', 'MOS', 'MPC', 'MPWR', 'MRK', 'MRNA', 'MRSH', 'MRVL', 'MS', 'MSCI',
      'MSI', 'MSTR', 'MTB', 'MTD', 'MU', 'NBIS', 'NCLH', 'NDAQ', 'NDSN', 'NEE',
      'NEM', 'NFLX', 'NI', 'NKE', 'NOC', 'NOW', 'NRG', 'NSC', 'NTAP', 'NTRS',
      'NUE', 'NVR', 'NWS', 'NWSA', 'NXPI', 'O', 'ODFL', 'OKE', 'OMC', 'ON',
      'ORCL', 'ORLY', 'OTIS', 'OXY', 'P', 'PANW', 'PAYX', 'PCAR', 'PCG', 'PDD',
      'PEG', 'PEP', 'PFE', 'PFG', 'PG', 'PGR', 'PH', 'PHM', 'PKG', 'PLD',
      'PLTR', 'PM', 'PNC', 'PNR', 'PNW', 'PODD', 'PPG', 'PPL', 'PRU', 'PSA',
      'PSX', 'PTC', 'PWR', 'PYPL', 'Q', 'QCOM', 'RCL', 'RDDT', 'REG', 'REGN',
      'RF', 'RJF', 'RKLB', 'RL', 'RMD', 'ROK', 'ROL', 'ROP', 'ROST', 'RSG',
      'RTX', 'RVTY', 'SBAC', 'SBUX', 'SCHW', 'SHOP', 'SHW', 'SJM', 'SKYD', 'SLB',
      'SMCI', 'SNA', 'SNDK', 'SNPS', 'SO', 'SOLV', 'SPCX', 'SPG', 'SPGI', 'SRE',
      'STE', 'STLD', 'STT', 'STX', 'STZ', 'SW', 'SWK', 'SWKS', 'SYF', 'SYK',
      'SYY', 'T', 'TDG', 'TDY', 'TECH', 'TEL', 'TER', 'TFC', 'TGT', 'TJX',
      'TKO', 'TMO', 'TMUS', 'TPL', 'TPR', 'TRGP', 'TRI', 'TRMB', 'TROW', 'TRV',
      'TSCO', 'TSN', 'TT', 'TTWO', 'TWLO', 'TXN', 'TXT', 'TYL', 'UAL', 'UBER',
      'UDR', 'UHS', 'ULTA', 'UNH', 'UNP', 'UPS', 'URI', 'USB', 'V', 'VEEV',
      'VICI', 'VLO', 'VLTO', 'VMC', 'VMRK', 'VRSK', 'VRSN', 'VRT', 'VRTX', 'VST',
      'VTR', 'VTRS', 'VYLR', 'VZ', 'WAB', 'WAT', 'WDAY', 'WDC', 'WEC', 'WELL',
      'WFC', 'WM', 'WMB', 'WRB', 'WSM', 'WST', 'WTW', 'WY', 'WYNN', 'XEL',
      'XOM', 'XYL', 'XYZ', 'YUM', 'ZBH', 'ZBRA', 'ZTS'
  ))
    OR (provider = 'TIINGO' AND symbol IN (
      'AAPL', 'AMZN', 'AVGO', 'US000000000280', 'GOOGL', 'JPM', 'META', 'MSFT', 'NVDA', 'TSLA',
      'WMT'
  )))
GROUP BY symbol, provider
ORDER BY symbol, provider
LIMIT 100001;

-- WAL 与来源、Native 及完整键值证明一起核对。
SELECT name, writerTxn, sequencerTxn, bufferedTxnSize, suspended
FROM wal_tables()
WHERE name IN ('us_equity_daily','us_equity_intraday')
ORDER BY name;
