import json,sys,requests
from pathlib import Path
import pandas as pd
import numpy as np
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_earnings_factor
root=Path(__file__).resolve().parent
report=json.loads((root/'etf-command-earnings-panel.json').read_text(encoding='utf-8'))
session=requests.Session()
def fetch(sql):
 r=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=90);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<1000001
 return pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']])
state="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('forecast','express') order by table_name"
before=fetch(state);assert before.to_dict('records')==report['earningsSourceState']
forecast=fetch("select ts_code,ann_date,p_change_min,p_change_max from forecast where ann_date<'2026-10-01' order by ann_date,ts_code,end_date")
express=fetch("select ts_code,ann_date,yoy_dedu_np,yoy_net_profit,n_income from express where ann_date<'2026-10-01' order by ann_date,ts_code,end_date")
assert before.equals(fetch(state));assert len(forecast)+len(express)==report['earningsSourceRows']
members=pd.DataFrame(json.loads((root/'etf-constituents-live.json').read_text())['members']).rename(columns={'etf':'etf_code','stock':'stock_code','weight':'component_weight'})
valid=set(pd.read_csv(root/'etf-live-frozen/inputs.tsv',sep='\t').symbol);members=members[members.etf_code.isin(valid)]
expected=_build_earnings_factor({'stock_forecast':forecast,'stock_express':express},members,pd.Timestamp('2026-09-30')).set_index('ts_code').sort_index()
actual=pd.DataFrame(report['earnings']).set_index('symbol').sort_index();assert actual.index.tolist()==expected.index.tolist()
bad=0
for column in ['weighted_outlook','earnings_coverage','component_earnings_outlook']:
 bad+=int((~np.isclose(actual[column].to_numpy(float),expected[column].to_numpy(float),rtol=1e-10,atol=1e-10,equal_nan=True)).sum())
result=dict(etfs=len(actual),checks=len(actual)*3,mismatches=bad,sourceRows=report['earningsSourceRows'],retainedEvents=report['earningsRetainedEvents'],sourceStateMatched=True)
(root/'etf-command-earnings-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
