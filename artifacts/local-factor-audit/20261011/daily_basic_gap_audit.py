import sys,json,requests
from pathlib import Path
from types import SimpleNamespace
import pandas as pd
sys.path.insert(0,r'F:\repo\engine\back-monitor\src')
from quant_platform.data.contracts.bse_code_mapping import normalize_stock_code_aliases
from quant_platform.data.adapters.questdb.stock_completeness import normalize_stock_listing_scope
root=Path('artifacts/local-factor-audit/20261011'); client=requests.Session()
def fetch(sql):
 r=client.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,300001'},timeout=90);r.raise_for_status();d=r.json();assert 'error' not in d,d
 assert len(d['dataset'])<300001,'query truncated'
 return pd.DataFrame(d['dataset'],columns=[c['name'] for c in d['columns']])
reference=fetch('select ts_code,list_date,delist_date from stock_detail_info order by ts_code')
state_sql="select id,table_name,directoryName,table_txn,wal_txn from tables() where table_name in ('backtest_daily','daily_basic','stock_detail_info') order by table_name"
before=fetch(state_sql)
start=pd.Timestamp('2025-06-27'); end=pd.Timestamp('2026-09-30'); gaps=[]; counts={'market':0,'basic':0}; excluded={'market':0,'basic':0}
while start<=end:
 stop=min(start+pd.offsets.MonthBegin(1),end+pd.Timedelta(days=1));frames={}
 for role,table,dataset in [('market','backtest_daily','market.stock.backtest_daily'),('basic','daily_basic','market.stock.daily_basic')]:
  fields=',open,high,low,close,vol,amount' if role=='market' else ''
  frame=fetch(f"select ts_code,trade_date{fields} from {table} where trade_date>='{start.date()}' and trade_date<'{stop.date()}' order by ts_code,trade_date")
  frame.trade_date=pd.to_datetime(frame.trade_date).dt.tz_localize(None)
  frame,_=normalize_stock_code_aliases(frame)
  request=SimpleNamespace(dataset_id=dataset,start_date=str(start.date()),end_date=str((stop-pd.Timedelta(days=1)).date()))
  frame,meta=normalize_stock_listing_scope(lambda sql:reference,request,frame)
  excluded[role]+=meta.get('listing_scope_rows_excluded',0)+meta.get('non_a_share_rows_excluded',0)
  frames[role]=frame[['ts_code','trade_date']];counts[role]+=len(frame)
 joined=frames['market'].merge(frames['basic'],on=['ts_code','trade_date'],how='left',indicator=True,validate='one_to_one')
 gaps.append(joined.loc[joined._merge=='left_only',['ts_code','trade_date']]);print(str(start.date()),flush=True);start=stop
assert before.equals(fetch(state_sql)),'source changed'
gap=pd.concat(gaps,ignore_index=True)
gap.to_json(root/'daily-basic-gap-keys.json',orient='records',date_format='iso')
r={'counts':counts,'excluded':excluded,'missingBasicRows':len(gap),'bySymbol':gap.groupby('ts_code').size().sort_values(ascending=False).to_dict(),'byMonth':gap.groupby(gap.trade_date.dt.strftime('%Y-%m')).size().to_dict(),'sourceState':before.where(pd.notna(before),None).to_dict('records'),'scope':'Python production normalization and listing filter; key coverage only, not full numerical parity.'}
(root/'daily-basic-gap-audit.json').write_text(json.dumps(r,indent=2,default=str));print(json.dumps(r,default=str),flush=True)
