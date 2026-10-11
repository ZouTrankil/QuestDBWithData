import json,math
from pathlib import Path
import pandas as pd,numpy as np
p=Path('artifacts/local-factor-audit/20261011');j=json.loads((p/'market-state-live-report.json').read_text());py=json.loads((p/'market-state-python-profile.json').read_text())
for source in py['sourceState']:
 actual=next(r for r in j['sourceState'] if r.get('table_name')==source['table_name'])
 for key,value in source.items():assert (value is None or isinstance(value,float) and math.isnan(value)) and actual.get(key) is None or value==actual.get(key),'Source differs'
a=pd.DataFrame(json.loads((p/'market-state-observations.json').read_text())['observations'],columns=['trade_date','entity_type','ts_code','factor_id','version','value_raw','available','quality','scope','lineage']);b=pd.read_json(p/'market-state-python-values.json')
for frame in (a,b):frame.trade_date=pd.to_datetime(frame.trade_date).dt.normalize()
assert set(a.entity_type)=={'market'}
keys=['trade_date','ts_code','factor_id'];a=a.set_index(keys).sort_index();b=b.set_index(keys).sort_index();assert a.index.is_unique and a.index.equals(b.index)
ok=np.isclose(a.value_raw.to_numpy(float),b.value_raw.to_numpy(float),rtol=1e-8,atol=1e-10,equal_nan=True)
result={'rows':len(a),'mismatches':int((~ok).sum()),'sourceVersionsEqual':True,'javaMarketComputeSeconds':j['marketStateComputeMillis']/1000,'pythonMarketComputeSeconds':py['computeSeconds'],'examples':[str(k) for k in a.index[~ok]]}
(p/'market-state-live-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert ok.all()
