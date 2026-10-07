"""Copy small, explicit native L2 performance artifacts; never copies source CSV or build trees."""
from pathlib import Path
import hashlib
import json
import shutil
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
DESTINATION = ROOT / 'artifacts/l2-performance-validation/20261007'
DATES = ('20260928', '20260929', '20260930')

def copy_file(relative):
    source = SOURCE / relative
    destination = DESTINATION / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)

for name in (
    'census.json', 'hardware.json', 'extraction-summary.json', 'benchmark-symbols.json',
    'jobs-0928.json', 'jobs-all.json', 'performance-schema.json', 'performance-summary.json',
    'performance-summary.md', 'parser-optimization-guard.json', 'cleaning-plan.json',
    'batch-real-first-metrics.json', 'batch-real-resume-metrics.json', 'real-batch-summary.json',
    'batch-real-first.log', 'batch-real-resume.log', 'script-resume.log', 'gradle-compile.log',
    'NativeL2Benchmark.java', 'NativeL2BatchMeasurement.java', 'analyze_performance.py',
    'summarize_real_batch.py', 'archive_inventory.py', 'README-benchmark.md', 'freeze_evidence.py',
):
    copy_file(Path(name))

for date in DATES:
    for name in (f'{date}-symbols.json', f'{date}-extraction.log', f'{date}-quantile-extraction.log',
                 f'{date}-selection.txt', f'{date}-quantile-selection.txt'):
        copy_file(Path('archive-metadata') / name)
    for snapshot in ('batch-real-first-snapshot', 'batch-real'):
        for name in ('manifest.json', 'progress.jsonl', 'errors.jsonl', 'l2_daily_features.jsonl'):
            copy_file(Path(snapshot) / date / name)
    for folder in ('features', 'state'):
        for path in sorted((SOURCE / 'batch-real' / date / folder).glob('*.json')):
            copy_file(path.relative_to(SOURCE))

for run in ('w1', 'optimized-w1', 'optimized-w2', 'optimized-w4', 'optimized-w8'):
    for name in ('benchmark.json', 'rounds.csv', 'tasks.csv', 'stages.csv', 'daily-features.jsonl'):
        copy_file(Path('benchmarks') / run / name)

script_runs = sorted((SOURCE / 'batch-real').glob('run-*/native-summary.json'), key=lambda p: p.stat().st_mtime_ns)
latest = script_runs[-1].parent
for name in ('plan.json', 'native-summary.json', 'native-arguments.json', 'native-arguments.init.gradle', 'native-batch.log'):
    copy_file((latest / name).relative_to(SOURCE))

production = [ROOT / 'build.gradle', ROOT / 'tools/run_l2_native_cleaning.ps1',
              ROOT / 'src/main/java/com/zoutrankil/batch/DfcfCsvParser.java',
              ROOT / 'src/main/java/com/zoutrankil/batch/DfcfCsvInspector.java',
              ROOT / 'src/main/java/com/zoutrankil/data/domain/DatasetDefinition.java']
production += sorted((ROOT / 'src/main/java/com/zoutrankil/batch/l2').glob('*.java'))
production += [ROOT / f'src/main/java/com/zoutrankil/data/domain/{name}.java'
               for name in ('L2DailyFeatureField', 'L2DailyFeatures', 'L2DailyFeaturesKey')]
source_hashes = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in production}
(DESTINATION / 'production-source-hashes.json').write_text(json.dumps(source_hashes, indent=2) + '\n', encoding='utf-8')

files = {}
for path in sorted(DESTINATION.rglob('*')):
    if path.is_file() and path.name != 'evidence-manifest.json':
        data = path.read_bytes()
        files[path.relative_to(DESTINATION).as_posix()] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
manifest = {'frozenAt': datetime.now(timezone.utc).isoformat(), 'fileCount': len(files),
            'totalBytes': sum(f['bytes'] for f in files.values()),
            'excludes': ['source CSV', 'Gradle build/cache', 'database ingestion'], 'files': files}
(DESTINATION / 'evidence-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'destination': str(DESTINATION), 'files': manifest['fileCount'], 'bytes': manifest['totalBytes']}))
