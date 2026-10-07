"""D105 file-only closure candidate after two actual READs and final review.

No database/native/test/provider calls. Root runs only after all immutable input
evidence passes. Earlier failed files and D104 data/code snapshots stay intact.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import audit_d105_macro_core_view as audit
import record_d105_validation_inventory as inventory_tool

ROOT, BASE, D = audit.REPO, audit.REPO / "artifacts/java-migration/D105", audit.DIRECTORY
DOCS = ROOT / "docs/migration-tasks-20260929"
SCRIPT = Path(__file__).resolve()
REVIEW = D / "coordinator-final-delivery-static-review-20261007.json"
GATE, SUMMARY, RESULT = BASE / "coordinator-review-20261007.json", BASE / "completion-summary-20261007.md", DOCS / "results/D105.json"
TERMINAL = D / "java-readers-process-and-final-readonly-review-20261007.json"
INVENTORY = D / "validation-inventory-20261007.json"
CREATED = D / "view-isolated-acceptance-20261007.json"
KEYS = ("runs", "entries", "events", "groups", "leases")
EXPECTED_COUNTS = dict(zip(KEYS, (9, 21, 85, 1, 0)))
require, digest, bits = audit.require, audit.digest, audit.raw_bits


def encoded(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def proof(item):
    require(isinstance(item, dict) and isinstance(item.get("path"), str) and re.fullmatch("[0-9a-f]{64}", str(item.get("sha256", ""))), "Immutable path/SHA required")
    path = Path(item["path"])
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve(strict=True)
    require(path.is_relative_to(ROOT.resolve()) or path.is_relative_to(audit.common.REFERENCE.resolve()), "Only project or readonly Python reference proof admitted")
    require(path.is_file() and path.stat().st_size <= 32 * 1024 * 1024 and digest(path) == item["sha256"], "Immutable reviewed file SHA/size differs: " + str(path))
    if "absolute_path" in item:
        require(Path(item["absolute_path"]).resolve(strict=True) == path, "Absolute/relative review paths differ")
    return path


def read(path):
    return audit.load(path, digest(path))


def exact(value, expected, field):
    require(type(value) is int and value == expected, "Exact integer evidence differs: " + field)


def nested_proofs(value, approved):
    if isinstance(value, dict):
        if "path" in value and "sha256" in value:
            path = proof(value)
            require(path not in approved or approved[path] == value["sha256"], "Conflicting immutable SHA in final review")
            approved[path] = value["sha256"]
        for item in value.values():
            nested_proofs(item, approved)
    elif isinstance(value, list):
        for item in value:
            nested_proofs(item, approved)


def rows(actual, oracle, java=False):
    require(isinstance(actual, list), "Complete actual monthly rows required")
    normalized = []
    for value in actual:
        require(isinstance(value, dict) and list(value) == list(audit.FIELDS), "Explicit ordered nine-field row required")
        row = dict(value)
        if java:
            require(isinstance(row["month"], str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-01", row["month"]), "Actual Java mapper first-day LocalDate required")
            row["month"] += "T00:00:00Z"
        normalized.append(row)
    require(audit.common.compare_output(normalized, oracle)["passed"], "Original frozen three-month oracle mismatch; no DOUBLE tolerance")
    return normalized


def stopped(value, producer):
    require(value.get("original_identity_present") is False and value.get("producer_identity") == producer, "Actual original PID/OS-birth stop proof required")
    matches = value.get("matches")
    require(isinstance(matches, list) and len(matches) <= 1, "Native original-identity absence census ambiguous")
    original = audit.base.native.normalize_birth(producer["birth_utc"])
    for match in matches:
        require(type(match.get("pid")) is int and match["pid"] == producer["pid"] and audit.base.native.normalize_birth(match.get("birth_utc")) > original, "Native evidence contains original or unknown/reused older identity")


def same_audit(current, prior, isolated):
    prefix = "private" if isolated else "formal"
    require(current[prefix + "_before"] == current[prefix + "_after"] == prior[prefix + "_after"], "Final seven-table/schema audit frontier changed")
    for key in ("actual_rows", "base_rows", "actual_double_bits", "view_metadata", "view_metadata_after", "view_schema", "view_schema_after", "view_physical_metadata", "view_physical_metadata_after", "owner_contract", "complete_source_readback"):
        require(current[key] == prior[key], "Final audit/native/raw-value proof changed: " + key)


def java_frontier(value, expected, isolated):
    target = audit.PRIVATE_BASE if isolated else audit.BASE
    require(set(value["tables"]) == set((*audit.common.SOURCES, target)), "Actual Java exact seven-table census required")
    for name, state in expected[("private" if isolated else "formal") + "_after"]["tables"].items():
        physical, wal, observed = state["physical"], state["wal"], value["tables"][name]
        for key, item in {**physical, **wal, "actual_select_count": state["actual_select_count"]}.items():
            require(key in observed and type(observed[key]) is type(item) and observed[key] == item, "Cross-stage Java/source native identity/counts differ: " + name + "." + key)
        require(state["settled"] and observed.get("matView") is False, "Actual source/base WAL/ordinary table state invalid")
    metadata, native, observed = expected["view_metadata_after"], expected["view_physical_metadata_after"], value["view"]
    for key in ("view_name", "view_table_dir_name", "view_status", "invalidation_reason"):
        require(key in observed and observed[key] == metadata[key], "Cross-stage actual alias state differs: " + key)
    require(audit.normalize_sql(observed["view_sql"]) == audit.normalize_sql(metadata["view_sql"]), "Actual Java original VIEW definition differs")
    for key, item in native.items():
        require(key in observed and type(observed[key]) is type(item) and observed[key] == item, "Cross-stage native VIEW identity/flags differ: " + key)
    dt = datetime.fromisoformat(metadata["view_status_update_time"].replace("Z", "+00:00"))
    require(dt.tzinfo is not None and dt.utcoffset() == timedelta(0), "Known actual UTC view history required")
    micros = (dt - datetime(1970, 1, 1, tzinfo=timezone.utc)) // timedelta(microseconds=1)
    exact(observed["view_status_update_micros"], micros, "view_status_update_micros")
    for name, actual_schema in value["schemas"].items():
        require(name in (target, audit.PRIVATE_VIEW if isolated else audit.VIEW), "Only expected actual view/base schemas admitted")
        expected_schema = expected["view_schema_after"] if name != target else expected[("private" if isolated else "formal") + "_after"]["schemas"][target]
        require(len(actual_schema) == len(expected_schema) == 9, "Full actual nine-column schemas required")
        for actual, prior in zip(actual_schema, expected_schema):
            require(all(type(actual[key]) is type(prior[key]) and actual[key] == prior[key] for key in ("column", "type", "designated", "upsertKey")), "Actual Java/view schema flags/order differ")
    require(set(value["schemas"]) == {target, audit.PRIVATE_VIEW if isolated else audit.VIEW}, "Both actual view/base schemas required")


def validate_java(receipt, scope, prior, final, oracle):
    require(receipt["task_id"] == "D105" and receipt["scope"] == scope and receipt["status"] == "VERIFIED_" + scope.upper() + "_BOUNDED_VIEW_READ", "Actual successful matching Java scope required")
    for key in ("DDL", "DML", "ILP", "base_writes", "formal_writes", "owner_invocations"):
        exact(receipt[key], 0, scope + "." + key)
    require(receipt["ledger_initialized"] is receipt["ledger_mutated"] is receipt["automatic_retry"] is receipt["actual_source_increment_during_D105"] is receipt["actual_source_revision_during_D105"] is False and receipt["D104_ledger_files_before"] == receipt["D104_ledger_files_after"], "Java SELECT scope created/mutated ledger or falsely claimed source revision")
    proof(receipt["audit"]); proof(receipt["java_admission"]); proof(receipt["jvm_identity"])
    for item in receipt["admitted_code_bindings"]:
        proof(item)  # Current D105 admission, not historical D104 shared hashes.
    identity = read(proof(receipt["jvm_identity"]))
    require(identity["task_id"] == "D105" and identity["scope"] == scope and identity["saved_before_connections"] is identity["identity_child_stopped"] is True and identity["jvm_pid"] == receipt["jvm_pid"] and identity["jvm_birth_utc"] == receipt["jvm_birth_utc"], "Pre-connection actual JVM identity differs")
    values = receipt["readback"]
    require(values["metadata_before"] == values["metadata_after"], "Actual Java metadata frontier drifted")
    java_frontier(values["metadata_before"], prior, scope == "private")
    java_frontier(values["metadata_after"], final, scope == "private")
    for key in ("actual_view_rows", "independent_jdbc_base_rows", "independent_jdbc_view_rows", "oracle_rows"):
        rows(values[key], oracle, java=True)
    require(values["actual_double_bits"] == prior["actual_double_bits"] == final["actual_double_bits"], "Java/Python actual nullable raw bits differ across stages")
    for key, count in (("unique_field_comparisons", 27), ("nullable_double_slot_comparisons", 24), ("nonnull_double_rawbit_comparisons", 22), ("null_double_comparisons", 2), ("pre_cancelled_reader_invocations", 0)):
        exact(values[key], count, key)
    for key in ("first_june_july_repeat_exact", "july_august_later_read_window_exact", "september_empty_without_failure", "changed_range_cursor_rejected", "invalid_month_projection_or_budget_before_reader", "typed_write_and_replacement_rejected"):
        require(values[key] is True, "Actual finite pagination/cancellation/rejection scenario missing: " + key)
    require(len(values["pages"]) == 3 and [page["month"] for page in values["pages"]] == ["2026-06", "2026-07", "2026-08"] and [page["has_more"] for page in values["pages"]] == [True, True, False] and all(page["source_version"] == values["physical_view_base_token"] for page in values["pages"]), "Actual three-page stable source-token proof differs")
    members, cancelled = values["configured_read_group"]["members"], values["pre_cancelled_group"]["members"]
    require(len(members) == len(cancelled) == 1 and members[0]["status"] == "READ" and members[0]["errorCode"] is None and members[0]["datasetId"] == "v_macro_core_monthly" and members[0]["rowType"] == "com.zoutrankil.data.domain.MacroCoreMonthlyView" and members[0]["page"]["sourceVersion"] == values["physical_view_base_token"] and members[0]["page"]["nextCursor"] is None, "Actual complete typed ReadGroup page required")
    require(cancelled[0]["status"] == "CANCELLED" and cancelled[0]["errorCode"] == "READ_CANCELLED" and "page" in cancelled[0] and cancelled[0]["page"] is None, "Actual pre-cancelled group must return no page")
    accessors = ("month", "cpiYoy", "ppiYoy", "pmiMfg", "gdpYoy", "m2Yoy", "socialFinancingStock", "newRmbLoan", "socialFinancingYoy")
    grouped_rows = []
    for record in members[0]["page"]["rows"]:
        require(list(record) == list(accessors) and re.fullmatch(r"[0-9]{4}-[0-9]{2}", record["month"]), "All actual typed ReadGroup properties/month required")
        grouped_rows.append(dict(zip(audit.FIELDS, (record["month"] + "-01", *[record[key] for key in accessors[1:]]))))
    rows(grouped_rows, oracle, java=True); proof(values["read_group_request"])
    exact(receipt["double_tolerance"], 0, "Java DOUBLE tolerance")


def validate_inventory(inventory):
    require(inventory["task_id"] == "D105" and inventory["status"] == "VERIFIED_CURRENT_UNIQUE_TEST_INVENTORY", "Actual current unique inventory required")
    for key, value in (("java_unique_passing_methods", 61), ("java_pure_unique_methods", 60), ("java_live_unique_methods", 1), ("java_live_actual_stage_invocations", 2), ("java_PASS_invocations", 62), ("python_unique_pure_guards", 44), ("current_inventory_failures", 0), ("current_inventory_errors", 0), ("current_inventory_skips", 0)):
        exact(inventory[key], value, key)
    pure, live, calls = set(), set(), 0
    for item in inventory["java_reports"]:
        path = proof(item)
        phase = "pure" if path.parent.name == inventory_tool.PURE_DIRECTORY.name else "live"
        for case in inventory_tool.suite_cases(path, phase):
            (pure if phase == "pure" else live).add((case["class"], case["method"])); calls += 1
    require(len(inventory["java_reports"]) == 7 and len(pure) == 60 and len(live) == 1 and not pure & live and calls == 62, "Archived current method/invocation census differs")
    recomputed = inventory_tool.record(argparse.Namespace(private_live_sha256=inventory["actual_live_receipts"]["private"]["sha256"], formal_live_sha256=inventory["actual_live_receipts"]["formal"]["sha256"]))
    for key in ("java_reports", "java_cases", "actual_live_receipts", "python", "historical_failures_preserved", "historical_subset_runs_excluded"):
        require(recomputed[key] == inventory[key], "Actual inventory source/log/XML binding or count differs")


def inputs(args):
    require(Path(args.final_review).resolve(strict=True) == REVIEW.resolve(strict=True) and digest(REVIEW) == args.final_review_sha256, "Exact new final independent review path/SHA required")
    review = read(REVIEW)
    require(type(review.get("protocol_version")) is int and review["protocol_version"] == 1 and review.get("task_id") == "D105" and review.get("status") == "PASS" and review.get("blockers") == [], "Final D105 independent cross-stage PASS required")
    approved = {}; nested_proofs(review, approved)
    require(SCRIPT in approved and approved[SCRIPT] == digest(SCRIPT), "Current closure tool must be independently bound before registration")
    required = (CREATED, audit.FORMAL_OUTPUT, TERMINAL, INVENTORY, BASE / "README.md", BASE / "source-contract-20261007.json", BASE / "mapping-contract-20261007.json")
    java_paths = {scope: D / f"java-view-read-{scope}-acceptance-20261007.json" for scope in ("private", "formal")}
    require(all(path.resolve() in approved for path in (*required, *java_paths.values())), "All current actual data/terminal/test/docs evidence must be independently bound")
    data = audit.prerequisites()  # Only six immutable D104 facts, never deep old shared-code validation.
    created, formal, terminal, inventory = (read(path) for path in (CREATED, audit.FORMAL_OUTPUT, TERMINAL, INVENTORY))
    oracle = data["preflight"]["expected_oracle_rows"]
    require(created["status"] == "VERIFIED_ISOLATED_VIEW_READ" and created["alias_created"] is True and created["automatic_retry"] is created["owner_invoked"] is created["ledger_mutated"] is created["formal_mutated"] is created["protected_private_tables_mutated"] is False, "Actual one-shot private VIEW acceptance required")
    exact(created["attempted_DDL"], 1, "attempted CREATE"); exact(created["acknowledged_DDL"], 1, "acknowledged CREATE")
    operation = created["create_operation"]; claim_path = proof({"path": operation["claim_path"], "sha256": operation["claim_sha256"]}); claim = read(claim_path)
    require(operation["ack"] == claim["ack"] == "ACKNOWLEDGED" and operation["request_started"] is claim["request_started"] is True and operation["kind"] == "DDL" and operation["table"] == audit.PRIVATE_VIEW and claim["invocation_id"] == created["invocation_id"] and claim["producer_identity"] == created["producer_identity"] and claim["target"] == created["private_target_attestation"], "Original unique CREATE claim/source/producer differs")
    raw_path = proof(operation["raw_response"])
    require(operation["raw_response"] == claim["raw_response"] and raw_path.read_bytes() == b'{"ddl":"OK"}' and operation["response"] == claim["response"] == {"ddl": "OK"} and operation["http_status"] == 200, "Actual original durable raw CREATE ACK differs")
    require(terminal["status"] == "VERIFIED_STOPPED_READERS_AND_FINAL_READONLY_DEPENDENCY_CLOSURE" and terminal["task_id"] == "D105" and terminal["created_view"] == audit.binding(CREATED), "Root actual terminal/dependency closure required")
    for key in ("DDL", "DML", "ILP", "source_writes", "output_writes", "formal_writes", "retained_leases"):
        exact(terminal[key], 0, "terminal." + key)
    require(terminal["ledger_mutated"] is terminal["reference_project_mutated"] is terminal["actual_source_increment"] is terminal["actual_source_revision"] is terminal["automatic_retry"] is terminal["D106_admitted"] is False, "Terminal read-only scope differs")
    same_audit(terminal["private"], created, True); same_audit(terminal["formal"], formal, False)
    rows(created["actual_rows"], oracle); rows(formal["actual_rows"], oracle)
    quiet = terminal["quiescence_after"]["ledger"]
    require(quiet == terminal["quiescence_before"]["ledger"] == created["quiescence_after"]["ledger"] and all(quiet[key] == data["ledger"][key] for key in KEYS), "Original complete ledger proof drifted")
    counts = {key: len(quiet[key]) for key in KEYS}
    require(counts == terminal["D104_ledger_counts"] == EXPECTED_COUNTS, "Actual D104 final ledger counts/history differ")
    stopped(terminal["creator_absence"], created["producer_identity"])
    require(terminal["private_target_attestation"] == created["private_target_attestation_after"] == data["results"]["private_target"], "Actual stopped-reader terminal private service differs")
    java = {}
    for scope, path in java_paths.items():
        java[scope] = read(path); reader = terminal["readers"][scope]
        require(reader["java_receipt"] == audit.binding(path), "Terminal must bind actual Java receipt")
        validate_java(java[scope], scope, created if scope == "private" else formal, terminal[scope], oracle)
        stopped(reader["original_reader_absence"], {"pid": java[scope]["jvm_pid"], "birth_utc": java[scope]["jvm_birth_utc"]})
        proof(reader["jvm_identity"]); junit = proof(reader["junit"])
        require(len(inventory_tool.suite_cases(junit, scope)) == 1, "Actual single live PASS required")
        execution = reader["executor_completion"]
        exact(execution["exit_code"], 0, "scope executor exit")
        require(type(execution["session_id"]) is int and execution["session_id"] > 0 and re.fullmatch("[0-9a-f]+", execution["chunk_id"]) is not None and "BUILD SUCCESSFUL" in proof(execution["log"]).read_text(encoding="utf-8-sig"), "Actual completed root executor/log required")
    validate_inventory(inventory)
    source = created["complete_source_readback"]
    source_counts = {key: sum(value[key] for value in source.values()) for key in ("rows", "full_field_comparisons", "nullable_double_slot_comparisons", "nonnull_double_rawbit_comparisons", "null_double_comparisons")}
    require(source_counts == {"rows": 28, "full_field_comparisons": 412, "nullable_double_slot_comparisons": 383, "nonnull_double_rawbit_comparisons": 338, "null_double_comparisons": 45}, "Whole previously accepted real source fixture census differs")
    return review, approved, created, formal, terminal, inventory, java, source_counts, counts


def create(path, text):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--final-review", type=Path, default=REVIEW)
    parser.add_argument("--final-review-sha256", required=True)
    args = parser.parse_args()
    review, approved, created, formal, terminal, inventory, java, source_counts, counts = inputs(args)
    require(not any(path.exists() for path in (GATE, SUMMARY, RESULT)), "New-only D105 completion identity required")
    record_paths = [DOCS / "11-derived/D105-v_macro_core_monthly.md", DOCS / "11-derived/D106-macro_liquidity_credit_monthly.md", DOCS / "completion-register.md", DOCS / "manifest.json", DOCS / "execution-status.md"]
    old = {path: path.read_text(encoding="utf-8-sig") for path in record_paths}; old_sha = {path: digest(path) for path in record_paths}
    manifest = json.loads(old[record_paths[3]], object_pairs_hook=audit.base.unique_json); baseline = copy.deepcopy(manifest)
    tasks = {task["id"]: task for task in manifest["tasks"]}
    require(tasks["D105"]["status"] == "in_progress" and tasks["D106"]["status"] == "planned" and manifest["current_thread_scope"]["remaining_mainline"] == "D105-D184", "Only exact serial D105 to D106 transition admitted")
    tasks["D105"]["status"] = "verified"; tasks["D106"]["status"] = "in_progress"; manifest["current_thread_scope"]["remaining_mainline"] = "D106-D184"
    require(all(task == {item["id"]: item for item in baseline["tasks"]}[task["id"]] for task in manifest["tasks"] if task["id"] not in ("D105", "D106")), "Other tasks/conditional Q admission cannot change")
    checked = datetime.now(timezone.utc).isoformat()
    matrix = {"D01": "Explicit ordered nine-column YearMonth/domain/key/mapper; eight finite nullable DOUBLEs preserve inherited units and NULLs.", "D02": "Month natural read key, first calendar day UTC midnight; ordinary VIEW UPSERT/conflict/direct writer N/A.",
              "D03": "Original identity SELECT SQL and exact schema/view/native history; one missing-only private CREATE with durable known raw ACK, no formal replacement.", "D04": "Actual key/range/three stable pages/cursor/ReadGroup in both scopes; changed-range cursor and invalid requests reject before reader.",
              "D05": "Ordinary VIEW direct typed write/static replacement/WAL replacement rejected; no business writer or publisher.", "D06": "Accepted D104 base and immutable original SQL; full previously captured six physical sources rechecked, no new ingestion/refresh.",
              "D07": "Dataset v1 dependency macro_core_monthly, catalog54/job42, own view jobs0; writer/job/checkpoint N/A.", "D08": "At most12 first-calendar-day months; actual June-August sample is closed. Finite row/metadata caps and per-statement timeouts, pre-cancelled group returns no page, invalid view/schema/version fail closed without Python base fallback.",
              "D09": "Private and formal real SELECT acceptance, three months/full nine fields/bit+NULL parity, unchanged source/base/ledger/formal; JuneJuly/repeat and JulyAugust window expansion reuse D104 real append."}
    limits = ["Only local formal/private bounded June-August2026 READ acceptance; no complete-history/universe/PIT or production cutover certification.", "D105 only created one missing private ordinary VIEW. All base/source/business/formal/ledger writes are zero; FULL and refresh N/A.", "Actual source increment and revision during D105 are false. The third-month data was published by the accepted D104 August stage.", "Native ordinary VIEW walEnabled metadata is diagnostic; logical View partition/WAL/DEDUP/UPSERT/write/job/checkpoint N/A.", "Legacy Python fetch_view_first broad fallback is documented; Java view reader and these audits reject missing/invalid views without fallback.", "GDP NULL and original SF preceding-observation interpretation/new_rmb_loan compatibility name and stored units remain inherited unchanged.", "The initial compile failure and old43-guard subset remain preserved; current60pure+1unique live(two invocations)/44guards count only actual passing final evidence.", "UNKNOWN CREATE behavior is mocked guard coverage; actual CREATE had one known ACK and no retry. Human pending_review; Q not admitted."]
    evidence = [audit.binding(REVIEW), *[{"path": str(path), "sha256": sha} for path, sha in approved.items()]]
    common_result = {"task_id": "D105", "dataset_id": audit.VIEW, "implementation_status": "implemented", "data_validation_status": "verified", "human_review": "pending_review", "blockers": [], "next_task": "D106", "next_task_admitted": True,
                     "java_unique_passing_methods": 61, "java_pure_unique_methods": 60, "java_live_unique_methods": 1, "java_live_actual_stage_invocations": 2, "java_PASS_invocations": 62, "python_unique_pure_guards": 44,
                     "rows_per_scope": 3, "field_values_per_scope": 27, "nullable_double_slots_per_scope": 24, "nonnull_double_bits_per_scope": 22, "null_double_slots_per_scope": 2, "double_tolerance": 0,
                     "private_view_CREATE_ACKs": 1, "source_writes": 0, "output_writes": 0, "base_writes": 0, "formal_writes": 0, "ledger_mutated": False, "actual_source_increment": False, "actual_source_revision": False,
                     "D104_ledger_counts": counts, "retained_leases": 0, "source_readback": source_counts, "source_readback_scope": "Private full source values; formal source metadata/schema/WAL/COUNT stability and inherited D104 original source capture", "delivery_matrix": matrix, "limitations": limits, "evidence": evidence}
    gate = {**common_result, "checked_at": checked, "decision": "accepted_for_serial_progress", "execution_mode": "direct_local_serial", "next_task_may_start": True, "code_bindings": review.get("code_bindings", []) + review.get("excluded_author_inventory", [])}
    result = {**common_result, "updated_at": checked, "definition_version": 1, "coordinator_gate": "accepted_for_serial_progress", "blocker": None, "source_contract": audit.binding(BASE / "source-contract-20261007.json"), "mapping_contract": audit.binding(BASE / "mapping-contract-20261007.json"),
              "private_target": created["private_target_attestation_after"], "private_view": created["view_metadata_after"], "formal_view": formal["view_metadata_after"], "source": {"dataset_id": "macro_core_monthly", "predecessor_gate": created["d104_evidence"]["gate"], "real_captured_raw_source": source_counts}, "actual_read_receipts": {scope: audit.binding(D / f"java-view-read-{scope}-acceptance-20261007.json") for scope in java}, "job_checkpoint_writer": "N/A ordinary VIEW", "FULL": 0}
    body = f"""状态：verified（正式/私有有界读取验收）；协调器 accepted_for_serial_progress，按序准许 D106；人工 pending_review。

- 原普通VIEW是 `SELECT * FROM macro_core_monthly`，九列identity投影；month以YearMonth/首日UTC表达，八个nullable DOUBLE值、单位及NULL保持。
- 私有missing-only CREATE一条ACK；D105来源/基表/业务/正式/ledger0写，View直写/静态及WAL替换被拒；独立job/refresh/checkpoint N/A。
- 正式、私有各3月27字段、24 nullable槽、22非空bits与2NULL原位一致，容差0；实际按键/三页cursor/ReadGroup/取消/拒写通过。
- 初次JuneJuly、同范围重读、JulAug窗口扩展复用D104真实August追加；D105 actual_source_increment/revision均false。
- 61唯一Java（60pure+1live），62通过调用（private/formal live各一次）；44唯一Pythonguards。原compile失败和43subset保留不加数。
- 私有28行真实源412字段/383槽/338非空bits/45NULL完整复核；正式来源核查7表/schema/WAL/COUNT前沿稳定，完整源值沿用D104原始捕获。两scope的View/base九字段均为本次实际回读。原D104账本{counts['runs']}/{counts['entries']}/{counts['events']}/{counts['groups']}/{counts['leases']}不变，创建者与两JVM原身份已停止。
- 仅本地有界月份读取，不认证全历史/PIT/最新universe；人审pending_review，Q未准入。
"""
    summary = "# D105 完成登记（2026-10-07）\n\n" + body + "\n[最新协调准入](coordinator-review-20261007.json) · [任务结果](../../../docs/migration-tasks-20260929/results/D105.json)\n"
    lines = old[record_paths[0]].splitlines(); indexes = [index for index, line in enumerate(lines) if line.startswith("- 状态：")]
    require(len(indexes) == 1 and "in_progress" in lines[indexes[0]], "Current D105 task state differs")
    lines[indexes[0]] = "- 状态：verified（2026-10-07，正式/私有有界读取验收）；协调器 accepted_for_serial_progress，按序准许 D106；人工 pending_review。"
    card = "\n".join(line.replace("- [ ]", "- [x]", 1) if line.startswith("- [ ]") else line for line in lines) + "\n\n## 实际验收完成登记（2026-10-07）\n\n" + body + "\n完整证据：[结果](../results/D105.json)、[完成登记](../../../artifacts/java-migration/D105/completion-summary-20261007.md)、[协调准入](../../../artifacts/java-migration/D105/coordinator-review-20261007.json)。\n"
    next_card = old[record_paths[1]]; require(next_card.count("- 状态：planned，尚未派发。") == 1, "Exact next task planned state required")
    next_card = next_card.replace("- 状态：planned，尚未派发。", "- 状态：in_progress（2026-10-07）；D105最终协调gate已准入，当前会话直接本地串行执行，人工 pending_review。", 1)
    register = old[record_paths[2]].splitlines(); require(sum(line.startswith("| D105 |") for line in register) == 1, "Only one D105 register row allowed")
    row = "| D105 | v_macro_core_monthly | verified（正式/私有有限读取） | 固定私有43084/19040/18852；正式只读 | 9列typed VIEW/ReadGroup；依赖D104，独立job0 | private CREATE1ACK；source/base/formal0写 | 两scope各3月27字段/24槽/22bits/2NULL；0tol | 真实cursor/重读/窗口扩展；继承D104增量，D105无source修订 | Java61unique/62PASS调用+Python44；原compile失败保留 | 2026-10-07 / 根与独立复核通过；accepted_for_serial_progress | [D105结果](results/D105.json)；[任务卡](11-derived/D105-v_macro_core_monthly.md)；[完成证据](../../artifacts/java-migration/D105/completion-summary-20261007.md) | pending_review |"
    register_text = "\n".join(row if line.startswith("| D105 |") else line for line in register) + "\n"
    execution = old[record_paths[4]].rstrip() + "\n\n## D105 verified，按序准许 D106（2026-10-07）\n\n" + body + "\n[D105协调准入](../../artifacts/java-migration/D105/coordinator-review-20261007.json) · [D105任务结果](results/D105.json)\n\n剩余主线 D106—D184 共79项；Q须独立准入。\n\n## D106 执行中（2026-10-07）\n\nD105最终协调gate准入后，按序开始 macro_liquidity_credit_monthly；本登记未实施D106业务或后续任务。\n"
    updates = {record_paths[0]: card, record_paths[1]: next_card, record_paths[2]: register_text, record_paths[3]: encoded(manifest), record_paths[4]: execution}
    require(all(digest(path) == sha for path, sha in approved.items()) and all(digest(path) == sha for path, sha in old_sha.items()), "Reviewed code/evidence or task records changed before registration")
    create(GATE, encoded(gate)); create(SUMMARY, summary); create(RESULT, encoded(result))
    for path, value in updates.items():
        path.write_text(value, encoding="utf-8", newline="\n")
    print(json.dumps({"decision": gate["decision"], "gate": audit.binding(GATE), "result": audit.binding(RESULT), "next_task": "D106", "formal_writes": 0, "source_writes": 0}))


if __name__ == "__main__":
    main()
