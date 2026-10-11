import hashlib
import json
import sys
from pathlib import Path

root = Path(__file__).parent

def load_bundle(name):
    directory = root / name
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    rows = {}
    for part in manifest['parts']:
        payload = (directory / part['file']).read_bytes()
        assert hashlib.sha256(payload).hexdigest() == part['sha256']
        observations = json.loads(payload)['observations']
        assert len(observations) == part['rows']
        for row in observations:
            key = tuple(row[:5])
            assert key not in rows, key
            rows[key] = row
    assert len(rows) == manifest['rows']
    return manifest, rows

view_comparison = '--view' in sys.argv[1:]
old_manifest, old = load_bundle('daily-scoped-bundle' if view_comparison else 'daily-missing-bundle')
manifest, actual = load_bundle('daily-view-bundle' if view_comparison else 'daily-scoped-bundle')
panel = json.loads((root / ('daily-view-panel.json' if view_comparison else 'daily-scoped-panel.json')).read_text(encoding='utf-8'))
if view_comparison:
    assert any(s.get('table_name') == 'v_backtest_daily' and s.get('backtestNativeVersion') for s in panel['sourceState'])
assert set(manifest['factors']) == set(old_manifest['factors'])
assert len(panel['stockPublicationMembership']) == 1
membership = panel['stockPublicationMembership'][0]
reference = json.loads((root / 'live-pool-membership-parity.json').read_text(encoding='utf-8'))
pool = next(p for p in reference['pools'] if p['pool'] == 'all_market')
assert membership['membershipHash'] == pool['membershipHash']
assert membership['members'] == pool['included']
counts = {}
symbols = {}
for key, row in actual.items():
    assert old[key] == row, key
    counts[row[3]] = counts.get(row[3], 0) + 1
    symbols.setdefault(row[3], set()).add(row[2])
assert set(counts) == set(manifest['factors'])
assert all(n == membership['members'] for n in counts.values())
first = next(iter(symbols.values()))
assert all(value == first for value in symbols.values())
report = dict(rows=len(actual), factors=len(counts), members=len(first),
              membershipHash=membership['membershipHash'], rawValueDifferences=0,
              duplicateKeys=0, bundleHashesVerified=True,
              previousRows=len(old), excludedRows=len(old)-len(actual),
              passed=True, publicationComplete=False)
(root / ('daily-view-bundle-audit.json' if view_comparison else 'daily-scoped-bundle-audit.json')).write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
