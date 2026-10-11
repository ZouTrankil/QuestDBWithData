import json,sys,hashlib
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.workflows.research.factor_data_product_bridge import _canonical_factor_observations
from quant_platform.data.adapters.questdb.factor_data_product import _canonical_observations
root=Path(__file__).resolve().parent;bundle=root/'l2-all-bundle'
panel=json.loads((root/'l2-all-publication-panel.json').read_text())
manifest=json.loads((bundle/'manifest.json').read_text())
assert manifest==panel['observationBundle'] and manifest['sourceComplete'] is False
assert len(manifest['factorIds'])==125
pool_parity=json.loads((root/'l2-all-pool-parity.json').read_text())
assert pool_parity['factors']==125 and pool_parity['mismatches']==0
sections={ (s['date'],s['definition']['factorId']):s for s in panel['poolResult']['sections'] if s['poolId']=='all_market' }
all_keys=set();checked_sections=set();total=0
for index,part in enumerate(manifest['parts']):
    assert part['file']==f'observations-{index:03d}.json'
    encoded=(bundle/part['file']).read_bytes();assert hashlib.sha256(encoded).hexdigest()==part['sha256']
    evidence=json.loads(encoded);assert evidence['sourceComplete'] is False
    assert evidence['inputFingerprint']==part['inputFingerprint']==hashlib.sha256((manifest['inputFingerprint']+'\n'+str(index)).encode()).hexdigest()
    actual=pd.DataFrame(evidence['observations'],columns=['signal_date','entity_type','entity_id','factor_id','definition_version','factor_value','available_at','quality_status','scope','lineage'])
    assert len(actual)==part['rows']<=100000
    selected=set(zip(actual.signal_date,actual.factor_id));assert not checked_sections&selected
    checked_sections|=selected;values=[]
    for key in selected:
        section=sections[key];definition=section['definition']
        for symbol,value in zip(section['symbols'],section['values']['raw']):
            value=float(value) if value is not None else np.nan
            values.append(dict(trade_date=key[0],ts_code=symbol,asset_type='stock',universe_id='all_market',factor_id=key[1],
                               factor_version=definition['version'],value_raw=value,coverage_flag='ok' if np.isfinite(value) else 'missing'))
    expected=_canonical_observations(_canonical_factor_observations(pd.DataFrame(values),computed_at=pd.Timestamp('2026-10-11')))
    for frame in [actual,expected]:
        frame['signal_date']=pd.to_datetime(frame.signal_date).dt.strftime('%Y-%m-%d')
        frame['available_at']=pd.to_datetime(frame.available_at).dt.strftime('%Y-%m-%dT%H:%M:%S')
    keys=['signal_date','entity_type','entity_id','factor_id','definition_version']
    actual=actual.set_index(keys).sort_index();expected=expected.set_index(keys).sort_index()
    assert actual.index.is_unique and actual.index.equals(expected.index)
    assert not all_keys&set(actual.index);all_keys.update(actual.index)
    np.testing.assert_allclose(actual.factor_value.astype(float),expected.factor_value.astype(float),rtol=1e-12,atol=1e-12,equal_nan=True)
    for column in ['available_at','quality_status','scope']:assert actual[column].equals(expected[column]),column
    assert actual.lineage.isna().all();total+=len(actual)
assert checked_sections==set(sections) and total==manifest['rows']
report=dict(factors=len(manifest['factorIds']),parts=len(manifest['parts']),rows=total,mismatches=0,duplicateKeys=0,
            missingSections=0,sourceComplete=False,published=False,manifestSha256=hashlib.sha256((bundle/'manifest.json').read_bytes()).hexdigest())
(root/'l2-all-bundle-parity.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
