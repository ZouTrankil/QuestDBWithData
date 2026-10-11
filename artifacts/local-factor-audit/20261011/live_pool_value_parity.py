import json,sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.factor_platform.factor_preprocess import robust_zscore
from quant_platform.research.engines.factor_platform.factor_neutralization import build_stock_exposures,neutralize_stock_factor_values
root=Path(__file__).resolve().parent
report=json.loads((root/'live-pools-report.json').read_text())
reference=json.loads((root/'normalized-python-repeat-profile.json').read_text())
for old in reference['sourceState']:
    current=next(s for s in report['sourceState'] if s['id']==old['id'])
    for key in ['directoryName','table_txn','wal_txn']:
        assert current[key]==old[key] or pd.isna(current[key]) and pd.isna(old[key]),key
industry_report=json.loads((root/'stock-exposure-parity.json').read_text())
old=next(s for s in industry_report['sourceVersions'] if s['table_name']=='index_member')
current=next(s for s in report['sourceState'] if s['table_name']=='index_member')
for key in ['id','directoryName','table_txn','wal_txn']:assert old[key]==current[key],key
daily=pd.read_json(root/'normalized-python-repeat-values.json').set_index('ts_code',drop=False)
membership=pd.read_csv(root/'exposure-members.tsv',sep='\t',names=['ts_code','in_date','out_date','level','l1_name','l2_name'],keep_default_na=False,na_values=['<NULL>'])
exposures=build_stock_exposures(daily.reset_index(drop=True),membership)
values=json.loads((root/'live-pools-values.json').read_text())
rows=0;max_error=0.0
for section in values['sections']:
    definition=section['definition']; symbols=section['symbols']; actual=section['values']
    raw=daily.loc[symbols,definition['sourceColumn']].reset_index(drop=True)
    np.testing.assert_allclose(np.array(actual['raw'],dtype=float),raw,rtol=1e-8,atol=1e-10,equal_nan=True)
    zscore=robust_zscore(raw)
    np.testing.assert_allclose(np.array(actual['zscore'],dtype=float),zscore,rtol=1e-8,atol=1e-10,equal_nan=True)
    frame=pd.DataFrame(dict(trade_date=section['date'],ts_code=symbols,factor_id=definition['factorId'],asset_type='stock',universe_id=section['poolId'],value_preprocessed=zscore))
    expected=neutralize_stock_factor_values(frame,exposures,policy_by_factor={definition['factorId']:'industry_size'})
    java=np.array(actual['neutralized'],dtype=float)
    np.testing.assert_allclose(java,expected.value_neutralized,rtol=1e-7,atol=1e-9,equal_nan=True)
    delta=np.abs(java-expected.value_neutralized.to_numpy()); finite=delta[np.isfinite(delta)]
    if len(finite):max_error=max(max_error,float(finite.max()))
    assert actual['exposureSampleCount']==int(expected.neutralization_sample_count.iloc[0])
    rows+=len(symbols)
result=dict(sections=len(values['sections']),rows=rows,mismatches=0,maxNeutralizationAbsoluteError=max_error,
            projectionBuilds=values['projectionBuilds'],projectionHits=values['projectionHits'],
            scope='Actual Python raw, robust zscore and industry-size residuals on Java-resolved members; live membership parity is a separate pending check.')
(root/'live-pool-value-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
