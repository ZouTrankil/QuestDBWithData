import argparse,json,sys,time
from pathlib import Path
from types import SimpleNamespace as N
import numpy as np
import pandas as pd
import requests
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.data.adapters.questdb.margin_completeness import margin_source_end,margin_available_dates
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_event_pit_factor_frame
parser=argparse.ArgumentParser();parser.add_argument('--reference-only',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parent;session=requests.Session()
def fetch(sql):
    response=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1500001'},timeout=120)
    response.raise_for_status();body=response.json();assert 'error' not in body,body
    assert len(body['dataset'])<1500001
    frame=pd.DataFrame(body['dataset'],columns=[c['name'] for c in body['columns']])
    for c in body['columns']:
        if c['type'] in ['DATE','TIMESTAMP']:frame[c['name']]=pd.to_datetime(frame[c['name']],utc=True).dt.tz_localize(None)
    return frame
if args.reference_only:
    state="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('margin_detail','exchange_calendar') order by table_name"
    before=fetch(state);end=margin_source_end(fetch,'20260930');timer=time.perf_counter()
    source=fetch(f"select ts_code,trade_date,rzye,rqye from margin_detail where trade_date>='2025-06-27' and trade_date<=to_date('{end}','yyyyMMdd') order by trade_date,ts_code")
    source['available_date']=margin_available_dates(fetch,source.trade_date)
    assert before.equals(fetch(state))
    definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['factorId'] in ['ownership_margin_balance','ownership_short_balance']]
    specs=[N(data_binding=N(source_group='event_pit',source_column=d['sourceColumn'],asset_type='stock'),lifecycle=N(enabled=True),spec=N(factor_id=d['factorId'],direction=N(value=d['direction']),family='ownership',frequency=N(value='daily'),version=d['version'])) for d in definitions]
    labels=pd.read_json(root/'normalized-python-repeat-values.json')[['trade_date','ts_code']]
    values=build_event_pit_factor_frame(specs,labels,{'margin':source})
    values[['factor_id','ts_code','value_raw']].to_json(root/'margin-factor-python-values.json',orient='records',double_precision=15)
    report=dict(sourceRows=len(source),rows=len(values),sourceEnd=end,referenceSeconds=time.perf_counter()-timer,sourceVersions=before.to_dict('records'))
    (root/'margin-factor-python-report.json').write_text(json.dumps(report,indent=2,default=str));print(json.dumps(report,default=str));sys.exit(0)
reference=json.loads((root/'margin-factor-python-report.json').read_text())
report=json.loads((root/'margin-factors-report.json').read_text())
for old in reference['sourceVersions']:
    current=next(s for s in report['sourceState'] if s['table_name']==old['table_name'])
    for key in ['id','directoryName','table_txn','wal_txn']:assert current[key]==old[key]
expected=pd.read_json(root/'margin-factor-python-values.json').set_index(['factor_id','ts_code']).sort_index()
rows=json.loads((root/'margin-factors-observations.json').read_text())['observations']
actual=pd.DataFrame(rows,columns=['date','type','ts_code','factor_id','version','value','available','quality','scope','lineage']).set_index(['factor_id','ts_code']).sort_index()
assert actual.index.equals(expected.index)
np.testing.assert_allclose(actual.value.astype(float),expected.value_raw,rtol=1e-12,atol=1e-10,equal_nan=True)
result=dict(rows=len(actual),mismatches=0,pythonHistoricalRows=reference['sourceRows'],javaBaselineAndChangeRows=report['eventSourceRows']['margin_detail'],
            finiteValues=expected.groupby(level='factor_id').value_raw.count().astype(int).to_dict(),sourceEnd=reference['sourceEnd'],published=False)
(root/'margin-factors-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
