"""Read-only acceptance of complete native batches against archive inventories and frozen schema."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import math
import re

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'artifacts/l2-performance-validation/20261007'
OUTPUT = Path(r'D:\l2-native-features')
RAW = Path(r'D:\l2-native-raw')
MODEL = ROOT / 'src/main/java/com/zoutrankil/data/domain/L2DailyFeatureField.java'

def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

def canonical(symbol):
    code, exchange = symbol.upper().replace('_', '.').split('.')
    code = code.zfill(6)
    if code.startswith(('5', '6', '9', '11')):
        exchange = 'SH'
    elif code.startswith(('00', '001', '002', '003', '12', '15', '16', '30')):
        exchange = 'SZ'
    elif code.startswith(('4', '8')):
        exchange = 'BJ'
    return code + '.' + exchange

def validate(day, schema, nullable):
    base = OUTPUT / day
    manifest = load(base / 'manifest.json')
    if manifest['status'] != 'COMPLETE' or not manifest['published']:
        return {'date': day, 'status': 'PENDING'}
    inventory = load(EVIDENCE / 'archive-metadata' / (day + '-symbols.json'))
    expected_raw = {item['raw_symbol']: item for item in inventory}
    expected = {canonical(symbol) for symbol in expected_raw}
    census = next(item for item in load(EVIDENCE / 'census.json')['archives'] if item['date'] == day)
    errors = []
    def check(condition, message):
        if not condition:
            errors.append(message)
    check(len(expected_raw) == census['symbol_count'], 'Inventory count differs from census')
    check(sum(len(item['tables']) for item in inventory) == census['file_count'], 'Inventory file count differs from census')
    check(sum(sum(item['tables'].values()) for item in inventory) == census['expanded_bytes'], 'Inventory bytes differ from census')
    check(manifest['counts']['failed'] == 0, 'Manifest has failures')
    check(not manifest['ignoredEntries'], 'Unexpected ignored source entries')
    check(not manifest['selection']['requestedNotPresent'], 'Requested symbols absent')
    check(manifest['selection']['excludedByUniverse'] == 0, 'Unexpected universe exclusions')
    check(manifest['selection']['selectedCanonicalSymbols'] == len(expected), 'Selected count differs from archive')
    actual_raw = {p.name for p in (RAW / day).iterdir() if p.is_dir()}
    check(actual_raw == set(expected_raw), 'Extracted symbol directories differ from archive')
    source_size_checks = 0
    for raw_symbol, item in expected_raw.items():
        for table, size in item['tables'].items():
            path = RAW / day / raw_symbol / table
            check(path.is_file() and path.stat().st_size == size, raw_symbol + '/' + table + ': source missing or size differs')
            source_size_checks += 1
    outcomes = {row['symbol']: row for row in manifest['symbols']}
    check(len(outcomes) == len(manifest['symbols']) and set(outcomes) == expected, 'Manifest business keys differ from archive')
    success = {symbol for symbol, row in outcomes.items() if row['status'] == 'SUCCESS'}
    empty = sorted(symbol for symbol, row in outcomes.items() if row['status'] == 'EMPTY')
    check(len(success) == manifest['counts']['success'], 'Success count differs')
    check(len(empty) == manifest['counts']['empty'], 'Empty count differs')
    aggregate = base / 'l2_daily_features.jsonl'
    actual_hash = sha(aggregate)
    check(actual_hash == manifest['aggregateSha256'], 'Aggregate SHA256 mismatch')
    seen = set()
    ordered = []
    field_values = 0
    null_counts = {}
    null_symbols = {}
    for ordinal, line in enumerate(aggregate.read_text(encoding='utf-8').splitlines(), 1):
        row = json.loads(line)
        symbol = row.get('symbol')
        check(isinstance(symbol, str) and re.fullmatch(r'[0-9]{6}\.(SH|SZ|BJ)', symbol) is not None and canonical(symbol) == symbol, f'Line {ordinal}: noncanonical code')
        check(symbol in success and symbol not in seen, f'Line {ordinal}: unexpected/duplicate symbol')
        check(row.get('ts') == day, f'Line {ordinal}: wrong business date')
        check(set(row) == set(schema['fields']), f'{symbol}: field set differs from 110-field schema')
        check(row.get('feature_version') == schema['featureVersion'], f'{symbol}: feature version differs')
        check(row.get('parser_version') == schema['parserVersion'], f'{symbol}: parser version differs')
        for field, kind in schema['fields'].items():
            value = row.get(field)
            valid = ((value is None and nullable[field]) or
                     (kind == 'str' and type(value) is str) or
                     (kind == 'bool' and type(value) is bool) or
                     (kind == 'int' and type(value) is int) or
                     (kind == 'number' and type(value) in (int, float) and math.isfinite(value)))
            check(valid, f'{symbol}/{field}: invalid type, null or nonfinite value')
            if value is None:
                null_counts[field] = null_counts.get(field, 0) + 1
                null_symbols.setdefault(field, []).append(symbol)
            field_values += 1
        feature = base / 'features' / (symbol + '.json')
        check(sha(feature) == outcomes[symbol]['resultSha256'], f'{symbol}: per-symbol SHA256 differs')
        check(feature.read_text(encoding='utf-8').strip() == line, f'{symbol}: aggregate differs from single result')
        seen.add(symbol)
        ordered.append(symbol)
    check(seen == success, 'Aggregate missing successful symbols')
    check(ordered == sorted(ordered), 'Aggregate is not ordered by canonical symbol')
    return {'date': day, 'status': 'PASSED' if not errors else 'FAILED', 'archiveRawSymbols': len(expected_raw),
            'expectedCanonicalSymbols': len(expected), 'outputRows': len(seen), 'emptySymbols': empty,
            'failedSymbols': [s for s, r in outcomes.items() if r['status'] == 'FAILED'],
            'ignoredEntries': manifest['ignoredEntries'], 'sourceFileSizeChecks': source_size_checks,
            'missingSourceDirectories': sorted(set(expected_raw) - actual_raw),
            'unexpectedSourceDirectories': sorted(actual_raw - set(expected_raw)),
            'archiveMissingTables': census['missing_tables'],
            'fieldValuesChecked': field_values, 'aggregateSha256': actual_hash, 'errors': errors,
            'nullCountsByField': null_counts, 'nullSymbolsByField': null_symbols,
            'nativeStartedAt': manifest['startedAt'], 'nativeFinishedAt': manifest['updatedAt']}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--date', choices=('20260928', '20260929', '20260930'))
    args = parser.parse_args()
    schema = load(EVIDENCE / 'performance-schema.json')
    definitions = re.findall(r'\("([a-z0-9_]+)", StorageType\.([A-Z]+), (true|false),', MODEL.read_text(encoding='utf-8'))
    nullable = {name: flag == 'true' for name, kind, flag in definitions}
    kinds = {'TIMESTAMP': 'str', 'SYMBOL': 'str', 'STRING': 'str', 'BOOLEAN': 'bool', 'LONG': 'int', 'DOUBLE': 'number'}
    assert len(definitions) == 110 and set(nullable) == set(schema['fields']), 'Formal field definition differs'
    assert all(kinds[kind] == schema['fields'][name] for name, kind, flag in definitions), 'Formal storage type differs'
    dates = (args.date,) if args.date else ('20260928', '20260929', '20260930')
    results = []
    for day in dates:
        if not (OUTPUT / day / 'manifest.json').is_file():
            results.append({'date': day, 'status': 'PENDING'})
        else:
            results.append(validate(day, schema, nullable))
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'formalDatabaseWrites': False,
              'nullableContractSource': str(MODEL), 'nullableContractSha256': sha(MODEL), 'dates': results}
    suffix = args.date or 'all'
    path = Path(__file__).resolve().parent / ('output-validation-' + suffix + '.json')
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
    return 2 if any(r['status'] == 'FAILED' for r in results) else 0

if __name__ == '__main__':
    raise SystemExit(main())
