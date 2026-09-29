"""Compare original trade_cal responses with actual SQL rows, independently of Java mappers."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

directory = Path(sys.argv[1]).resolve()
is_runner = (directory / 'runner-readback.json').exists()
proof = json.loads((directory / ('runner-readback.json' if is_runner else 'write-readback.json')).read_text(encoding='utf-8'))
if is_runner:
    assert proof['first']['state'] == proof['second']['state'] == 'VERIFIED'
    assert proof['checkpointBefore'] == {'SSE': '2026-09-26', 'SZSE': '2026-09-26'}
    assert proof['checkpointAfter'] == {'SSE': '2026-09-28', 'SZSE': '2026-09-28'}
else:
    assert proof['first']['status'] == proof['repeated']['status'] == 'VERIFIED'
expected = {}
source_directory = directory / 'second' if is_runner else directory
for path in source_directory.glob('source-*.json'):
    source = json.loads(path.read_text(encoding='utf-8'))
    assert source['endpoint'] == 'trade_cal'
    params = source['parameters']
    for row in source['rows']:
        assert row['exchange'] == params['exchange']
        assert params['start_date'] <= row['cal_date'] <= params['end_date']
        day = datetime.strptime(row['cal_date'], '%Y%m%d').replace(tzinfo=timezone.utc)
        micros = int(day.timestamp()) * 1_000_000
        key = (row['exchange'], micros)
        assert key not in expected
        assert type(row['is_open']) is int and row['is_open'] in (0, 1)
        expected[key] = dict(exchange=row['exchange'], date_micros=micros,
                             is_open=row['is_open'], pretrade_date=row['pretrade_date'] or None)
actual = {}
for row in proof['readback']:
    key = (row['exchange'], row['date_micros'])
    assert key not in actual, 'Duplicate target key'
    actual[key] = row
assert expected and actual == expected, 'Calendar source/SQL mismatch'
if is_runner:
    assert proof['first']['verifiedRows'] == 4 and proof['second']['verifiedRows'] == len(expected)
else:
    assert proof['first']['verifiedRows'] == proof['repeated']['verifiedRows'] == len(expected)
result = dict(passed=True, matched_rows=len(expected), mismatched_rows=0, duplicate_keys=0,
              missing_keys=0, columns=['exchange','date_micros','is_open','pretrade_date'],
              final_row_count=len(actual), target=proof['table'], incremental_runner=is_runner)
(directory/'independent-values.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result))
