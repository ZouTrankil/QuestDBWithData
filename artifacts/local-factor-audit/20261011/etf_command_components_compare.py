import json,sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_maturity import _build_recovery
root=Path(__file__).resolve().parent
report=json.loads((root/'etf-command-components-panel.json').read_text(encoding='utf-8'))
inputs=pd.read_csv(root/'etf-live-frozen/inputs.tsv',sep='\t');valid=set(inputs.symbol)
reference=json.loads((root/'etf-component-l2-frozen.json').read_text())
reference={r['etf']:r['columns'] for r in reference if r['etf'] in valid}
actual={r['symbol']:r['columns'] for r in report['components']}
assert actual.keys()==reference.keys()
checks=0;bad=0
for etf,columns in reference.items():
 for name,value in columns.items():
  expected=float(value) if value is not None else np.nan;observed=actual[etf][name];observed=float(observed) if observed is not None else np.nan
  checks+=1;bad+=int(not np.isclose(observed,expected,rtol=1e-10,atol=1e-10,equal_nan=True))
market=pd.read_csv(root/'etf-live-frozen/java.tsv',sep='\t').rename(columns={'symbol':'ts_code','date':'trade_date'})
market=market[market.trade_date.eq('2026-09-30')].copy();market.trade_date=pd.to_datetime(market.trade_date)
market['name']='';market['category']='其他'
component=pd.DataFrame([dict(ts_code=k,trade_date=pd.Timestamp('2026-09-30'),component_up_breadth_1d=v['component_up_breadth_1d']) for k,v in reference.items()])
recovery=_build_recovery(market,component).set_index('ts_code')
for section in report['securities']:
 assert section['dates']==['2026-09-30']
 for name in ['component_up_breadth_1d','recovery_quality_5d']:
  observed=section['columns'][name][0];observed=np.nan if observed is None else float(observed)
  checks+=1;bad+=int(not np.isclose(observed,recovery.loc[section['symbol'],name],rtol=1e-10,atol=1e-10,equal_nan=True))
result=dict(componentEtfs=len(actual),marketEtfs=len(report['securities']),checks=checks,mismatches=bad,stockSourceRows=report['stockSourceRows'],factorReady=report['factorReady'])
(root/'etf-command-components-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
