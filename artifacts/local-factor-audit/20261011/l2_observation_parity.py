import json,sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.workflows.research.factor_data_product_bridge import _canonical_factor_observations
from quant_platform.data.adapters.questdb.factor_data_product import _canonical_observations
root=Path(__file__).resolve().parent
panel=json.loads((root/'l2-publication-panel.json').read_text())
prior=json.loads((root/'l2-live-pools-v2.json').read_text())
assert panel['sourceState']==prior['sourceState']
assert panel['poolResult']==prior['poolResult'],'Pool computation changed from independently checked Python parity'
values=[]
for section in panel['poolResult']['sections']:
    d=section['definition']
    for symbol,value in zip(section['symbols'],section['values']['raw']):
        value=float(value) if value is not None else np.nan
        values.append(dict(trade_date=section['date'],ts_code=symbol,asset_type=d['assetType'],universe_id=section['poolId'],
                           factor_id=d['factorId'],factor_version=d['version'],value_raw=value,
                           coverage_flag='ok' if np.isfinite(value) else 'missing'))
expected=_canonical_observations(_canonical_factor_observations(pd.DataFrame(values),computed_at=pd.Timestamp('2026-10-11')))
actual=pd.DataFrame(json.loads((root/'l2-observations.json').read_text())['observations'],columns=[
    'signal_date','entity_type','entity_id','factor_id','definition_version','factor_value','available_at','quality_status','scope','lineage'])
for frame in [expected,actual]:
    frame['signal_date']=pd.to_datetime(frame.signal_date).dt.strftime('%Y-%m-%d')
    frame['available_at']=pd.to_datetime(frame.available_at).dt.strftime('%Y-%m-%dT%H:%M:%S')
keys=['signal_date','entity_type','entity_id','factor_id','definition_version']
expected=expected.set_index(keys).sort_index();actual=actual.set_index(keys).sort_index()
assert actual.index.equals(expected.index) and actual.index.is_unique
np.testing.assert_allclose(actual.factor_value.astype(float),expected.factor_value.astype(float),rtol=1e-12,atol=1e-12,equal_nan=True)
for column in ['available_at','quality_status','scope']:assert actual[column].equals(expected[column]),column
assert actual.lineage.isna().all()
report=dict(rows=len(actual),factors=len(panel['factors']),mismatches=0,scope='all_market raw observations as GLOBAL',
            canonicalFieldsCompared=9,l2DigestAbsentInPythonContract=True,sourceComplete=False,published=False)
(root/'l2-observation-parity.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
