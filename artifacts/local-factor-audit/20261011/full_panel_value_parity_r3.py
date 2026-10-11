import json
from pathlib import Path
import numpy as np,pandas as pd
root=Path('artifacts/local-factor-audit/20261011')
a=pd.read_json(root/'full-panel-java-values-r3.json');b=pd.read_json(root/'full-panel-python-values.json')
for frame in (a,b):frame['trade_date']=pd.to_datetime(frame.trade_date).dt.normalize()
a=a.set_index(['ts_code','trade_date']).sort_index();b=b.set_index(['ts_code','trade_date']).sort_index();assert a.index.equals(b.index),'security/date keys differ'
checks=[]
for name in sorted(set(a.columns)&set(b.columns)):
 x=a[name].to_numpy(float);y=b[name].to_numpy(float);good=np.isclose(x,y,rtol=1e-8,atol=1e-10,equal_nan=True);finite=np.isfinite(x)&np.isfinite(y)
 checks.append({'column':name,'mismatches':int((~good).sum()),'max_abs_error':float(np.max(np.abs(x[finite]-y[finite]))) if finite.any() else None,'examples':list(a.index[~good].get_level_values('ts_code')[:5])})
report={'rows':len(a),'comparedColumns':len(checks),'failed':[r for r in checks if r['mismatches']],'checks':checks,'javaOnlyColumns':sorted(set(a.columns)-set(b.columns)),'pythonOnlyColumns':sorted(set(b.columns)-set(a.columns))};(root/'full-panel-value-parity-r3.json').write_text(json.dumps(report,indent=2));print(json.dumps({k:v for k,v in report.items() if k!='checks'},indent=2));assert not report['failed']
