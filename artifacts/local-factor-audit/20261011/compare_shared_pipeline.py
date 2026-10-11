import json,math,argparse
from pathlib import Path
import pandas as pd,numpy as np
p=Path('artifacts/local-factor-audit/20261011')
parser=argparse.ArgumentParser();parser.add_argument('--python-prefix',default='normalized-python');options=parser.parse_args()
report=json.loads((p/'shared-final-report.json').read_text())
python_report=json.loads((p/(options.python_prefix+'-profile.json')).read_text())
for source in python_report['sourceState']:
 actual=next(r for r in report['sourceState'] if r.get('table_name')==source['table_name'])
 for key,value in source.items():
  assert (value is None or isinstance(value,float) and math.isnan(value)) and actual.get(key) is None or value==actual.get(key),'Python source version differs'
old=json.loads((p/'structural-java-report.json').read_text())
for state in old['sourceState']:
 assert state in report['sourceState'],'Source snapshot differs'
financial=json.loads((p/'financial-panel-report.json').read_text())
for state in financial['sourceState']:
 assert state in report['sourceState'],'Financial source snapshot differs'
expected={}
for file in ['panel-observations.json','financial-panel-observations.json','structural-observations.json']:
 for row in json.loads((p/file).read_text())['observations']:
  key=tuple(row[:5]);assert key not in expected;expected[key]=row
rows=json.loads((p/'shared-final-observations.json').read_text())['observations']
assert len(rows)==len(expected)
seen=set();bad=[]
for row in rows:
 key=tuple(row[:5]);assert key not in seen;seen.add(key);ref=expected[key]
 ok=(row[5] is None and ref[5] is None) or (row[5] is not None and ref[5] is not None and math.isclose(row[5],ref[5],rel_tol=1e-8,abs_tol=1e-10))
 if not ok or row[6:]!=ref[6:]:bad.append(key)
a=pd.read_json(p/'shared-final-values.json');b=pd.read_json(p/(options.python_prefix+'-values.json'))
for f in (a,b):f.trade_date=pd.to_datetime(f.trade_date).dt.normalize()
keys=['ts_code','trade_date'];a=a.set_index(keys).sort_index();b=b.set_index(keys).sort_index();assert a.index.equals(b.index)
columns=sorted(set(a.columns)&set(b.columns));fail={}
for c in columns:
 n=int((~np.isclose(a[c].to_numpy(float),b[c].to_numpy(float),rtol=1e-8,atol=1e-10,equal_nan=True)).sum())
 if n:fail[c]=n
result={'observationRows':len(rows),'observationMismatches':len(bad),'dailyRows':len(a),'dailyComparedColumns':len(columns),'dailyMismatches':fail,'sourceVersionsEqual':True,'pythonReference':options.python_prefix}
(p/'shared-final-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert not bad and not fail
