-- 四家历史普通股的固定来源窗口，二十列价格加六列身份、现金依据和报价质量。
-- 正式现金额与原 divCash 分列；来源调整价保持，零量平价不证明实际成交。
select p.*,i.source_ticker,i.security_name,
  case
    when (p.symbol='US000000041341' and p.trade_date='2007-06-27T00:00:00Z') then 0.16
    when (p.symbol='US000000041341' and p.trade_date='2008-09-29T00:00:00Z') then 0.13
    when (p.symbol='US000000041341' and p.trade_date='2013-06-10T00:00:00Z') then 1.49
    when (p.symbol='US000000009458' and p.trade_date='2007-12-13T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2008-03-07T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2008-06-06T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2008-09-08T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2008-12-08T00:00:00Z') then 0.08
    when (p.symbol='US000000009458' and p.trade_date='2011-06-08T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2011-09-07T00:00:00Z') then 0.08
    when (p.symbol='US000000009458' and p.trade_date='2011-12-07T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2012-03-07T00:00:00Z') then 0.08
    when (p.symbol='US000000009458' and p.trade_date='2012-06-07T00:00:00Z') then 0.07
    when (p.symbol='US000000009458' and p.trade_date='2012-09-06T00:00:00Z') then 0.08
    when (p.symbol='US000000009458' and p.trade_date='2012-12-05T00:00:00Z') then 0.08
    else p.dividend end as source_div_cash,
  case when (p.symbol='US000000041341' and p.trade_date='2007-06-27T00:00:00Z') or (p.symbol='US000000041341' and p.trade_date='2008-09-29T00:00:00Z') or (p.symbol='US000000041341' and p.trade_date='2013-06-10T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2007-12-13T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2008-03-07T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2008-06-06T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2008-09-08T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2008-12-08T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2011-06-08T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2011-09-07T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2011-12-07T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2012-03-07T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2012-06-07T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2012-09-06T00:00:00Z') or (p.symbol='US000000009458' and p.trade_date='2012-12-05T00:00:00Z') then 'ISSUER_VERIFIED_CASH' else 'TIINGO_REPORTED_CASH' end as cash_value_basis,
  case
    when (p.symbol='US000000041341' and p.trade_date='2007-06-27T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/12978/000104746909001823/a2190902z10-k.htm'
    when (p.symbol='US000000041341' and p.trade_date='2008-09-29T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/12978/000104746909001823/a2190902z10-k.htm'
    when (p.symbol='US000000041341' and p.trade_date='2013-06-10T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/12978/000119312513203859/d533500dex991.htm'
    when (p.symbol='US000000009458' and p.trade_date='2007-12-13T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2008-03-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2008-06-06T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2008-09-08T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2008-12-08T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2011-06-08T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000119312512087802/d228252d10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2011-09-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000119312512087802/d228252d10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2011-12-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000119312512087802/d228252d10k.htm'
    when (p.symbol='US000000009458' and p.trade_date='2012-03-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000119312512343042/d362139d10q.htm'
    when (p.symbol='US000000009458' and p.trade_date='2012-06-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000119312512343042/d362139d10q.htm'
    when (p.symbol='US000000009458' and p.trade_date='2012-09-06T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000005925512000277/timet10q0912.htm'
    when (p.symbol='US000000009458' and p.trade_date='2012-12-05T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/1011657/000005925512000291/tiepr121120.htm'
    else null end as issuer_cash_source,
  case when p.volume=0 and p.open=p.high and p.high=p.low and p.low=p.close then 'SOURCE_ZERO_VOLUME_FLAT_UNVERIFIED' else 'SOURCE_REPORTED_BAR' end as source_quote_quality
from us_equity_daily p join us_security_identifiers i on p.symbol=i.price_symbol and p.provider=i.provider
  and p.source_sha256=i.price_source_sha256
where p.trade_date>='2006-01-01T00:00:00Z' and p.trade_date<'2017-09-13T00:00:00Z'
  and p.provider='TIINGO' and p.symbol in ('US000000002661','US000000041341','US000000002099','US000000009458')
  and i.source_sha256='d47f15415c12d926d5b9130e7adf2c792719f1e52080a34c2671a3ca639a89f7'
order by p.trade_date,p.symbol,p.provider limit 100001;
