import json,sys,requests
from pathlib import Path
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_style_benchmarks import _constituent_versions,_refresh_status
root=Path(__file__).parent
d=json.loads((root/'csi-frozen-inputs.json').read_text(encoding='utf-8'))
def fetch(sql):
 r=requests.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,100001'},timeout=60);r.raise_for_status();x=r.json();assert 'error' not in x,x
 assert len(x['dataset'])<100001
 return pd.DataFrame(x['dataset'],columns=[c['name'] for c in x['columns']])
state="SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='etf_portfolio'"
before=fetch(state);assert before.to_dict('records')==d['holdings']['history']['sourceState']
directory=fetch("SELECT DISTINCT ts_code,ann_date,end_date FROM etf_portfolio WHERE ann_date<'2026-10-01' AND (ts_code LIKE '%.SH' OR ts_code LIKE '%.SZ')")
assert before.equals(fetch(state))
directory['symbol']='DIRECTORY';directory['stk_mkv_ratio']=1.;directory['mkv']=1.
members=_constituent_versions({'constituents_history':directory},set(d['validEtfs']),pd.Timestamp('2026-09-30'))
refresh=_refresh_status(members,pd.DataFrame(),pd.Timestamp('2026-09-30')).iloc[0]
actual=d['holdings']['refresh']
for py,java in [('covered_etfs','coveredEtfs'),('snapshot_versions','snapshotVersions'),('stale_days','staleDays'),('state','state')]:assert refresh[py]==actual[java],(py,refresh[py],actual[java])
for py,java in [('latest_announcement_date','latestAnnouncementDate'),('latest_report_end_date','latestReportEndDate')]:assert str(refresh[py].date())==actual[java]
selected=members.sort_values(['etf_code','ann_date','end_date']).drop_duplicates('etf_code',keep='last')
keys=lambda f:set(zip(f.etf_code,f.ann_date.dt.strftime('%Y-%m-%d'),f.end_date.dt.strftime('%Y-%m-%d')))
rows=pd.DataFrame(d['holdings']['history']['rows']).rename(columns={'etf':'etf_code','announced':'ann_date','periodEnd':'end_date'})
rows=rows[rows.etf_code.isin(d['validEtfs'])];rows.ann_date=pd.to_datetime(rows.ann_date);rows.end_date=pd.to_datetime(rows.end_date)
assert keys(selected)==keys(rows)
result={'sourceDirectoryRows':len(directory),'snapshotVersions':len(members),'selectedEtfs':len(selected),'versionMismatches':0,'refreshMatched':True,'sourceStateMatched':True,'scope':'Version selection and full-history refresh; constituent weights checked separately'}
(root/'csi-holdings-directory-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
