import json, os, subprocess, sys
from pathlib import Path
import numpy as np
import pandas as pd
import requests

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, 'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.factor_platform.factor_neutralization import build_stock_exposures

def query(sql):
    response = requests.get('http://localhost:9000/exec', params={'query': sql}, timeout=60)
    response.raise_for_status()
    body = response.json()
    if 'error' in body: raise RuntimeError(body['error'])
    return pd.DataFrame(body['dataset'], columns=[c['name'] for c in body['columns']])

def versions():
    return query("select table_name,id,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended from tables() where table_name in ('index_member','index_weight','daily_basic')").to_dict('records')

before = versions()
members = query('select ts_code,in_date,out_date,level,l1_name,l2_name from index_member')
daily = query("select ts_code,circ_mv,total_mv from daily_basic where trade_date='2026-09-30'")
assert not daily.ts_code.duplicated().any()
for col in ['in_date', 'out_date']:
    members[col] = pd.to_datetime(members[col], errors='coerce', format='mixed').dt.strftime('%Y-%m-%d')
members.to_csv(ROOT/'exposure-members.tsv', sep='\t', index=False, header=False, na_rep='<NULL>')
daily.to_csv(ROOT/'exposure-daily.tsv', sep='\t', index=False, header=False, na_rep='NaN')
dates = ['2020-01-02', '2025-09-30', '2026-09-30']
java = Path('F:/repo/engine/data/.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12/bin')
classes = ROOT/'exposure-classes'; classes.mkdir(exist_ok=True)
source = 'data-app/src/main/java/com/zoutrankil/data/factor/engine/PointInTimeStockExposures.java'
subprocess.run([str(java/'javac.exe'), '-d', str(classes), source, str(ROOT/'StockExposureProbe.java')], check=True)
subprocess.run([str(java/'java.exe'), '-cp', str(classes), 'StockExposureProbe', str(ROOT/'exposure-members.tsv'), str(ROOT/'exposure-daily.tsv'), str(ROOT/'exposure-java.tsv'), ','.join(dates)], check=True)
actual = pd.read_csv(ROOT/'exposure-java.tsv', sep='\t', names=['trade_date','ts_code','log_market_cap','industry'], keep_default_na=False, na_values=['NaN','<NULL>'])
expected = pd.concat([build_stock_exposures(daily.assign(trade_date=d), members) for d in dates], ignore_index=True)
np.testing.assert_allclose(actual.log_market_cap, expected.log_market_cap, rtol=1e-14, atol=1e-14, equal_nan=True)
assert actual.industry.fillna('<NULL>').tolist() == expected.industry.fillna('<NULL>').tolist()
coverage = query('select index_code,max(trade_date) latest,count() rows from index_weight group by index_code').to_dict('records')
after = versions(); assert before == after, 'Source changed during parity comparison'
assert all(not row['table_suspended'] and row['table_txn']==row['wal_txn'] and row['wal_pending_row_count']==0 for row in after)
# Explicit boundaries and missing-value behavior beyond the current physical snapshot.
edge_members = pd.DataFrame([
    ['A',None,'2025-01-02','L1','old',None],
    ['A','2025-01-02',None,'L1','new',None],
    ['B',None,None,'L1',None,'fallback'],
    ['C',None,None,'L1',' ', 'must_not_fallback'],
    ['D',None,None,'L2','excluded',None],
    ['E',None,None,'L1','base',None],
    ['E','2025-01-01','2025-01-02','L1','temporary',None],
    ['F','2025-01-01',None,'L1','tie_first',None],
    ['F','2025-01-01',None,'L1','tie_last',None],
], columns=['ts_code','in_date','out_date','level','l1_name','l2_name'])
edge_daily = pd.DataFrame({'ts_code':list('ABCDEF'), 'circ_mv':[np.nan,0,-1,100,200,300], 'total_mv':[10,20,30,40,50,60]})
edge_members.to_csv(ROOT/'exposure-edge-members.tsv',sep='\t',index=False,header=False,na_rep='<NULL>')
edge_daily.to_csv(ROOT/'exposure-edge-daily.tsv',sep='\t',index=False,header=False,na_rep='NaN')
edge_dates=['2024-12-31','2025-01-01','2025-01-02']
subprocess.run([str(java/'java.exe'),'-cp',str(classes),'StockExposureProbe',str(ROOT/'exposure-edge-members.tsv'),str(ROOT/'exposure-edge-daily.tsv'),str(ROOT/'exposure-edge-java.tsv'),','.join(edge_dates)],check=True)
edge_actual=pd.read_csv(ROOT/'exposure-edge-java.tsv',sep='\t',names=['trade_date','ts_code','log_market_cap','industry'],keep_default_na=False,na_values=['NaN','<NULL>'])
edge_expected=pd.concat([build_stock_exposures(edge_daily.assign(trade_date=d),edge_members) for d in edge_dates],ignore_index=True)
np.testing.assert_allclose(edge_actual.log_market_cap,edge_expected.log_market_cap,rtol=1e-14,atol=1e-14,equal_nan=True)
assert edge_actual.industry.fillna('<NULL>').tolist()==edge_expected.industry.fillna('<NULL>').tolist()
report = dict(rows=len(actual), membershipRows=len(members), dates=dates, mismatches=0,
              boundaryRows=len(edge_actual), boundaryMismatches=0,
              industryCoverage=expected.groupby('trade_date').industry.count().astype(int).rename(index=str).to_dict(),
              indexCoverage=coverage, missingRequiredIndices=['000906','000985'], sourceVersions=after,
              note='Historical dates validate industry intervals; market capitalization uses fixed 2026-09-30 fixture, not historical market-cap evidence.')
(ROOT/'stock-exposure-parity.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
