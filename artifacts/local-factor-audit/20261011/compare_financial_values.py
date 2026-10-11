import json
from pathlib import Path
import pandas as pd,numpy as np
p=Path('artifacts/local-factor-audit/20261011');report=json.loads((p/'financial-panel-report.json').read_text());source=json.loads((p/'financial-python-source.json').read_text())
jstate=[r for r in report['sourceState'] if 'walEnabled' in r];assert len(jstate)==1
fields=['id','directoryName','table_txn','wal_txn','walEnabled','table_suspended'];assert [[r[k] for k in fields] for r in jstate]==source['sourceState'],'Source mismatch'
a=pd.DataFrame(json.loads((p/'financial-panel-observations.json').read_text())['observations'],columns=['trade_date','entity_type','ts_code','factor_id','version','value_raw','available_at','quality','scope','lineage']);b=pd.read_json(p/'financial-python-values.json')
keys=['trade_date','ts_code','factor_id']
for f in (a,b):f.trade_date=pd.to_datetime(f.trade_date).dt.normalize()
a=a.set_index(keys).sort_index();b=b.set_index(keys).sort_index();assert a.index.is_unique and b.index.is_unique and a.index.equals(b.index)
x=a.value_raw.to_numpy(float);y=b.value_raw.to_numpy(float);ok=np.isclose(x,y,rtol=1e-8,atol=1e-10,equal_nan=True)
r={'rows':len(a),'sourceRows':source['sourceRows'],'mismatches':int((~ok).sum()),'missingJava':int(np.isnan(x).sum()),'missingPython':int(np.isnan(y).sum()),'examples':[str(k) for k in a.index[~ok][:10]],'sourceVersionsEqual':True}
(p/'financial-value-parity.json').write_text(json.dumps(r,indent=2));print(json.dumps(r));assert ok.all()
