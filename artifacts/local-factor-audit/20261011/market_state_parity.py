import sys,json,math
from pathlib import Path
from types import SimpleNamespace as N
import pandas as pd,numpy as np
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_market_state_factor_frame
p=Path('artifacts/local-factor-audit/20261011');rows=[]
for s in range(4):
 for i in range(45):
  price=10+s+math.sin(i*.3+s)
  rows.append({'ts_code':f's{s}','trade_date':pd.Timestamp('2026-01-01')+pd.Timedelta(days=i),'price':np.nan if i%17==0 else price,
   'amount':np.nan if i%11==0 else 100+s*5+i,'turnover_rate':np.nan if (i+s)%13==0 else 1+s+i*.01,
   'up_limit':price if i%5==0 else price*1.1,'down_limit':price if i%7==0 else price*.9})
daily=pd.DataFrame(rows);daily['ret']=daily.groupby('ts_code').price.transform(lambda x:x.ffill().pct_change(fill_method=None))
defs=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['sourceGroup']=='market_state' and d['enabled']]
specs=[N(data_binding=N(source_group='market_state',source_column=d['sourceColumn'],asset_type='market'),lifecycle=N(enabled=True),spec=N(direction=N(value=d['direction']),factor_id=d['factorId'],family='market',frequency=N(value='daily'),version=d['version'])) for d in defs]
ref=build_market_state_factor_frame(specs,daily).pivot(index='trade_date',columns='factor_id',values='value_raw')
actual=pd.read_csv(p/'market-state-java.csv',parse_dates=['date']).set_index('date');assert actual.index.equals(ref.index)
checks={}
for c in ref.columns:checks[c]=int((~np.isclose(actual[c],ref[c],rtol=1e-9,atol=1e-11,equal_nan=True)).sum())
result={'dates':len(ref),'factors':len(ref.columns),'mismatches':checks};(p/'market-state-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert not any(checks.values())
