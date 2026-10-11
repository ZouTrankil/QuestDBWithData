import json,sys,time
from pathlib import Path
from types import SimpleNamespace
import numpy as np
import pandas as pd
import requests
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.data.contracts.bse_code_mapping import normalize_stock_code_aliases
from quant_platform.data.adapters.questdb.stock_completeness import normalize_stock_listing_scope
from quant_platform.research.engines.research_universe import ResearchUniverse
from quant_platform.research.contracts.common.research_universe import DEFAULT_RESEARCH_UNIVERSES
from quant_platform.research.ports.factor_market_data import FactorMarketInputBundle
root=Path(__file__).resolve().parent
session=requests.Session()
def fetch(sql):
    response=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,300001'},timeout=90)
    response.raise_for_status(); body=response.json(); assert 'error' not in body,body
    assert len(body['dataset'])<300001,'truncated source query'
    return pd.DataFrame(body['dataset'],columns=[c['name'] for c in body['columns']])
state_sql="select id,table_name,directoryName,table_txn,wal_txn,table_suspended from tables() where table_name in ('backtest_daily','daily_basic','stock_detail_info','index_weight') order by table_name"
before=fetch(state_sql)
java_report=json.loads((root/'live-pools-report.json').read_text())
for row in before.to_dict('records'):
    expected=next(s for s in java_report['sourceState'] if s['table_name']==row['table_name'])
    for key in ['id','directoryName','table_txn','wal_txn']:
        assert row[key]==expected[key] or pd.isna(row[key]) and pd.isna(expected[key]),(key,row)
    assert not row['table_suspended']
    assert pd.isna(row['wal_txn']) or row['table_txn']==row['wal_txn']
reference=fetch('select ts_code,list_date,delist_date from stock_detail_info order by ts_code')
start=pd.Timestamp(java_report['warmupFrom']); end=pd.Timestamp('2026-09-30'); frames=[]; timer=time.perf_counter()
while start<=end:
    stop=min(start+pd.offsets.MonthBegin(1),end+pd.Timedelta(days=1))
    frame=fetch(f"select ts_code,trade_date,is_st,is_suspended,amount from backtest_daily where trade_date>='{start.date()}' and trade_date<'{stop.date()}' order by ts_code,trade_date")
    frame.trade_date=pd.to_datetime(frame.trade_date).dt.tz_localize(None)
    frame,_=normalize_stock_code_aliases(frame)
    request=SimpleNamespace(dataset_id='market.stock.backtest_daily',start_date=str(start.date()),end_date=str((stop-pd.Timedelta(days=1)).date()))
    frame,_=normalize_stock_listing_scope(lambda sql:reference,request,frame)
    frames.append(frame); print(str(start.date()),flush=True);start=stop
market=pd.concat(frames,ignore_index=True)
basic=fetch("select ts_code,trade_date,total_mv,circ_mv from daily_basic where trade_date='2026-09-30'")
basic.trade_date=pd.to_datetime(basic.trade_date).dt.tz_localize(None)
basic,_=normalize_stock_code_aliases(basic)
basic,_=normalize_stock_listing_scope(lambda sql:reference,SimpleNamespace(dataset_id='market.stock.daily_basic',start_date='2026-09-30',end_date='2026-09-30'),basic)
weights=fetch("select index_code,trade_date,con_code,exchange from index_weight where trade_date<'2026-10-01' order by index_code,trade_date,con_code")
weights.trade_date=pd.to_datetime(weights.trade_date).dt.tz_localize(None)
assert before.equals(fetch(state_sql)), 'Sources changed during independent read'
read_seconds=time.perf_counter()-timer; timer=time.perf_counter()
resolver=ResearchUniverse(input_bundle=FactorMarketInputBundle('live-parity',java_report['warmupFrom'],'2026-09-30',{'market':market,'daily_basic':basic,'index_weights':weights}))
actual=json.loads((root/'live-pools-values.json').read_text()); report=[]
for definition in DEFAULT_RESEARCH_UNIVERSES:
    expected=resolver.resolve('2026-09-30',definition.universe_id,definition.universe_version,'base_ic')
    observed=next(x for x in actual['coverage'] if x['pool']==definition.universe_id)
    assert observed['available']==(expected.status!='unavailable'),definition.universe_id
    if not observed['available']:
        assert observed['reason']==expected.reason
        report.append(dict(pool=definition.universe_id,available=False,reason=expected.reason));continue
    assert observed['membershipAsOf']==expected.membership_as_of
    expected_rows=expected.members.set_index('entity_id')
    assert set(expected_rows.index)=={row['symbol'] for row in observed['members']}
    for row in observed['members']:
        target=expected_rows.loc[row['symbol']]
        assert row['reasons']==target.reason_codes,(definition.universe_id,row,target.reason_codes)
        assert (not row['reasons'])==bool(target.included)
        np.testing.assert_allclose(float(row['parentPercentile']),target.parent_rank_percentile,equal_nan=True)
    report.append(dict(pool=definition.universe_id,available=True,compared=len(expected_rows),included=len(expected.included),membershipAsOf=expected.membership_as_of,membershipHash=expected.membership_hash,mismatches=0))
result=dict(marketRows=len(market),indexRows=len(weights),readSeconds=read_seconds,pythonResolveSeconds=time.perf_counter()-timer,pools=report,sourceVersions=before.to_dict('records'))
(root/'live-pool-membership-parity.json').write_text(json.dumps(result,indent=2,default=str));print(json.dumps(result,default=str),flush=True)
