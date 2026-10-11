import sys,json
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_maturity import _risk_context
root=Path(__file__).resolve().parent
inputs=pd.read_csv(root/'etf-live-frozen/inputs.tsv',sep='\t').rename(columns={'symbol':'ts_code','date':'trade_date'})
features=pd.read_csv(root/'etf-live-frozen/java.tsv',sep='\t').rename(columns={'symbol':'ts_code','date':'trade_date'})
market=inputs.merge(features[['ts_code','trade_date','ret1']],on=['ts_code','trade_date']);market.trade_date=pd.to_datetime(market.trade_date)
expected=_risk_context(market).set_index('trade_date');actual=pd.read_csv(root/'etf-risk-live.tsv',sep='\t');actual.trade_date=pd.to_datetime(actual.trade_date);actual=actual.set_index('trade_date')
assert list(actual.index)==list(expected.index)
selected=market.groupby('ts_code').amount.mean().nlargest(100).index.tolist();assert selected==(root/'etf-risk-live.tsv.symbols').read_text().splitlines()
bad=0;maximum=0
for c in expected.columns.drop('state'):
 a=actual[c].to_numpy(float);b=expected[c].to_numpy(float);bad+=int((~np.isclose(a,b,rtol=1e-9,atol=1e-9,equal_nan=True)).sum())
 mask=np.isfinite(a)&np.isfinite(b)
 if mask.any():maximum=max(maximum,float(np.max(np.abs(a[mask]-b[mask]))))
assert actual.state.tolist()==expected.state.tolist()
result=dict(dates=len(actual),checks=len(actual)*6,mismatches=bad,maxAbsoluteError=maximum,selectedSymbolsMatched=True)
(root/'etf-risk-live-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
