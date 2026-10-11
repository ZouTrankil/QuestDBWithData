import json,sys
from pathlib import Path
from types import SimpleNamespace as N
import numpy as np
import pandas as pd
import requests
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.factor_platform.factor_platform_compute import build_l2_factor_frame
from quant_platform.research.engines.factor_platform.factor_preprocess import robust_zscore
from quant_platform.research.engines.factor_platform.factor_neutralization import build_stock_exposures,neutralize_stock_factor_values
from quant_platform.data.adapters.governance.l2_ingest_receipt import assess_l2_source_scope
root=Path(__file__).resolve().parent
all_factors='--all' in sys.argv
java=json.loads((root/('l2-all-publication-panel.json' if all_factors else 'l2-live-pools-v2.json')).read_text());session=requests.Session()
def fetch(sql):
    response=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=90)
    response.raise_for_status();body=response.json();assert 'error' not in body,body
    assert len(body['dataset'])<1000001
    return pd.DataFrame(body['dataset'],columns=[c['name'] for c in body['columns']])
states=fetch('select id,table_name,directoryName,table_txn,wal_txn from tables()')
for old in java['sourceState']:
    current=states.loc[states.id.eq(old['id'])].iloc[0]
    for key in ['directoryName','table_txn','wal_txn']:
        assert old[key]==current[key] or pd.isna(old[key]) and pd.isna(current[key]),key
definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['factorId'] in java['factors']]
specs=[N(data_binding=N(source_group=d['sourceGroup'],source_column=d['sourceColumn'],transform=d['transform'],asset_type=d['assetType']),
         lifecycle=N(enabled=True),spec=N(factor_id=d['factorId'],direction=N(value=d['direction']),family='l2',frequency=N(value='daily'),version=d['version'])) for d in definitions]
raw=fetch('select ts,symbol,'+','.join(java['selectedColumns'])+" from l2_daily_features where ts>='2026-09-23' and ts<'2026-10-01' order by ts,symbol,feature_version,parser_version")
raw.ts=pd.to_datetime(raw.ts).dt.tz_localize(None)
expected=build_l2_factor_frame(specs,raw,output_dates={pd.Timestamp('2026-09-30')}).set_index(['factor_id','ts_code'])
daily=pd.read_json(root/'normalized-python-repeat-values.json')
industry=pd.read_csv(root/'exposure-members.tsv',sep='\t',names=['ts_code','in_date','out_date','level','l1_name','l2_name'],keep_default_na=False,na_values=['<NULL>'])
# These cached exposure inputs have independent live parity; require identical source generations now.
old_states=json.loads((root/'normalized-python-repeat-profile.json').read_text())['sourceState']
old_states+=json.loads((root/'stock-exposure-parity.json').read_text())['sourceVersions']
for old in old_states:
    matching=[s for s in java['sourceState'] if s['id']==old['id']]
    if not matching:continue
    for key in ['directoryName','table_txn','wal_txn']:
        assert old[key]==matching[0][key] or pd.isna(old[key]) and pd.isna(matching[0][key]),key
exposures=build_stock_exposures(daily,industry)
rows=0;max_error=0.;members=[];coverage=[]
prior_membership=json.loads((root/'live-pools-v2-values.json').read_text()) if (root/'live-pools-v2-values.json').exists() else None
for row in java['poolResult']['coverage']:
    included=[m['symbol'] for m in row['members'] if not m['reasons']]
    coverage.append(dict(trade_date=row['date'],universe_id=row['pool'],membership_status='ok' if row['available'] else 'missing'))
    members.extend(dict(trade_date=row['date'],universe_id=row['pool'],ts_code=s) for s in included)
    if prior_membership:
        previous=next(c for c in prior_membership['coverage'] if c['pool']==row['pool'] and c['date']==row['date'])
        assert row['membershipHash']==previous['membershipHash']
    for section in [s for s in java['poolResult']['sections'] if s['poolId']==row['pool']]:
        definition=section['definition'];symbols=section['symbols'];actual=section['values']
        expected_symbols=set(included)&set(raw.loc[raw.ts.eq(pd.Timestamp(row['date'])),'symbol'])
        assert set(symbols)==expected_symbols and len(symbols)==len(expected_symbols)
        values=expected.loc[(definition['factorId'],symbols),'value_raw'].reset_index(drop=True)
        np.testing.assert_allclose(np.array(actual['raw'],dtype=float),values,rtol=1e-12,atol=1e-12,equal_nan=True)
        assert definition['normalizePolicy'] in ['robust_zscore','cross_section_robust_zscore']
        z=robust_zscore(values)
        np.testing.assert_allclose(np.array(actual['zscore'],dtype=float),z,rtol=1e-10,atol=1e-10,equal_nan=True)
        frame=pd.DataFrame(dict(trade_date=row['date'],ts_code=symbols,factor_id=definition['factorId'],asset_type='stock',universe_id=row['pool'],value_preprocessed=z))
        if definition['neutralizePolicy']=='industry_size':
            neutral=neutralize_stock_factor_values(frame,exposures,policy_by_factor={definition['factorId']:'industry_size'}).value_neutralized
            effective=neutral
        else:
            assert definition['neutralizePolicy']=='none'
            neutral=pd.Series(np.nan,index=z.index);effective=z
        array=np.array(actual['neutralized'],dtype=float)
        np.testing.assert_allclose(array,neutral,rtol=1e-8,atol=1e-9,equal_nan=True)
        np.testing.assert_allclose(np.array(actual['effective'],dtype=float),effective*(-1 if definition['direction']=='negative' else 1),rtol=1e-8,atol=1e-9,equal_nan=True)
        delta=np.abs(array-neutral.to_numpy());finite=delta[np.isfinite(delta)]
        if len(finite):max_error=max(max_error,float(finite.max()))
        rows+=len(symbols)
scope=assess_l2_source_scope(Path('F:/repo/engine/back-monitor'),'20260930',universe_membership=pd.DataFrame(members),universe_coverage=pd.DataFrame(coverage))
assert scope.status==java['sourceScopeDecisions'][0]['status'] and scope.publishable==java['scopePublishable']
report=dict(factors=len(definitions),poolSections=len(java['poolResult']['sections']),rows=rows,mismatches=0,maxNeutralizationAbsoluteError=max_error,
            scopeStatus=scope.status,scopePublishable=scope.publishable,sourceComplete=False,published=False,
            projectionBuilds=java['poolResult']['projectionBuilds'],projectionHits=java['poolResult']['projectionHits'])
(root/('l2-all-pool-parity.json' if all_factors else 'l2-pool-parity.json')).write_text(json.dumps(report,indent=2));print(json.dumps(report))
