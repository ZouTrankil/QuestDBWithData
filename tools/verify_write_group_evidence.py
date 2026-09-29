"""Verify F015 source-to-QuestDB values and no-resend recovery using captured live evidence."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def verify(directory: Path) -> dict:
    result = json.loads((directory / 'write-group-readback.json').read_text(encoding='utf-8'))
    expected = {}
    columns = ['ts_code', 'symbol', 'name', 'area', 'industry', 'list_date']
    source_files = sorted((directory / 'tushare-source').glob('source-*.json'))
    for path in source_files:
        source = json.loads(path.read_text(encoding='utf-8'))
        date = datetime.fromisoformat(source['logicalDate']).replace(tzinfo=timezone.utc)
        micros = int((date - datetime(1970, 1, 1, tzinfo=timezone.utc)).total_seconds()) * 1_000_000
        for row in source['rows']:
            key = (micros, row['ts_code'])
            if key in expected:
                raise ValueError('Duplicate source sample identity')
            expected[key] = {name: row[name] for name in columns} | {'snapshot_micros': micros}
    seen = set()
    for table, rows in result['actual'].items():
        if len(rows) != 1:
            raise ValueError('Expected exactly one visible sample row per isolated table')
        for row in rows:
            key = (row['snapshot_micros'], row['ts_code'])
            if key in seen or expected.get(key) != row:
                raise ValueError('Duplicate, missing source, or full-field mismatch')
            seen.add(key)
    if len(seen) != 2 or seen != expected.keys():
        raise ValueError('Expected two fully matched real source rows')
    if [result[k]['state'] for k in ('first', 'resumed', 'repeated')] != ['PARTIAL', 'VERIFIED', 'VERIFIED']:
        raise ValueError('Group recovery states differ')
    if result['physicalSendCalls'] != {'a': 1, 'b': 1}:
        raise ValueError('Unexpected repeated physical send')
    if not all(m['reused'] for m in result['repeated']['members']):
        raise ValueError('Repeated recovery did not exclusively revalidate')
    return {'passed': True, 'matched_rows': 2, 'matched_tables': 2, 'columns': ['snapshot_micros'] + columns,
            'mismatched_rows': 0, 'missing_keys': 0, 'duplicate_keys': 0,
            'physical_send_calls': result['physicalSendCalls'],
            'source_files': [path.relative_to(directory).as_posix() for path in source_files]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence_directory', type=Path)
    directory = parser.parse_args().evidence_directory
    proof = verify(directory)
    (directory / 'independent-values.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(proof, ensure_ascii=False))
