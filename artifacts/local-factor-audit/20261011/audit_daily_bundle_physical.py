"""Compare the whole bundle by factor, independent of file partition boundaries."""
import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
import requests

root = Path(__file__).parent
directory = root / 'daily-scoped-bundle'
manifest_bytes = (directory / 'manifest.json').read_bytes()
manifest = json.loads(manifest_bytes)
expected = defaultdict(dict)

def canonical(row):
    row = list(row)
    row[0] = row[0][:10]
    row[6] = datetime.fromisoformat(row[6].replace('Z', '+00:00')).replace(tzinfo=None).isoformat()
    return row

for part in manifest['parts']:
    payload = (directory / part['file']).read_bytes()
    assert hashlib.sha256(payload).hexdigest() == part['sha256']
    rows = json.loads(payload)['observations']
    assert len(rows) == part['rows']
    for row in rows:
        row = canonical(row)
        key = tuple(row[:5])
        assert row[0] == '2026-09-30' and row[1] == 'stock'
        assert key not in expected[row[3]]
        expected[row[3]][key] = row
assert sum(map(len, expected.values())) == manifest['rows']

def query(sql):
    response = requests.get('http://localhost:9000/exec', params={'query':sql,'limit':'0,100001'}, timeout=90)
    response.raise_for_status()
    body = response.json()
    assert 'error' not in body, body
    assert len(body['dataset']) < 100001, 'Query truncated'
    return body['dataset']

state_sql = "SELECT id,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended FROM tables() WHERE table_name='factor_observation'"
before = query(state_sql)
parts = []
for factor, want in sorted(expected.items()):
    assert re.fullmatch(r'[A-Za-z0-9_]+', factor)
    actual = [canonical(row) for row in query("SELECT signal_date,entity_type,entity_id,factor_id,definition_version,factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation "
        f"WHERE signal_date='2026-09-30' AND entity_type='stock' AND factor_id='{factor}' LIMIT 100001")]
    got = {tuple(row[:5]): row for row in actual}
    parts.append(dict(factor=factor, expectedRows=len(want), physicalRows=len(actual),
        missingRows=len(want.keys()-got.keys()), unexpectedRows=len(got.keys()-want.keys()),
        duplicateRows=len(actual)-len(got), mismatches=sum(want[k]!=got[k] for k in want.keys() & got.keys())))
after = query(state_sql)
report = dict(manifestSha256=hashlib.sha256(manifest_bytes).hexdigest(), factors=parts,
    sourceStable=before==after, walSettled=len(after)==1 and after[0][2]==after[0][3] and after[0][4]==0 and after[0][5] is False,
    observedAtUtc=datetime.now(timezone.utc).isoformat(), sourceComplete=False)
report['passed'] = report['sourceStable'] and report['walSettled'] and all(
    p[k]==0 for p in parts for k in ['missingRows','unexpectedRows','duplicateRows','mismatches'])
(root / 'daily-scoped-bundle-physical-audit.json').write_text(json.dumps(report, indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='factors'}))
assert report['passed'], 'Physical bundle differs; inspect per-factor report'
