import sys,time,json,base64
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_market,_build_share_factors
from quant_platform.research.engines.etf_factor_maturity import _build_recovery
root=Path(sys.argv[1]);inputs=pd.read_csv(root/'inputs.tsv',sep='\t');shares=pd.read_csv(root/'shares.tsv',sep='\t')
inputs['name']=inputs.name_base64.fillna('').map(lambda x:base64.b64decode(x).decode())
reference=inputs[['symbol','name']].drop_duplicates('symbol').rename(columns={'symbol':'ts_code'})
frames={'etf_market':inputs[['symbol','date','close','amount']].rename(columns={'symbol':'ts_code','date':'timestamp'}),'etf_reference':reference,'etf_share':shares.rename(columns={'symbol':'ts_code','date':'timestamp'})}
start=time.perf_counter();market=_build_market(frames);flow=_build_share_factors(market,frames);recovery=_build_recovery(market,pd.DataFrame());seconds=time.perf_counter()-start
expected=market.set_index(['ts_code','trade_date'])
for frame in [flow,recovery]:
    frame=frame.set_index(['ts_code','trade_date'])
    for column in frame.columns:
        if column not in expected:expected[column]=frame[column]
actual=pd.read_csv(root/'java.tsv',sep='\t').rename(columns={'symbol':'ts_code','date':'trade_date'})
actual.trade_date=pd.to_datetime(actual.trade_date);actual=actual.set_index(['ts_code','trade_date'])
assert actual.index.is_unique and expected.index.is_unique and set(actual.index)==set(expected.index)
expected=expected.reindex(actual.index);checks=0;bad=0;max_error=0.;finite=0
for column in actual:
    a=actual[column].to_numpy(float);b=expected[column].to_numpy(float)
    equal=np.isclose(a,b,rtol=1e-9,atol=1e-9,equal_nan=True);checks+=len(a);bad+=int((~equal).sum())
    mask=np.isfinite(a)&np.isfinite(b);finite+=int(mask.sum())
    if mask.any():max_error=max(max_error,float(np.max(np.abs(a[mask]-b[mask])/np.maximum(1,np.abs(b[mask])))))
result=dict(rows=len(actual),checks=checks,finiteChecks=finite,mismatches=bad,maxScaledError=max_error,pythonComputeSeconds=seconds,input='same frozen Java source snapshot',published=False)
(root/'parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
