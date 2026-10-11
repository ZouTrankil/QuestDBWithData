import sys,json,requests
from pathlib import Path
from types import SimpleNamespace as N
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_fundamental_pit_frame
p=Path('artifacts/local-factor-audit/20261011');defs=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['sourceGroup']=='fundamental_pit'];cols=list(dict.fromkeys(d['sourceColumn'] for d in defs))
s=requests.Session()
def q(sql):
 r=s.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=90);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<1000001;return d
state="select id,directoryName,table_txn,wal_txn,walEnabled,table_suspended from tables() where table_name='fina_indicator'"
before=q(state)['dataset'];d=q("select ts_code,ann_date,end_date,"+','.join(cols)+" from fina_indicator where ann_date<'2026-10-01' and end_date<='20260930' order by end_date,ts_code,ann_date");assert before==q(state)['dataset']
f=pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']]);f.ann_date=pd.to_datetime(f.ann_date).dt.tz_localize(None)
labels=pd.read_json(p/'normalized-java-values.json')[['ts_code','trade_date']];labels.trade_date=pd.to_datetime(labels.trade_date)
specs=[N(data_binding=N(source_group='fundamental_pit',source_column=d['sourceColumn'],asset_type='stock'),lifecycle=N(enabled=d['enabled']),spec=N(direction=N(value=d['direction']),factor_id=d['factorId'],family='fundamental',frequency=N(value='daily'),version=d['version'])) for d in defs]
result=build_fundamental_pit_frame(specs,labels,f);result.to_json(p/'financial-python-values.json',orient='records',date_format='iso',double_precision=15)
(p/'financial-python-source.json').write_text(json.dumps({'sourceState':before,'sourceRows':len(f),'outputRows':len(result)}));print('Python actual fundamental builder:',len(f),'source rows ->',len(result),'factor rows',flush=True)
