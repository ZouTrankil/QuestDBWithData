"""Update D101 mutable documentation after the four actual live stages passed.

Does not query a service, invoke an owner, run tests, or admit D102.
"""
from pathlib import Path
import json
import re
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / 'artifacts/java-migration/D101'
DOCS = ROOT / 'docs/migration-tasks-20260929'
def load(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def save(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
def command(name): return load(BASE / 'commands' / name)
first = command('java-readthrough-first-20261006.json')
hit = command('java-readthrough-hit-20261006.json')
increment = command('java-readthrough-increment-20261006.json')
typed = command('java-readthrough-typed-write-20261006.json')
assert first['status'] == 'VERIFIED_CANONICAL_FIRST_MISSES'
assert hit['status'] == 'VERIFIED_CANONICAL_HITS_ZERO_PUBLISH'
assert increment['status'] == 'VERIFIED_CANONICAL_FULL_PREFIX_INCREMENT'
assert typed['status'] == 'VERIFIED_TYPED_OWNER_ASSERTIONS_ZERO_PUBLISH'
assert typed['matching_actual_owner_hits'] == 3
assert typed['matching_cache_submitted_rows'] == typed['matching_coverage_submitted_rows'] == 0
assert typed['tables_before'] == typed['tables_after'] == increment['tables_after']
assert increment['old_actual_cursor_rejected_before_row_query'] is True
assert increment['stored_cache_rows_count'] == increment['stored_receipt_rows_count'] == 5
assert typed['retained_interval_leases'] == increment['retained_interval_leases'] == 0

result_path = DOCS / 'results/D101.json'
result = load(result_path)
result.update(implementation_status='implemented', data_validation_status='verified_isolated',
              blocker='Final independent typed/data readiness review and coordinator gate pending; D102 not yet admitted',
              coordinator_gate='awaiting_final_review', next_task_admitted=False,
              completed_data_validation_at=datetime.now(timezone.utc).isoformat())
result['tested_so_far'].update(java_unique=190, java_pure_unique=184, java_live_unique_passed=6,
                              python_bridge_unique=43, python_fixture_unique=32, python_unique=75,
                              earlier_failed_live_attempts_preserved=True)
result['source_fixture']['java_publisher_acceptance'] = 'VERIFIED_FIRST_HIT_FULL_PREFIX_INCREMENT_AND_TYPED_ASSERTIONS'
result['run_ids'] = dict(first=first['run']['id'], hit=hit['run']['id'], increment=increment['run']['id'],
                         original_recovered_hit='d101-1be2292a-c0b0-4810-a22b-9747bef68071',
                         typed_group=typed['write_group_result']['runId'], typed_child=typed['child_run']['id'])
result['validation'] = dict(scope='Dedicated private QuestDB, bounded real formal-source copies; no production cutover',
    first=dict(actual_misses=2, cache_submitted=2, coverage_submitted=2, acknowledgement_count=4),
    hit=dict(actual_hits=2, cache_submitted=0, coverage_submitted=0),
    increment=dict(actual_misses=3, cache_submitted=3, coverage_submitted=3, acknowledgement_count=6,
        current_days=3, stored_cache_keys=5, stored_receipt_keys=5, unique_cache_field_values=25,
        unique_receipt_field_values=25, cache_key_and_range_field_comparisons=50,
        pg_reference_double_rawbit_comparisons=20, independent_jdbc_double_rawbit_comparisons=20,
        source_scan_rows_with_repeated_timeless_basic=17583, source_vector=increment['source_version_after'],
        old_actual_cursor_rejected_before_row_query=True, physical_source_version=increment['cache_physical_source_version']),
    typed=dict(group_members=1, verified_assertion_rows=3, actual_hits=3, cache_submitted=0, coverage_submitted=0,
        exact_ingest_parameters=['groupBatch','memberBatch','planFingerprint','payloadFingerprint'],
        rejection_database_effects=0, rejected=['spoof','historical generation','malformed SHA','standalone D094 writer']),
    retained_leases=0, double_tolerance=0, ddl_by_java_owner=0, source_mutations_by_java=0,
    cache_and_receipt_publish_owner='Original Python MarketBarometerReadThroughCache.read',
    raw_normal_ack_ledger_writerStopped=False,
    actual_stopped_proof='Separate Gateway OS birth/parent/exit evidence and live writerStopped checks; normal ACK proof default is preserved',
    insert_update_breakdown='Driver does not classify physical inserts/updates; actual five full business keys and before/after values are verified',
    same_key_source_value_correction_live_tested=False,
    source_revision_live_scope='Actual third-day append changes YEAR partition generation and revalidates all three dates; timeless basic unchanged',
    full_history_or_production_current_certified=False)
for name in ('coordinator-java-increment-data-review-20261006.json', 'coordinator-typed-write-admission-20261006.json',
             'java-readthrough-typed-write-20261006.json', 'strictwrite-group-request-D101-typed-20261006.json'):
    path = f'artifacts/java-migration/D101/commands/{name}'
    if path not in result['evidence']: result['evidence'].append(path)
save(result_path, result)

mapping_path = BASE / 'mapping-contract-20261006.json'
mapping = load(mapping_path)
mapping.update(status='VERIFIED_ISOLATED_AWAITING_COORDINATOR',
    java_scope='Five-field typed READ and bounded canonical Java management plus prepared typed WRITE assertions verified through the unique original Python owner; no production caller cutover',
    actual_publisher_acceptance='VERIFIED_FIRST_HIT_FULL_PREFIX_INCREMENT_AND_TYPED_ASSERTIONS',
    final_test_counts=dict(java_pure=184, java_live=6, java_unique=190, python_bridge=43, python_fixture=32, python_unique=75),
    actual_validation=result['validation'])
mapping['process_identity_repair'].update(implementation_status='VERIFIED_PRIVATE_PROCESS_PREVIEW_AND_CANONICAL_STAGES',
                                        new_live_acceptance='PASSED')
mapping['multi_day_unknown_boundary'].update(explicit_live_recovery_status='VERIFIED_READONLY_ORIGINAL_HIT_RECOVERY',
                                           new_two_hit_acceptance_and_source_increment_admitted=True)
save(mapping_path, mapping)

readme_path = BASE / 'README.md'
readme = readme_path.read_text(encoding='utf-8-sig')
readme = re.sub(r'^状态：.*$', '状态：**Java 实现与隔离数据验收已通过，最终协调复核中**。人工复核仍为 pending_review；D102 待最终 gate。', readme, count=1, flags=re.M)
readme = readme.replace('总184唯一Java纯测、63唯一Python纯测通过。实际只读进程预览和原 HIT 只读恢复另各1项通过。',
    '184唯一Java纯测与6个现场方法（进程预览、显式只读恢复、FIRST/HIT/增量/typed）共190唯一Java通过；Python75项为桥43+夹具32，旧20项是子集。')
readme = readme.replace('D101 完整验收尚未完成，不计为 verified。', '四个canonical现场阶段已通过，最终协调验收待登记。')
readme = readme.replace('Java保留IN_DOUBT与1全表lease。', '当时Java保留IN_DOUBT与1全表lease。')
readme = readme.replace('新HIT2已单独准入并执行，来源增量和D102仍未准入。',
    '独立新HIT2、第三日来源增量、Java完整前缀增量及typed断言WRITE均通过；D102待最终协调gate。')
readme = readme.replace('该文件尚须等待阶段产出；可查看原 JSON 后作为输入', '该文件已由通过的现场阶段产出；可查看冻结的原 JSON')
readme = readme.replace('| 后续完整前缀增量 |', '| 完整前缀增量 |').replace('| 后续 typed WRITE |', '| typed WRITE |')
readme = readme.replace('：阶段输出路径，当前示例不声明已通过 |', '：实际阶段已通过，详见冻结输出 |')
readme += '\n## 最终隔离数据核对\n\n真实来源初始8764行，加9/21的2903行/7ACK，合计11667行/164180完整字段值匹配。FIRST两日2miss/4ACK；新HIT两日2hit/0发布；YEAR来源修订后全前缀3miss/6ACK，cache与coverage各5个历史键、25个字段值及5个原digest匹配，PG参考与独立JDBC各20个double原位比较、零容差。真实旧游标txn2在新txn5前拒绝，checkpoint9/18→9/21。配置typed组合3实际hit/0发布，四类错误均在创建新run或调用owner前拒绝；0保留lease。正常ACK账本proof的writerStopped=false默认值如实保留，实际停写由独立Gateway进程证据证明。未现场验证同键源值更正、全历史、正式current freshness或生产切换；正式5既存代次的3一致/2摘要异常保持原样。\n'
readme_path.write_text(readme, encoding='utf-8')

card_path = DOCS / '11-derived/D101-etf_market_overview_daily_cache.md'
card = card_path.read_text(encoding='utf-8-sig')
card = card.replace('- 状态：in_progress；D100 协调 gate 已通过，正在实现与隔离验收。',
                    '- 状态：implemented；Java实现和隔离数据验收通过，最终协调复核中，人工pending_review。')
card = card.replace('- [ ]', '- [x]')
card += '\n## 实际验收（2026-10-06）\n\nJava五列typed READ、完整(date,source_version)键/MONTH/WAL/DEDUP、ReadGroup及canonical管理已实现；prepared WRITE仅断言委托原Python owner，D094仍公开只读。专用PID23388/19020/18832复制11667真实来源行并核对164180字段值；FIRST2miss/4ACK→新HIT2hit/0发布→第三日全前缀3miss/6ACK→配置typed3hit/0发布全部通过。历史cache/coverage各5键及5列、digest、原位double和真实旧游标拒绝通过，checkpoint9/18→9/21，0lease。未知首日HIT保留原失败证据，再显式只读恢复：全窗口proof2/原slice1，无重发，不当HIT2；之后独立新HIT2单列验收。190唯一Java+75唯一Python通过。正式3一致/2历史摘要异常未修复，不认证current/latest或生产切换，同键源值更正未现场测试。结果见[results/D101.json](../results/D101.json)及[证据](../../../artifacts/java-migration/D101/README.md)，D102待最终协调gate。\n'
card_path.write_text(card, encoding='utf-8')

register_path = DOCS / 'completion-register.md'
register = register_path.read_text(encoding='utf-8-sig')
row = '| D101 | etf_market_overview_daily_cache | implemented；隔离数据verified，协调复核中 | 私有23388/19020/18832；正式只读 | Java typed READ/WriteGroup断言；原Python唯一publisher | 11667实源/164180字段；FIRST2miss4ACK/新HIT2hit0发布/增量3miss6ACK/typed3hit0发布 | cache及coverage各5键/25字段/5digest匹配，原位double零容差 | 完整前缀≤31日；checkpoint9/18→9/21；真实旧txn2游标拒绝新txn5 | Java190+Python75唯一PASS；真实UNKNOWN显式只读恢复proof2/slice1，0lease；正式3一致/2摘要异常未修 | 2026-10-06 / FIRST/HIT/增量/typed全部通过，最终协调复核中 | [D101结果](results/D101.json)；[任务卡](11-derived/D101-etf_market_overview_daily_cache.md)；[证据](../../artifacts/java-migration/D101/README.md) | pending_review |'
register, count = re.subn(r'^\| D101 \|.*$', row, register, flags=re.M); assert count == 1
register_path.write_text(register, encoding='utf-8')

status_path = DOCS / 'execution-status.md'
status = status_path.read_text(encoding='utf-8-sig')
section = '## D101 隔离验收通过，最终协调复核中（2026-10-06）\n\nJava typed READ与原Python唯一owner委托管理/断言WRITE全部实现。私有8764初始实源→新增2903实源，最终11667行/164180完整字段匹配；FIRST2miss/4ACK、新HIT2hit/0发布、全前缀增量3miss/6ACK、配置typed3hit/0发布通过。cache/coverage各5完整历史键、25字段值及5原digest一致，PG参考和独立JDBC各20double原位比对，旧txn2物理游标在txn5前拒绝，checkpoint至9/21，0保留lease。184纯+6现场共190Java、43桥+32夹具共75Python唯一PASS，旧失败归档不增计。\n\nWindows启动器/桥OS身份及晚UNKNOWN重复采样已修复。旧首日HIT真实IN_DOUBT现场经严格进程调查与fresh两日全部字段/WAL/来源的显式只读恢复结算，0新owner/0业务DB写；RUN/ATTEMPT proof2与原实际slice1分开，未补造第二日提交，不当作HIT2。之后新run完整HIT2独立通过。所有历史失败文件及旧false标记SHA保持；正常ACK的原ledger writerStopped=false默认值未伪改，实际Gateway停止证明独立。第三日每批硬绑定成功HIT/预检/准入SHA并检查所有ledger/producer/targets，7ACK无UNKNOWN。正式历史5代次3匹配/2摘要异常保留，无正式修复/current/latest认证、生产切换或同键源值修订现场声明。最终typed与数据证据独立复核及协调gate待落盘，D102仍未准入，人工pending_review。\n'
status, count = re.subn(r'## D101 执行中（2026-10-06）.*?(?=\n## |\Z)', section, status, count=1, flags=re.S); assert count == 1
status_path.write_text(status, encoding='utf-8')
print(json.dumps({'task_id':'D101','status':'DOCUMENTS_UPDATED_AWAITING_FINAL_COORDINATOR','java_unique':190,'python_unique':75,'D102_admitted':False}))
