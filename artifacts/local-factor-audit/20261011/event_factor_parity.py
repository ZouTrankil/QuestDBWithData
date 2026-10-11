import json,sys,time
from pathlib import Path
from types import SimpleNamespace as N
import numpy as np
import pandas as pd
import requests
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_event_pit_factor_frame
root=Path(__file__).resolve().parent;session=requests.Session()
def fetch(sql):
    response=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=120)
    response.raise_for_status();body=response.json();assert 'error' not in body,body
    assert len(body['dataset'])<1000001
    return pd.DataFrame(body['dataset'],columns=[c['name'] for c in body['columns']])
state_sql="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('stk_holdertrade','repurchase','share_float','dividend','forecast') order by table_name"
before=fetch(state_sql);timer=time.perf_counter()
bindings={
 'holder_trade':('stk_holdertrade','ann_date','ann_date,ts_code','ts_code,ann_date,in_de,change_ratio'),
 'repurchase':('repurchase','ann_date','ann_date,ts_code,end_date','ts_code,ann_date,end_date,amount'),
 'share_float':('share_float','float_date','float_date,ts_code,ann_date','ts_code,ann_date,float_date,float_ratio'),
 'dividend':('dividend','ann_date','ann_date,ts_code,end_date','ts_code,ann_date,end_date,cash_div_tax'),
 'forecast':('forecast','ann_date','ann_date,ts_code,end_date','ts_code,ann_date,end_date,p_change_min,p_change_max')}
frames={}
for role,(table,column,order,fields) in bindings.items():
    clause=" AND ann_date<='20260930'" if role=='share_float' else ''
    frame=fetch(f"select {fields} from {table} where {column}>='2025-06-27' and {column}<'2026-10-01'{clause} order by {order}")
    frame.ann_date=pd.to_datetime(frame.ann_date,errors='coerce',format='mixed',utc=True).dt.tz_localize(None)
    frames[role]=frame;print(role,len(frame),flush=True)
assert before.equals(fetch(state_sql)), 'Source changed during reference read'
read_seconds=time.perf_counter()-timer
definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['sourceGroup']=='event_pit' and d['factorId'] not in ['ownership_margin_balance','ownership_short_balance'] and d['enabled']]
specs=[N(data_binding=N(source_group=d['sourceGroup'],source_column=d['sourceColumn'],asset_type='stock'),lifecycle=N(enabled=True),spec=N(factor_id=d['factorId'],direction=N(value=d['direction']),family='event',frequency=N(value='daily'),version=d['version'])) for d in definitions]
labels=pd.read_json(root/'normalized-python-repeat-values.json')[['ts_code','trade_date']]
timer=time.perf_counter();expected=build_event_pit_factor_frame(specs,labels,frames);compute_seconds=time.perf_counter()-timer
java_report=json.loads((root/'events-report.json').read_text())
for old in before.to_dict('records'):
    current=next(s for s in java_report['sourceState'] if s['table_name']==old['table_name'])
    for key in ['id','directoryName','table_txn','wal_txn']:assert current[key]==old[key]
java=json.loads((root/'events-observations.json').read_text())['observations']
actual=pd.DataFrame(java,columns=['date','type','ts_code','factor_id','version','value','available','quality','scope','lineage'])
keys=['factor_id','ts_code'];actual=actual.set_index(keys).sort_index();expected=expected.set_index(keys).sort_index()
assert actual.index.equals(expected.index)
np.testing.assert_allclose(actual.value.astype(float),expected.value_raw,rtol=1e-12,atol=1e-12,equal_nan=True)
counts=expected.groupby(level='factor_id').value_raw.count().astype(int).to_dict()
result=dict(rows=len(actual),mismatches=0,finiteValues=counts,sourceRows={k:len(v) for k,v in frames.items()},pythonReadSeconds=read_seconds,pythonComputeSeconds=compute_seconds,sourceVersions=before.to_dict('records'),published=False)
(root/'events-parity.json').write_text(json.dumps(result,indent=2,default=str));print(json.dumps(result,default=str),flush=True)
