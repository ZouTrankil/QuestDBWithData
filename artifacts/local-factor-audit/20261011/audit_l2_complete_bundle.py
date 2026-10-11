import hashlib
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).parent
manifest_path = root / 'l2-all-bundle/manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
audits = []
assert len(manifest['parts']) == 6
for number, part in enumerate(manifest['parts']):
    assert part['file'] == f'observations-{number:03}.json'
    payload = (manifest_path.parent / part['file']).read_bytes()
    assert hashlib.sha256(payload).hexdigest() == part['sha256']
    assert len(json.loads(payload)['observations']) == part['rows']
for number in range(6):
    subprocess.run([sys.executable, str(root / 'audit_l2_part.py'), str(number)], check=True,
                   stdout=subprocess.DEVNULL)
    audit = json.loads((root / f'l2-all-part{number}-physical-audit.json').read_text())
    assert audit['sourceStable'] and audit['walSettled'], audit
    assert all(audit[key] == 0 for key in ['missingRows', 'unexpectedRows', 'duplicateRows', 'mismatches']), audit
    audits.append(audit)
assert all(a['state'] == audits[0]['state'] for a in audits), 'Target changed between parts'
report = dict(schema='java.l2_bundle_physical_acceptance.v1',
              manifestSha256=hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
              parts=audits, expectedRows=sum(a['expectedRows'] for a in audits),
              physicalRows=sum(a['physicalRows'] for a in audits),
              physicalAcceptance=True, sourceComplete=False, factorReady=False)
assert report['physicalRows'] == 567375
(root / 'l2-complete-bundle-physical-audit.json').write_text(json.dumps(report, indent=2))
print(json.dumps({k:v for k,v in report.items() if k != 'parts'}))
