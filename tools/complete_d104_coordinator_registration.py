"""File-only D104 closure after immutable final review; no DB/native/test calls.

This tool validates all inputs before changing the serial task records. Existing
stage evidence, failed receipts and reference files are never edited. Root runs
it only after the actual increment, stopped-JVM/ledger proof and final review.
"""
from __future__ import annotations

import argparse
import ast
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
REFERENCE = Path("D:/work/fund_2/back-monitor").resolve()
BASE = ROOT / "artifacts/java-migration/D104"
CMDS = BASE / "commands"
DOCS = ROOT / "docs/migration-tasks-20260929"
REVIEW = CMDS / "coordinator-final-delivery-static-review-20261007.json"
GATE = BASE / "coordinator-review-20261007.json"
SUMMARY = BASE / "completion-summary-20261007.md"
RESULT = DOCS / "results/D104.json"
SOURCES = ("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month")
FIELDS = ("month", "cpi_yoy", "ppi_yoy", "pmi_mfg", "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy")
LEDGER_KEYS = ("runs", "entries", "events", "groups", "leases")
KNOWN_FAILED_SHA = "516195048104e45c61ed0d7ff5796f32eca6d72dabc493c16b2a3f1e3667c534"


def require(value, message):
    if not value:
        raise RuntimeError(message)


def unique(items):
    result = {}
    for key, value in items:
        require(key not in result, "Duplicate JSON proof key: " + key)
        result[key] = value
    return result


def read(path):
    path = Path(path).resolve(strict=True)
    require(path.stat().st_size <= 32 * 1024 * 1024, "Bounded file evidence required")
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def binding(path):
    path = Path(path).resolve(strict=True)
    return {"path": str(path), "sha256": digest(path)}


def proof(item):
    require(isinstance(item, dict) and isinstance(item.get("path"), str) and re.fullmatch("[0-9a-f]{64}", str(item.get("sha256", ""))), "Actual immutable path/SHA proof required")
    path = Path(item["path"])
    if not path.is_absolute():
        candidates = {(prefix / path).resolve() for prefix in (ROOT, BASE, CMDS) if (prefix / path).is_file()}
        require(len(candidates) == 1, "Relative evidence path absent or ambiguous: " + str(path))
        path = candidates.pop()
    path = path.resolve(strict=True)
    require(path.is_relative_to(ROOT) or path.is_relative_to(REFERENCE), "Only project/reference readonly proof paths admitted")
    require(path.is_file() and path.stat().st_size <= 32 * 1024 * 1024 and digest(path) == item["sha256"], "Immutable proof SHA/size differs: " + str(path))
    if "absolute_path" in item:
        require(Path(item["absolute_path"]).resolve(strict=True) == path, "Proof absolute and relative paths differ")
    return path


def verify_nested(value):
    if isinstance(value, dict):
        if "path" in value and "sha256" in value:
            proof(value)
        for child in value.values():
            verify_nested(child)
    elif isinstance(value, list):
        for child in value:
            verify_nested(child)


def exact(value, expected, name):
    require(type(value) is int and value == expected, "Exact integer proof differs: " + name)


def bits(value):
    if value is None:
        return None
    require(type(value) is float and math.isfinite(value), "Finite DOUBLE or explicit NULL required")
    return struct.pack(">d", value).hex()


def exact_rows(actual, expected, schema):
    require(len(actual) == len(expected), "Complete raw key row census differs")
    for left, right in zip(actual, expected):
        require(list(left) == list(right) == list(schema), "Full ordered fields required")
        for field, kind in schema.items():
            require(bits(left[field]) == bits(right[field]) if kind == "DOUBLE" else type(left[field]) is type(right[field]) and left[field] == right[field], "Exact native field differs: " + field)


def source_fingerprint(rows, models, months):
    lines = []
    def append(table, records):
        for row in records:
            for field, kind in models[table]["schema"].items():
                value = row[field]
                if value is None:
                    token = "null"
                elif kind == "DOUBLE":
                    token = str(int(bits(value), 16))
                else:
                    text = value[:10] if kind == "TIMESTAMP" else value
                    token = ("LocalDate" if kind == "TIMESTAMP" else "String") + ":" + str(len(text)) + ":" + text
                lines.append(field + ":" + token + "\n")
    for table in SOURCES:
        window = [row for row in rows[table] if row[models[table]["timestamp_col"]][:7].replace("-", "") in months]
        lines.append(table + ":window:" + str(len(window)) + "\n"); append(table, window)
    context = [row for row in rows["sf_month"] if row["month"][:7].replace("-", "") < months[0]]
    lines.append("sf_month:preceding-observations:" + str(len(context)) + "\n"); append("sf_month", context)
    return hashlib.sha256("".join(lines).encode()).hexdigest()


def validate_inventory(inventory):
    require(inventory.get("task_id") == "D104" and inventory.get("status") == "VERIFIED_CURRENT_UNIQUE_TEST_INVENTORY", "Final current validation inventory required")
    for key, expected in (("java_unique_passing_methods", 123), ("java_pure_unique_methods", 122), ("java_live_unique_methods", 1), ("java_live_actual_stage_invocations", 2), ("java_PASS_invocations", 124), ("python_unique_pure_guards", 159), ("current_inventory_failures", 0), ("current_inventory_errors", 0), ("current_inventory_skips", 0)):
        exact(inventory.get(key), expected, key)
    reports = [proof(item) for item in inventory["java_reports"]]
    require(len(reports) == len(set(reports)) == 12, "Ten pure and two actual live XMLs required")
    pure, live, invocations = set(), set(), 0
    for path in reports:
        xml = ET.parse(path).getroot()
        require(xml.tag == "testsuite" and all(xml.get(key) == "0" for key in ("failures", "errors", "skipped")), "Current passing XML has failed/skipped cases")
        cases = xml.findall("testcase")
        require(len(cases) == int(xml.get("tests")), "Actual XML case count differs")
        for case in cases:
            require(not case.findall("failure") and not case.findall("error") and not case.findall("skipped"), "Passing method evidence is not PASS")
            key = (case.get("classname"), case.get("name")); require(all(key), "Actual unique method identity required")
            (live if "java-initial-result-" in str(path) or "java-increment-result-" in str(path) else pure).add(key)
            invocations += 1
    require(len(pure) == 122 and len(live) == 1 and not pure & live and len(pure | live) == 123 and invocations == 124, "Actual Java XML unique/invocation census differs")
    expected_groups = {"test_d104_preflight_guards.py": 44, "test_d104_fixture_guards.py": 35, "test_d104_known_partial_guards.py": 31, "test_d104_increment_guards.py": 49}
    observed = {}
    for group in inventory["python"]:
        path, log = proof(group["guard_source"]), proof(group["actual_log"])
        require(path.name in expected_groups and path.name not in observed, "Only four current unique guard sources admitted")
        tree = ast.parse(path.read_text(encoding="utf-8"))
        methods = [node.name + "." + child.name for node in tree.body if isinstance(node, ast.ClassDef) for child in node.body if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)) and child.name.startswith("test_")]
        text = log.read_text(encoding="utf-8-sig"); counts = re.findall(r"Ran (\d+) tests", text)
        require(methods == group["methods"] and len(methods) == len(set(methods)) == group["unique_PASS"] == expected_groups[path.name] and len(counts) == 1 and int(counts[0]) == len(methods) and text.rstrip().endswith("OK"), "Actual pure guard source/log census differs")
        observed[path.name] = len(methods)
    require(observed == expected_groups, "Python 44+35+31+49 proof incomplete")
    require(len(inventory["historical_failures_preserved"]) == 2, "Both historical failures must remain explicit")
    verify_nested(inventory)


def validate_java(receipt, stage, source_rows, fixture, preflight):
    require(receipt.get("task_id") == "D104" and receipt.get("stage") == stage and receipt.get("status") == ("VERIFIED_ISOLATED_INITIAL_REPLAY" if stage == "initial" else "VERIFIED_ISOLATED_INCREMENTAL"), "Actual successful Java stage required")
    count = 2 if stage == "initial" else 3
    for key, expected in (("key_and_full_field_comparisons", count * 9), ("nullable_double_slot_comparisons", count * 8), ("exact_double_bit_comparisons", 15 if count == 2 else 22), ("double_tolerance", 0)):
        exact(receipt.get(key), expected, stage + "." + key)
    require(receipt["formal_mutated"] is False and receipt["reference_project_mutated"] is False and receipt["actual_source_revision"] is False and receipt["actual_source_increment"] is (stage == "increment") and receipt["formal_before"] == receipt["formal_after"], "Actual readonly formal/source revision scope differs")
    require(Path(receipt["fixture_receipt"]).resolve() == fixture["path"] and receipt["fixture_sha256"] == fixture["sha256"], "Actual Java fixture binding differs")
    require(receipt["preflight_sha256"] == digest(CMDS / "macro-core-readonly-preflight-20261007.json"), "Actual Java oracle binding differs")
    require(receipt["source"]["rawRows"] == sum(map(len, source_rows.values())) and receipt["source"]["sfContextRows"] == 12 and receipt["source"]["rawFingerprint"] == source_fingerprint(source_rows, preflight["original_models"], ["202606", "202607"] if count == 2 else ["202606", "202607", "202608"]), "Actual Java full six-source fingerprint differs from captured source values")
    rows = [{**row, "month": row["month"] + "T00:00:00Z"} for row in receipt["expected_rows"]]
    exact_rows(rows, preflight["expected_oracle_rows"][:count], preflight["original_models"]["macro_core_monthly"]["schema"])
    exact(receipt["actual_target"]["rowCount"], count, "actual target count")
    vector = receipt["source"]["snapshot"]["sources"]
    require([source["table"] for source in vector] == list(SOURCES), "Actual Java six-source vector required")
    states = fixture["receipt"]["private_after"]
    for source in vector:
        state = states[source["table"]]; p, w = state["physical"], state["wal"]
        for field, expected in (("tableId", p["id"]), ("directory", p["directoryName"]), ("physicalTxn", p["table_txn"]), ("walTxn", w["writerTxn"]), ("sequenceTxn", w["sequencerTxn"]), ("writerTxn", w["writerTxn"]), ("pendingRows", p["wal_pending_row_count"]), ("bufferedTxns", w["bufferedTxnSize"]), ("metadataRowCount", p["table_row_count"])):
            require(type(source[field]) is type(expected) and source[field] == expected, "Cross-stage source physical counter differs: " + source["table"] + "." + field)
    verify_nested(receipt)


def validate_terminal(value, java, initial_ledger):
    require(value["task_id"] == "D104" and value["stage"] == "increment" and value["status"] == "VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER", "Final actual terminal proof required")
    for field in ("retained_leases", "source_writes", "output_writes", "formal_writes"):
        exact(value[field], 0, "final terminal " + field)
    require(proof(value["java_receipt"]) == CMDS / "java-increment-acceptance-20261007.json", "Final terminal binds different actual Java receipt")
    native, executor, ledger = read(proof(value["native"])), read(proof(value["executor_completion"])), read(proof(value["ledger_terminal"]))
    identity = read(proof(java["jvm_identity_evidence"]))
    require(native["original_identity_present"] is False and native["pid"] == identity["jvm_pid"] == java["jvm_pid"] and native["jvm_identity_evidence"] == java["jvm_identity_evidence"], "Known final JVM absence proof differs")
    birth = datetime.fromisoformat(java["jvm_birth_utc"].replace("Z", "+00:00"))
    require(datetime.fromisoformat(identity["jvm_birth_utc"].replace("Z", "+00:00")) == datetime.fromisoformat(native["birth_utc"].replace("Z", "+00:00")) == birth and all(row["pid"] == java["jvm_pid"] and datetime.fromisoformat(row["birth_utc"].replace("Z", "+00:00")) > birth for row in native["matches"]), "Unknown/original native identity cannot be treated as stopped")
    require(executor["status"] == "COMPLETED" and type(executor["exit_code"]) is int and executor["exit_code"] == 0 and executor["chunk_id"], "Actual successful final executor completion required")
    require(ledger["all_entries_terminal"] is True and ledger["ledger_mutated"] is False and ledger["initial_operations_preserved"] is True and ledger["leases"] == [] and all(row["state"] in ("VERIFIED", "VERIFIED_EMPTY", "CANCELLED") for row in ledger["entries"]), "Final retained/unresolved ledger refuses serial admission")
    require(Path(ledger["ledger_path"]).resolve() == ROOT / "var/d104-java-acceptance.sqlite3" and set(java["run_ids"]) <= {row["id"] for row in ledger["runs"]}, "Actual scoped ledger/run identity differs")
    for key in LEDGER_KEYS[:-1]:
        require(all(row in ledger[key] for row in initial_ledger[key]), "Original initial history changed: " + key)
    require(len(ledger["runs"]) == len(initial_ledger["runs"]) + 1 and len(java["run_ids"]) == 1, "Exactly one new canonical increment run required")
    counts = {key: len(ledger[key]) for key in LEDGER_KEYS}
    for name, key in (("run_count", "runs"), ("entry_count", "entries"), ("event_count", "events")):
        exact(value[name], counts[key], name)
    verify_nested(value); verify_nested(ledger)
    return counts


def inputs(args):
    review_path = Path(args.final_review).resolve(strict=True)
    require(review_path.is_relative_to(CMDS) and digest(review_path) == args.final_review_sha256 and re.fullmatch("[0-9a-f]{64}", args.final_review_sha256), "Explicit final independent-review SHA required")
    review = read(review_path)
    require(type(review.get("protocol_version")) is int and review.get("protocol_version") == 1, "Final independent review must explicitly use protocol1")
    require(review["task_id"] == "D104" and review["status"] == "PASS" and review["blockers"] == [] and not review.get("findings"), "Final independent review must PASS without blockers/findings")
    approved = {}
    for key in ("code_bindings", "excluded_author_inventory", "bindings"):
        require(isinstance(review.get(key), list) and review[key], "Complete independent code/author/evidence inventory required: " + key)
        for item in review[key]:
            path = proof(item)
            require(path not in approved or approved[path] == item["sha256"], "Conflicting final-review SHA")
            approved[path] = item["sha256"]
    require(approved.get(Path(__file__).resolve()) == digest(__file__), "Registration implementation must be included in final independent SHA review")
    def load(path):
        path = Path(path).resolve(strict=True)
        require(approved.get(path) == digest(path), "Final review did not approve current immutable input: " + str(path))
        value = read(path); verify_nested(value)
        return value
    names = ("macro-core-readonly-preflight-20261007.json", "source-fixture-initial-20261007.json", "source-fixture-initial-after-known-partial-20261007.json", "source-fixture-increment-20261007.json", "coordinator-complete-increment-source-readonly-review-20261007.json", "java-initial-acceptance-20261007.json", "java-increment-acceptance-20261007.json", "initial-java-terminal-ledger-20261007.json", "increment-java-process-and-ledger-review-20261007.json", "validation-inventory-20261007.json", "java-catalog-startup-final-20261007.json")
    data = {name: load(CMDS / name) for name in names}
    mapping, contract = load(BASE / "mapping-contract-20261007.json"), load(BASE / "source-contract-20261007.json")
    require(approved.get(BASE / "README.md") == digest(BASE / "README.md"), "Frozen final README must be independently SHA approved")
    require(len(mapping["columns"]) == 9 and tuple(column["storage"] for column in mapping["columns"]) == FIELDS and mapping["dataset"]["registered_dependencies"] == mapping["job"]["dependencies"] == [] and mapping["dataset"]["governed_physical_inputs"] == list(SOURCES), "Actual nine-field mapping/physical dependency scope differs")
    require(contract["registered_dependency_graph"]["dataset_dependencies"] == contract["registered_dependency_graph"]["job_dependencies"] == [], "D065-D070 must not be falsely registered as Java upstreams")
    preflight = data[names[0]]; failed = data[names[1]]; initial = data[names[2]]; fixture = data[names[3]]; audit = data[names[4]]; first = data[names[5]]; incremental = data[names[6]]
    require(preflight["status"] == "VERIFIED_BOUNDED_SOURCE_ORACLE" and preflight["formal_mutated"] is False and preflight["stable_physical_and_wal_versions"] is True and preflight["tables_before"] == preflight["tables_after"], "Frozen formal source/oracle proof required")
    whole = {}
    for table in SOURCES:
        item = preflight["source_captures"][table]; records = [json.loads(line, object_pairs_hook=unique) for line in proof(item).read_text(encoding="utf-8").splitlines()]
        model = preflight["original_models"][table]
        for record in records:
            require(record["raw_double_bits"] == {field: bits(record["values"][field]) for field, kind in model["schema"].items() if kind == "DOUBLE"}, "Captured full source raw bits differ")
        whole[table] = [record["values"] for record in records]
        exact(len(whole[table]), item["rows"], table + " captured row census")
        exact_rows(fixture["actual_source_rows"][table], whole[table], model["schema"])
        exact_rows(audit["actual_source_rows"][table], whole[table], model["schema"])
    require(digest(CMDS / names[1]) == KNOWN_FAILED_SHA and failed["status"] == "FAILED" and failed["attempted_operations"] == failed["acknowledged_operations"] == 2 and all(op["table"] == "cn_cpi" and op["ack"] == "ACKNOWLEDGED" for op in failed["operations"]), "Original known-ACK CPI failure must stay FAILED and unchanged")
    require(initial["status"] == "VERIFIED_ISOLATED_SOURCE_INITIAL" and initial["original_initial_status"] == "FAILED_PRESERVED" and initial["cpi_resubmissions"] == 0 and initial["new_acknowledged_operations"] == 10 and initial["total_known_acknowledged_operations"] == 12, "Initial CPI no-resend continuation provenance required")
    require(fixture["status"] == "VERIFIED_ISOLATED_SOURCE_INCREMENT" and fixture["automatic_retry"] is False and fixture["formal_writes"] == fixture["private_output_writes"] == fixture["DDL"] == 0 and fixture["formal_mutated"] is False and fixture["formal_before"] == fixture["formal_after"], "Only actual August source increment with no formal/output/DDL write required")
    exact(fixture["attempted_operations"], 5, "August attempts"); exact(fixture["acknowledged_operations"], 5, "August ACKs")
    require([(op["table"], op["kind"], op["rows"], op["ack"]) for op in fixture["operations"]] == [(table, "DML", 1, "ACKNOWLEDGED") for table in ("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "sf_month")], "Exactly five known source-only August INSERTs required")
    require(audit["status"] == "VERIFIED_COMPLETE_INCREMENT_SOURCE_BY_INDEPENDENT_READONLY_REVIEW" and audit["private_before"] == audit["private_after"] == fixture["private_after"] and audit["formal_before"] == audit["formal_after"] and audit["original_ledger_unchanged"] is True and audit["DDL_DML_in_this_review"] == 0, "Independent actual complete source readback required")
    for key, value in (("source_rows", 28), ("full_field_comparisons", 412), ("nullable_double_slots", 383), ("source_INSERT_ACKs", 5), ("source_new_rows", 5), ("GDP_INSERT_rows", 0), ("retained_leases", 0)):
        exact(audit[key], value, "independent source " + key)
    nonnull = sum(row[field] is not None for table in SOURCES for row in whole[table] for field, kind in preflight["original_models"][table]["schema"].items() if kind == "DOUBLE")
    exact(audit["non_null_double_bits"], nonnull, "actual source nonnull raw bits")
    validate_java(first, "initial", initial["actual_source_rows"], {**binding(CMDS / names[2]), "path": (CMDS / names[2]).resolve(), "receipt": initial}, preflight)
    validate_java(incremental, "increment", whole, {**binding(CMDS / names[3]), "path": (CMDS / names[3]).resolve(), "receipt": fixture}, preflight)
    require(incremental["incremental"]["result"]["state"] == "VERIFIED" and incremental["incremental"]["result"]["verifiedRows"] == 2 and incremental["increment_plan"]["request"]["parameters"]["checkpoint_reason"] == "VERIFIED_PREFIX_APPEND" and incremental["increment_plan"]["request"]["from"] == "2026-07-01" and incremental["increment_plan"]["request"]["to"] == "2026-08-01", "Actual canonical July-overlap/August increment required")
    counts = validate_terminal(data[names[8]], incremental, data[names[7]])
    validate_inventory(data[names[9]])
    catalog = data[names[10]]
    require(catalog["task_id"] == "D104" and catalog["dataset_count"] == 53 and catalog["job_count"] == 42 and catalog["definition"]["dependencies"] == catalog["job"]["dependencies"] == [] and catalog["database_connections"] == 0 and catalog["ledger_created"] is False, "Actual registered management/startup catalog proof required")
    return review_path, review, approved, data, mapping, contract, counts, nonnull


def create(path, text):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(text)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--final-review", type=Path, default=REVIEW)
    parser.add_argument("--final-review-sha256", required=True)
    args = parser.parse_args()
    review_path, review, approved, data, mapping, contract, counts, nonnull = inputs(args)
    require(not any(path.exists() for path in (GATE, SUMMARY, RESULT)), "New D104 closure identity required; no overwriting gate/result/summary")
    record_paths = [DOCS / "11-derived/D104-macro_core_monthly.md", DOCS / "11-derived/D105-v_macro_core_monthly.md", DOCS / "completion-register.md", DOCS / "manifest.json", DOCS / "execution-status.md"]
    old = {path: path.read_text(encoding="utf-8-sig") for path in record_paths}
    old_sha = {path: digest(path) for path in record_paths}
    manifest = json.loads(old[record_paths[3]], object_pairs_hook=unique); baseline = copy.deepcopy(manifest)
    tasks = {task["id"]: task for task in manifest["tasks"]}
    require(tasks["D104"]["status"] == "in_progress" and tasks["D105"]["status"] == "planned" and manifest["current_thread_scope"]["remaining_mainline"] == "D104-D184", "Only current serial D104→D105 transition admitted")
    require(all(tasks["D0" + str(number)]["status"] == "planned" for number in range(65, 71)), "Upstream Java task readiness differs; review records before closure")
    tasks["D104"]["status"] = "verified"; tasks["D105"]["status"] = "in_progress"; manifest["current_thread_scope"]["remaining_mainline"] = "D105-D184"
    require(all(task == {item["id"]: item for item in baseline["tasks"]}[task["id"]] for task in manifest["tasks"] if task["id"] not in ("D104", "D105")), "Other tasks/conditional Q must remain unchanged")
    checked = datetime.now(timezone.utc).isoformat()
    first, increment = data["java-initial-acceptance-20261007.json"], data["java-increment-acceptance-20261007.json"]
    limits = ["Only isolated bounded June-August 2026 acceptance; no formal write, FULL, production cutover or complete-history certification.",
              "D065-D070 Java ingestion owners remain planned; registered Dataset/job dependencies are empty, with six explicit governed physical inputs.",
              "GDP uses report-date own month without forward fill; July/August GDP stays NULL. SF YoY uses the preceding twelfth physical observation, not calendar fill or percent scaling.",
              "Legacy new_rmb_loan denotes sf_month.inc_month total social financing increment; source stored units are unchanged.",
              "Original initial source FAILED after two known CPI ACKs is preserved; reviewed continuation wrote only five missing sources and never resubmitted CPI.",
              "Original initial pure fixture failure remains unchanged; final121 pure methods plus separately passed catalog1 are current PASS evidence.",
              "Actual append passed; UNKNOWN publication recovery and old-prefix source revision have pure-test coverage and did not occur in this live acceptance.",
              "Human review remains pending_review. Conditional Q tasks have no new admission."]
    matrix = {"D01": "Explicit ordered nine-column typed domain/key/mapper, finite nullable binary64 and original stored units.",
              "D02": "YearMonth JSON/first-day LocalDate/UTC-midnight TIMESTAMP, full month business and UPSERT key.",
              "D03": "Isolated missing-only YEAR/WAL/DEDUP(month), stable identity/schema/WAL and actual empty-count guard.",
              "D04": "Actual full-key/range/stable page/cursor/ReadGroup; 1..12 months, cancellation/rejection guards.",
              "D05": "Bounded typed batch and actual prepared WriteGroup, one synchronous send/no retry, complete-key/null/rawbit readback.",
              "D06": "Six governed physical sources; finite complete raw fields plus12 preceding SF observations, original Python AST oracle.",
              "D07": "data.macro_core_monthly v1, 53 datasets/42 jobs, manual canonical plan/run/status/cancel/resume/reconcile.",
              "D08": "Full-prefix source hash/version and target proof, closed-month completeness, one-month overlap/checkpoint, bounded timeouts.",
              "D09": "Actual initial/replay/cancel/resume/readonly reconcile/WriteGroup/read composition and July-overlap/August append; stopped JVM and terminal zero-lease ledger."}
    evidence = [binding(review_path), *[{"path": str(path), "sha256": sha} for path, sha in approved.items()]]
    gate = {"task_id": "D104", "dataset_id": "macro_core_monthly", "checked_at": checked, "decision": "accepted_for_serial_progress", "implementation_status": "implemented", "data_validation_status": "verified", "human_review": "pending_review", "blockers": [],
            "execution_mode": "direct_local_serial", "next_task": "D105", "next_task_may_start": True, "next_task_admitted": True, "delivery_matrix": matrix,
            "java_unique_passing_methods": 123, "java_pure_unique_methods": 122, "java_live_unique_methods": 1, "java_PASS_invocations": 124, "python_unique_pure_guards": 159,
            "source_initial_rows": 23, "source_final_rows": 28, "source_field_values": 412, "source_nullable_double_slots": 383, "source_nonnull_double_bits": nonnull, "source_null_double_slots": 383 - nonnull, "full_source_capture_sha256": data["macro-core-readonly-preflight-20261007.json"]["full_sources_sha256"], "august_source_INSERT_ACKs": 5, "august_source_DDL": 0,
            "actual_target_rows": 3, "actual_target_field_values": 27, "actual_target_nullable_double_slots": 24, "actual_target_nonnull_double_bits": 22, "tolerance": 0, "final_ledger": counts,
            "actual_source_increment": True, "actual_source_revision": False, "formal_writes": 0, "full_runs": 0, "original_initial_source_status": "FAILED_KNOWN_CPI_ACKS_PRESERVED", "unknown_live_recovery": False, "upstream_java_owners_ready": False,
            "evidence": evidence, "code_bindings": review["code_bindings"] + review["excluded_author_inventory"], "limitations": limits}
    run = increment["incremental"]["result"]["runId"]
    summary = f"""# D104 完成登记（2026-10-07）

状态：verified（隔离有界验收）；协调器 accepted_for_serial_progress，按序准许 D105；人工 pending_review。

- D01—D09 完成：9列 typed domain/key/mapper/read/write、ReadGroup/WriteGroup、canonical job与管理入口。
- 六个实际来源：June/July 初始23行，经5个August INSERT新增5行至28行；完整412字段、383 nullable DOUBLE槽位，非空位比较{nonnull}。GDP未新增，来源增量0DDL。
- 初次、同范围重跑、取消后精确恢复、只读reconcile和typed WriteGroup真实通过；增量run `{run}` 重叠July并新增August，VERIFIED2行，checkpoint July→August。
- 最终3月27字段、24 nullable DOUBLE槽位、22非空DOUBLE位值一致，容差0；最终账本 {counts['runs']} runs / {counts['entries']} entries / {counts['events']} events / {counts['groups']} groups / {counts['leases']} leases。
- 123唯一Java方法（122pure+1live），124通过调用（live初始/增量各一次）；Python44+35+31+49=159个唯一保护方法通过。
- 原initial source FAILED的2个CPI已知ACK保持；只读确认后另外续接5个缺失来源10newACK，CPI未重发。原pure测试fixture失败保持，最终121pure与catalog1独立PASS。
- D065—D070 Java owner未实现；注册依赖graph为空，六个物理源受schema/WAL/hash完整治理。正式0写、FULL0；UNKNOWN恢复和旧前缀source revision本次未发生，仅pure覆盖。
- GDP仅report_date本月、不ffill；SF YoY按前12条physical observation，new_rmb_loan兼容旧名实为总社融inc_month；原存储单位不缩放。

[最新协调准入](coordinator-review-20261007.json) · [任务结果](../../../docs/migration-tasks-20260929/results/D104.json)
"""
    summary_body = "\n".join(line for line in summary.split("\n\n", 1)[1].splitlines() if not line.startswith("[最新协调准入]")) + "\n"
    result = {"task_id": "D104", "dataset_id": "macro_core_monthly", "definition_version": 1, "implementation_status": "implemented", "data_validation_status": "verified", "human_review": "pending_review", "coordinator_gate": "accepted_for_serial_progress", "next_task_admitted": True, "next_task": "D105", "blocker": None, "updated_at": checked,
              "python_project": str(REFERENCE), "java_project": str(ROOT), "delivery_matrix": matrix, "contract": mapping, "source_contract": binding(BASE / "source-contract-20261007.json"), "target": increment["actual_target"], "private_target": data["source-fixture-increment-20261007.json"]["private_target_attestation_after"],
              "request_window": {"from": "2026-06", "to_inclusive": "2026-08", "months_max": 12}, "source": {"tables": list(SOURCES), "registered_dependencies": [], "unregistered_java_tasks": ["D065", "D066", "D067", "D068", "D069", "D070"], "initial_rows": 23, "august_rows": 5, "final_rows": 28, "full_field_values": 412, "nullable_double_slots": 383, "nonnull_double_bits": nonnull, "null_double_slots": 383 - nonnull, "full_capture_sha256": data["macro-core-readonly-preflight-20261007.json"]["full_sources_sha256"], "august_INSERT_ACKs": 5, "august_DDL": 0},
              "runs": {"first": first["first"]["result"]["runId"], "same_range_replay": first["same_range_replay"]["result"]["runId"], "exact_resume": first["exact_resume"]["result"]["runId"], "readonly_reconcile": first["readonly_reconcile"]["result"]["runId"], "configured_write_group": first["configured_write_group"]["runId"], "increment": run},
              "incremental": {"reason": "VERIFIED_PREFIX_APPEND", "checkpoint_before": "2026-07", "checkpoint_after": "2026-08", "overlap_months": 1, "new_months": 1, "submitted_rows": 2, "verified_rows": 2, "actual_source_revision": False},
              "readback": {"rows": 3, "field_values": 27, "nullable_double_slots": 24, "nonnull_double_bits": 22, "matched": True, "tolerance": 0}, "tests": {"java_unique": 123, "java_pure_unique": 122, "java_live_unique": 1, "java_PASS_invocations": 124, "python_unique": 159, "current_failures": 0, "current_errors": 0, "current_skips": 0},
              "final_ledger": counts, "formal_writes": 0, "full_runs": 0, "limitations": limits, "evidence": evidence + [{"path": str(GATE), "sha256": hashlib.sha256(encoded(gate).encode()).hexdigest()}, {"path": str(SUMMARY), "sha256": hashlib.sha256(summary.encode()).hexdigest()}]}
    lines = old[record_paths[0]].splitlines(); state = [i for i, line in enumerate(lines) if line.startswith("- 状态：")]; require(len(state) == 1 and "in_progress" in lines[state[0]], "Current D104 card state differs")
    lines[state[0]] = "- 状态：verified（2026-10-07，隔离有界验收）；协调器 accepted_for_serial_progress，按序准许 D105；人工 pending_review。"
    card = "\n".join(line.replace("- [ ]", "- [x]", 1) if line.startswith("- [ ]") else line for line in lines) + "\n\n## 实际验收完成登记（2026-10-07）\n\n" + summary_body + "\n完整证据：[结果](../results/D104.json)、[完成登记](../../../artifacts/java-migration/D104/completion-summary-20261007.md)、[协调准入](../../../artifacts/java-migration/D104/coordinator-review-20261007.json)。\n"
    next_card = old[record_paths[1]]; require(next_card.count("- 状态：planned，尚未派发。") == 1, "D105 planned card state differs")
    next_card = next_card.replace("- 状态：planned，尚未派发。", "- 状态：in_progress（2026-10-07）；D104协调gate已准入，当前会话直接本地串行执行，人工 pending_review。", 1)
    register = old[record_paths[2]].splitlines(); require(sum(line.startswith("| D104 |") for line in register) == 1, "Only one D104 register row may change")
    replacement = f"| D104 | macro_core_monthly | verified（隔离验收） | 私有43084/19040/18852；正式只读 | 9列typed读写/ReadGroup/WriteGroup；canonical v1 | 六源23→28；5Aug INSERT0DDL；初次/重跑/恢复/reconcile | 最终3月27字段/24槽/22非空bits原位一致；源412字段/383槽/{nonnull}非空bits | checkpoint07→08；07重叠；≤12月；终账本{counts['runs']}/{counts['entries']}/{counts['events']}/{counts['groups']}/{counts['leases']} leases | Java123unique/124PASS调用+Python159；旧knownCPI失败保留/续接不重发 | 2026-10-07 / 独立及根协调复核通过；accepted_for_serial_progress | [D104结果](results/D104.json)；[任务卡](11-derived/D104-macro_core_monthly.md)；[完成证据](../../artifacts/java-migration/D104/completion-summary-20261007.md) | pending_review |"
    register_text = "\n".join(replacement if line.startswith("| D104 |") else line for line in register) + "\n"
    execution = old[record_paths[4]].rstrip() + "\n\n## D104 verified，按序准许 D105（2026-10-07）\n\n" + summary_body + "\n[D104协调准入](../../artifacts/java-migration/D104/coordinator-review-20261007.json) · [D104任务结果](results/D104.json)\n\n剩余主线 D105—D184 共80项；Q须独立准入。\n\n## D105 执行中（2026-10-07）\n\nD104最终协调gate准入后，按序开始 v_macro_core_monthly 的真实普通View定义/typed read调查与有限验收；未准入D106。\n"
    updates = {record_paths[0]: card, record_paths[1]: next_card, record_paths[2]: register_text, record_paths[3]: encoded(manifest), record_paths[4]: execution}
    require(all(digest(path) == sha for path, sha in approved.items()) and all(digest(path) == old_sha[path] for path in record_paths), "Reviewed files/task records changed while preparing registration")
    create(GATE, encoded(gate)); create(SUMMARY, summary); create(RESULT, encoded(result))
    for path, text in updates.items():
        path.write_text(text, encoding="utf-8", newline="\n")
    print(json.dumps({"decision": gate["decision"], "gate": binding(GATE), "result": binding(RESULT), "next_task": "D105", "final_ledger": counts, "formal_writes": 0, "FULL": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
