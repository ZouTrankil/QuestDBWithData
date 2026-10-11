import json,sys
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_maturity import _factor_value_parts,_style_factor_parts,_factor_rows
root=Path(__file__).parent
d=json.loads((root/'etf-long-history-panel.json').read_text(encoding='utf-8'))
e=json.loads((root/'etf-long-history-observations.json').read_text())
catalog=json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions']
specs={r['sourceColumn']:r for r in catalog if r['assetType']=='etf' and r['enabled'] and r['publishObservations']}
def frame(rows):
    f=pd.DataFrame(rows).rename(columns={'symbol':'ts_code','date':'trade_date'})
    if 'trade_date' in f:f.trade_date=pd.to_datetime(f.trade_date)
    return f
market=pd.concat([pd.DataFrame(dict(s['columns'],trade_date=pd.to_datetime(s['dates']),ts_code=s['symbol'])) for s in d['securities']],ignore_index=True)
market['name']='';market['category']=''
components=frame([dict(r['columns'],symbol=r['symbol'],date=r['date']) for r in d['components']])
technical=frame(d['technical']).rename(columns={'technical_rsi_6d':'rsi_bfq_6'})
parts=_factor_value_parts({'etf_factor':technical},market=market,share=market,component=components,earnings=frame(d['earnings']),recovery=market,etf_l2=frame(d['selfL2']))
csi=frame(d['csiStyles']).rename(columns={'factor':'factor_id','componentCount':'component_count','announced':'constituent_ann_date','periodEnd':'constituent_end_date'})
parts+=_style_factor_parts(csi,market)
risk=frame(d['riskContext']).rename(columns={'correlation_z_60d':'market_correlation_shock_20d'});risk['ts_code']='MARKET.CN'
parts.append(_factor_rows(risk,'market_correlation_shock_20d'))
expected=pd.concat(parts,ignore_index=True);expected=expected[expected.factor_id.isin(specs)].copy()
expected.factor_id=expected.factor_id.map(lambda x:specs[x]['factorId'])
actual=pd.DataFrame(e['observations'],columns=['trade_date','entity_type','ts_code','factor_id','version','value','available','quality','scope','lineage']);actual.trade_date=pd.to_datetime(actual.trade_date)
keys=['trade_date','ts_code','factor_id'];expected=expected.set_index(keys).sort_index();actual=actual.set_index(keys).sort_index()
assert not expected.index.duplicated().any() and not actual.index.duplicated().any()
assert list(expected.index)==list(actual.index)
bad=int((~np.isclose(expected.value.to_numpy(float),actual.value.to_numpy(float),rtol=1e-12,atol=1e-12,equal_nan=True)).sum())
assert set(actual.entity_type)=={'etf'} and set(actual.scope)=={'GLOBAL'} and set(actual.quality)=={'complete'}
result={'rows':len(actual),'factors':actual.index.get_level_values('factor_id').nunique(),'mismatches':bad,'scope':'Python maturity emission applied to identical computed panels; formula/source coverage audited separately'}
(root/'etf-export-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
