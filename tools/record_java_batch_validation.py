"""Archive a completed full live Batch validation. Does not run jobs or touch databases."""
import argparse
import datetime
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--log', type=Path, required=True)
args = parser.parse_args()
log = args.log.read_text()
if 'BUILD SUCCESSFUL' not in log or 'BUILD FAILED' in log:
    raise SystemExit('A successful completed Gradle validation log is required')

evidence = ROOT / 'docs/java-batch-20260929/evidence'
counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
classes = []
for path in (ROOT / 'build/test-results/test').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    result = {key: int(suite.get(key, 0)) for key in counts}
    for key in counts:
        counts[key] += result[key]
    if '.batch.' in suite.get('name', ''):
        classes.append({'class': suite.get('name'), **result})
if not counts['tests'] or counts['errors'] or counts['failures']:
    raise SystemExit('Missing or failing test results')
for name in ('NativeProducerLiveTest', 'QuestDbProtocolTest'):
    if not any(c['class'].endswith('.' + name) and c['tests'] > 0 and c['skipped'] == 0 for c in classes):
        raise SystemExit('Required real integration test did not execute: ' + name)

now = datetime.datetime.now(datetime.timezone.utc)
history = evidence / 'history' / now.strftime('%Y%m%dT%H%M%S%fZ')
history.mkdir(parents=True)
for name in ('validation.json', 'native-producers.json', 'questdb-protocol.json', 'side-effects.json'):
    shutil.copyfile(evidence / name, history / name)

validation = json.loads((evidence / 'validation.json').read_text())
validation.update(at_utc=now.isoformat(), counts=counts, native_test_classes=classes,
                  overall_requirement_status='incomplete', goal_turn_classification='progress')
validation['validation_log_sha256'] = hashlib.sha256(args.log.read_bytes()).hexdigest()
for name in validation['artifacts']:
    data = (ROOT / name).read_bytes()
    validation['artifacts'][name] = dict(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
for source, target in [('jdb-native-producers.json', 'native-producers.json'),
                       ('jdb-questdb-protocol.json', 'questdb-protocol.json')]:
    shutil.copyfile(ROOT / 'build/reports' / source, evidence / target)

side_effects = json.loads((evidence / 'side-effects.json').read_text())
seen = {entry['table'] for entry in side_effects['questdb_test_tables']}
for path in (ROOT / 'var/jdb-source-acceptance').glob('*/writes/*/certificate.json'):
    certificate = json.loads(path.read_text())
    table = certificate['target']
    if table not in seen:
        source = json.loads(Path(certificate['sourceArtifact']).read_text())
        side_effects['questdb_test_tables'].append(dict(table=table, dataset=source['dataset'],
            rows=certificate['verifiedRows'], evidence=str(path)))
        seen.add(table)
protocol = json.loads((evidence / 'questdb-protocol.json').read_text())
protocol_archive = history / 'verified-questdb-protocol.json'
shutil.copyfile(evidence / 'questdb-protocol.json', protocol_archive)
if protocol['table'] not in seen:
    side_effects['questdb_test_tables'].append(dict(table=protocol['table'], dataset='protocol-test',
        rows=protocol['visibleRows'], evidence=str(protocol_archive.relative_to(ROOT))))

for name, data in [('validation.json', validation), ('side-effects.json', side_effects)]:
    (evidence / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(dict(counts=counts, history=str(history),
                      isolated_tables=len(side_effects['questdb_test_tables']))))
