from pathlib import Path
import json
import math
from datetime import datetime, timezone

base = Path(__file__).resolve().parent
results = []
for symbol in ('160515.SZ', '185640.SZ', '246132.SZ'):
    expected = json.loads((base / 'null-reference' / (symbol + '.json')).read_text(encoding='utf-8'))
    actual = json.loads((Path(r'D:\l2-native-features\20260928\features') / (symbol + '.json')).read_text(encoding='utf-8'))
    differences = []
    if set(expected) != set(actual):
        differences.append('Field set mismatch')
    for field, value in expected.items():
        other = actual.get(field)
        if type(value) in (float, int) and type(other) in (float, int):
            same = math.isclose(value, other, rel_tol=1e-10, abs_tol=1e-9) if type(value) is float else type(value) is type(other) and value == other
        else:
            same = type(value) is type(other) and value == other
        if not same:
            differences.append({'field': field, 'python': value, 'java': other})
    results.append({'symbol': symbol, 'fieldsChecked': len(expected), 'pythonOfiSlope': expected['ofi_slope'], 'javaOfiSlope': actual['ofi_slope'], 'differences': differences})
report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'status': 'PASSED' if all(not r['differences'] for r in results) else 'FAILED', 'results': results}
(base / 'nullable-reference-comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
raise SystemExit(0 if report['status'] == 'PASSED' else 2)
