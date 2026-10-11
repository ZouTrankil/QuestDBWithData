import sys,json,requests,time,argparse
from pathlib import Path
from types import SimpleNamespace
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.data.contracts.bse_code_mapping import normalize_stock_code_aliases
from quant_platform.data.adapters.questdb.stock_completeness import normalize_stock_listing_scope
parser=argparse.ArgumentParser();parser.add_argument('--prefix',default='normalized-python');parser.add_argument('--market-only',action='store_true');options=parser.parse_args()
root=Path('artifacts/local-factor-audit/20261011'); client=requests.Session();read_start=time.perf_counter()
def fetch(sql):
 r=client.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,300001'},timeout=90);r.raise_for_status();d=r.json();assert 'error' not in d,d
 assert len(d['dataset'])<300001,'query truncated'
 return pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']])
reference=fetch('select ts_code,list_date,delist_date from stock_detail_info order by ts_code')
state_sql="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('backtest_daily','daily_basic','stock_detail_info') order by table_name"
before=fetch(state_sql)
start=pd.Timestamp('2025-06-27'); end=pd.Timestamp('2026-09-30'); gaps=[]; panels=[]; counts={'market':0,'basic':0}; excluded={'market':0,'basic':0}
while start<=end:
 stop=min(start+pd.offsets.MonthBegin(1),end+pd.Timedelta(days=1));frames={}
 for role,table,dataset in [('market','backtest_daily','market.stock.backtest_daily'),('basic','daily_basic','market.stock.daily_basic')]:
  fields=',open,high,low,close,vol,amount,up_limit,down_limit,is_st,is_suspended' if role=='market' else ',turnover_rate,total_mv,circ_mv,pb,pe_ttm,ps_ttm,dv_ttm'
  frame=fetch(f"select ts_code,trade_date{fields} from {table} where trade_date>='{start.date()}' and trade_date<'{stop.date()}' order by ts_code,trade_date")
  frame.trade_date=pd.to_datetime(frame.trade_date).dt.tz_localize(None)
  frame,_=normalize_stock_code_aliases(frame)
  request=SimpleNamespace(dataset_id=dataset,start_date=str(start.date()),end_date=str((stop-pd.Timedelta(days=1)).date()))
  frame,meta=normalize_stock_listing_scope(lambda sql:reference,request,frame)
  excluded[role]+=meta.get('listing_scope_rows_excluded',0)+meta.get('non_a_share_rows_excluded',0)
  frames[role]=frame;counts[role]+=len(frame)
 joined=frames['market'].merge(frames['basic'],on=['ts_code','trade_date'],how='left',indicator=True,validate='one_to_one')
 panels.append(joined.drop(columns=['_merge']));gaps.append(joined.loc[joined._merge=='left_only',['ts_code','trade_date']]);print(str(start.date()),flush=True);start=stop
assert before.equals(fetch(state_sql)),'source changed'
from quant_platform.research.engines.factor_platform.factor_platform_daily_pandas import build_daily_market_pandas
panel=pd.concat(panels,ignore_index=True);read_seconds=time.perf_counter()-read_start;print('computing pandas',flush=True);t=time.perf_counter()
if options.market_only:
 from quant_platform.research.engines.factor_platform.factor_platform_compute import build_market_state_factor_frame
 definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['sourceGroup']=='market_state' and d['enabled']]
 N=SimpleNamespace
 specs=[N(data_binding=N(source_group='market_state',source_column=d['sourceColumn'],asset_type='market'),lifecycle=N(enabled=True),spec=N(direction=N(value=d['direction']),factor_id=d['factorId'],family='market',frequency=N(value='daily'),version=d['version'])) for d in definitions]
 daily=panel.rename(columns={'close':'price'});daily['ret']=daily.groupby('ts_code',sort=False).price.transform(lambda x:x.ffill().pct_change(fill_method=None))
 result=build_market_state_factor_frame(specs,daily,output_dates={end});seconds=time.perf_counter()-t
 result.to_json(root/(options.prefix+'-values.json'),orient='records',date_format='iso',double_precision=15)
 report={'counts':counts,'excluded':excluded,'targetRows':len(result),'readSeconds':read_seconds,'computeSeconds':seconds,'sourceState':before.where(pd.notna(before),None).to_dict('records')}
 (root/(options.prefix+'-profile.json')).write_text(json.dumps(report,indent=2,default=str));print(json.dumps(report,default=str),flush=True);sys.exit(0)
result=build_daily_market_pandas(panel,pd.DataFrame(),reference,start=pd.Timestamp('2025-06-27'),end=end)
seconds=time.perf_counter()-t
selected=result[result.trade_date==end];selected.to_json(root/(options.prefix+'-values.json'),orient='records',date_format='iso',double_precision=15)
report={'counts':counts,'excluded':excluded,'targetRows':len(selected),'readSeconds':read_seconds,'computeSeconds':seconds,'sourceState':before.where(pd.notna(before),None).to_dict('records')}
(root/(options.prefix+'-profile.json')).write_text(json.dumps(report,indent=2,default=str));print(json.dumps(report,default=str),flush=True)
