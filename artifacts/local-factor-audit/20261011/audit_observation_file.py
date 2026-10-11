"""Read-only physical comparison for one frozen date/entity evidence file."""
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
import requests

evidence = Path(sys.argv[1])
destination = Path(sys.argv[2])
payload = evidence.read_bytes()
expected = json.loads(payload)['observations']
assert expected and len(expected) <= 100000
dates = {r[0][:10] for r in expected}
types = {r[1] for r in expected}
factors = {r[3] for r in expected}
assert len(dates) == len(types) == 1
date = next(iter(dates))
entity_type = next(iter(types))
assert re.fullmatch(r'\d{4}-\d{2}-\d{2}', date)
assert all(re.fullmatch(r'[A-Za-z0-9_]+', v) for v in factors | types)

def query(sql):
    response = requests.get('http://localhost:9000/exec', params={'query': sql, 'limit': '0,100001'}, timeout=90)
    response.raise_for_status()
    body = response.json()
    assert 'error' not in body, body
    return body['dataset']

state_sql = "SELECT id,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended FROM tables() WHERE table_name='factor_observation'"
before = query(state_sql)
factor_sql = ','.join("'" + f + "'" for f in sorted(factors))
actual = query("SELECT signal_date,entity_type,entity_id,factor_id,definition_version,factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation "
               f"WHERE signal_date='{date}' AND entity_type='{entity_type}' AND factor_id IN ({factor_sql}) LIMIT 100001")
after = query(state_sql)
assert len(actual) < 100001, 'Read limit reached'

def canonical(row):
    row = list(row)
    row[0] = row[0][:10]
    row[6] = datetime.fromisoformat(row[6].replace('Z', '+00:00')).replace(tzinfo=None).isoformat()
    return row

expected = [canonical(r) for r in expected]
actual = [canonical(r) for r in actual]
want = {tuple(r[:5]): r for r in expected}
got = {tuple(r[:5]): r for r in actual}
assert len(want) == len(expected), 'Duplicate expected keys'
report = dict(evidence=str(evidence.resolve()), evidenceSha256=hashlib.sha256(payload).hexdigest(),
              date=date, factors=len(factors), expectedRows=len(want), physicalRows=len(actual),
              missingRows=len(want.keys()-got.keys()), unexpectedRows=len(got.keys()-want.keys()),
              duplicateRows=len(actual)-len(got), mismatches=sum(want[k]!=got[k] for k in want.keys() & got.keys()),
              sourceStable=before==after,
              walSettled=len(after)==1 and after[0][2]==after[0][3] and after[0][4]==0 and after[0][5] is False,
              observedAtUtc=datetime.now(timezone.utc).isoformat(), sourceComplete=False)
report['passed'] = report['sourceStable'] and report['walSettled'] and all(report[k]==0 for k in ['missingRows','unexpectedRows','duplicateRows','mismatches'])
destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
assert report['passed'], 'Physical observation comparison failed; see report'
