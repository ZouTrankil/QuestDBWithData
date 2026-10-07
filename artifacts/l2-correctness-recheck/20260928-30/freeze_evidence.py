"""Freeze small correctness evidence; excludes CSV, parser exports, build/cache and databases."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil

SOURCE = Path(__file__).resolve().parent
REPO = SOURCE.parents[1]
DESTINATION = REPO / 'artifacts/l2-correctness-recheck/20260928-30'
DATES = ('20260928', '20260929', '20260930')

def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def copy(path):
    relative = path.relative_to(SOURCE)
    target = DESTINATION / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)

fresh = load(SOURCE / 'fresh-native-comparison.json')
parser_reports = {date: load(SOURCE / ('parser-comparison-' + date + '.json')) for date in DATES}
tests = load(SOURCE / 'targeted-junit-summary.json')
behaviors = load(SOURCE / 'batch-cli-behavior-summary.json')
assert fresh['status'] == 'passed' and fresh['fieldsCompared'] == 2310 and fresh['differenceCount'] == 0
assert fresh['exactlyUnchangedNativeRows'] == 21 and fresh['pythonInputHashChecks'] == 63
assert all(r['status'] == 'passed' and r['failedTables'] == 0 and r['mismatchedCells'] == 0 for r in parser_reports.values())
assert sum(t['tests'] for t in tests) == 42
assert sum(t['failures'] + t['errors'] + t['skipped'] for t in tests) == 0
assert behaviors['status'] == 'passed' and not behaviors['errors'] and behaviors['completedBehaviorCases'] == 15

DESTINATION.mkdir(parents=True, exist_ok=True)
for path in SOURCE.iterdir():
    if path.is_file() and path.suffix in ('.json', '.log', '.py', '.java', '.md', '.txt', '.gradle'):
        copy(path)
for date in DATES:
    for path in (SOURCE / date).rglob('*'):
        if path.is_file() and path.suffix in ('.json', '.log', '.py'):
            copy(path)
    native = SOURCE / 'native-fresh' / date
    for name in ('manifest.json', 'progress.jsonl', 'errors.jsonl', 'l2_daily_features.jsonl'):
        copy(native / name)
    for kind in ('features', 'state'):
        for path in (native / kind).glob('*.json'):
            copy(path)
for run in (SOURCE / 'native-fresh').glob('run-*'):
    for path in run.iterdir():
        if path.is_file() and path.suffix in ('.json', '.log', '.gradle'):
            copy(path)
for path in (SOURCE / 'gradle-build/test-results/test').glob('TEST-*.xml'):
    copy(path)
batch_run = Path(behaviors['validationRun'])
for path in batch_run.glob('*.log'):
    copy(path)

production = load(REPO / 'artifacts/l2-performance-validation/20261007/production-source-hashes.json')
current = {name: hashlib.sha256((REPO / name).read_bytes()).hexdigest() for name in production}
assert current == production, 'Production source changed since the measured batch'
(DESTINATION / 'production-source-hashes.json').write_text(json.dumps(current, indent=2) + '\n', encoding='utf-8')
summary = {'recordedAt': datetime.now(timezone.utc).isoformat(), 'status': 'passed',
           'dates': list(DATES), 'symbolDays': 21, 'stocksPerDate': 4, 'etfsPerDate': 3,
           'freshNativeComputations': 21, 'resumedNativeComputations': 0,
           'featureFieldsCompared': fresh['fieldsCompared'], 'featureDifferences': fresh['differenceCount'],
           'pythonNativeInputFileHashMatches': fresh['pythonInputHashChecks'],
           'parserRowsCompared': sum(r['rowsCompared'] for r in parser_reports.values()),
           'parserCellsCompared': sum(r['cellsCompared'] for r in parser_reports.values()),
           'parserDifferences': 0, 'junitPassed': 42, 'junitFailed': 0, 'junitSkipped': 0,
           'batchBehaviorCasesPassed': 15, 'productionSourceFilesUnchanged': len(current),
           'featureTolerance': {'relative': 1e-10, 'absolute': 1e-9},
           'parserTolerance': {'relative': 1e-12, 'absolute': 1e-12},
           'formalDatabaseWrites': False, 'fullArchiveCleaningStarted': False}
(DESTINATION / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
files = {}
for path in sorted(DESTINATION.rglob('*')):
    if path.is_file() and path.name != 'evidence-manifest.json':
        payload = path.read_bytes()
        files[path.relative_to(DESTINATION).as_posix()] = {'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}
manifest = {'frozenAt': datetime.now(timezone.utc).isoformat(), 'fileCount': len(files),
            'totalBytes': sum(v['bytes'] for v in files.values()), 'files': files}
(DESTINATION / 'evidence-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False))
print(json.dumps({'files': manifest['fileCount'], 'bytes': manifest['totalBytes'], 'destination': str(DESTINATION)}))
