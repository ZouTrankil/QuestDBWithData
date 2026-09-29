"""Independent six-field comparison of actual D004 stage readback with retained real source response."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import sys


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    folder = Path(sys.argv[1]).resolve()
    receipt = json.loads((folder / 'stage-readback.json').read_text(encoding='utf-8'))
    source_path = root / receipt['sourceReceipt']
    source_bytes = source_path.read_bytes()
    assert hashlib.sha256(source_bytes).hexdigest() == receipt['sourceReceiptSha256']
    source = json.loads(source_bytes)
    assert source['fullResponseBelowLimit']
    expected = {r['ts_code']: r for r in source['rows']}
    actual_rows = receipt['second']['snapshot']['rows']
    actual = {r['tsCode']: r for r in actual_rows}
    assert len(expected) == len(source['rows']) and len(actual) == len(actual_rows)
    mapping = {'ts_code': 'tsCode', 'name': 'name', 'count': 'count', 'exchange': 'exchange',
               'list_date': 'listDate', 'type': 'type'}
    differences = Counter()
    for code in expected.keys() & actual.keys():
        for source_field, actual_field in mapping.items():
            if expected[code][source_field] != actual[code][actual_field]:
                differences[source_field] += 1
    result = {'source_rows': len(expected), 'actual_rows': len(actual),
              'missing_keys': sorted(expected.keys() - actual.keys()),
              'extra_keys': sorted(actual.keys() - expected.keys()),
              'field_differences': dict(differences), 'compared_source_fields': list(mapping),
              'null_counts': sum(r['count'] is None for r in expected.values()),
              'questdb_receipt': str(folder / 'stage-readback.json'),
              'source_sha256': receipt['sourceReceiptSha256'],
              'observation_time': 'generated timestamp checked by Java complete seven-field stage comparison'}
    result['passed'] = (len(expected) == len(actual) == 2517 and not result['missing_keys']
                        and not result['extra_keys'] and not differences)
    (folder / 'independent-source-values.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True))
    assert result['passed']


if __name__ == '__main__':
    main()
