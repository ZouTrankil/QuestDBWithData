import requests,json,time
from pathlib import Path
sql='''SELECT t.id,t.directoryName,t.designatedTimestamp,t.walEnabled,t.table_suspended,t.table_txn,t.wal_txn,
t.wal_pending_row_count,w.suspended AS writer_suspended,w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,
c."column",c."type",c.upsertKey,c.designated
FROM tables() t CROSS JOIN table_columns('factor_observation') c CROSS JOIN wal_tables() w
WHERE t.table_name='factor_observation' AND w.name='factor_observation' '''
t=time.perf_counter();r=requests.get('http://localhost:9000/exec',params={'query':sql},timeout=30);r.raise_for_status();d=r.json();assert 'error' not in d,d
assert len(d['dataset'])==10
report={'seconds':time.perf_counter()-t,'columns':[c['name'] for c in d['columns']],'rows':len(d['dataset']),'metadata':d['dataset'][0][:12]}
Path('artifacts/local-factor-audit/20261011/preflight-join-probe.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
