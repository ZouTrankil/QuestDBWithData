import sys,json,math
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_market
from quant_platform.research.engines.etf_factor_maturity import _risk_context
root=Path(__file__).resolve().parent
if sys.argv[1]=='generate':
 rows=[]
 for s in range(125):
  for i,date in enumerate(pd.date_range('2026-01-01',periods=145)):
   if s%11==0 and i%17==0:continue
   close=10 if s%13==0 else 10+s*.02+math.sin(i/7+s/13)+i*.001
   if i in [71,72] or s%7==0 and i%9==0:close=np.nan
   amount=1000+s%110 if i>=45 else 100000-s
   if s>=120:amount=np.nan
   rows.append(dict(ts_code=f'E{s:03}',timestamp=date,close=close,amount=amount))
 frame=pd.DataFrame(rows);frame.rename(columns={'ts_code':'symbol','timestamp':'date'}).to_csv(root/'etf-risk-boundary-input.tsv',sep='\t',index=False,na_rep='NaN')
 market=_build_market({'etf_market':frame});expected=_risk_context(market)
 expected.to_csv(root/'etf-risk-boundary-expected.tsv',sep='\t',index=False,na_rep='NaN')
 recent=market[market.trade_date.isin(sorted(market.trade_date.unique())[-100:])]
 (root/'etf-risk-boundary-selected.txt').write_text('\n'.join(recent.groupby('ts_code').amount.mean().nlargest(100).index)+'\n')
 print('145 input dates, 125 ETFs')
else:
 expected=pd.read_csv(root/'etf-risk-boundary-expected.tsv',sep='\t').set_index('trade_date')
 actual=pd.read_csv(root/'etf-risk-boundary-java.tsv',sep='\t').set_index('trade_date')
 assert actual.index.tolist()==expected.index.tolist()
 assert (root/'etf-risk-boundary-selected.txt').read_text().splitlines()==(root/'etf-risk-boundary-java.tsv.symbols').read_text().splitlines()
 bad=0;maximum=0
 for c in expected.columns.drop('state'):
  a=actual[c].to_numpy(float);b=expected[c].to_numpy(float);bad+=int((~np.isclose(a,b,rtol=1e-9,atol=1e-9,equal_nan=True)).sum())
  mask=np.isfinite(a)&np.isfinite(b)
  if mask.any():maximum=max(maximum,float(np.max(np.abs(a[mask]-b[mask]))))
 assert actual.state.tolist()==expected.state.tolist()
 result=dict(inputDates=145,inputEtfs=125,outputDates=len(actual),checks=len(actual)*6,mismatches=bad,maxAbsoluteError=maximum,selectedSymbolsMatched=True)
 (root/'etf-risk-boundary-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
