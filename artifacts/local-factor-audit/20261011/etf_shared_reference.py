import etf_panel_reference as base
from quant_platform.research.engines.etf_factor_calculations import _build_share_factors
from quant_platform.research.engines.etf_factor_maturity import _build_recovery
pd=base.pd
market=base.expected.copy()
market['name']=market['ts_code']
market['category']='其他'
shares=[];components=[]
for row in market.to_dict('records'):
    day=(row['trade_date']-pd.Timestamp('2026-01-01')).days
    code=int(row['ts_code'][:6])
    if day%7==0:shares.append(dict(timestamp=row['trade_date'],ts_code=row['ts_code'],fd_share=1000+day*2+code%17))
    if day%13!=0:components.append(dict(trade_date=row['trade_date'],ts_code=row['ts_code'],component_up_breadth_1d=(day+code)%10/10))
shares=pd.DataFrame(shares);components=pd.DataFrame(components)
flow=_build_share_factors(market,{'etf_share':shares})
recovery=_build_recovery(market,components)
columns=['total_share','share_change_5d','share_delta','net_flow_yi','aum_yi','positive_flow','flow_intensity_5d','flow_persistence_5d']
expected=market.merge(flow[['trade_date','ts_code']+columns],on=['trade_date','ts_code'])
expected=expected.merge(recovery[['trade_date','ts_code','recovery_quality_5d']],on=['trade_date','ts_code'])
expected=expected.merge(shares.rename(columns={'timestamp':'trade_date','fd_share':'reported_share'}),on=['trade_date','ts_code'],how='left').merge(components,on=['trade_date','ts_code'],how='left')
names=['close','amount','reported_share','component_up_breadth_1d']+base.columns+columns+['recovery_quality_5d']
lines=['\t'.join(['symbol','date']+names)]
for row in expected.to_dict('records'):
    lines.append('\t'.join([row['ts_code'],str(row['trade_date'].date())]+[base.encode(row[c]) for c in names]))
(base.root/'etf-shared-reference.tsv').write_text('\n'.join(lines)+'\n')
