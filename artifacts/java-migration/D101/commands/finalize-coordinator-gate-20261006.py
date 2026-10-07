"""Finalize D101 records only after frozen independent readiness acceptance.

No service queries, tests, owner calls, writes to QuestDB, or next-task execution.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / 'artifacts/java-migration/D101'
DOCS = ROOT / 'docs/migration-tasks-20260929'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cmd(name):
    return BASE / 'commands' / name


gate_path = BASE / 'coordinator-review-20261006.json'
if gate_path.exists():
    raise RuntimeError('Final coordinator record exists; refusing to replace evidence')

bindings = {
    'coordinator-typed-write-data-review-20261006.json': '2e1665daa650aec966bc16e46ce59b73dc38a2139a0bde77aec88403c91d2b19',
    'unique-test-inventory-final-20261006.json': '6482df3552b58b66c622e85c72fa06694151e389246c4bb22fc551154a49547a',
    'java-readthrough-typed-write-20261006.json': '6914f8cddb9d1bf638d70d6db7a8100cc3ea3631f141267b4d145ec70dce0cf5',
    'java-readthrough-increment-20261006.json': '66fd525f51d94e3b4db4ae33aa5257d67f62062a588504ba14cb9f7334edcb9b',
}
for name, expected in bindings.items():
    if sha(cmd(name)) != expected:
        raise RuntimeError(f'Frozen evidence changed: {name}')
typed_review = read(cmd('coordinator-typed-write-data-review-20261006.json'))
if typed_review['recommendation'] != 'accepted_for_D101_final_coordinator_gate_review' or typed_review['blockers']:
    raise RuntimeError('Typed review does not admit final gate')
readiness = read(cmd('coordinator-final-readiness-review-20261006.json'))
if readiness['recommendation'] != 'accepted_for_serial_progress' or readiness.get('blockers'):
    raise RuntimeError('Final readiness not accepted')
inventory = read(cmd('unique-test-inventory-final-20261006.json'))
if (inventory['java_unique_passed_methods'], inventory['python_unique_passed_methods']) != (190, 75):
    raise RuntimeError('Unique method inventory changed')
typed = read(cmd('java-readthrough-typed-write-20261006.json'))
increment = read(cmd('java-readthrough-increment-20261006.json'))
if typed['tables_before'] != typed['tables_after'] or typed['tables_after'] != increment['tables_after']:
    raise RuntimeError('Typed stage frontier mismatch')
if typed['matching_actual_owner_hits'] != 3 or typed['matching_cache_submitted_rows'] != 0 or typed['matching_coverage_submitted_rows'] != 0:
    raise RuntimeError('Typed owner results changed')
if increment['stored_cache_rows_count'] != 5 or increment['stored_receipt_rows_count'] != 5 or not increment['old_actual_cursor_rejected_before_row_query']:
    raise RuntimeError('Increment field/cursor results changed')

now = datetime.now(timezone.utc).isoformat()
result_path = DOCS / 'results/D101.json'
result = read(result_path)
result.update(status='verified', implementation_status='implemented', data_validation_status='verified',
              validation_scope='Dedicated isolated actual QuestDB with bounded real-source copies; no production cutover',
              blocker=None, coordinator_gate='accepted_for_serial_progress', next_task_admitted=True,
              next_task='D102', finished_at=now, task_record_date='2026-10-06')
result['request_logical_dates'] = {
    'first': read(cmd('java-readthrough-first-20261006.json'))['run']['logicalDate'],
    'hit': read(cmd('java-readthrough-hit-20261006.json'))['run']['logicalDate'],
    'increment': increment['run']['logicalDate'],
    'typed_child': typed['child_run']['logicalDate'],
}
result['checkpoint'].update(initial_before=None, increment_before='2026-09-18', increment_after='2026-09-21')
new_evidence = [
    'commands/coordinator-typed-write-data-review-20261006.json',
    'commands/unique-test-inventory-final-20261006.json',
    'commands/coordinator-final-readiness-review-20261006.json',
    'coordinator-review-20261006.json',
]
for suffix in new_evidence:
    relative = f'artifacts/java-migration/D101/{suffix}'
    if relative not in result['evidence']:
        result['evidence'].append(relative)

mapping_path = BASE / 'mapping-contract-20261006.json'
mapping = read(mapping_path)
mapping.update(status='VERIFIED_ISOLATED', coordinator_gate='accepted_for_serial_progress', human_review='pending_review')

readme_path = BASE / 'README.md'
readme = readme_path.read_text(encoding='utf-8-sig')
readme = readme.replace('状态：**Java 实现与隔离数据验收已通过，最终协调复核中**。人工复核仍为 pending_review；D102 待最终 gate。',
    '状态：**verified，协调器 accepted_for_serial_progress**。人工复核仍为 pending_review；按序准许 D102。')
readme = readme.replace('四个canonical现场阶段已通过，最终协调验收待登记。', '四个canonical现场阶段及最终独立证据复核均通过，协调结论已登记。')
readme = readme.replace('D101 保持 **in_progress**。', '当时 D101 保持 **in_progress**。')
readme = readme.replace('typed WriteGroup 将委托', 'typed WriteGroup 已实现委托')
readme = readme.replace('当前CIM证明7个unique PID', '当时保存的CIM记录证明7个unique PID')
readme = readme.replace('D102待最终协调gate。', '最终协调gate已接受串行推进D102。')
readme = readme.replace('最终typed数据独立review与协调gate待落盘，D102尚未准入。', '最终typed数据独立review与协调gate已落盘，D102已准许按序执行。')
readme += '\n最终准入记录：[coordinator-review-20261006.json](coordinator-review-20261006.json)。接受本卡隔离迁移交付，D102 可按序开始；正式库历史摘要异常、全历史/生产当前有效性及同键源值修订现场限制保留，人工复核 pending_review。\n'

card_path = DOCS / '11-derived/D101-etf_market_overview_daily_cache.md'
card = card_path.read_text(encoding='utf-8-sig')
lines = card.splitlines()
for index, line in enumerate(lines):
    if line.startswith('- 状态：'):
        lines[index] = '- 状态：verified；实际隔离验收及独立证据复核通过，协调器 accepted_for_serial_progress；人工 pending_review。'
        break
card = '\n'.join(lines) + '\n'
card = card.replace('D102待最终协调gate。', '最终协调gate已接受串行推进D102。')
card += '\n最终协调结论：本卡 verified，accepted_for_serial_progress；按序准许 D102。记录见 `artifacts/java-migration/D101/coordinator-review-20261006.json`，人工复核仍 pending_review。\n'

register_path = DOCS / 'completion-register.md'
register = register_path.read_text(encoding='utf-8-sig')
register_lines = register.splitlines()
for index, line in enumerate(register_lines):
    if line.startswith('| D101 |'):
        register_lines[index] = line.replace('implemented；隔离数据verified，协调复核中', 'verified（隔离验收）').replace('FIRST/HIT/增量/typed全部通过，最终协调复核中', 'FIRST/HIT/增量/typed及独立协调复核通过；accepted_for_serial_progress')
        break
register = '\n'.join(register_lines) + '\n'

status_path = DOCS / 'execution-status.md'
status = status_path.read_text(encoding='utf-8-sig')
status = status.replace('## D101 隔离验收通过，最终协调复核中（2026-10-06）', '## D101 verified，按序准许 D102（2026-10-06）')
status = status.replace('最终typed与数据证据独立复核及协调gate待落盘，D102仍未准入，人工pending_review。',
    '最终typed与数据证据独立复核通过，协调gate accepted_for_serial_progress，按序准许D102，人工pending_review。剩余主线D102–D184共83项，Q仍须独立准入。')

manifest_path = DOCS / 'manifest.json'
manifest = read(manifest_path)
task = next(item for item in manifest['tasks'] if item['id'] == 'D101')
if task['status'] != 'in_progress':
    raise RuntimeError('Unexpected D101 manifest state')
task['status'] = 'verified'
manifest['current_thread_scope']['remaining_mainline'] = 'D102-D184'

# All assertions and independent admission checks above precede mutable status updates.
save(result_path, result)
save(mapping_path, mapping)
readme_path.write_text(readme, encoding='utf-8')
card_path.write_text(card, encoding='utf-8')
register_path.write_text(register, encoding='utf-8')
status_path.write_text(status, encoding='utf-8')
save(manifest_path, manifest)

evidence_names = [
    'coordinator-first-data-review-20261006.json',
    'coordinator-hit-readonly-recovery-review-20261006.json',
    'coordinator-hit-new-run-review-20261006.json',
    'coordinator-source-increment-data-review-20261006.json',
    'coordinator-java-increment-data-review-20261006.json',
    'coordinator-typed-write-data-review-20261006.json',
    'coordinator-final-static-review-20261006.json',
    'coordinator-final-readiness-review-20261006.json',
    'unique-test-inventory-final-20261006.json',
    'java-readthrough-first-20261006.json',
    'java-readthrough-hit-20261006.json',
    'java-readthrough-increment-20261006.json',
    'java-readthrough-typed-write-20261006.json',
    'java-hit-readonly-recovery-20261006.json',
    'source-fixture-initial-20261006.json',
    'source-fixture-increment-20261006.json',
    'cache-readonly-audit-20261006.json',
]
paths = [cmd(name) for name in evidence_names] + [result_path, mapping_path, readme_path, card_path, register_path, status_path, manifest_path]
gate = dict(task_id='D101', reviewed_at=now, reviewer='primary coordinator + independent static/data/readiness reviews',
    decision='accepted_for_serial_progress', human_review='pending_review',
    validation_scope='Dedicated isolated QuestDB, three bounded real-source days and all timeless ETF basic rows; original Python unique publisher',
    tests=dict(java_pure=184, java_live=6, java_unique=190, python_bridge=43, python_fixture=32, python_unique=75, failures=0, errors=0, skipped=0),
    source_rows=11667, source_full_field_values=164180,
    cache_full_keys=5, receipt_full_keys=5, cache_unique_field_values=25, receipt_unique_field_values=25, matched_original_digests=5,
    first_actual_misses=2, full_hit_actual_hits=2, full_prefix_increment_actual_misses=3, typed_actual_hits=3,
    cache_submitted_rows=5, coverage_submitted_rows=5, acknowledged_publish_batches=10,
    typed_cache_and_coverage_submitted_rows=0, retained_leases=0,
    raw_normal_ack_ledger_writerStopped=False, actual_stopped_proof='Separate native Gateway birth/parent/exit evidence and fresh checks; no ledger field rewritten',
    explicit_unknown_readonly_recovery=dict(owner_calls=0, business_db_writes=0, run_attempt_matched_units=2, original_slice_actual_units=1, historical_failures_unchanged=True),
    old_actual_cursor_rejected_before_row_query=True, checkpoint_before_increment='2026-09-18', checkpoint_after='2026-09-21',
    pg_reference_double_rawbit_comparisons=20, independent_jdbc_double_rawbit_comparisons=20, double_tolerance=0,
    formal_writes=0, formal_receipt_generations=5, formal_digest_matches=3, formal_digest_mismatches=2, formal_repaired=False,
    current_formal_cache_hit_certified=False, production_cutover=False, full_history_certified=False, same_key_source_value_correction_live_tested=False,
    physical_insert_update_counts='unknown; driver submissions are not physical insert/update counts',
    coordinator_rechecked_sha_bindings=dict(static_sources=14, typed_data_evidence_files=245),
    evidence=[dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path)) for path in paths],
    result_sha256=sha(result_path), next_task='D102', next_task_may_start=True,
    execution_mode='direct_local_serial; Orca runtime previously unavailable, no duplicate dispatch', conditional_Q_admitted=False)
with gate_path.open('x', encoding='utf-8') as stream:
    json.dump(gate, stream, ensure_ascii=False, indent=2)
    stream.write('\n')
print(json.dumps(dict(task_id='D101', decision=gate['decision'], gate_sha256=sha(gate_path), next_task='D102', next_task_may_start=True)))
