"""Check fresh native outputs against independent canonical Python and the earlier native batch."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parent
DATES = ('20260928', '20260929', '20260930')
SYMBOLS = {'000001.SZ', '600000.SH', '300750.SZ', '688981.SH', '510300.SH', '159915.SZ', '588000.SH'}

def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def rows(path):
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.strip():
            row = json.loads(line)
            if row['symbol'] in result:
                raise ValueError('Duplicate canonical symbol in ' + str(path))
            result[row['symbol']] = (row, line)
    return result

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def compare(java, expected):
    mismatches = []
    for key in sorted(set(java) | set(expected)):
        left, right = java.get(key), expected.get(key)
        reason = None
        if key not in java or key not in expected:
            reason = 'missing field'
        elif type(left) is not type(right):
            reason = 'type mismatch'
        elif type(left) is float:
            if not math.isfinite(left) or not math.isfinite(right) or not math.isclose(left, right, rel_tol=1e-10, abs_tol=1e-9):
                reason = 'nonfinite or outside tolerance'
        elif left != right:
            reason = 'exact value mismatch'
        if reason:
            mismatches.append({'field': key, 'java': left, 'python': right, 'reason': reason})
    return mismatches

report = {'generatedAt': datetime.now(timezone.utc).isoformat(), 'status': 'passed',
          'relativeTolerance': 1e-10, 'absoluteTolerance': 1e-9,
          'source': 'Fresh formal Java batch vs freshly calculated Python KuakeSyncPipeline P0-P13',
          'rowsCompared': 0, 'fieldsCompared': 0, 'matched': 0, 'differenceCount': 0,
          'exactlyUnchangedNativeRows': 0, 'pythonInputHashChecks': 0, 'errors': [], 'dates': {}}
for day in DATES:
    base = ROOT / 'native-fresh' / day
    manifest = load(base / 'manifest.json')
    java_rows = rows(base / 'l2_daily_features.jsonl')
    prior_root = ROOT.parent / 'l2-performance-validation/batch-real' / day
    prior = load(prior_root / 'manifest.json')
    prior_rows = rows(prior_root / 'l2_daily_features.jsonl')
    if set(java_rows) != SYMBOLS or manifest['status'] != 'COMPLETE' or not manifest['published']:
        report['errors'].append(day + ': unexpected symbol set or incomplete manifest')
    if manifest['counts'] != {'success': 7, 'empty': 0, 'failed': 0, 'resumed': 0}:
        report['errors'].append(day + ': not seven successful fresh computations')
    if sha(base / 'l2_daily_features.jsonl') != manifest['aggregateSha256']:
        report['errors'].append(day + ': aggregate hash mismatch')
    if manifest['computeFingerprint'] != prior['computeFingerprint']:
        report['errors'].append(day + ': computation fingerprint changed since performance batch')
    prior_states = {s['symbol']: s for s in prior['symbols']}
    python_inputs = (load(ROOT / day / 'source-input-hashes.json')['csvInputs'] if day == '20260928'
                     else load(ROOT / day / 'raw-source-hashes.json'))
    date_report = {'counts': manifest['counts'], 'aggregateSha256': manifest['aggregateSha256'],
                   'computeFingerprint': manifest['computeFingerprint'], 'symbols': {}}
    for symbol in sorted(SYMBOLS):
        java, java_text = java_rows[symbol]
        ref_path = ROOT / day / 'python-reference' / (symbol + '.json')
        expected = load(ref_path)
        mismatches = compare(java, expected)
        if len(java) != 110 or len(expected) != 110 or java.get('ts') != day or java.get('symbol') != symbol:
            report['errors'].append(day + '/' + symbol + ': field count or business key error')
        unchanged = java_text == prior_rows[symbol][1]
        if not unchanged:
            report['errors'].append(day + '/' + symbol + ': fresh native row differs from prior batch')
        state = load(base / 'state' / (symbol + '.json'))
        if state['inputFingerprint'] != prior_states[symbol]['inputFingerprint']:
            report['errors'].append(day + '/' + symbol + ': input fingerprint changed')
        for filename, metadata in python_inputs[symbol]['tables'].items():
            native_file = state['inputFiles'][filename]
            if not native_file['present'] or native_file['bytes'] != metadata['bytes'] or native_file['sha256'] != metadata['sha256']:
                report['errors'].append(day + '/' + symbol + '/' + filename + ': Python and native input SHA/size differ')
            report['pythonInputHashChecks'] += 1
        report['rowsCompared'] += 1
        report['fieldsCompared'] += len(expected)
        report['differenceCount'] += len(mismatches)
        report['matched'] += len(expected) - len(mismatches)
        report['exactlyUnchangedNativeRows'] += unchanged
        date_report['symbols'][symbol] = {'fieldsCompared': len(expected), 'differences': mismatches,
                                          'nativeExactlyUnchanged': unchanged, 'pythonSha256': sha(ref_path),
                                          'inputFingerprint': state['inputFingerprint']}
    report['dates'][day] = date_report
if report['errors'] or report['differenceCount']:
    report['status'] = 'failed'
(ROOT / 'fresh-native-comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k != 'dates'}, ensure_ascii=False))
raise SystemExit(0 if report['status'] == 'passed' else 2)
