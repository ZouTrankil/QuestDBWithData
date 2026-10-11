import sys,json
from pathlib import Path
import numpy as np,pandas as pd
root=Path('artifacts/local-factor-audit/20261011');sys.path.insert(0,str((root/'benchmark-deps').resolve()));sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.factor_platform.factor_platform_daily_polars import build_daily_market_polars
source=json.loads((root/'daily-feature-java-direct.json').read_text())['input'];parts=[];statuses=[]
for s in range(3):
 frame=pd.DataFrame(source['columns']);frame['trade_date']=pd.to_datetime(source['dates']);frame['ts_code']=f'00000{s+1}.SZ';frame['close']*=1+s*.05*np.sin(np.arange(len(frame))*.1);frame['total_mv']*=1+s*.7
 if s==2:frame['vol']=np.nan
 parts.append(frame);statuses.append({'ts_code':f'00000{s+1}.SZ','list_date':f'{2000+s}-01-01'})
frame=pd.concat(parts,ignore_index=True);expected=build_daily_market_polars(frame,pd.DataFrame(),pd.DataFrame(statuses),start=frame.trade_date.min(),end=frame.trade_date.max()).sort_values(['ts_code','trade_date'])
actual=pd.DataFrame(json.loads((root/'panel-java.json').read_text())).sort_values(['ts_code','trade_date']);checks=[]
for name in ['market_return','nonlinear_size','listing_days','market_beta_252','residual_vol_252','volume_momentum']:
 a=actual[name].to_numpy(float);b=expected[name].to_numpy(float);good=np.isclose(a,b,rtol=1e-8,atol=1e-10,equal_nan=True);checks.append({'feature':name,'mismatches':int((~good).sum())})
report={'rows':len(actual),'synthetic_securities_from_real_snapshot':3,'checks':checks};(root/'panel-parity.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2));assert all(x['mismatches']==0 for x in checks)
