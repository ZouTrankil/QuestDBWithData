import sys,json,time,requests
from pathlib import Path
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.research.engines.global_structural_factors import build_global_structural_pit_panel
p=Path('artifacts/local-factor-audit/20261011');s=requests.Session()
def query(sql):
 r=s.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1000001'},timeout=120);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<1000001;return d
fields={'income':['revenue','oper_cost','operate_profit','n_income_attr_p'],'balance':['total_assets','money_cap','trad_asset','st_borr','non_cur_liab_due_1y','lt_borr','bond_payable','total_liab'],'cashflow':['n_cashflow_act','c_pay_acq_const_fiolta']}
state="select id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended from tables() where table_name in ('income','balance','cashflow') order by table_name"
before=query(state);frames={}
for table,cols in fields.items():
 d=query('select ts_code,end_date,ann_date,'+','.join(cols)+f" from {table} where ann_date<'2026-10-01' order by ann_date,ts_code,end_date")
 f=pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']]);f.ann_date=pd.to_datetime(f.ann_date).dt.tz_localize(None);f.end_date=pd.to_datetime(f.end_date,format='%Y%m%d',errors='coerce');frames[table]=f;print(table,len(f),flush=True)
assert before['dataset']==query(state)['dataset'],'Source changed'
daily=pd.read_json(p/'normalized-java-values.json')[['ts_code','trade_date','total_mv']];daily.trade_date=pd.to_datetime(daily.trade_date)
t=time.perf_counter();result=build_global_structural_pit_panel(daily,frames);seconds=time.perf_counter()-t
result.to_json(p/'structural-python-values.json',orient='records',date_format='iso',double_precision=15)
r={'sourceState':[dict(zip([c['name'] for c in before['columns']],row)) for row in before['dataset']],'sourceRows':{k:len(v) for k,v in frames.items()},'resultRows':len(result),'computeSeconds':seconds}
(p/'structural-python-profile.json').write_text(json.dumps(r,indent=2));print(json.dumps(r),flush=True)
