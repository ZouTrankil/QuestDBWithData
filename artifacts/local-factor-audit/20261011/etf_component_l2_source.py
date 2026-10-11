import json,requests
from pathlib import Path
session=requests.Session()
def fetch(sql):
 r=session.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,20001'},timeout=60);r.raise_for_status();d=r.json();assert 'error' not in d,d
 return [dict(zip([c['name'] for c in d['columns']],row)) for row in d['dataset']]
state="select id,directoryName,table_txn,wal_txn,table_suspended,wal_pending_row_count from tables() where table_name='l2_daily_features'"
before=fetch(state)
assert len(before)==1 and before[0]['table_txn']==before[0]['wal_txn'] and not before[0]['table_suspended'] and before[0]['wal_pending_row_count']==0
rows=fetch("select symbol,feature_version,parser_version,main_net_inflow,mfi_score,obi_top1_mean,mean_obi_top5,ofi_mean,mean_spread,depth_top5_mean from l2_daily_features where ts>='2026-09-30' and ts<'2026-10-01' order by symbol")
assert len(rows)<20001 and len({r['symbol'] for r in rows})==len(rows)
assert all(r['feature_version']=='v1.3' and r['parser_version']=='dfcf_csv_v1.2' for r in rows)
assert before==fetch(state)
Path(__file__).with_name('etf-component-l2-source.json').write_text(json.dumps(dict(date='2026-09-30',state=before,rows=rows)))
print(len(rows))
