"""One separately admitted August source INSERT, never output or formal writes.

The failed initial INSERT remains UNKNOWN. This new invocation requires its
readonly reconciliation, actual Java initial acceptance, and a scoped terminal
manifest. A durable UNKNOWN claim precedes HTTP; raw response precedes ACK
recognition. Existing claims are never retried.
"""
from __future__ import annotations

import argparse
import base64
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
from urllib.parse import urlencode
from urllib.error import HTTPError
from urllib.request import urlopen
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import reconcile_d103_initial_source_readonly as recovery

fixture, audit = recovery.fixture, recovery.audit
DIRECTORY = fixture.DIRECTORY
LEDGER = audit.REPO / "var/d103-java-acceptance.sqlite3"
CLAIM = DIRECTORY / "index-monthly-august-insert-once-20261006.json"
RECOVERY_SHA = "ed912df9fc1116110b4e1b4a5986b2e86221010124a6f68ce3ba5c44bd80401d"
JAVA_RECEIPT = DIRECTORY / "java-initial-acceptance-20261006.json"
TERMINAL = {"VERIFIED", "VERIFIED_EMPTY", "PARTIAL", "FAILED", "CANCELLED"}
JOB_IDS = {"data.equity_style_monthly", "write.equity_style_monthly", "group.prepared_writes"}


def proof_file(item):
    audit.require(isinstance(item, dict) and set(item) == {"path", "sha256"}, "Exact evidence path/SHA pair required")
    path = Path(item["path"]).resolve(strict=True)
    helpers = {(audit.REPO / "tools/prepare_d103_equity_style_isolated.py").resolve(),
               (audit.REPO / "tools/reconcile_d103_initial_source_readonly.py").resolve()}
    audit.require(path.is_relative_to(DIRECTORY.resolve()) or path in helpers, "Evidence must remain in scoped D103 files")
    audit.require(isinstance(item["sha256"], str) and fixture.re.fullmatch("[0-9a-f]{64}", item["sha256"]) and audit.digest(path) == item["sha256"], "Immutable evidence SHA differs")
    return {"path": str(path), "sha256": item["sha256"]}


def source_insert_sql(rows):
    audit.require(len(rows) == 16, "Only sixteen held actual August source rows are admitted")
    census = audit.source_census(rows, ["202608"])
    audit.require(not census["gaps"] and all(item["rows"] == 1 for item in census["coverage"]), "Exactly one real row per August code required")
    tuples = ["(" + ",".join(fixture.sql_literal(row[field], kind) for field, kind in audit.SOURCE_TYPES.items()) + ")" for row in rows]
    sql = "INSERT INTO index_monthly (" + ",".join(audit.SOURCE_FIELDS) + ") VALUES " + ",".join(tuples)
    audit.require(";" not in sql and len(sql.encode("utf-8")) <= fixture.MAX_SQL_BYTES, "One bounded INSERT required")
    return sql


def validate_dml_ack(payload):
    audit.require(isinstance(payload, dict) and payload.get("dml") == "OK" and set(payload) <= {"dml", "updated"}, "Exact native DML ACK required; old UNKNOWN is never upgraded")
    if "updated" in payload:
        audit.require(type(payload["updated"]) is int and payload["updated"] == 16, "DML affected-row count differs from the sole sixteen-row attempt")


def validate_java_snapshot(state, snapshot, source=False):
    audit.require(state["exists"] and state["settled"], "Current Java table must exist with settled WAL")
    physical, wal = state["physical"], state["wal"]
    mapping = {"tableId": physical["id"], "directory": physical["directoryName"], "physicalTxn": physical["table_txn"], "sequenceTxn": wal["sequencerTxn"]}
    if source:
        mapping["table"] = audit.SOURCE
    else:
        mapping.update({"writerTxn": wal["writerTxn"], "pendingRows": physical["wal_pending_row_count"], "bufferedTxns": wal["bufferedTxnSize"],
                        "suspended": physical["table_suspended"] or wal["suspended"], "rowCount": state["actual_select_count"], "metadataRowCount": physical["table_row_count"]})
    for field, expected in mapping.items():
        audit.require(field in snapshot and type(snapshot[field]) is type(expected) and snapshot[field] == expected, "Actual Java frontier differs: " + field)
    audit.require(isinstance(snapshot.get("schemaHash"), str) and fixture.re.fullmatch("[0-9a-f]{64}", snapshot["schemaHash"]), "Java frozen schema hash required")
    if not source:
        audit.require(isinstance(snapshot.get("targetId"), str) and snapshot["targetId"], "Actual Java target ID required")


def inspect_ledger(path, run_ids, target_id):
    path = Path(path).resolve(strict=True)
    audit.require(path == LEDGER.resolve() and path.is_file(), "Only the actual scoped D103 Java ledger is admitted")
    audit.require(isinstance(run_ids, list) and run_ids and all(isinstance(value, str) and value for value in run_ids), "Actual initial Java run IDs required")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
        connection.execute("PRAGMA busy_timeout=5000")
        data = {}
        for key, table, order in (("runs", "sync_runs", "id"), ("entries", "sync_entries", "id"), ("leases", "sync_interval_locks", "id")):
            rows = [dict(row) for row in connection.execute(f"SELECT * FROM {table} ORDER BY {order} LIMIT 1001").fetchall()]
            audit.require(len(rows) <= 1000, "Scoped Java ledger row cap reached")
            data[key] = rows
    runs = {row["id"]: row for row in data["runs"]}
    audit.require(runs and all(row["job_id"] in JOB_IDS and row["job_version"] == 1 for row in runs.values()), "Unrelated or unversioned Java run blocks source fixture changes")
    audit.require(all(row["state"] in TERMINAL and row["kind"] in {"RUN", "ATTEMPT", "SLICE"} and row["run_id"] in runs for row in data["entries"]), "Active or UNKNOWN Java entry blocks source changes")
    audit.require(not data["leases"], "Any retained Java interval lease blocks the source append")
    run_entries = {row["id"]: row for row in data["entries"] if row["kind"] == "RUN"}
    audit.require(set(runs) == set(run_entries), "Every scoped Java run needs an actual terminal RUN entry")
    audit.require(set(run_ids) <= set(runs), "Initial live receipt run missing from actual ledger")
    audit.require(all(row["target_id"] == target_id for row in runs.values() if row["job_id"] != "group.prepared_writes"), "Canonical/prepared child target differs from actual initial target")
    return {"ledger_path": str(path), **data, "all_entries_terminal": True, "retained_leases": 0, "mode": "ro", "query_only": True}


def validate_original_java_absent(records, pid, birth, stopped_at):
    audit.require(type(pid) is int and pid > 0 and isinstance(records, list) and len(records) <= 1, "Scoped original JVM identity proof required")
    original = fixture.normalize_birth(birth)
    completed = fixture.normalize_birth(stopped_at)
    for row in records:
        audit.require(type(row.get("pid")) is int and row["pid"] == pid, "Native query returned another process")
        actual = fixture.normalize_birth(row.get("birth"))
        audit.require(actual != original and actual > completed, "Original JVM still exists, or reused identity cannot be proved later than its completed STOP")
    return {"pid": pid, "original_birth_utc": original, "original_identity_present": False, "current_matches": records}


def current_java_identity(process):
    pid = process["pid"]
    audit.require(type(pid) is int and pid > 0, "Explicit original Java JVM PID required")
    command = "$ErrorActionPreference='Stop'; $pD103=@(Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(pid) + "'); $rowsD103=@(foreach($p in $pD103){[pscustomobject]@{pid=[long]$p.ProcessId;birth=$p.CreationDate.ToUniversalTime().ToString('o');name=$p.Name}}); ConvertTo-Json -InputObject $rowsD103 -Compress"
    completed = subprocess.run([str(Path(fixture.os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", command],
                               capture_output=True, text=True, timeout=30)
    audit.require(completed.returncode == 0, "Scoped Java process identity query failed")
    return validate_original_java_absent(json.loads(completed.stdout), pid, process["birth_utc"], process["stopped_observed_at"])


def validate_junit(item):
    proof = proof_file(item)
    root = ET.parse(proof["path"]).getroot()
    audit.require(root.tag == "testsuite" and root.get("name") == "com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest" and
        root.get("tests") == "1" and root.get("failures") == root.get("errors") == root.get("skipped") == "0",
        "Actual one-test Java initial JUnit success with no skips required")
    cases = root.findall("testcase")
    audit.require(len(cases) == 1 and cases[0].get("name", "").startswith("actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition") and
        not any(cases[0].find(tag) is not None for tag in ("failure", "error", "skipped")), "Different or failed actual Java test")
    return proof


def admission_inputs(args):
    # Helper versions are part of the reviewed invocation, never silently repaired.
    proofs = [proof_file({"path": str(audit.REPO / "tools/prepare_d103_equity_style_isolated.py"), "sha256": recovery.PRODUCER_SHA}),
              proof_file({"path": str(audit.REPO / "tools/reconcile_d103_initial_source_readonly.py"), "sha256": RECOVERY_SHA})]
    reconciled = fixture.load(args.initial_recovery, args.initial_recovery_sha256)
    audit.require(reconciled.get("task_id") == "D103" and reconciled.get("status") == "VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION" and
        reconciled.get("original_insert_ack") == "UNKNOWN" and reconciled.get("source_delivery_exact_verified") is True and
        reconciled.get("java_source_read_admission_recommended") is True and reconciled.get("stable_physical_and_wal_versions") is True,
        "Actual initial readonly recovery must preserve UNKNOWN and prove exact source delivery")
    for field in ("new_source_submissions", "retries", "DDL", "DML", "claim_writes", "old_receipt_writes"):
        audit.require(type(reconciled.get(field)) is int and reconciled[field] == 0, "Initial recovery cannot contain any new submission")
    failed, preflight, whole, initial, claims, original_proofs = recovery.failed_inputs(recovery.FAILED, recovery.FAILED_SHA)
    proofs.extend(original_proofs)
    audit.require(reconciled["whole_real_source"] == failed["whole_real_source"] and reconciled["initial_capture"] == failed["initial_capture"] and
        reconciled["held_increment_capture"] == failed["held_increment_capture"] and reconciled["private_target_attestation"] == failed["private_target_attestation"] and
        reconciled["private_before"] == reconciled["private_after"] and reconciled["formal_before"] == reconciled["formal_after"] == failed["formal_before"],
        "Initial recovery lineage or exact stable frontier differs")
    sender = reconciled["sender_completion_proof"]["review"]
    sender_proof = recovery.completion_proof(sender["path"], sender["sha256"], failed, claims)
    proofs.extend([proof_file(sender), proof_file(sender_proof["executor_completion"]), proof_file(sender_proof["ownership"]["producer_source"])])
    java = fixture.load(args.java_initial, args.java_initial_sha256)
    audit.require(Path(args.java_initial).resolve() == JAVA_RECEIPT.resolve() and java.get("task_id") == "D103" and java.get("stage") == "initial" and
        java.get("status") == "VERIFIED_ISOLATED_INITIAL_REPLAY" and java.get("formal_mutated") is False and java.get("reference_project_mutated") is False and
        java.get("double_tolerance") == 0 and type(java.get("key_and_full_field_comparisons")) is int and java["key_and_full_field_comparisons"] == 60 and
        type(java.get("exact_double_bit_comparisons")) is int and java["exact_double_bit_comparisons"] == 58,
        "Actual successful initial two-month Java full-field acceptance required")
    audit.require(Path(java["fixture_receipt"]).resolve() == Path(args.initial_recovery).resolve() and java["fixture_sha256"] == args.initial_recovery_sha256 and
        java["preflight_sha256"] == failed["preflight_evidence"]["sha256"] and java["formal_before"] == java["formal_after"], "Java initial receipt binds another fixture or unstable formal frontier")
    for field in ("first", "same_range_replay", "exact_resume", "readonly_reconcile"):
        item = java[field]
        audit.require(item["result"]["state"] == "VERIFIED" and item["result"]["verifiedRows"] == 2 and item.get("targetSnapshotError") is None,
                      "Required actual Java initial operation is incomplete: " + field)
    audit.require(java["configured_write_group"]["state"] == "VERIFIED" and java["cancelled_before_source"]["state"] == "CANCELLED" and
        java["cancelled_before_source"]["verifiedRows"] == 0 and java["source"]["rawRows"] == 32, "Actual typed write/cancel and thirty-two-row source proof required")
    members = java["configured_read_group"]["members"]
    audit.require(isinstance(members, list) and len(members) == 1 and members[0]["memberId"] == "styles" and members[0]["datasetId"] == "equity_style_monthly" and
        members[0]["definitionVersion"] == 1 and members[0]["status"] == "READ" and len(members[0]["page"]["rows"]) == 2, "Actual configured typed ReadGroup evidence required")
    gate = fixture.load(args.terminal_admission, args.terminal_admission_sha256)
    audit.require(gate.get("protocol_version") == 1 and gate.get("task_id") == "D103" and gate.get("decision") == "accepted_for_bounded_source_increment" and gate.get("retry_forbidden") is True,
                  "Explicit scoped terminal admission required")
    expected_bindings = {"initial_recovery": {"path": str(Path(args.initial_recovery).resolve()), "sha256": args.initial_recovery_sha256},
                         "java_initial": {"path": str(Path(args.java_initial).resolve()), "sha256": args.java_initial_sha256}}
    for name, expected in expected_bindings.items():
        audit.require(proof_file(gate[name]) == expected, "Terminal admission binds another stage")
    audit.require(gate["target"] == reconciled["private_target_attestation"] == failed["private_target_attestation"] and
        gate["bounded_source"] == failed["held_increment_capture"], "Private target or held actual August source differs")
    process = gate["java_process"]
    audit.require(type(java["jvm_pid"]) is int and process["pid"] == java["jvm_pid"] and fixture.normalize_birth(process["birth_utc"]) == fixture.normalize_birth(java["jvm_birth_utc"]) and
        recovery.explicit_utc(process["stopped_observed_at"]) > recovery.explicit_utc(java["finished_at"]), "Scoped original Java identity/finished chronology differs")
    native = fixture.load(process["native_evidence"]["path"], process["native_evidence"]["sha256"])
    audit.require(native.get("pid") == process["pid"] and fixture.normalize_birth(native["birth_utc"]) == fixture.normalize_birth(process["birth_utc"]) and
        native.get("original_identity_present") is False and recovery.explicit_utc(native["observed_at"]) == recovery.explicit_utc(process["stopped_observed_at"]),
        "Actual original JVM STOP evidence differs")
    validate_original_java_absent(native["matches"], process["pid"], process["birth_utc"], process["stopped_observed_at"])
    executor = fixture.load(gate["executor_completion"]["path"], gate["executor_completion"]["sha256"])
    audit.require(type(executor.get("exit_code")) is int and executor["exit_code"] == 0, "Actual Java executor exit0 required")
    junit = validate_junit(gate["junit"])
    ledger = gate["ledger"]
    audit.require(Path(ledger["path"]).resolve() == LEDGER.resolve() and Path(java["ledger"]).resolve() == LEDGER.resolve() and ledger["run_ids"] == java["run_ids"],
                  "Terminal manifest must bind exact actual initial run IDs and scoped ledger")
    actual_ledger = inspect_ledger(ledger["path"], java["run_ids"], java["actual_target"]["targetId"])
    terminal = fixture.load(ledger["terminal_evidence"]["path"], ledger["terminal_evidence"]["sha256"])
    audit.require(Path(terminal["ledger_path"]).resolve() == LEDGER.resolve() and terminal.get("run_ids") == java["run_ids"] and
        terminal.get("all_entries_terminal") is True and type(terminal.get("retained_leases")) is int and terminal["retained_leases"] == 0,
        "Actual terminal ledger evidence required")
    audit.require(all(audit.canonical_sha(terminal[key]) == audit.canonical_sha(actual_ledger[key]) for key in ("runs", "entries", "leases")), "Actual scoped ledger differs from root terminal inspection")
    proofs.extend([recovery.evidence(args.initial_recovery, args.initial_recovery_sha256), recovery.evidence(args.java_initial, args.java_initial_sha256),
                   recovery.evidence(args.terminal_admission, args.terminal_admission_sha256), proof_file(process["native_evidence"]), proof_file(gate["executor_completion"]),
                   junit, proof_file(ledger["terminal_evidence"])])
    return reconciled, java, gate, preflight, whole, initial, proofs, actual_ledger


def private_outputs(reader, initial_rows):
    states, schemas = {}, {}
    for table in fixture.PROTECTED_OUTPUTS:
        states[table] = fixture.private_state(reader, table)
        schemas[table] = reader.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) if states[table]["exists"] else []
    target = states[fixture.PRIVATE_OUTPUT]
    audit.require(target["exists"] and target["settled"] and target["actual_select_count"] == 2, "Actual Java initial target must contain exactly two settled monthly keys")
    audit.validate_schema(schemas[fixture.PRIVATE_OUTPUT], target, audit.literal_schema(audit.TARGET_MODEL, "EquityStyleMonthly"), False)
    rows = reader.records(f"SELECT {','.join(audit.OUTPUT_FIELDS)} FROM {fixture.PRIVATE_OUTPUT} ORDER BY month LIMIT 3", cap=2)
    expected, _ = audit.original_oracle(initial_rows, "202606", "202607")
    comparison = audit.compare_output(rows, expected)
    audit.require(comparison["passed"] and comparison["full_field_comparisons"] == 60 and comparison["double_rawbit_comparisons"] == 58, "Actual initial Java output differs from independent unchanged original oracle")
    return {"tables": states, "schemas": schemas, "rows": rows, "comparison": comparison}


def formal_complete(reader, preflight, whole):
    state = fixture.formal_snapshot(reader)
    audit.require(state == {"tables": preflight["tables_after"], "schemas": preflight["schemas"]}, "Formal source/output schema/identity/COUNT changed")
    source = fixture.strict_source_compare(reader.records(preflight["source_select"]["sql"], tuple(preflight["source_select"]["params"])), whole)
    output = reader.records(f"SELECT {','.join(audit.OUTPUT_FIELDS)} FROM equity_style_monthly WHERE month >= %s AND month < %s ORDER BY month LIMIT 4",
                            (preflight["range_from_inclusive"], preflight["range_to_exclusive"]), cap=3)
    parity = audit.compare_output(output, preflight["formal_existing_output_rows"])
    audit.require(parity["passed"], "Formal original bounded output values/bits changed")
    return {"snapshot": state, "source_readback": source, "output_rows": output, "output_readback": parity}


def submit_increment_once(target, rows, claim, output, result, boundary, cancel_file=None):
    sql = source_insert_sql(rows)
    url = "http://127.0.0.1:19030/exec?" + urlencode({"query": sql})
    audit.require(len(url.encode("ascii")) <= fixture.MAX_SQL_BYTES, "Single encoded source request exceeds its finite budget")
    audit.check_cancel(cancel_file)
    audit.require(Path(claim).resolve() == CLAIM.resolve() and not claim.exists(), "Existing UNKNOWN/ACK increment claim must never be resent")
    boundary()
    journal = {"protocol_version": 1, "task_id": "D103", "invocation_id": result["invocation_id"], "kind": "SOURCE_AUGUST_INSERT", "table": "index_monthly",
        "sql": sql, "sql_sha256": audit.sha_bytes(sql.encode("utf-8")), "rows": 16, "ack": "UNKNOWN", "attempted": False,
        "private_target": target.verify(), "created_at": datetime.now(timezone.utc).isoformat(), "automatic_retry": False}
    audit.save_new(claim, journal)
    operation = {"kind": "SOURCE_AUGUST_INSERT", "table": "index_monthly", "rows": 16, "claim_path": str(claim), "intent_sha256": audit.digest(claim), "ack": "UNKNOWN", "attempted": False}
    result["operations"].append(operation)
    fixture.save_progress(output, result)
    audit.check_cancel(cancel_file)
    boundary()
    journal["attempted"] = operation["attempted"] = True
    journal["attempt_started_at"] = datetime.now(timezone.utc).isoformat()
    result["attempted_source_rows"] = 16
    fixture.save_progress(claim, journal)
    fixture.save_progress(output, result)
    try:
        response = urlopen(url, timeout=20)
    except HTTPError as failed_response:
        response = failed_response
    with response:
        body = response.read(fixture.MAX_HTTP_BYTES + 1)
        status = response.status
    # Persist the actual received bytes before decoding, validation, or any ACK update.
    journal["http_response"] = {"http_status": status, "body_base64": base64.b64encode(body).decode("ascii"), "body_bytes": len(body),
        "body_sha256": audit.sha_bytes(body), "complete_within_cap": len(body) <= fixture.MAX_HTTP_BYTES, "captured_at": datetime.now(timezone.utc).isoformat()}
    fixture.save_progress(claim, journal)
    operation["response_captured"] = True
    operation["response_claim_sha256"] = audit.digest(claim)
    fixture.save_progress(output, result)
    audit.require(status == 200 and len(body) <= fixture.MAX_HTTP_BYTES, "Mutation HTTP response failed or exceeded the finite cap")
    def unique(items):
        payload = {}
        for key, value in items:
            audit.require(key not in payload, "Duplicate mutation response key")
            payload[key] = value
        return payload
    payload = json.loads(body, object_pairs_hook=unique)
    validate_dml_ack(payload)
    journal["ack"] = operation["ack"] = "ACKNOWLEDGED"
    journal["ack_at"] = datetime.now(timezone.utc).isoformat()
    journal["response"] = payload
    fixture.save_progress(claim, journal)
    operation["claim_sha256"] = audit.digest(claim)
    result["acknowledged_source_rows"] = 16
    result["acknowledged_operations"] = 1
    fixture.save_progress(output, result)


def run(args, output, result):
    audit.require(args.execute_isolated is True, "Explicit execute-isolated is required")
    reconciled, java, gate, preflight, whole, initial, proofs, ledger_before = admission_inputs(args)
    august = [row for row in whole if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") == "202608"]
    recovery.rows_from_capture(gate["bounded_source"], august)
    result["immutable_input_evidence"] = proofs
    result["initial_recovery"] = {"path": str(Path(args.initial_recovery).resolve()), "sha256": args.initial_recovery_sha256}
    result["java_initial"] = {"path": str(Path(args.java_initial).resolve()), "sha256": args.java_initial_sha256}
    result["terminal_admission"] = {"path": str(Path(args.terminal_admission).resolve()), "sha256": args.terminal_admission_sha256}
    result["whole_real_source"], result["source_increment_capture"] = reconciled["whole_real_source"], gate["bounded_source"]
    result["startup_evidence"] = reconciled["startup_evidence"]
    result["java_ledger_before"] = ledger_before
    startup = reconciled["startup_evidence"]
    target = fixture.PrivateTarget(args.private_root, args.expected_pid, startup["path"], startup["sha256"])
    result["private_target_attestation"] = target.verify()
    audit.require(result["private_target_attestation"] == gate["target"], "Original private source fixture service identity differs")
    formal, private = None, None
    try:
        formal, private = audit.ReadOnlyPG(args.cancel_file), fixture.PrivateReader(target, args.cancel_file)
        result["formal_before"] = formal_complete(formal, preflight, whole)
        result["source_before"] = fixture.private_state(private, audit.SOURCE)
        audit.require(result["source_before"] == reconciled["private_after"][audit.SOURCE], "Initial source frontier changed after readonly reconciliation")
        validate_java_snapshot(result["source_before"], java["source"]["snapshot"], True)
        result["source_schema_before"] = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=14)
        audit.require(result["source_schema_before"] == reconciled["private_schemas_after"][audit.SOURCE], "Initial source schema/order changed after readonly reconciliation")
        audit.validate_schema(result["source_schema_before"], result["source_before"], audit.literal_schema(audit.SOURCE_MODEL, "IndexMonthly"), True)
        result["initial_source_readback"] = fixture.strict_source_compare(fixture.source_rows(private), initial)
        result["outputs_before"] = private_outputs(private, initial)
        audit.require(result["outputs_before"]["tables"][audit.TARGET] == reconciled["private_after"][audit.TARGET], "Protected original private output was created/replaced")
        validate_java_snapshot(result["outputs_before"]["tables"][fixture.PRIVATE_OUTPUT], java["actual_target"])
        result["private_before"] = {audit.SOURCE: result["source_before"], **result["outputs_before"]["tables"]}
        def boundary():
            audit.check_cancel(args.cancel_file)
            for proof in proofs:
                proof_file(proof)
            fixture.validate_preflight(fixture.load(reconciled["preflight_evidence"]["path"], reconciled["preflight_evidence"]["sha256"]))
            target.verify()
            result["java_original_identity_absence"] = current_java_identity(gate["java_process"])
            audit.require(inspect_ledger(LEDGER, java["run_ids"], java["actual_target"]["targetId"]) == ledger_before, "Java ledger changed before source-only append")
            audit.require(fixture.private_state(private, audit.SOURCE) == result["source_before"], "Original source physical/WAL frontier changed before append")
            fixture.strict_source_compare(fixture.source_rows(private), initial)
            audit.require(private_outputs(private, initial) == result["outputs_before"], "Java target values/schema/frontier changed before source append")
            audit.require(formal_complete(formal, preflight, whole) == result["formal_before"], "Formal source/output changed before private append")
        submit_increment_once(target, august, CLAIM, output, result, boundary, args.cancel_file)
        result["source_after"] = fixture.wait_visible(private, 48, args.cancel_file)
        before, after = result["source_before"], result["source_after"]
        audit.require(after["physical"]["id"] == before["physical"]["id"] and after["physical"]["directoryName"] == before["physical"]["directoryName"] and
            after["physical"]["table_txn"] == before["physical"]["table_txn"] + 1 and after["wal"]["sequencerTxn"] == before["wal"]["sequencerTxn"] + 1,
            "Expected one newly acknowledged source transaction differs")
        result["source_schema_after"] = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=14)
        audit.require(result["source_schema_after"] == result["source_schema_before"], "Source schema changed during append")
        actual = fixture.source_rows(private)
        result["complete_source_readback"] = fixture.strict_source_compare(actual, whole)
        result["actual_source_rows"] = actual
        result["actual_source_double_bits"] = [{field: audit.raw_bits(row[field]) for field, kind in audit.SOURCE_TYPES.items() if kind == "DOUBLE"} for row in actual]
        result["source_census_after"] = audit.source_census(actual, list(fixture.MONTHS))
        audit.require(not result["source_census_after"]["gaps"], "Full three-month actual source census incomplete")
        result["outputs_after"] = private_outputs(private, initial)
        result["private_after"] = {audit.SOURCE: result["source_after"], **result["outputs_after"]["tables"]}
        result["formal_after"] = formal_complete(formal, preflight, whole)
        result["java_ledger_after"] = inspect_ledger(LEDGER, java["run_ids"], java["actual_target"]["targetId"])
        result["java_original_identity_absence_after"] = current_java_identity(gate["java_process"])
        result["private_target_attestation_after"] = target.verify()
        audit.require(result["outputs_after"] == result["outputs_before"] and result["formal_after"] == result["formal_before"] and
            result["java_ledger_after"] == ledger_before and result["private_target_attestation_after"] == result["private_target_attestation"], "A protected source/output/ledger/service boundary changed")
        for proof in proofs:
            proof_file(proof)
        fixture.validate_preflight(fixture.load(reconciled["preflight_evidence"]["path"], reconciled["preflight_evidence"]["sha256"]))
        result["status"] = "VERIFIED_ISOLATED_SOURCE_INCREMENT"
    finally:
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--initial-recovery", type=Path, required=True)
    parser.add_argument("--initial-recovery-sha256", required=True)
    parser.add_argument("--java-initial", type=Path, required=True)
    parser.add_argument("--java-initial-sha256", required=True)
    parser.add_argument("--terminal-admission", type=Path, required=True)
    parser.add_argument("--terminal-admission-sha256", required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = audit.output_path(args.output)
    result = {"protocol_version": 1, "task_id": "D103", "stage": "increment", "invocation_id": str(uuid.uuid4()), "status": "FAILED",
        "checked_at": datetime.now(timezone.utc).isoformat(), "operations": [], "automatic_retry": False, "original_initial_insert_ack": "UNKNOWN",
        "planned_source_rows": 16, "attempted_source_rows": 0, "acknowledged_source_rows": 0, "acknowledged_operations": 0,
        "DDL": 0, "formal_writes": 0, "private_output_writes": 0, "initial_resubmissions": 0, "source_insert_attempts_allowed": 1,
        "formal_mutated": False, "private_output_mutated": False, "java_materialization_verified": False,
        "known_limits": ["The initial INSERT ACK remains UNKNOWN; no initial claim or failed receipt is rewritten.", "The only DML is one sixteen-row August INSERT into the attested private non-deduplicating source.", "Complete raw response bytes are durably saved before recognition of a native DML ACK. UNKNOWN never permits automatic retry.", "HTTP20s is a socket timeout; PG SELECT uses an absolute20s deadline and settled-WAL observation is bounded30s.", "This source increment does not certify the later Java output/checkpoint; formal June/July historical gaps remain unchanged.", "Unconverted stored pct_chg and same-unit differences are preserved without percent scaling."]}
    audit.save_new(output, result)
    try:
        run(args, output, result)
    except BaseException as failure:
        result["status"] = "FAILED"
        result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        fixture.save_progress(output, result)
    print(json.dumps({"task_id": "D103", "status": result["status"], "output": str(output), "sha256": audit.digest(output),
        "attempted_source_rows": result["attempted_source_rows"], "acknowledged_source_rows": result["acknowledged_source_rows"],
        "acknowledged_operations": result["acknowledged_operations"], "original_initial_insert_ack": "UNKNOWN", "automatic_retry": False,
        "formal_writes": 0, "private_output_writes": 0}))
    return 0 if result["status"] == "VERIFIED_ISOLATED_SOURCE_INCREMENT" else 1


if __name__ == "__main__":
    raise SystemExit(main())
