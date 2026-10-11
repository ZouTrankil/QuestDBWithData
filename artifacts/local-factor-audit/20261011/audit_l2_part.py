import json, sys, time
from pathlib import Path
from datetime import datetime, timezone
import requests

root=Path(__file__).parent
part=int(sys.argv[1])
expected=json.loads((root/f'l2-all-bundle/observations-{part:03}.json').read_text())['observations']
def query(sql):
    response=requests.get('http://localhost:9000/exec',params={'query':sql},timeout=90)
    response.raise_for_status()
    result=response.json()
    if 'error' in result: raise RuntimeError(result['error'])
    return result['dataset']
state_sql="SELECT id,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended FROM tables() WHERE table_name='factor_observation'"
before=query(state_sql)
factors=sorted({row[3] for row in expected})
assert all(f.replace('_','').isalnum() for f in factors)
start=time.perf_counter()
actual=query("SELECT signal_date,entity_type,entity_id,factor_id,definition_version,factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation WHERE signal_date='2026-09-30' AND factor_id IN ("+','.join("'"+f+"'" for f in factors)+") LIMIT 100001")
elapsed=time.perf_counter()-start
after=query(state_sql)
def canonical(row):
    row=list(row);row[0]=row[0][:10]
    row[6]=datetime.fromisoformat(row[6].replace('Z','+00:00')).replace(tzinfo=None).isoformat()
    return row
expected=[canonical(row) for row in expected]; actual=[canonical(row) for row in actual]
want={tuple(row[:5]):row for row in expected}
got={tuple(row[:5]):row for row in actual}
report={'part':part,'expectedRows':len(want),'physicalRows':len(actual),'missingRows':len(want.keys()-got.keys()),
        'unexpectedRows':len(got.keys()-want.keys()),'duplicateRows':len(actual)-len(got),
        'mismatches':sum(want[key]!=got[key] for key in want.keys()&got.keys()),
        'sourceStable':before==after,'walSettled':len(after)==1 and after[0][2]==after[0][3] and after[0][4]==0 and after[0][5] is False,
        'readSeconds':elapsed,'state':after,'observedAtUtc':datetime.now(timezone.utc).isoformat()}
(root/f'l2-all-part{part}-physical-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
