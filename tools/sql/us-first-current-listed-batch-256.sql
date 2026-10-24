-- 首批非核心、非固定持仓的 256 个挂牌候选，保留供应商及原始二十列。
-- 本查询只读 2026 年至 10 月 16 日；其他年份按单年范围分别读取。
-- 短来源不代表满五年或完整上市史，实际范围见本批独立验收。
SELECT
    trade_date,
    symbol,
    provider,
    venue,
    open,
    high,
    low,
    close,
    volume,
    adj_open,
    adj_high,
    adj_low,
    adj_close,
    adj_volume,
    dividend,
    split_factor,
    currency,
    raw_price_basis,
    source_sha256,
    observed_at
FROM us_equity_daily
WHERE provider = 'YAHOO'
  AND trade_date >= '2026-01-01'
  AND trade_date < '2026-10-17'
  AND symbol IN (
      'AA', 'AAC', 'AACG', 'AACI', 'AACO', 'AACP', 'AADX', 'AAL',
      'AAME', 'AAMI', 'AAOI', 'AAON', 'AAP', 'AAPG', 'AARD', 'AAT',
      'AAUC', 'ABAT', 'ABCB', 'ABCL', 'ABEO', 'ABEV', 'ABG', 'ABLV',
      'ABM', 'ABOS', 'ABR', 'ABSI', 'ABTC', 'ABTS', 'ABUS', 'ABVC',
      'ABVX', 'ABX', 'ACA', 'ACAA', 'ACAD', 'ACB', 'ACCL', 'ACCO',
      'ACCS', 'ACCV', 'ACDC', 'ACEL', 'ACET', 'ACFN', 'ACGC', 'ACH',
      'ACHC', 'ACHR', 'ACHV', 'ACI', 'ACIC', 'ACIU', 'ACIW', 'ACLS',
      'ACM', 'ACMR', 'ACNB', 'ACNT', 'ACOG', 'ACON', 'ACR', 'ACRE',
      'ACRS', 'ACRV', 'ACT', 'ACTG', 'ACTU', 'ACU', 'ACXP', 'AD',
      'ADAC', 'ADAG', 'ADAM', 'ADBT', 'ADC', 'ADCT', 'ADEA', 'ADGM',
      'ADIG', 'ADIL', 'ADMA', 'ADNT', 'ADPT', 'ADRX', 'ADSE', 'ADT',
      'ADTN', 'ADUR', 'ADUS', 'ADV', 'ADVB', 'ADXN', 'AEAQ', 'AEBI',
      'AEC', 'AEG', 'AEHL', 'AEHR', 'AEI', 'AEIS', 'AEM', 'AEMD',
      'AENT', 'AEO', 'AEON', 'AER', 'AERO', 'AERT', 'AESI', 'AESP',
      'AEVA', 'AEXA', 'AEYE', 'AFCG', 'AFG', 'AFJK', 'AFRI', 'AFRM',
      'AFYA', 'AG', 'AGBK', 'AGCC', 'AGCO', 'AGEN', 'AGI', 'AGIG',
      'AGIO', 'AGL', 'AGM', 'AGM.A', 'AGMB', 'AGMH', 'AGNC', 'AGNT',
      'AGO', 'AGPU', 'AGRO', 'AGRZ', 'AGX', 'AGYS', 'AHCO', 'AHG',
      'AHMA', 'AHR', 'AHRT', 'AHT', 'AI', 'AIAI', 'AIB', 'AIBZ',
      'AIDX', 'AIFA', 'AIFC', 'AIFF', 'AIFU', 'AII', 'AIIA', 'AIIO',
      'AIIR', 'AIM', 'AIMD', 'AIN', 'AIOK', 'AIOS', 'AIOT', 'AIP',
      'AIR', 'AIRE', 'AIRG', 'AIRI', 'AIRJ', 'AIRO', 'AIRS', 'AIRT',
      'AISP', 'AIT', 'AIV', 'AIXI', 'AKA', 'AKAN', 'AKBA', 'AKO.A',
      'AKO.B', 'AKR', 'AKTS', 'AKTX', 'ALAR', 'ALC', 'ALCO', 'ALDF',
      'ALDX', 'ALEC', 'ALF', 'ALG', 'ALGM', 'ALGS', 'ALGT', 'ALH',
      'ALHC', 'ALIS', 'ALIT', 'ALK', 'ALKS', 'ALKT', 'ALLO', 'ALLR',
      'ALLT', 'ALLY', 'ALM', 'ALMR', 'ALMS', 'ALMU', 'ALNT', 'ALOV',
      'ALOY', 'ALP', 'ALPS', 'ALPX', 'ALRM', 'ALRS', 'ALSN', 'ALT',
      'ALTG', 'ALTI', 'ALTO', 'ALUB', 'ALV', 'ALVO', 'ALX', 'ALXO',
      'ALZN', 'AM', 'AMAC', 'AMAL', 'AMAN', 'AMBA', 'AMBO', 'AMBP',
      'AMBQ', 'AMBR', 'AMC', 'AMCI', 'AMCX', 'AMG', 'AMH', 'AMIX',
      'AMKR', 'AMLX', 'AMN', 'AMOD', 'AMPG', 'AMPH', 'AMPL', 'AMPX'
)
ORDER BY trade_date, symbol, provider
LIMIT 100001;
