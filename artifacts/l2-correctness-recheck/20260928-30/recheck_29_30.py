"""Isolated canonical reference and strict field comparison for the two sample days."""
from __future__ import annotations

import hashlib
import json
import math
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
PROJECT = ROOT.parent.parent
PYTHON = pathlib.Path(r'D:\work\fund_2\back-monitor\.venv\Scripts\python.exe')
SOURCE = PROJECT / 'var/l2-performance-validation/raw'
REFERENCE_RUNNER = PROJECT / 'var/l2-pipeline-validation/python-reference/generate_reference.py'
SYMBOLS = ['000001.SZ', '600000.SH', '300750.SZ', '688981.SH', '510300.SH', '159915.SZ', '588000.SH']


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')


def hash_file(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(day):
    output = ROOT / day
    output.mkdir(parents=True, exist_ok=True)
    script = REFERENCE_RUNNER.read_text(encoding='utf-8')
    script = script.replace('parser.add_argument("--input", required=True)', 'parser.add_argument("--input", required=True)\n    parser.add_argument("--date", required=True)')
    script = script.replace('date = datetime(2026, 9, 24)', 'date = datetime.strptime(args.date, "%Y%m%d")')
    script = script.replace('"date": "20260924"', '"date": args.date')
    script = script.replace('datetime(2026,9,24)', 'datetime.strptime(date, yyyyMMdd)')
    (output / 'generate_reference.py').write_text(script, encoding='utf-8')
    hashes = {}
    for symbol in SYMBOLS:
        code = symbol.split('.')[0]
        directories = sorted((SOURCE / day).glob(code + '.*'))
        if len(directories) != 1:
            raise RuntimeError(f'Expected one raw directory for {symbol} on {day}, found {directories}')
        tables = {}
        for path in sorted(directories[0].glob('*.csv')):
            tables[path.name] = {'path': str(path), 'bytes': path.stat().st_size, 'sha256': hash_file(path)}
        hashes[symbol] = {'raw_directory': str(directories[0]), 'tables': tables}
    dump(output / 'raw-source-hashes.json', hashes)
    return output


def compare(day, output):
    actual_path = PROJECT / f'var/l2-performance-validation/batch-real/{day}/l2_daily_features.jsonl'
    actual_bytes = actual_path.read_bytes()
    actual = [json.loads(line) for line in actual_bytes.decode('utf-8').splitlines() if line.strip()]
    result = {'date': day, 'reference': 'Canonical KuakeSyncPipeline / all P0-P13 / load_snapshot=True',
              'java_file': str(actual_path), 'java_file_sha256': hashlib.sha256(actual_bytes).hexdigest(),
              'relative_tolerance': 1e-10, 'absolute_tolerance': 1e-9, 'java_total_rows': len(actual),
              'selected_symbols': SYMBOLS, 'ignored_noncore_symbols': sorted({r['symbol'] for r in actual} - set(SYMBOLS)),
              'symbols': {}}
    for symbol in SYMBOLS:
        matches = [row for row in actual if row['symbol'] == symbol]
        if len(matches) != 1:
            raise RuntimeError(f'Expected exactly one native row for {symbol} on {day}, got {len(matches)}')
        row = matches[0]
        if row['ts'] != day:
            raise RuntimeError(f'Wrong native business date: {row["ts"]}')
        reference = json.loads((output / 'python-reference' / f'{symbol}.json').read_text(encoding='utf-8'))
        differences = []
        all_fields = set(reference) | set(row)
        if len(reference) != 110 or len(row) != 110:
            differences.append({'field': '__field_count__', 'python': len(reference), 'java': len(row), 'reason': 'Expected 110 fields in both rows'})
        for field in sorted(all_fields):
            expected = reference.get(field)
            value = row.get(field)
            if field not in row or field not in reference:
                differences.append({'field': field, 'python': expected, 'java': value, 'reason': 'Missing field'})
            elif type(expected) is float and type(value) is float:
                if not math.isfinite(value) or not math.isfinite(expected) or not math.isclose(expected, value, rel_tol=1e-10, abs_tol=1e-9):
                    differences.append({'field': field, 'python': expected, 'java': value, 'delta': value - expected,
                                        'reason': 'Float tolerance exceeded'})
            elif type(expected) is not type(value) or expected != value:
                differences.append({'field': field, 'python': expected, 'java': value,
                                    'python_type': type(expected).__name__, 'java_type': type(value).__name__,
                                    'reason': 'Strict type/value mismatch'})
        result['symbols'][symbol] = {'fields_compared': len(all_fields), 'fields_matched': len(all_fields) - len(differences),
                                     'differences': differences}
    result['fields_compared'] = sum(s['fields_compared'] for s in result['symbols'].values())
    result['difference_count'] = sum(len(s['differences']) for s in result['symbols'].values())
    result['success'] = result['difference_count'] == 0
    dump(output / 'daily-feature-comparison.json', result)
    print('COMPARISON ' + json.dumps(result, ensure_ascii=False), flush=True)
    return result


def main():
    summaries = []
    for day in ['20260929', '20260930']:
        output = prepare(day)
        reference = output / 'python-reference'
        args = [str(PYTHON), '-X', 'utf8', str(output / 'generate_reference.py'), '--input', str(SOURCE),
                '--output', str(reference), '--date', day, '--symbols', *SYMBOLS]
        print('REFERENCE_START ' + day, flush=True)
        with (output / 'python-reference.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
            for line in process.stdout:
                log.write(line)
                log.flush()
                if line.startswith(('START ', 'DONE ', 'FAILED ')):
                    print(day + ' ' + line.strip(), flush=True)
            code = process.wait()
        if code:
            raise RuntimeError(f'Canonical pipeline failed on {day}; inspect {output / "python-reference.log"}')
        comparison = compare(day, output)
        reference_summary = json.loads((reference / 'summary.json').read_text(encoding='utf-8'))
        summary = {'date': day, 'symbols': len(SYMBOLS), 'fields_compared': comparison['fields_compared'],
                   'differences': comparison['difference_count'], 'success': comparison['success'],
                   'python_elapsed_seconds': sum(e['elapsed_seconds'] for e in reference_summary['symbols'].values()),
                   'java_file_sha256': comparison['java_file_sha256'],
                   'comparison': str(output / 'daily-feature-comparison.json'),
                   'python_reference': str(reference), 'raw_hashes': str(output / 'raw-source-hashes.json')}
        dump(output / 'summary.json', summary)
        summaries.append(summary)
        dump(ROOT / 'summary-29-30.json', summaries)
    return 0 if all(s['success'] for s in summaries) else 1


if __name__ == '__main__':
    raise SystemExit(main())
