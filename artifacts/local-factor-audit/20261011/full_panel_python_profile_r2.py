import sys,json,time,gc
from pathlib import Path
import requests,numpy as np,pandas as pd
root=Path('artifacts/local-factor-audit/20261011');sys.path.insert(0,str((root/'benchmark-deps').resolve()));sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.factor_platform.factor_platform_daily_polars import build_daily_market_polars
market=['close','open','high','low','amount','vol','up_limit','down_limit','is_st','is_suspended'];basic=['turnover_rate','total_mv','circ_mv','pb','pe_ttm','ps_ttm','dv_ttm']
client=requests.Session()
def query(sql):
 response=client.get('http://127.0.0.1:9000/exec',params={'query':sql,'limit':'0,300001'},timeout=90);response.raise_for_status();data=response.json()
 if 'error' in data:raise RuntimeError(data['error'])
 return data
state_sql="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('backtest_daily','daily_basic') order by table_name"
def state():
 data=query(state_sql);return [dict(zip([c['name'] for c in data['columns']],row)) for row in data['dataset']]
before=state();java=json.loads((root/'full-panel-profile.json').read_text());assert before==java['sourceState'],'Source state changed since Java profile'
start=pd.Timestamp('2025-06-27');through=pd.Timestamp('2026-09-30');parts=[];read_start=time.perf_counter()
while start<=through:
 end=min(start+pd.offsets.MonthBegin(1),through+pd.Timedelta(days=1));where=f"trade_date>='{start.date()}' and trade_date<'{end.date()}'"
 fields=','.join(['m.ts_code','m.trade_date']+['m.'+c for c in market]+['b.'+c for c in basic]+['b.ts_code as basic_code'])
 sql=f"select {fields} from (select ts_code,trade_date,{','.join(market)} from backtest_daily where {where}) m left join (select ts_code,trade_date,{','.join(basic)} from daily_basic where {where}) b on (ts_code,trade_date) order by m.ts_code,m.trade_date limit 300001"
 data=query(sql);assert len(data['dataset'])<300001,'Monthly row cap reached'
 frame=pd.DataFrame(data['dataset'],columns=[c['name'] for c in data['columns']]);parts.append(frame);print(f'read {start.date()} rows={len(frame)}',flush=True);start=end
frame=pd.concat(parts,ignore_index=True);del parts,data;gc.collect();frame['trade_date']=pd.to_datetime(frame.trade_date).dt.tz_localize(None)
read_seconds=time.perf_counter()-read_start
assert before==state(),'Sources changed while reading'
assert len(frame)==java['sourceRows'];assert frame.ts_code.nunique()==java['securities'];assert int(frame.basic_code.isna().sum())==java['missingDailyBasicRows']
print('computing Polars',flush=True);compute_start=time.perf_counter();result=build_daily_market_polars(frame.drop(columns=['basic_code']),pd.DataFrame(),start=frame.trade_date.min(),end=through);compute_seconds=time.perf_counter()-compute_start
selected=result[result.trade_date==through];selected.to_json(root/'full-panel-python-values.json',orient='records',date_format='iso',double_precision=15);checksum=float(selected.market_beta_252.sum());assert len(selected)==java['requestedSecurityDateRows'];assert np.isclose(checksum,java['betaChecksum'],rtol=1e-8,atol=1e-8)
report={'rows':len(frame),'securities':frame.ts_code.nunique(),'requestedRows':len(selected),'readSeconds':read_seconds,'computeSeconds':compute_seconds,'betaChecksum':checksum,'sourceState':before,'scope':'One cold process, same fields and period; Python HTTP JSON/pandas conversion differs from Java JDBC. Compute includes Polars to pandas result conversion. No full column parity or multipool timing yet.'}
try:
 import psutil
 report['processMemory']=psutil.Process().memory_info()._asdict()
except ImportError:pass
(root/'full-panel-python-profile-r2.json').write_text(json.dumps(report,indent=2,default=int));print(json.dumps(report,indent=2,default=int),flush=True)
