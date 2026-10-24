-- 四家历史普通股的固定来源窗口，二十列价格加五列身份及事件说明。
-- dividend 是正式现金语义，source_div_cash 保留已核对的来源调整额；不把来源末日当作法律终止时间。
select p.*,i.source_ticker,i.security_name,
  case when (p.symbol='US000000086368' and p.trade_date='2014-04-07T00:00:00Z') then 9.52618 else p.dividend end as source_div_cash,
  case when (p.symbol='US000000086368' and p.trade_date='2014-04-07T00:00:00Z') then 'NON_CASH_SPIN_OFF' when p.dividend>0 then 'SOURCE_REPORTED_CASH_DIVIDEND' else 'NONE' end as distribution_kind,
  case when (p.symbol='US000000086368' and p.trade_date='2014-04-07T00:00:00Z') then 'https://www.sec.gov/Archives/edgar/data/799288/000119312514134340/d703946dex992.htm' else null end as primary_event_source
from us_equity_daily p join us_security_identifiers i on p.symbol=i.price_symbol and p.provider=i.provider
  and p.source_sha256=i.price_source_sha256
where p.trade_date>='2006-01-01T00:00:00Z' and p.trade_date<'2023-09-30T00:00:00Z'
  and p.provider='TIINGO' and p.symbol in ('US000000002604','US000000010155','US000000000258','US000000086368')
  and i.source_sha256='0e6b074517624d5926f113a9e9090c3b06713b6b9a3eb381921ceed5f6743da7'
order by p.trade_date,p.symbol,p.provider limit 100001;
