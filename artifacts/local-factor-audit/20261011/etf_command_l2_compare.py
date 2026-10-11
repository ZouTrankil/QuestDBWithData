import json,sys,base64
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_etf_l2_self
from quant_platform.data.contracts.etf_style import classify_research_style
root=Path(__file__).resolve().parent
report=json.loads((root/'etf-command-l2-panel.json').read_text(encoding='utf-8'));source=json.loads((root/'etf-component-l2-source.json').read_text(encoding='utf-8'))
assert report['l2SourceRows']==len(source['rows'])
for name in ['id','directoryName','table_txn','wal_txn','table_suspended']:
 assert report['l2SourceState'][0][name]==source['state'][0][name]
market=pd.read_csv(root/'etf-live-frozen/inputs.tsv',sep='\t').rename(columns={'symbol':'ts_code','date':'trade_date'})
market.trade_date=pd.to_datetime(market.trade_date)
market['name']=market.name_base64.fillna('').map(lambda s:base64.b64decode(s).decode())
market['category']=market.name.map(classify_research_style)
l2=pd.DataFrame(source['rows']);l2['ts']=pd.Timestamp(source['date'])
expected=_build_etf_l2_self({'stock_l2':l2},market,output_dates={pd.Timestamp('2026-09-30')}).set_index(['ts_code','trade_date']).sort_index()
actual=pd.DataFrame(report['selfL2']).rename(columns={'symbol':'ts_code','date':'trade_date'});actual.trade_date=pd.to_datetime(actual.trade_date)
actual=actual.set_index(['ts_code','trade_date']).sort_index();assert list(actual.index)==list(expected.index)
bad=0
for c in ['etf_l2_obi1','etf_l2_ofi','etf_l2_spread','etf_l2_depth']:
 bad+=int((~np.isclose(actual[c].to_numpy(float),expected[c].to_numpy(float),rtol=1e-12,atol=1e-12,equal_nan=True)).sum())
assert actual.name.fillna('').tolist()==expected.name.fillna('').tolist() and actual.category.fillna('').tolist()==expected.category.fillna('').tolist()
result=dict(rows=len(actual),checks=len(actual)*4,mismatches=bad,sourceStateMatched=True,factorReady=report['factorReady'])
(root/'etf-command-l2-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
