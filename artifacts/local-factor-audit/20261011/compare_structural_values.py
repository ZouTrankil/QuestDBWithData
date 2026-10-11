import json,pandas as pd,numpy as np
from pathlib import Path
p=Path('artifacts/local-factor-audit/20261011');jr=json.loads((p/'structural-java-report.json').read_text());pr=json.loads((p/'structural-python-profile.json').read_text());assert [r for r in jr['sourceState'] if r.get('table_name') in ('income','balance','cashflow')]==pr['sourceState']
a=pd.DataFrame(json.loads((p/'structural-observations.json').read_text())['observations'],columns=['trade_date','type','ts_code','factor_id','version','value','available','quality','scope','lineage']);b=pd.read_json(p/'structural-python-values.json');b=b.melt(id_vars=['trade_date','ts_code'],var_name='factor_id',value_name='value')
keys=['trade_date','ts_code','factor_id']
for f in (a,b):f.trade_date=pd.to_datetime(f.trade_date).dt.normalize()
a=a.set_index(keys).sort_index();b=b.set_index(keys).sort_index();assert a.index.equals(b.index),'Output identities differ'
ok=np.isclose(a.value.to_numpy(float),b.value.to_numpy(float),rtol=1e-8,atol=1e-10,equal_nan=True)
r={'rows':len(a),'mismatches':int((~ok).sum()),'missingJava':int(a.value.isna().sum()),'missingPython':int(b.value.isna().sum()),'examples':[str(k) for k in a.index[~ok][:8]],'sourceVersionsEqual':True};(p/'structural-value-parity.json').write_text(json.dumps(r,indent=2));print(json.dumps(r));assert ok.all()
