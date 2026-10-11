import json,sys,time
from pathlib import Path
from types import SimpleNamespace as N
import requests
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_l2_factor_frame

root=Path(__file__).resolve().parent
java=json.loads((root/'l2-shared-panel.json').read_text())
session=requests.Session()
def fetch(sql):
    response=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=90)
    response.raise_for_status();body=response.json();assert 'error' not in body,body
    assert len(body['dataset'])<1000001
    return pd.DataFrame(body['dataset'],columns=[c['name'] for c in body['columns']])
state_sql="select id,table_name,directoryName,table_txn,wal_txn,table_suspended from tables() where table_name='l2_daily_features'"
before=fetch(state_sql)
assert before.to_dict('records')==java['sourceState'],'Source changed since Java read'
columns=java['selectedColumns']
started=time.perf_counter()
raw=fetch('select ts,symbol,feature_version,parser_version,'+','.join(columns)
          +" from l2_daily_features where ts>='"+java['historyFrom']+"' and ts<'2026-10-01' order by ts,symbol,feature_version,parser_version")
raw.ts=pd.to_datetime(raw.ts).dt.tz_localize(None)
read_seconds=time.perf_counter()-started
assert len(raw)==java['sourceRows']
assert before.equals(fetch(state_sql))
assert set(raw.feature_version)=={java['featureVersion']}
assert set(raw.parser_version)=={java['parserVersion']}
definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['factorId'] in java['factors']]
specs=[N(data_binding=N(source_group=d['sourceGroup'],source_column=d['sourceColumn'],transform=d['transform'],asset_type=d['assetType']),
         lifecycle=N(enabled=True),spec=N(factor_id=d['factorId'],direction=N(value=d['direction']),family='l2',frequency=N(value='daily'),version=d['version'])) for d in definitions]
started=time.perf_counter()
expected=build_l2_factor_frame(specs,raw,output_dates={pd.Timestamp(java['from'])})
compute_seconds=time.perf_counter()-started
actual=[]
for section in java['sections']:
    for factor,values in section['factors'].items():
        actual.extend((pd.Timestamp(date),section['symbol'],factor,float(value) if value is not None else np.nan)
                      for date,value in zip(section['dates'],values))
keys=['trade_date','ts_code','factor_id']
actual=pd.DataFrame(actual,columns=keys+['value_raw']).set_index(keys).sort_index()
expected=expected.set_index(keys).sort_index()
assert actual.index.is_unique and expected.index.is_unique and actual.index.equals(expected.index)
np.testing.assert_allclose(actual.value_raw,expected.value_raw,rtol=1e-12,atol=1e-12,equal_nan=True)
finite=np.isfinite(expected.value_raw.to_numpy())
max_error=float(np.max(np.abs(actual.value_raw.to_numpy()[finite]-expected.value_raw.to_numpy()[finite]))) if finite.any() else 0
report=dict(factors=len(definitions),rows=len(actual),mismatches=0,maxAbsoluteError=max_error,
            finiteValues=int(finite.sum()),sourceRows=len(raw),retainedJavaRows=java['retainedRows'],sharedSourceColumns=len(columns),
            transforms=sorted({d['transform'] for d in definitions}),pythonReadSeconds=read_seconds,
            pythonComputeSeconds=compute_seconds,javaReadSeconds=java['readMillis']/1000,
            javaComputeSeconds=java['computeMillis']/1000,sourceState=java['sourceState'],publicationReady=False,
            limitation='One date; raw L2 transforms only. Pool/source archive certification and publication are not included. Different HTTP/JDBC readers; single run, not a steady-state benchmark.')
(root/'l2-shared-panel-parity.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report),flush=True)
