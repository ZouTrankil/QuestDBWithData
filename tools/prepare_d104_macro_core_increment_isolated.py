"""D104 five real August source INSERTs after scoped terminal Java admission.

No DDL/output/provider write exists. All five SQLite tables, initial source
fields, two-month output and actual known JVM completion are frozen before each
one-shot mutation. Native ACK bytes are durable before parsing; UNKNOWN stops.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import copy
from datetime import datetime
import json
from pathlib import Path
import sqlite3
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import prepare_d104_macro_core_initial_after_known_partial as continued

base, audit = continued.base, continued.audit
DIRECTORY, ROOT, LEDGER = continued.DIRECTORY, continued.ROOT, continued.LEDGER
CONTINUED_SHA = "d813f0363ad5dc0558001b2b9f8c6a4225187ddf1c8fe4520d6898feb7ffac32"
INITIAL = continued.OUTPUT
JAVA_INITIAL = DIRECTORY / "java-initial-acceptance-20261007.json"
TERMINAL = DIRECTORY / "initial-java-process-and-ledger-review-20261007.json"
OUTPUT = DIRECTORY / "source-fixture-increment-20261007.json"
GUARDS = audit.REPO / "tools/test_d104_increment_guards.py"
AUGUST_TABLES = ("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "sf_month")
SQLITE_TABLES = {"runs": ("sync_runs", "id"), "entries": ("sync_entries", "id"), "events": ("sync_events", "entry_id,revision"), "groups": ("sync_group_members", "parent_run_id,ordinal"), "leases": ("sync_interval_locks", "id")}


def proof_file(item):
    audit.require(isinstance(item, dict) and set(item) == {"path", "sha256"}, "Exact immutable file binding required")
    path = Path(item["path"]).resolve(strict=True)
    audit.require(path.is_relative_to(DIRECTORY.resolve()) and path.stat().st_size <= 16 * 1024 * 1024 and audit.digest(path) == item["sha256"], "Scoped evidence path/SHA/size differs")
    return path


def load_binding(item):
    return base.load(proof_file(item), item["sha256"])


def instant(value):
    audit.require(isinstance(value, str) and (value.endswith("Z") or value.endswith("+00:00")), "Explicit UTC proof timestamp required")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    audit.require(parsed.utcoffset().total_seconds() == 0, "UTC proof timestamp required")
    return parsed


def java_source_schema_hash(model):
    text = "{" + ", ".join(field + "=" + kind for field, kind in model["schema"].items()) + "}"
    text += "[" + model["timestamp_col"] + "]" + "[" + model["timestamp_col"] + "]"
    return audit.transport.sha_bytes(text.encode("utf-8"))


def java_raw_fingerprint(rows, models, months):
    lines = []
    def append(table, records):
        for row in records:
            for field, kind in models[table]["schema"].items():
                value = row[field]
                if value is None:
                    token = "null"
                elif kind == "DOUBLE":
                    token = str(int(audit.raw_bits(value), 16))
                else:
                    text = value[:10] if kind == "TIMESTAMP" else value
                    token = ("LocalDate" if kind == "TIMESTAMP" else "String") + ":" + str(len(text)) + ":" + text
                lines.append(field + ":" + token + "\n")
    for table in audit.SOURCES:
        window = [row for row in rows[table] if audit.month_key(row[models[table]["timestamp_col"]], table != "cn_gdp") in months]
        lines.append(table + ":window:" + str(len(window)) + "\n"); append(table, window)
    context = [row for row in rows["sf_month"] if audit.month_key(row["month"]) < months[0]]
    lines.append("sf_month:preceding-observations:" + str(len(context)) + "\n"); append("sf_month", context)
    return audit.transport.sha_bytes("".join(lines).encode("utf-8"))


def java_source_version(snapshot):
    fields = ("table", "tableId", "directory", "physicalTxn", "walTxn", "sequenceTxn", "writerTxn", "pendingRows", "bufferedTxns", "metadataRowCount", "schemaHash")
    parts = ["PhysicalSnapshot[" + ", ".join(field + "=" + ("null" if source[field] is None else str(source[field])) for field in fields) + "]" for source in snapshot["sources"]]
    return audit.transport.sha_bytes(("[" + ", ".join(parts) + "]").encode("utf-8"))


def validate_java_source(snapshot, states, models):
    vector = snapshot["sources"]
    audit.require([item["table"] for item in vector] == list(audit.SOURCES), "Exact original six-source Java vector required")
    for source in vector:
        table = source["table"]; state = states[table]; p, w = state["physical"], state["wal"]
        expected = {"tableId": p["id"], "directory": p["directoryName"], "physicalTxn": p["table_txn"], "walTxn": w["writerTxn"],
                    "sequenceTxn": w["sequencerTxn"], "writerTxn": w["writerTxn"], "pendingRows": p["wal_pending_row_count"], "bufferedTxns": w["bufferedTxnSize"], "metadataRowCount": p["table_row_count"], "schemaHash": java_source_schema_hash(models[table])}
        audit.require(state["settled"] and all(field in source and type(source[field]) is type(value) and source[field] == value for field, value in expected.items()), "Java initial source identity/counters/schema differ from actual source fixture: " + table)


def java_output_rows(records):
    rows = []
    for record in records:
        audit.require(list(record) == list(audit.OUTPUT_FIELDS) and isinstance(record["month"], str) and len(record["month"]) == 10, "Actual nine-field mapper output with ISO LocalDate required")
        row = dict(record); row["month"] = record["month"] + "T00:00:00Z"
        audit.month_key(row["month"])
        for field in audit.OUTPUT_FIELDS[1:]:
            audit.raw_bits(row[field])
        rows.append(row)
    return rows


def validate_java_initial(java, initial_item, preflight):
    audit.require(java.get("task_id") == "D104" and java.get("stage") == "initial" and java.get("status") == "VERIFIED_ISOLATED_INITIAL_REPLAY" and
        java.get("formal_mutated") is False and java.get("reference_project_mutated") is False and java.get("actual_source_increment") is False and java.get("actual_source_revision") is False and
        Path(java["fixture_receipt"]).resolve() == Path(initial_item["path"]).resolve() and java["fixture_sha256"] == initial_item["sha256"], "Actual successful initial Java receipt bound to continued real fixture required")
    for field, expected in (("key_and_full_field_comparisons", 18), ("nullable_double_slot_comparisons", 16), ("exact_double_bit_comparisons", 15), ("double_tolerance", 0)):
        audit.require(type(java.get(field)) is int and java[field] == expected, "Initial Java comparison proof differs")
    audit.require(java.get("verified_resume_rejected_without_write") is True and java.get("fresh_empty_target_typed_read") is True and java["formal_before"] == java["formal_after"], "Actual initial/replay/typed empty/verified-resume rejection/formal stability required")
    audit.require(audit.compare_output(java_output_rows(java["expected_rows"]), preflight["expected_oracle_rows"][:2])["passed"], "Java initial mapper values differ from original Python nine-field oracle")
    for role in ("first", "same_range_replay", "exact_resume", "readonly_reconcile"):
        result = java[role]["result"]
        audit.require(result["state"] == "VERIFIED" and type(result["verifiedRows"]) is int and result["verifiedRows"] == 2 and java[role]["targetSnapshotError"] is None, "Actual original canonical role failed: " + role)
    for role in ("cancelled_for_resume", "cancelled_before_source"):
        audit.require(java[role]["state"] == "CANCELLED" and type(java[role]["verifiedRows"]) is int and java[role]["verifiedRows"] == 0, "Original cancellation proof differs")
    audit.require(java["configured_write_group"]["state"] == "VERIFIED" and len(java["run_ids"]) == 7 and len(set(java["run_ids"])) == 7 and Path(java["ledger"]).resolve() == LEDGER.resolve(), "Original prepared group and scoped run IDs required")


def inspect_ledger(path=LEDGER):
    path = Path(path).resolve(strict=True)
    audit.require(path == LEDGER.resolve(), "Only actual scoped D104 acceptance SQLite ledger admitted")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON"); connection.execute("BEGIN")
        snapshot = {}
        for key, (table, order) in SQLITE_TABLES.items():
            snapshot[key] = [dict(row) for row in connection.execute(f"SELECT * FROM {table} ORDER BY {order} LIMIT 1001").fetchall()]
            audit.require(len(snapshot[key]) <= 1000, "SQLite snapshot cap reached")
        connection.rollback()
    audit.require(len(snapshot["runs"]) == 8 and not snapshot["leases"] and all(row["state"] in ("VERIFIED", "VERIFIED_EMPTY", "CANCELLED") for row in snapshot["entries"]), "All eight original runs/entries must be terminal with zero retained leases")
    return snapshot


def validate_ledger(snapshot, historical, java):
    audit.require(historical.get("task_id") == "D104" and Path(historical["ledger_path"]).resolve() == LEDGER.resolve() and historical.get("all_entries_terminal") is True and
        type(historical.get("retained_leases")) is int and historical["retained_leases"] == 0 and historical.get("ledger_mutated") is False, "Actual immutable terminal ledger authority required")
    for table in SQLITE_TABLES:
        audit.require(snapshot[table] == historical[table], "Original SQLite history changed: " + table)
    runs = {row["id"]: row for row in snapshot["runs"]}
    entries = {row["id"]: row for row in snapshot["entries"]}
    audit.require(len(runs) == 8 and set(java["run_ids"]) <= set(runs) and historical["run_ids"] == sorted(runs), "Original exact run census differs")
    target = java["actual_target"]["targetId"]
    for run_id, run in runs.items():
        audit.require(type(run["job_version"]) is int and run["job_version"] == 1 and run["job_id"] in ("data.macro_core_monthly", "write.macro_core_monthly", "group.prepared_writes") and entries[run_id]["kind"] == "RUN", "Original job/version/RUN identity differs")
        if run["job_id"] == "group.prepared_writes":
            audit.require(run["target_id"] == "group-control" and run_id == java["configured_write_group"]["runId"], "Prepared group identity differs")
        else:
            audit.require(run["target_id"] == target, "Frozen native output target differs")
        frozen = json.loads(run["frozen_json"], object_pairs_hook=base.unique_json)
        if run["job_id"] == "data.macro_core_monthly":
            params = frozen["parameters"]
            audit.require(frozen["mode"] in ("MATERIALIZE", "RECONCILE") and frozen["from"] == "2026-06-01" and frozen["to"] == "2026-07-01" and params["bootstrap_from"] == "2026-06-01" and
                params["target_id"] == target and params["source_hash"] == java["source"]["rawFingerprint"] and params["source_version"] == java_source_version(java["source"]["snapshot"]), "Original canonical frozen source/window/target parameters differ")
    audit.require(len(snapshot["groups"]) == 1 and snapshot["groups"][0]["parent_run_id"] == java["configured_write_group"]["runId"] and
        snapshot["groups"][0]["child_run_id"] in runs and runs[snapshot["groups"][0]["child_run_id"]]["job_id"] == "write.macro_core_monthly", "Original prepared child membership differs")


def validate_terminal(value, java_item, java):
    audit.require(value.get("task_id") == "D104" and value.get("stage") == "initial" and value.get("status") == "VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER" and value["java_receipt"] == java_item and
        type(value.get("run_count")) is int and value["run_count"] == 8 and type(value.get("retained_leases")) is int and value["retained_leases"] == 0, "Actual scoped terminal Java review required")
    for field in ("source_writes", "output_writes", "formal_writes"):
        audit.require(type(value.get(field)) is int and value[field] == 0, "Terminal review must be readonly")
    executor = load_binding(value["executor_completion"])
    audit.require(executor.get("task_id") == "D104" and executor.get("status") == "COMPLETED" and type(executor.get("exit_code")) is int and executor["exit_code"] == 0 and executor.get("chunk_id"), "Actual successful Java executor completion required")
    proof_file(executor["log"])
    xml = ET.parse(proof_file(value["junit"])).getroot()
    audit.require(xml.tag == "testsuite" and xml.get("name") == "com.zoutrankil.data.config.MacroCoreMonthlyLiveAcceptanceTest" and xml.get("tests") == "1" and all(xml.get(field) == "0" for field in ("failures", "errors", "skipped")), "Actual one-test initial JUnit success required")
    cases = xml.findall("testcase")
    audit.require(len(cases) == 1 and cases[0].get("name", "").startswith("actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition"), "Different actual initial JUnit method")
    native = load_binding(value["native"]); identity = load_binding(java["jvm_identity_evidence"])
    audit.require(type(java["jvm_pid"]) is int and java["jvm_pid"] > 0 and identity["task_id"] == native["task_id"] == "D104" and identity["jvm_pid"] == native["pid"] == java["jvm_pid"] and
        base.native.normalize_birth(identity["jvm_birth_utc"]) == base.native.normalize_birth(native["birth_utc"]) == base.native.normalize_birth(java["jvm_birth_utc"]) and native["jvm_identity_evidence"] == java["jvm_identity_evidence"] and
        native["original_identity_present"] is False and instant(native["observed_at"]) >= instant(java["finished_at"]), "Actual known JVM birth/completion/absence binding differs")
    birth = base.native.normalize_birth(java["jvm_birth_utc"])
    audit.require(all(row["pid"] == java["jvm_pid"] and base.native.normalize_birth(row["birth_utc"]) > birth for row in native["matches"]), "Terminal native evidence contains original JVM or unknown identity")
    historical = load_binding(value["ledger_terminal"])
    return historical


def validate_admission_scope(gate):
    audit.require(type(gate.get("protocol_version")) is int and gate["protocol_version"] == 1 and gate.get("task_id") == "D104" and gate.get("decision") == "accepted_for_bounded_source_increment" and gate.get("source_month") == "202608" and gate.get("automatic_retry") is False, "Explicit bounded August admission required")
    for field, expected in (("source_rows", 5), ("source_INSERT_attempts", 5), ("new_DDL", 0), ("private_output_writes", 0), ("formal_writes", 0)):
        audit.require(type(gate.get(field)) is int and gate[field] == expected, "Exact source-only five INSERT scope differs")


def validate_admitted_code(gate):
    script = gate.get("script")
    audit.require(isinstance(script, dict) and set(script) == {"path", "sha256"} and Path(script["path"]).resolve(strict=True) == Path(__file__).resolve() and script["sha256"] == audit.digest(__file__), "Admission must bind this exact increment script path/SHA before launch")
    review = load_binding(gate["independent_static_review"])
    audit.require(review.get("task_id") == "D104" and review.get("status") == "PASS" and review.get("recommendation") == "accepted_for_bounded_source_increment" and review.get("blockers") == [], "Independent increment static review must pass without blockers")
    bindings = review.get("bindings")
    audit.require(isinstance(bindings, list) and 7 <= len(bindings) <= 64, "Independent static review code/helper binding set required")
    actual = {}
    for item in bindings:
        audit.require(isinstance(item, dict) and set(item) == {"path", "absolute_path", "sha256"}, "Exact independent static-review binding required")
        relative = Path(item["path"])
        path = (audit.REPO / relative if not relative.is_absolute() else relative).resolve(strict=True)
        audit.require(path == Path(item["absolute_path"]).resolve(strict=True) and path.is_relative_to((audit.REPO / "tools").resolve()) and path not in actual and audit.digest(path) == item["sha256"], "Independent static review file/path/SHA changed or duplicated")
        actual[path] = item["sha256"]
    required = [Path(__file__).resolve(), GUARDS.resolve(), *(Path(module.__file__).resolve() for module in (continued, base, audit, audit.transport, base.native))]
    audit.require(all(path in actual and actual[path] == audit.digest(path) for path in required), "Static review omits actual script/guards/helper SHA closure")
    # Guard source belongs to tools; a separately supplied log belongs to commands.
    if "guards" in gate:
        guards = gate["guards"]
        audit.require(isinstance(guards, dict) and set(guards) == {"path", "sha256"} and Path(guards["path"]).resolve(strict=True) == GUARDS.resolve() and guards["sha256"] == actual[GUARDS.resolve()], "Admission guard source path/SHA differs from independent review")
    if "guard_log" in gate:
        proof_file(gate["guard_log"])
    return review


def admission_inputs(args):
    audit.require(audit.digest(continued.__file__) == CONTINUED_SHA and audit.digest(base.__file__) == continued.BASE_SHA, "Frozen fixture helper changed")
    gate = base.load(args.increment_admission, args.increment_admission_sha256)
    validate_admission_scope(gate)
    validate_admitted_code(gate)
    initial = load_binding(gate["initial_fixture"]); java = load_binding(gate["java_initial"]); terminal = load_binding(gate["terminal_review"])
    audit.require(proof_file(gate["initial_fixture"]) == INITIAL.resolve() and proof_file(gate["java_initial"]) == JAVA_INITIAL.resolve() and proof_file(gate["terminal_review"]) == TERMINAL.resolve(), "Only actual canonical initial fixture/Java/terminal identities admitted")
    audit.require(initial.get("task_id") == "D104" and initial.get("status") == "VERIFIED_ISOLATED_SOURCE_INITIAL" and initial.get("original_initial_status") == "FAILED_PRESERVED" and initial.get("automatic_retry") is False and
        initial.get("formal_mutated") is False and initial["formal_before"] == initial["formal_after"] and initial["private_target_attestation"] == initial["private_target_attestation_after"] == gate["target"], "Verified continued initial real sources required")
    for field, expected in (("new_attempted_operations", 10), ("new_acknowledged_operations", 10), ("new_submitted_source_rows", 21), ("new_acknowledged_source_rows", 21), ("total_known_acknowledged_operations", 12), ("total_known_acknowledged_source_rows", 23), ("cpi_resubmissions", 0), ("formal_writes", 0), ("private_output_writes", 0)):
        audit.require(type(initial.get(field)) is int and initial[field] == expected, "Initial known ACK provenance/count differs")
    audit.require(gate["preflight"] == initial["preflight_evidence"] and gate["startup"] == initial["startup_evidence"], "Frozen formal/startup bindings differ")
    preflight = load_binding(gate["preflight"]); base.validate_preflight(preflight)
    whole = base.captured_sources(preflight); initial_rows = base.initial_sources(whole, preflight["original_models"])
    for table in audit.SOURCES:
        base.strict_compare(initial["actual_source_rows"][table], initial_rows[table], table, preflight["original_models"][table])
    failed = load_binding(initial["failed_initial"])
    continued.validate_known_operations(failed, initial_rows, preflight["original_models"])
    for operation in initial["remaining_operations"]:
        audit.require(operation["table"] in continued.REMAINING and operation["ack"] == "ACKNOWLEDGED", "Initial continuation UNKNOWN not admissible")
        path = Path(operation["claim_path"]).resolve(strict=True)
        audit.require(path == continued.claim_path(operation["kind"], operation["table"]).resolve(), "Known remaining source claim identity differs")
        claim = base.load(path, operation["claim_sha256"])
        raw = operation["raw_response"]; raw_path = Path(raw["path"]).resolve(strict=True)
        audit.require(claim["ack"] == "ACKNOWLEDGED" and raw == claim["raw_response"] and raw_path == path.with_suffix(".response.bin") and audit.digest(raw_path) == raw["sha256"] and raw_path.stat().st_size == raw["bytes"], "Initial known claim/raw response SHA differs")
        payload = json.loads(raw_path.read_text(encoding="utf-8"), object_pairs_hook=base.unique_json)
        base.validate_ack(payload, operation["kind"], operation["rows"])
        audit.require(payload == claim["response"] == operation["response"], "Initial known ACK response differs")
    validate_java_initial(java, gate["initial_fixture"], preflight)
    audit.require(java["preflight_sha256"] == gate["preflight"]["sha256"] and java["source"]["rawRows"] == 23 and java["source"]["sfContextRows"] == 12 and java["source"]["rawFingerprint"] == java_raw_fingerprint(initial_rows, preflight["original_models"], list(base.INITIAL_MONTHS)), "Actual Java complete initial source fingerprint differs from full frozen capture")
    validate_java_source(java["source"]["snapshot"], initial["private_after"], preflight["original_models"])
    historical = validate_terminal(terminal, gate["java_initial"], java)
    return gate, initial, java, terminal, historical, preflight, whole, initial_rows


def current_java_absent(java):
    return continued.original_absent({"pid": java["jvm_pid"], "birth_utc": java["jvm_birth_utc"]})


def output_frontier(reader, java, preflight):
    tables = {table: continued.private_state(reader, table) for table in base.PROTECTED}
    audit.require(not tables[audit.TARGET]["exists"], "Protected original macro output must remain absent")
    state = tables[base.PRIVATE_OUTPUT]
    audit.require(state["exists"] and state["settled"] and state["actual_select_count"] == 2, "Actual settled two-month Java target required")
    schema = reader.records("SELECT * FROM table_columns(%s)", (base.PRIVATE_OUTPUT,), cap=100)
    audit.validate_schema(schema, state, preflight["original_models"][audit.TARGET])
    p, w, target = state["physical"], state["wal"], java["actual_target"]
    schema_hash = audit.transport.sha_bytes("".join(f"{item['column']}:{item['type']}:{str(item['upsertKey']).lower()}:{str(item['designated']).lower()}\n" for item in schema).encode("utf-8"))
    counters = {"tableId": p["id"], "directory": p["directoryName"], "schemaHash": schema_hash, "physicalTxn": p["table_txn"], "walTxn": w["writerTxn"], "sequenceTxn": w["sequencerTxn"], "writerTxn": w["writerTxn"], "pendingRows": p["wal_pending_row_count"], "bufferedTxns": w["bufferedTxnSize"], "suspended": p["table_suspended"] or w["suspended"], "rowCount": state["actual_select_count"], "metadataRowCount": p["table_row_count"]}
    audit.require(all(field in target and type(target[field]) is type(value) and target[field] == value for field, value in counters.items()), "Actual Java target identity/schema/counters drifted before source append")
    rows = reader.records(f"SELECT {','.join(audit.OUTPUT_FIELDS)} FROM {base.PRIVATE_OUTPUT} ORDER BY month LIMIT 3", cap=2)
    parity = audit.compare_output(rows, preflight["expected_oracle_rows"][:2])
    audit.require(parity["passed"], "Actual two-month Java output values/nullable bits differ")
    fresh = {table: continued.private_state(reader, table) for table in base.PROTECTED}
    audit.require(fresh == tables, "Protected output metadata/COUNT drifted during full target field read")
    return {"tables": tables, "schema": schema, "actual_rows": rows, "complete_output_parity": parity, "target_id": target["targetId"]}


def final_protected_frontier(reader, current, output_tables, original_ledger):
    # All source/output field reads finish before these independent closing reads.
    fresh = {table: continued.private_state(reader, table) for table in (*audit.SOURCES, *base.PROTECTED)}
    audit.require(fresh == {**current, **output_tables}, "Final eight-table metadata/COUNT vector changed after complete field reads")
    ledger = inspect_ledger()
    audit.require(ledger == original_ledger, "Original five SQLite tables changed after complete field reads")
    return fresh, ledger


def claim_path(table):
    audit.require(table in AUGUST_TABLES, "Only five August source claims admitted; no GDP/DDL/output mutation")
    return DIRECTORY / f"source-fixture-august-dml-{table}-20261007.claim.json"


def submit_once(target, table, rows, model, output, result, boundary, cancel_file=None):
    audit.require(table in AUGUST_TABLES and len(rows) == 1 and audit.month_key(rows[0]["month"]) == "202608", "Exactly one real August row per five monthly sources required")
    sql = base.insert_sql(table, rows, model)
    audit.require(";" not in sql and len(sql.encode("utf-8")) <= base.MAX_SQL_BYTES and len(result["operations"]) < 5 and not any(item["table"] == table for item in result["operations"]), "Five distinct bounded source-only INSERT attempts required")
    audit.check_cancel(cancel_file); boundary(); identity = target.verify()
    audit.require(identity == result["private_target_attestation"] and base.producer_identity() == result["producer_identity"], "Known native service or producer identity drifted")
    claim = claim_path(table)
    journal = {"task_id": "D104", "stage": "august_source_increment", "invocation_id": result["invocation_id"], "kind": "DML", "table": table, "rows": 1, "ack": "UNKNOWN", "created_at": base.utc_now(), "automatic_retry": False,
        "target": identity, "producer_identity": result["producer_identity"], "increment_admission": result["increment_admission"], "initial_fixture": result["initial_fixture"], "java_initial": result["java_initial"], "terminal_review": result["terminal_review"],
        "sql_sha256": audit.transport.sha_bytes(sql.encode("utf-8")), "source_records_sha256": audit.canonical_sha(rows)}
    audit.save_new(claim, journal)
    operation = {"kind": "DML", "table": table, "rows": 1, "ack": "UNKNOWN", "claim_path": str(claim), "claim_sha256": audit.digest(claim), "sql_sha256": journal["sql_sha256"]}
    result["operations"].append(operation); result["attempted_operations"] += 1; result["submitted_source_rows"] += 1
    base.save_progress(output, result)
    url = f"http://127.0.0.1:{base.HTTP_PORT}/exec?" + urlencode({"query": sql})
    try:
        with urlopen(url, timeout=20) as response:
            status = response.status; body = response.read(base.MAX_RESPONSE_BYTES + 1)
    except HTTPError as failure:
        with failure:
            status = failure.code; body = failure.read(base.MAX_RESPONSE_BYTES + 1)
    raw = base.save_raw(claim.with_suffix(".response.bin"), body)
    journal.update(raw_response=raw, http_status=status); operation.update(raw_response=raw, http_status=status)
    base.save_progress(claim, journal); operation["claim_sha256"] = audit.digest(claim); base.save_progress(output, result)
    audit.require(type(status) is int and status == 200 and len(body) <= base.MAX_RESPONSE_BYTES, "Native INSERT failed/overflow: UNKNOWN retained, no retry")
    payload = json.loads(body.decode("utf-8"), object_pairs_hook=base.unique_json); base.validate_ack(payload, "DML", 1)
    journal.update(ack="ACKNOWLEDGED", ack_at=base.utc_now(), response=payload); base.save_progress(claim, journal)
    operation.update(ack="ACKNOWLEDGED", response=payload, claim_sha256=audit.digest(claim))
    result["acknowledged_operations"] += 1; result["acknowledged_source_rows"] += 1; base.save_progress(output, result)


def run(args, output, result):
    audit.require(args.execute_isolated is True, "Explicit bounded source increment execution required")
    gate, initial, java, terminal, historical, preflight, whole, initial_rows = admission_inputs(args)
    result.update(increment_admission={"path": str(Path(args.increment_admission).resolve()), "sha256": args.increment_admission_sha256}, initial_fixture=gate["initial_fixture"], java_initial=gate["java_initial"], terminal_review=gate["terminal_review"], preflight_evidence=gate["preflight"], startup_evidence=gate["startup"], whole_real_sources=preflight["source_captures"], full_sources_sha256=preflight["full_sources_sha256"])
    august = {table: [row for row in whole[table] if audit.month_key(row[preflight["original_models"][table]["timestamp_col"]], table != "cn_gdp") == "202608"] for table in audit.SOURCES}
    audit.require([len(august[table]) for table in audit.SOURCES] == [1, 1, 1, 1, 0, 1], "Exactly five captured August monthly source rows and GDP0 required")
    target = base.PrivateTarget(args.private_root, args.expected_pid, gate["startup"]["path"], gate["startup"]["sha256"])
    result["private_target_attestation"] = target.verify(); audit.require(result["private_target_attestation"] == gate["target"], "Dedicated admitted D104 service differs")
    result["producer_identity"] = base.producer_identity(); base.save_progress(output, result)
    formal = private = None
    try:
        result["java_original_identity_absence"] = current_java_absent(java)
        result["java_ledger_before"] = inspect_ledger(); validate_ledger(result["java_ledger_before"], historical, java)
        formal, private = audit.ReadOnlyPG(args.cancel_file), base.PrivateReader(target, args.cancel_file)
        result["formal_before"] = base.formal_complete(formal, preflight, whole)
        result["source_before"] = {table: continued.private_state(private, table) for table in audit.SOURCES}
        audit.require(result["source_before"] == {table: initial["private_after"][table] for table in audit.SOURCES}, "Actual initial six-source frontier differs")
        result["initial_source_readback"] = {table: base.strict_compare(base.source_rows(private, table, preflight["original_models"][table]), initial_rows[table], table, preflight["original_models"][table]) for table in audit.SOURCES}
        result["outputs_before"] = output_frontier(private, java, preflight)
        result["private_before"] = {**result["source_before"], **result["outputs_before"]["tables"]}
        current, expected = copy.deepcopy(result["source_before"]), copy.deepcopy(initial_rows)
        for table in AUGUST_TABLES:
            audit.require(not claim_path(table).exists(), "Old August INSERT claim exists; resend forbidden")
        def boundary():
            audit.check_cancel(args.cancel_file)
            audit.require(audit.digest(__file__) == result["script_sha256"] and all(audit.digest(path) == expected_sha for path, expected_sha in result["helper_sha256"].items()), "Frozen source tool/helper changed")
            admission_inputs(args); target.verify(); current_java_absent(java)
            audit.require(inspect_ledger() == result["java_ledger_before"], "Original five SQLite tables changed before source INSERT")
            audit.require({table: continued.private_state(private, table) for table in audit.SOURCES} == current, "Source vector drifted before INSERT")
            for table in audit.SOURCES:
                base.strict_compare(base.source_rows(private, table, preflight["original_models"][table]), expected[table], table, preflight["original_models"][table])
            audit.require(output_frontier(private, java, preflight) == result["outputs_before"] and base.formal_complete(formal, preflight, whole) == result["formal_before"], "Output or formal source/output changed before INSERT")
            final_protected_frontier(private, current, result["outputs_before"]["tables"], result["java_ledger_before"])
        for table in AUGUST_TABLES:
            submit_once(target, table, august[table], preflight["original_models"][table], output, result, boundary, args.cancel_file)
            expected[table].extend(august[table])
            after = continued.wait_visible(private, table, len(expected[table])); before = current[table]
            audit.require(after["physical"]["id"] == before["physical"]["id"] and after["physical"]["directoryName"] == before["physical"]["directoryName"] and after["physical"]["table_txn"] == before["physical"]["table_txn"] + 1 and after["wal"]["sequencerTxn"] == before["wal"]["sequencerTxn"] + 1, "Exactly one acknowledged source transaction required")
            current[table] = after
            base.strict_compare(base.source_rows(private, table, preflight["original_models"][table]), expected[table], table, preflight["original_models"][table])
        result["actual_source_rows"] = {table: base.source_rows(private, table, preflight["original_models"][table]) for table in audit.SOURCES}
        result["complete_source_readback"] = {table: base.strict_compare(result["actual_source_rows"][table], whole[table], table, preflight["original_models"][table]) for table in audit.SOURCES}
        result["actual_source_double_bits"] = {table: [{field: audit.raw_bits(row[field]) for field, kind in preflight["original_models"][table]["schema"].items() if kind == "DOUBLE"} for row in result["actual_source_rows"][table]] for table in audit.SOURCES}
        result["source_census_after"] = audit.source_census(result["actual_source_rows"], preflight["original_models"], list(base.MONTHS))
        oracle, _proof = audit.original_oracle(result["actual_source_rows"], list(base.MONTHS)); audit.require(audit.compare_output(oracle, preflight["expected_oracle_rows"])["passed"], "Actual full original Python oracle differs after append")
        result["expected_oracle_rows"] = oracle
        result["private_schemas_after"] = {table: private.records("SELECT * FROM table_columns(%s)", (table,), cap=100) for table in audit.SOURCES}
        for table in audit.SOURCES:
            audit.validate_schema(result["private_schemas_after"][table], current[table], preflight["original_models"][table])
        result["outputs_after"] = output_frontier(private, java, preflight); result["formal_after"] = base.formal_complete(formal, preflight, whole)
        result["java_original_identity_absence_after"] = current_java_absent(java)
        result["private_target_attestation_after"] = target.verify(); result["producer_identity_after"] = base.producer_identity()
        admission_inputs(args)
        result["private_after"], result["java_ledger_after"] = final_protected_frontier(private, current, result["outputs_before"]["tables"], result["java_ledger_before"])
        result["source_after"] = {table: result["private_after"][table] for table in audit.SOURCES}
        audit.require(result["source_after"] == current and result["source_after"]["cn_gdp"] == result["source_before"]["cn_gdp"] and result["outputs_after"] == result["outputs_before"] and result["formal_after"] == result["formal_before"] and result["java_ledger_after"] == result["java_ledger_before"] and
            result["private_target_attestation_after"] == gate["target"] and result["producer_identity_after"] == result["producer_identity"], "Protected ledger/source/GDP/output/formal/service drifted after source append")
        audit.require(result["attempted_operations"] == result["acknowledged_operations"] == result["submitted_source_rows"] == result["acknowledged_source_rows"] == 5, "Exactly five known August INSERT ACKs required")
        result.update(status="VERIFIED_ISOLATED_SOURCE_INCREMENT", initial_source_rows=23, source_only_rows=28, source_only_field_values=412, source_only_double_slots=383,
            actual_source_increment=True, actual_source_revision=False, producer_sender_stopped=True, finished_at=base.utc_now(), producer_sender_stop_scope="All five foreground HTTP contexts are closed; not a native-absence claim about the currently running Python producer.")
    finally:
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--increment-admission", type=Path, required=True)
    parser.add_argument("--increment-admission-sha256", required=True)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = args.output.resolve()
    audit.require(output == OUTPUT.resolve() and not output.exists(), "One new source increment identity required; no overwrite/resend")
    result = {"task_id": "D104", "protocol_version": 1, "stage": "increment", "status": "FAILED", "invocation_id": str(uuid.uuid4()), "checked_at": base.utc_now(), "automatic_retry": False,
        "automatic_resend_permitted": False, "formal_writes": 0, "private_output_writes": 0, "formal_mutated": False, "reference_project_mutated": False, "DDL": 0,
        "operations": [], "attempted_operations": 0, "acknowledged_operations": 0, "submitted_source_rows": 0, "acknowledged_source_rows": 0, "script_sha256": audit.digest(__file__),
        "helper_sha256": {str(Path(module.__file__).resolve()): audit.digest(module.__file__) for module in (continued, base, audit, audit.transport, base.native)}}
    audit.save_new(output, result)
    try:
        run(args, output, result)
    except BaseException as failure:
        result["status"] = "FAILED"; result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        if audit.digest(__file__) != result["script_sha256"] or any(audit.digest(path) != expected for path, expected in result["helper_sha256"].items()):
            result["status"] = "FAILED"; result["error"] = {"type": "FrozenCodeChanged", "message": "Script/helper changed during source increment"}
        base.save_progress(output, result)
    print(json.dumps({"task_id": "D104", "status": result["status"], "output": str(output), "sha256": audit.digest(output), "ACK": result["acknowledged_operations"], "submitted_source_rows": result["submitted_source_rows"], "formal_writes": 0, "private_output_writes": 0, "DDL": 0}))
    return 0 if result["status"] == "VERIFIED_ISOLATED_SOURCE_INCREMENT" else 1


if __name__ == "__main__":
    raise SystemExit(main())
