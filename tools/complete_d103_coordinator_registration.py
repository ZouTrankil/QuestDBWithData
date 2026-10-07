"""File-only D103 coordinator closure; never connects to QuestDB or changes old evidence."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).absolute().parent.parent
BASE = ROOT / 'artifacts/java-migration/D103'
CMDS = BASE / 'commands'
DOCS = ROOT / 'docs/migration-tasks-20260929'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def binding(path):
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def verify(items):
    for item in items:
        assert binding(Path(item['path']))['sha256'] == item['sha256'], item['path']


def create(path, data):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def main():
    review_path = CMDS / 'coordinator-final-delivery-static-review-20261006.json'
    review = read(review_path)
    assert binding(review_path)['sha256'] == '9a377e15ce27aa86fa5ee119d4849a009630a8fef0602ef1038505a4878a9c80'
    assert review['status'] == 'PASS' and not review['blockers'] and not review['findings']
    for key in ('code_bindings', 'excluded_author_inventory', 'bindings'):
        verify(review[key])
    inventory_path = CMDS / 'validation-inventory-20261006.json'
    inventory = read(inventory_path)
    assert binding(inventory_path)['sha256'] == 'cd2d4e399495d3a539a913a0cbb179db690b931347c70b5ccf93b2393faa6872'
    assert inventory['unique_java_passing_methods'] == 125
    assert inventory['python_unique_pure_guards'] == 122
    verify(inventory['java_reports'])
    mapping = read(BASE / 'mapping-contract.json')
    assert len(mapping['columns']) == 30
    named = {
        'java-initial-readonly-recovery-20261006.json': '59dcdd9257c198c72944828f67226e74be05d760fb0bb37475f032b162aec7b4',
        'java-increment-acceptance-20261006.json': 'a13875847e736ba3848190071800fb8968fb6dfde879344e0338fbda647549e5',
        'source-fixture-increment-20261006.json': 'b04f0d4b5dac39a6d1defc879ad182113a79d170ebecffc7743e63883ef8d6b3',
        'final-java-process-and-ledger-review-20261006.json': '473fec15bf5a6e9ce8ad8f523c8d58fee88c0bac3841bc2cc021b23e9b6abe61',
        'coordinator-initial-java-receipt-failure-recovery-20261006.json': 'd449e9713a69d478e511f485b0a1bc52d24a0d68e86ecb72cac2dfc47227c893',
        'coordinator-increment-after-readonly-recovery-admission-20261006.json': 'df7686a861627b1feea8f5dd87f0773039933981e0d884e788774da400c1fd53',
        'source-contract-stored-units-20261006.json': '8cd85288b3fcc6c8bc6b1bc574bd2e422fe178e531782e6639114a5b7f6d8997',
    }
    evidence = [binding(review_path), binding(inventory_path)]
    for name, sha in named.items():
        path = BASE / name if name == 'source-contract-stored-units-20261006.json' else CMDS / name
        item = binding(path)
        assert item['sha256'] == sha, name
        evidence.append(item)
    evidence.extend(binding(BASE / name) for name in (
        'mapping-contract.json', 'mapping-contract-increment-update-20261006.json', 'README.md'))
    checked = datetime.now(timezone.utc).isoformat()
    limits = [
        'Only isolated bounded June-August 2026 acceptance; no formal writes, FULL, production cutover or full-history certification.',
        'Formal June/July output gaps remain unmodified; bounded August comparison is exact.',
        'Stored source pct_chg units remain unchanged; corrected stored-unit supplement supersedes old percent claims without modifying historical evidence.',
        'Legacy growth=000921.SH/value=000920.SH binding remains explicit despite index-universe naming conflict.',
        'Original initial source INSERT ACK remains UNKNOWN; landed source admitted by separate read-only reconciliation, never resubmitted.',
        'Original Java initial JUnit remains FAILED_FINAL_RECEIPT_SERIALIZATION; original operations are independently validated by fresh read-only recovery, not rerun.',
        'Original first Java JVM native PID/birth was lost; scoped synchronous executor completion is preserved, not fabricated native absence.',
        'Actual source append was verified; old-prefix source revision is pure-test coverage only, not a live revision claim.',
        'Point-in-time availability, complete upstream provider readiness and consumer admission are not certified.',
    ]
    matrix = {
        'D01': 'Explicit 30-column YearMonth domain/key/mapper; exact null/finite binary64 values and stored units.',
        'D02': 'Month business and physical UPSERT key; first calendar day at exact UTC midnight, strict YYYY-MM JSON.',
        'D03': 'Isolated missing-only YEAR/WAL/DEDUP(month) target; stable physical identity and independent empty COUNT proof.',
        'D04': 'Typed key/range/stable cursor/page and actual configured ReadGroup verified; maximum 12 months/page.',
        'D05': 'Bounded 12-row/1MiB typed batch, prepared WriteGroup, single synchronous HTTP flush, ACK then complete-key bitwise readback.',
        'D06': 'Finite real D022 full14 source capture; 16 index last-non-null monthly returns and 13 unchanged-unit spreads.',
        'D07': 'Canonical data.equity_style_monthly v1, 52 datasets/41 jobs, manual management/plan/run/status/cancel/resume/reconcile.',
        'D08': 'Closed-month completeness, full source-prefix hash and old target checks, overlap checkpoint, durable UNKNOWN/cancel/resume scope.',
        'D09': 'Actual first/replay/resume/reconcile/typed WriteGroup proved by immutable ledger and fresh read-only recovery; live July overlap/August append passed.',
    }
    gate = {
        'task_id': 'D103', 'dataset_id': 'equity_style_monthly', 'checked_at': checked,
        'decision': 'accepted_for_serial_progress', 'implementation_status': 'implemented',
        'data_validation_status': 'verified', 'human_review': 'pending_review', 'blockers': [],
        'execution_mode': 'direct_local_serial', 'next_task': 'D104', 'next_task_may_start': True,
        'next_task_admitted': True, 'delivery_matrix': matrix,
        'root_independent_review': {
            'scope': 'Domain/key/dataset/mapper/read repository/write port authored by another agent; root wiring remains covered by independent final delivery reviewer.',
            'findings': [], 'checks': ['All 30 positions mapped explicitly both ways; finite nullable DOUBLEs and signed zero retained.',
                'Exact first-day UTC month; key/range/projection/page/batch constraints reject before transport.',
                'One HTTP flush, auto flush disabled, zero retry, reset before close; uncertain send remains unresolved.',
                'Physical identity/schema/WAL/count before and after complete-key readback; independent empty table proof.'],
        },
        'binding_counts': {'reviewed_code': 30, 'author_inventory': 10, 'review_evidence': 30, 'passing_xml': 21},
        'java_unique_passing_methods': 125, 'python_unique_pure_guards': 122,
        'actual_target_rows': 3, 'actual_target_field_values': 90, 'actual_target_double_bits': 87,
        'tolerance': 0, 'source_rows': 48, 'source_field_values': 672, 'source_double_bits': 432,
        'final_ledger': {'runs': 9, 'entries': 21, 'events': 85, 'groups': 1, 'leases': 0},
        'actual_source_revision': False, 'formal_writes': 0, 'full_runs': 0,
        'original_initial_source_ack': 'UNKNOWN_PRESERVED',
        'original_initial_java_junit': 'FAILED_FINAL_RECEIPT_SERIALIZATION_PRESERVED',
        'evidence': evidence, 'code_bindings': review['code_bindings'] + review['excluded_author_inventory'],
        'limitations': limits,
    }
    gate_path = BASE / 'coordinator-review-20261006.json'
    create(gate_path, gate)
    summary_path = BASE / 'completion-summary-20261006.md'
    summary_text = '''# D103 完成登记（2026-10-06）

状态：verified（隔离有界验收），协调器 accepted_for_serial_progress；人工 pending_review。按序准许 D104。

- 30 列 typed domain/key/mapper/read/write、ReadGroup/WriteGroup、canonical job 和管理入口完成。
- 真实 D022 来源：2026-06 至 2026-08，48 行、672 字段值、432 个 DOUBLE 位值。
- 首次两月、同范围重跑、取消恢复、只读 reconcile、typed WriteGroup 的原实际操作，由冻结账本及 fresh Java 只读恢复逐字段核实；恢复没有 DDL/DML 或账本改写。
- 实际增量 run `d103-9a1915a5-31f0-4e0c-9412-ae4fc64c366b`：重叠七月并新增八月，VERIFIED 2 行；最终三月 90 字段、87 个 DOUBLE 位值一致，容差 0。
- 最终账本 9 runs / 21 entries / 85 events / 1 group / 0 leases；125 个唯一 Java 方法和 122 个 Python 保护检查通过。
- 正式库只读；六月/七月既存输出缺口保留。没有 FULL、正式修复、生产切换或真实旧期来源修订声明。
- 原 source INSERT 的 UNKNOWN ACK 与原 Java initial 最终 YearMonth 序列化失败均保留；没有重发初始写入或将原失败改为成功。
- 来源 pct_chg 不换算、不舍入；采用 stored-units 补充契约，保留原增长/价值代码绑定。

[最新协调准入](coordinator-review-20261006.json) · [任务结果](../../../docs/migration-tasks-20260929/results/D103.json)

`README.md`、初始 mapping-contract 和增量 mapping-contract 均为此前冻结截止点，其 gate pending 描述不是当前状态；以本完成登记与最新协调准入为准。
'''
    with summary_path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(summary_text)
    result = {
        'task_id': 'D103', 'dataset_id': 'equity_style_monthly', 'definition_version': 1,
        'implementation_status': 'implemented', 'data_validation_status': 'verified',
        'human_review': 'pending_review', 'coordinator_gate': 'accepted_for_serial_progress',
        'next_task_admitted': True, 'next_task': 'D104', 'blocker': None, 'updated_at': checked,
        'python_project': 'D:/work/fund_2/back-monitor', 'java_project': str(ROOT),
        'contract': {'columns': mapping['columns'], 'key': mapping['key'], 'physical': mapping['physical_contract'],
            'canonical_job': mapping['canonical_job']},
        'delivery_matrix': matrix,
        'target': {'table': 'java_d103_equity_style_monthly_acceptance',
            'target_id': 'd103-f05f9296fbfab0e04e6e6de13d953aaa9ccdb1771a1f4c8668d47ac02301b323',
            'server_pid': 41560, 'server_birth_utc': '2026-10-06T14:15:29.583498Z',
            'server_root': str(ROOT / 'var/d103-isolated-questdb'), 'http_port': 19030, 'pg_port': 18842,
            'table_id': 10, 'directory': 'java_d103_equity_style_monthly_acceptance~10',
            'physical_txn': 5, 'sequencer_txn': 5, 'writer_txn': 5, 'rows': 3},
        'request_window': {'from': '2026-06', 'to_inclusive': '2026-08', 'max_months': 12},
        'source': {'dataset': 'index_monthly', 'initial_rows': 32, 'appended_rows': 16, 'final_rows': 48,
            'full_field_values': 672, 'double_bits': 432, 'source_txn_before': 1, 'source_txn_after': 2},
        'runs': {
            'first': 'd103-84e190c2-db7f-4e97-9308-d813e6ed16dc',
            'replay': 'd103-083ca356-3c1d-4f93-9ab6-9eef63658eab',
            'resume': 'd103-9817fd74-462e-4fcb-87cc-dceffaac2423',
            'reconcile': 'd103-dee9e136-8495-4baf-b5f3-8f34a20fdbae',
            'write_group': 'write-group-d1c905f9-7cc3-4b7c-b134-b42b2d870e74',
            'increment': 'd103-9a1915a5-31f0-4e0c-9412-ae4fc64c366b'},
        'incremental': {'reason': 'VERIFIED_PREFIX_APPEND', 'checkpoint_before': '2026-07',
            'checkpoint_after': '2026-08', 'effective_from': '2026-07-01', 'effective_to': '2026-08-01',
            'submitted_rows': 2, 'verified_rows': 2, 'new_months_observed': 1,
            'overlap_months_observed': 1, 'inserted_updated_transport_counts': 'unknown; complete before/after readback distinguishes new August key and unchanged July overlap',
            'actual_old_prefix_source_revision': False},
        'readback': {'rows': 3, 'field_values': 90, 'double_bits': 87, 'matched': True,
            'tolerance': 0, 'typed_key_range_page_readgroup': 'PASS', 'wal_settled': True},
        'tests': {'java_unique': 125, 'java_pure': 123, 'd103_java_pure': 96, 'shared_java_pure': 27,
            'java_live': 2, 'python_unique': 122, 'passing_current_inventory_failures': 0,
            'original_initial_java_failure': 'preserved; final receipt serialization failed after actual operations'},
        'final_ledger': gate['final_ledger'], 'formal_writes': 0, 'full_runs': 0,
        'limitations': limits, 'evidence': evidence + [binding(gate_path), binding(summary_path)],
    }
    create(DOCS / 'results/D103.json', result)
    card_path = DOCS / '11-derived/D103-equity_style_monthly.md'
    card = card_path.read_text(encoding='utf-8-sig')
    card = card.replace('- 状态：in_progress（2026-10-06）；D102协调gate已准入，当前会话直接串行执行，人工pending_review。',
        '- 状态：verified（2026-10-06，隔离有界验收）；协调器 accepted_for_serial_progress，按序准许 D104；人工 pending_review。')
    card = card.replace('- [ ]', '- [x]')
    card += '\n## 实际验收完成登记（2026-10-06）\n\n' + summary_text.split('\n\n', 1)[1]
    card += '\n完整证据：[结果](../results/D103.json)、[完成登记](../../../artifacts/java-migration/D103/completion-summary-20261006.md)、[协调准入](../../../artifacts/java-migration/D103/coordinator-review-20261006.json)。\n'
    card_path.write_text(card, encoding='utf-8', newline='\n')
    register_path = DOCS / 'completion-register.md'
    lines = register_path.read_text(encoding='utf-8-sig').splitlines()
    replacement = '| D103 | equity_style_monthly | verified（隔离验收） | 私有41560/19030/18842；正式只读 | 30列typed读写/ReadGroup/WriteGroup；canonical job v1 | 初始32实源→增量48；首次/重跑/恢复/reconcile2月；增量2月 | 最终3月90字段/87DOUBLE原位一致；source672字段/432bits；WAL稳定 | checkpoint07→08；07重叠；≤12月；终账本9/21/85/1/0 leases | Java125+Python122唯一PASS；原UNKNOWN及序列化失败保留、fresh只读恢复通过 | 2026-10-06 / 独立及根协调复核通过；accepted_for_serial_progress | [D103结果](results/D103.json)；[任务卡](11-derived/D103-equity_style_monthly.md)；[完成证据](../../artifacts/java-migration/D103/completion-summary-20261006.md) | pending_review |'
    assert sum(line.startswith('| D103 |') for line in lines) == 1
    register_path.write_text('\n'.join(replacement if line.startswith('| D103 |') else line for line in lines) + '\n', encoding='utf-8', newline='\n')
    manifest_path = DOCS / 'manifest.json'
    manifest = read(manifest_path)
    d103 = next(t for t in manifest['tasks'] if t['id'] == 'D103')
    d104 = next(t for t in manifest['tasks'] if t['id'] == 'D104')
    assert d103['status'] == 'in_progress' and d104['status'] == 'planned'
    d103['status'] = 'verified'
    d104['status'] = 'in_progress'
    manifest['current_thread_scope']['remaining_mainline'] = 'D104-D184'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    d104_path = DOCS / '11-derived/D104-macro_core_monthly.md'
    text = d104_path.read_text(encoding='utf-8-sig')
    text = text.replace('- 状态：planned，尚未派发。', '- 状态：in_progress（2026-10-06）；D103协调gate已准入，当前会话直接本地串行执行，人工 pending_review。')
    d104_path.write_text(text, encoding='utf-8', newline='\n')
    with (DOCS / 'execution-status.md').open('a', encoding='utf-8', newline='\n') as stream:
        stream.write('\n## D103 verified，按序准许 D104（2026-10-06）\n\n30列typed读写、组合、canonical job v1及管理完成。48实源/672字段/432bits，初始两月实际操作经冻结账本与fresh Java只读恢复核实；新增八月并重叠七月的实际INCREMENTAL通过，最终三月90字段/87bits原位一致。125唯一Java+122Python保护检查PASS，最终9runs/21entries/85events/1group/0leases。原source INSERT ACK UNKNOWN和原initial Java最终序列化失败保持，不重发初始写入、不伪造旧JVM身份。正式库只读、旧六月/七月缺口保留；无FULL/生产切换/真实旧期来源修订声明。独立交付复核及根协调gate accepted_for_serial_progress，人工pending_review。剩余主线D104–D184共81项；Q须独立准入。\n\n## D104 执行中（2026-10-06）\n\nD103最终协调gate准入后，按序开始macro_core_monthly真实owner/source语义调查、Java实现与隔离有界验收。直接本地串行；未准入D105。\n')
    print(json.dumps({'decision': gate['decision'], 'gate': binding(gate_path), 'result': binding(DOCS / 'results/D103.json'), 'next_task': 'D104'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
