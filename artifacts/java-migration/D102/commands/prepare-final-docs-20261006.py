"""Prepare completed D102 data evidence for final independent readiness review."""
import json
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
load = lambda path: json.loads(path.read_text(encoding="utf-8"))
private_path = folder / "view-isolated-acceptance-history-rule-new-attempt-20261006.json"
private = load(private_path)
live = load(folder / "java-view-read-acceptance-20261006.json")
inventory = load(folder / "unique-test-inventory-final-20261006.json")
assert private["status"] == "VERIFIED_ISOLATED_VIEW_READ"
assert live["status"] == "VERIFIED_PRIVATE_AND_FORMAL_BOUNDED_VIEW_READ"
assert inventory["java_unique"] == 130 and inventory["python_unique"] == 81
result_path = repo / "docs/migration-tasks-20260929/results/D102.json"
result = load(result_path)
result.update(data_validation_status="verified", coordinator_gate="pending_final_review", next_task_admitted=False,
              blocker="Final independent data/readiness review and root coordinator gate pending", updated_at=datetime.now(timezone.utc).isoformat())
result["tests"].update(java_unique=130, java_live=1, java_d102_methods=43, python_unique=81)
result["actual_private_validation"] = {"status": private["status"], "pid": 23388, "http_port": 19020, "pg_port": 18832,
    "source_rows": 11667, "source_full_field_values": 164180, "output_rows": 3, "field_values": 12,
    "double_raw_bit_comparisons": 6, "double_tolerance": 0, "ordinary_view_creates": 1,
    "create_ack": "ACKNOWLEDGED", "source_cache_coverage_or_owner_writes": 0,
    "five_tables_equal_accepted_D101": True, "formal_unchanged": True}
result["actual_java_validation"] = {"status": live["status"], "private_and_formal_each_rows": 3,
    "each_target_field_values": 12, "each_target_source_double_bits": 6, "each_target_independent_view_double_bits": 6,
    "double_tolerance": 0, "actual_pages_each": 3, "same_range_cursor_resumed_and_replayed": True,
    "changed_range_cursor_rejected": True, "configured_read_group_matched": True, "cancelled_member_no_page": True,
    "write_static_and_wal_replace_rejected": True, "actual_source_revision": False,
    "third_day_window_extension_uses_D101_accepted_increment": True, "ddl_source_cache_or_formal_writes": 0}
result["private_precreate_failure"].update(explicit_precreate_attempts=3,
    latest_error="Historical unsubmitted third attempt failed its fresh OS identity gate; offending PID was not retained",
    resolved_by="Independent guarded new-attempt admission; fourth attempt succeeded, prior three FAILED artifacts retained",
    historical_ddl_attempts=0, ddl_claim_exists=False)
result["identity_admission"] = {"protocol_version": 2, "historical_process_files": 659, "historical_responses": 90,
    "historical_pids": 475, "eligible_pids": 466, "hard_denied_pids": 9, "eligible_original_instances": 556,
    "complete_original_invocations": 88, "incomplete_original_invocations": 2,
    "rule": "Fresh present PID known OS birth strictly after all its complete original STOPs; incomplete or unknown original identities require native absence",
    "leaf_reuse_rule": "Distinct normalized PID/birth leaves permitted; exact duplicate, root reuse and ambiguous parent/bridge rejected",
    "default_and_protocol1_preserved": True, "unknown_create_never_retried": True}
result["double_scope"] = {"within_each_target": "Original source SQL/view/Java independent JDBC/typed DTO/ReadGroup exact binary64, tolerance0",
    "between_targets": "Four actual aggregate DOUBLE bit differences retained: Sep17 size +7 ULP; Sep18 share +1 ULP and size -2 ULP; Sep21 size -4 ULP (private minus formal)",
    "cross_target_bit_stability_certified": False,
    "inference": "Identical captured inputs and different physical layouts can yield different native DOUBLE SUM reduction order; execution-plan cause was not independently certified"}
names = ["coordinator-completed-producer-identity-review-20261006.json", "actual-historical-proof-consumption-20261006.json",
    "python-historical-identity-leaf-guards-20261006.json", "coordinator-historical-identity-rule-review-20261006.json",
    "coordinator-private-view-history-rule-admission-20261006.json", "view-isolated-acceptance-history-rule-20261006.json",
    "failed-history-rule-precreate-diagnostic-20261006.json", "coordinator-precreate-new-attempt-review-20261006.json",
    "coordinator-guarded-new-view-attempt-admission-20261006.json", private_path.name,
    "v_etf_market_overview_daily-create-once.json", "coordinator-java-view-read-admission-20261006.json",
    "java-view-read-acceptance-20261006.json", "java-view-read-test-20261006.json", "unique-test-inventory-final-20261006.json"]
for name in names:
    assert (folder / name).is_file()
    path = "artifacts/java-migration/D102/commands/" + name
    if path not in result["evidence"]:
        result["evidence"].append(path)
result["delivery_matrix"] = {
    "D01": "Four-column semantic DTO/domain/mapper, exact date/count/null/double behavior; actual PG/JDBC verification",
    "D02": "Natural trade_date identity, no own physical DEDUP; underlying full ts_code/timestamp source keys guarded",
    "D03": "Ordinary VIEW missing-only isolated CREATE ACK; no view WAL/partition; both source schemas and settled versions guarded",
    "D04": "Typed key/date/range/page reads and actual configured ReadGroup; real cursor continuation/replay and changed-range rejection",
    "D05": "Ordinary VIEW rejects batch/static/WAL replacement preparation before writers; actual rejection assertions",
    "D06": "Original direct share/daily JOIN and native SAMPLE BY; real D101 source captures full-field reread, no independent refresh publisher",
    "D07": "51 registered DatasetDefinition entries, 40 jobs; ordinary VIEW independent job/checkpoint/run/slice not applicable; base owners remain existing source jobs",
    "D08": "31-day/page bounds, finite per-statement deadlines, truncation/null/schema/version failure propagation, prelaunch cancellation, stable-source cursor; UNKNOWN CREATE nonretryable",
    "D09": "Actual private and formal SELECT comparisons; complete real sources, same-range read replay, D101 third-day window extension; source mutations not claimed"}
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
mapping_path = folder.parent / "mapping-contract-20261006.json"
mapping = load(mapping_path)
mapping["status"] = "VERIFIED_BOUNDED_DATA_PENDING_COORDINATOR"
mapping["double_scope"] = result["double_scope"]
mapping["actual_java_and_private_view_verified"] = True
mapping_path.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

readme = folder.parent / "README.md"
text = readme.read_text(encoding="utf-8")
text = text.replace("状态：Java 已实现，正式三日只读对照通过；隔离视图验收尚未完成。人工复核 pending_review，D103 未准入。",
    "状态：私有视图创建与实际 Java/正式只读验收通过，最终独立复核与协调 gate 待完成。人工复核 pending_review，D103 未准入。")
text = text.replace("[Python 脚本护栏](commands/python-identity-guards-20261006.json)：59 个最终唯一方法通过（含旧 39 项）",
    "[Python 脚本护栏](commands/python-historical-identity-leaf-guards-20261006.json)：81 个最终唯一方法通过（包含旧 39/59/74 项，不叠加重跑）")
text += "\n## 完成的实际验收\n\n"
text += "[唯一缺失视图创建与私有验收](commands/view-isolated-acceptance-history-rule-new-attempt-20261006.json) 已通过，CREATE ACK1；11667真实源行/164180字段原位回读和三日12字段/6DOUBLE比较通过。三次早先CREATE前FAILED保留，未提交DDL。完整旧身份规则审466 eligible与9 hard-deny；未知出生/不完整STOP不放宽，PID12620两次已知叶子复用不会当作同一个OS实例。\n\n"
text += "[Java实际验收](commands/java-view-read-acceptance-20261006.json) 私有与正式各三页1行、两日真实cursor恢复与重读、第三日窗口扩展、变化range拒旧cursor、按键回读、独立JDBC与实际配置ReadGroup均匹配；取消无page，批写及两种replace准备拒绝。五表/view元数据跨阶段及读前后稳定。合计[130Java与81Python唯一方法](commands/unique-test-inventory-final-20261006.json)，0失败/错误/跳过。本卡未发生源修订或追加。\n\n"
text += "DOUBLE比较严格限定在各实际目标内，原SQL/view/Java映射零容差；私有与正式之间保留4项原生聚合bits差异（9/17市值+7ULP；9/18份额+1ULP、市值-2ULP；9/21市值-4ULP），没有扩大容差或改SQL。捕获输入一致但物理布局不同，原生SUM归约顺序是可能解释；没有验证具体执行计划原因，不认证跨布局全局bits稳定。\n"
readme.write_text(text, encoding="utf-8")
print("D102 actual data docs prepared; final coordinator and D103 admission still pending")
