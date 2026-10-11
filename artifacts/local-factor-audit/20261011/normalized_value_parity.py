import json
from pathlib import Path
import pandas as pd,numpy as np
p=Path('artifacts/local-factor-audit/20261011')
ja=json.loads((p/'normalized-java-profile.json').read_text());py=json.loads((p/'normalized-python-profile.json').read_text())
a_state=pd.DataFrame(ja['sourceState']).sort_values('table_name').reset_index(drop=True);b_state=pd.DataFrame(py['sourceState']).sort_values('table_name').reset_index(drop=True)
assert a_state.fillna(-1).equals(b_state.fillna(-1)),'Different source versions'
a=pd.read_json(p/'normalized-java-values.json');b=pd.read_json(p/'normalized-python-values.json')
for frame in (a,b):frame['trade_date']=pd.to_datetime(frame.trade_date).dt.normalize()
a=a.set_index(['ts_code','trade_date']).sort_index();b=b.set_index(['ts_code','trade_date']).sort_index();assert a.index.is_unique and b.index.is_unique;assert a.index.equals(b.index)
checks=[]
for col in sorted(set(a.columns)&set(b.columns)):
 x=a[col].to_numpy(float);y=b[col].to_numpy(float);ok=np.isclose(x,y,rtol=1e-8,atol=1e-10,equal_nan=True)
 checks.append({'column':col,'mismatches':int((~ok).sum()),'examples':list(a.index[~ok].get_level_values('ts_code')[:5])})
r={'rows':len(a),'comparedColumns':len(checks),'checks':checks,'failed':[c for c in checks if c['mismatches']],'sourceVersionsEqual':True,'javaOnly':sorted(set(a.columns)-set(b.columns)),'pythonOnly':sorted(set(b.columns)-set(a.columns))}
(p/'normalized-value-parity.json').write_text(json.dumps(r,indent=2));print(json.dumps({k:v for k,v in r.items() if k!='checks'},indent=2));assert not r['failed']
