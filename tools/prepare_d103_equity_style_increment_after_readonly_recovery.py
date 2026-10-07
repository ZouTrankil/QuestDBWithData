"""D103 source-only append after explicitly reviewed receipt-only Java failure.

Protocol2 never invents an original JVM PID or a successful original receipt.
It binds that one final serialization failure and frozen synchronous ownership,
all eight original terminal runs, and a new zero-publication readonly recovery.
The original source INSERT ACK remains UNKNOWN. One August INSERT is permitted
only after the new recovery JVM has a real known identity and completed STOP.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import sys
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import prepare_d103_equity_style_increment_isolated as base

fixture, audit, recovery = base.fixture, base.audit, base.recovery
DIRECTORY, LEDGER = base.DIRECTORY, base.LEDGER
BASE_SHA = "98d1adf7d4ab431d390b4ec6b51b195180b88c4b7d20ed7aebc5b9fff822988e"
READONLY_RECEIPT = DIRECTORY / "java-initial-readonly-recovery-20261006.json"
ORIGINAL_GATE = DIRECTORY / "coordinator-initial-java-receipt-failure-recovery-20261006.json"
READONLY_CLASS = "com.zoutrankil.data.config.EquityStyleMonthlyInitialReadOnlyRecoveryTest"
READONLY_METHOD = "actualInitialPrefixIsRecoveredWithoutPublication"
ROLES = ("first", "same_range_replay", "cancelled_for_resume", "exact_resume", "readonly_reconcile",
         "configured_write_group", "typed_write_child", "cancelled_before_source")
EXPECTED = {role: ("CANCELLED", 0) if role in {"cancelled_for_resume", "cancelled_before_source"} else
            ("VERIFIED", 1) if role in {"configured_write_group", "typed_write_child"} else ("VERIFIED", 2) for role in ROLES}


def proof_file(item):
    path = Path(item["path"]).resolve(strict=True)
    if path.is_relative_to((audit.REPO / "src").resolve()) and path.suffix == ".java":
        audit.require(set(item) == {"path", "sha256"} and isinstance(item["sha256"], str) and fixture.re.fullmatch("[0-9a-f]{64}", item["sha256"]) and
            audit.digest(path) == item["sha256"], "Readonly project Java source SHA changed")
        return {"path": str(path), "sha256": item["sha256"]}
    if Path(item["path"]).resolve() == (audit.REPO / "tools/prepare_d103_equity_style_increment_isolated.py").resolve():
        audit.require(set(item) == {"path", "sha256"} and item["sha256"] == BASE_SHA and audit.digest(item["path"]) == BASE_SHA, "Frozen original increment helpers changed")
        return {"path": str(Path(item["path"]).resolve()), "sha256": BASE_SHA}
    return base.proof_file(item)


def inspect_original_ledger(run_ids, target_id):
    result = base.inspect_ledger(LEDGER, run_ids, target_id)
    audit.require(len(result["runs"]) == 8 and len(result["entries"]) == 18, "Only the eight original runs/eighteen terminal entries are admitted")
    with closing(sqlite3.connect(LEDGER.resolve(strict=True).as_uri() + "?mode=ro", uri=True, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
        events = [dict(row) for row in connection.execute("SELECT * FROM sync_events ORDER BY entry_id,revision LIMIT 1001").fetchall()]
        audit.require(len(events) <= 1000, "Original event cap reached")
        groups = [dict(row) for row in connection.execute("SELECT * FROM sync_group_members ORDER BY parent_run_id,ordinal LIMIT 1001").fetchall()]
        audit.require(len(groups) <= 1000, "Original group member cap reached")
    result["events"] = events
    result["groups"] = groups
    return result


def validate_original_frontier(receipt, historical, actual):
    audit.require(Path(historical["ledger_path"]).resolve() == LEDGER.resolve() and historical["run_ids"] == receipt["original_run_ids"] and
        historical.get("all_entries_terminal") is True and type(historical.get("retained_leases")) is int and historical["retained_leases"] == 0,
        "Immutable original ledger authority differs")
    for field in ("runs", "entries", "events", "groups", "leases"):
        audit.require(audit.canonical_sha(historical[field]) == audit.canonical_sha(actual[field]), "Original ledger history changed: " + field)
        audit.require(audit.canonical_sha(receipt["ledger_snapshot"][field]) == audit.canonical_sha(actual[field]), "Fresh recovery snapshot differs: " + field)
    old = historical["original_operations"]
    audit.require(len(old) == len(receipt["original_operations"]) == 8, "Immutable original operation coverage differs")
    for original, fresh in zip(old, receipt["original_operations"]):
        audit.require(all(field in fresh and audit.canonical_sha(fresh[field]) == audit.canonical_sha(value) for field, value in original.items()),
                      "Readonly recovery altered an original operation field")
    return {"all_original_current_exact": True, "runs": 8, "entries": 18, "events": len(actual["events"]),
            "groups": len(actual["groups"]), "leases": 0, "original_operations_preserved": True}


def source_version(snapshot):
    fields = ("table", "tableId", "directory", "physicalTxn", "sequenceTxn", "schemaHash")
    return audit.sha_bytes("\n".join(str(snapshot[field]) for field in fields).encode("utf-8"))


def validate_original_operations(receipt, ledger):
    operations = receipt["original_operations"]
    audit.require(isinstance(operations, list) and len(operations) == 8 and [item.get("role") for item in operations] == list(ROLES),
                  "Exactly the eight original operation roles in frozen order are required")
    run_ids = [item["run_id"] for item in operations]
    audit.require(run_ids == receipt["original_run_ids"] and len(set(run_ids)) == 8, "Original operation IDs must be unique and match the receipt")
    runs = {item["id"]: item for item in ledger["runs"]}
    entries = {item["id"]: item for item in ledger["entries"]}
    audit.require(set(runs) == set(run_ids), "Readonly recovery cannot introduce or omit a materialization/write run")
    role_ids = dict(zip(ROLES, run_ids))
    for item in operations:
        role, run_id = item["role"], item["run_id"]
        state, rows = EXPECTED[role]
        run, entry = runs[run_id], entries[run_id]
        audit.require(item.get("state") == entry["state"] == state and type(item.get("verified_rows")) is int and item["verified_rows"] == rows and
            entry["kind"] == "RUN" and entry["run_id"] == run_id, "Original operation state/row metric differs: " + role)
        audit.require(audit.canonical_sha(item["ledger_entry"]) == audit.canonical_sha(entry), "Original actual RUN entry differs: " + role)
        parent = role_ids["configured_write_group"] if role == "typed_write_child" else role_ids["cancelled_for_resume"] if role == "exact_resume" else None
        audit.require("parent_run_id" in item and item["parent_run_id"] == run["parent_run_id"] == parent, "Original parent chain differs: " + role)
        frozen = json.loads(run["frozen_json"])
        expected_mode = None if role == "configured_write_group" else "INGEST" if role == "typed_write_child" else "RECONCILE" if role == "readonly_reconcile" else "MATERIALIZE"
        audit.require("mode" in item and item["mode"] == frozen.get("mode") == expected_mode, "Original frozen mode differs: " + role)
        job = "group.prepared_writes" if role == "configured_write_group" else "write.equity_style_monthly" if role == "typed_write_child" else "data.equity_style_monthly"
        audit.require(run["job_id"] == job and run["job_version"] == 1, "Original exact job/version differs: " + role)
        payload = json.loads(entry["payload_json"])
        if state == "VERIFIED":
            verified = payload["verification"]
            audit.require(verified.get("passed") is True and all(type(verified.get(field)) is int and verified[field] == rows for field in ("expectedRows", "actualRows", "matchedRows")) and
                all(type(verified.get(field)) is int and verified[field] == 0 for field in ("mismatchedRows", "duplicateKeys", "missingKeys")),
                "Original full RUN readback proof differs: " + role)
        else:
            audit.require(not any(row["kind"] == "SLICE" and row["run_id"] == run_id for row in ledger["entries"]), "Cancelled pre-source run must have no submitted slice")
        if job == "data.equity_style_monthly":
            params = frozen["parameters"]
            audit.require(frozen["from"] == "2026-06-01" and frozen["to"] == "2026-07-01" and params["bootstrap_from"] == "2026-06-01" and
                params["target_id"] == receipt["actual_target"]["targetId"] and params["source_hash"] == receipt["source"]["rawFingerprint"] and
                params["source_version"] == source_version(receipt["source"]["snapshot"]), "Original canonical request differs from fresh exact source/target proof")
        slice_ids = {row["id"] for row in ledger["entries"] if row["run_id"] == run_id and row["kind"] == "SLICE"}
        ack_events = [row for row in ledger["events"] if row["entry_id"] in slice_ids and row["state"] == "ACKNOWLEDGED"]
        audit.require(audit.canonical_sha(item["acked_slice_receipts"]) == audit.canonical_sha(ack_events), "Original ACK event maps differ: " + role)
    return {"original_runs": 8, "original_entries": 18, "retained_leases": 0, "roles": list(ROLES),
            "scope": "Actual original RUN verification/ACK events, not reconstructed success receipts. RECONCILE acknowledgement is a readonly adapter result, not native ILP publication."}


def validate_original_failure(gate_item):
    proof = proof_file(gate_item)
    audit.require(Path(proof["path"]) == ORIGINAL_GATE.resolve(), "Only the actual retained receipt-serialization failure review is admitted")
    gate = fixture.load(proof["path"], proof["sha256"])
    audit.require(gate.get("task_id") == "D103" and gate.get("decision") == "accepted_for_readonly_receipt_recovery" and
        gate.get("original_jvm_pid_birth_not_recorded") is True and gate.get("retry_forbidden") is True and
        gate.get("all_original_operations_finished_before_serialization_failure") is True, "Explicit scoped original completion review is required")
    flags = ("synchronous_flush", "auto_flush_disabled", "retry_timeout_zero", "close_in_finally", "no_business_writer_child")
    audit.require(all(gate["sender_ownership"].get(field) is True for field in flags), "Frozen synchronous sender ownership boundary is unresolved")
    proofs = [proof]
    for key in ("failed_junit", "log", "executed_test_source", "port_source", "adapter_source", "runner_source", "typed_adapter_source",
                "original_executor_completion", "ledger_snapshot", "create_claim", "initial_source_recovery"):
        proofs.append(proof_file(gate[key]))
    execution = fixture.load(gate["original_executor_completion"]["path"], gate["original_executor_completion"]["sha256"])
    audit.require(type(execution.get("exit_code")) is int and execution["exit_code"] == 1, "Actual original Gradle completed exit1 required")
    xml = ET.parse(gate["failed_junit"]["path"]).getroot()
    audit.require(xml.tag == "testsuite" and xml.get("name") == "com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest" and
        xml.get("tests") == xml.get("failures") == "1" and xml.get("errors") == xml.get("skipped") == "0", "Original exact one-test failure required")
    cases = xml.findall("testcase")
    audit.require(len(cases) == 1 and cases[0].get("name", "").startswith("actualBoundedMaterializationUsesCanonicalRunnerTypedReadsAndComposition"), "Different failed initial test")
    failure = cases[0].find("failure")
    audit.require(failure is not None and failure.get("type") == "com.fasterxml.jackson.databind.exc.InvalidDefinitionException" and
        "java.time.YearMonth" in failure.get("message", "") and "EquityStyleMonthlyLiveAcceptanceTest.java:177" in (failure.text or ""),
        "Only the known final YearMonth serialization failure is admitted")
    stdout = xml.findtext("system-out") or ""
    audit.require("d103-private - Shutdown completed." in stdout and "d103-formal - Shutdown completed." in stdout, "Original two pools did not complete shutdown before failure")
    test_source = Path(gate["executed_test_source"]["path"]).read_text(encoding="utf-8-sig").splitlines()
    audit.require(len(test_source) >= 177 and "Files.writeString(output" in test_source[176] and "writeValueAsString(evidence)" in test_source[176], "Original failed line is not the final receipt serialization")
    port = Path(gate["port_source"]["path"]).read_text(encoding="utf-8-sig")
    compact = " ".join(port.split())
    start, end = compact.index("@Override public synchronized void send("), compact.index("public static void requireBatch(")
    send = compact[start:end]
    audit.require(send.count("sender.flush();") == 1 and "finally" in send and "sender.reset(); sender.close();" in send and
        ".disableAutoFlush().retryTimeoutMillis(0)." in compact and "senderStopped = true; senderActive = false;" in send and
        "senderStopped = false; senderActive = true; unresolved = true;" in send, "Original frozen synchronous flush/close/no-retry implementation changed")
    return gate, proofs, {"original_jvm_pid_birth_not_recorded": True, "original_native_absence_claimed": False, "original_receipt_status": "FAILED_FINAL_SERIALIZATION",
        "scope": "Only this completed foreground initial test after all terminal operations and synchronous finally-close. No generic UNKNOWN writer-stop or native PID0 proof."}


def validate_readonly_receipt(value, source_receipt, source_sha):
    audit.require(value.get("task_id") == "D103" and value.get("stage") == "readonly_recovery" and
        value.get("status") == "VERIFIED_ISOLATED_INITIAL_BY_READONLY_RECOVERY" and value.get("ledger_mutated") is False and value.get("formal_mutated") is False,
        "Actual zero-publication initial readonly recovery is required")
    for field in ("new_ddl", "new_dml", "new_ilp_batches", "new_materialization_runs"):
        audit.require(type(value.get(field)) is int and value[field] == 0, "Readonly recovery must not publish or create a run")
    audit.require(Path(value["fixture_receipt"]).resolve() == Path(source_receipt).resolve() and value["fixture_sha256"] == source_sha and
        type(value.get("key_and_full_field_comparisons")) is int and value["key_and_full_field_comparisons"] == 60 and
        type(value.get("exact_double_bit_comparisons")) is int and value["exact_double_bit_comparisons"] == 58 and
        type(value.get("double_tolerance")) is int and value["double_tolerance"] == 0 and
        type(value["source"].get("rawRows")) is int and value["source"]["rawRows"] == 32 and
        type(value["actual_target"].get("rowCount")) is int and value["actual_target"]["rowCount"] == 2, "Fresh complete source32/target2 fields and bits proof differs")
    members = value["configured_read_group"]["members"]
    audit.require(isinstance(members, list) and len(members) == 1 and members[0]["memberId"] == "styles" and members[0]["datasetId"] == "equity_style_monthly" and
        members[0]["definitionVersion"] == 1 and members[0]["status"] == "READ" and len(members[0]["page"]["rows"]) == 2, "Fresh configured typed READ2 required")


def validate_readonly_junit(item):
    proof = proof_file(item)
    root = ET.parse(proof["path"]).getroot()
    audit.require(root.tag == "testsuite" and root.get("name") == READONLY_CLASS and root.get("tests") == "1" and
        root.get("failures") == root.get("errors") == root.get("skipped") == "0", "Actual single readonly recovery JUnit success required")
    cases = root.findall("testcase")
    audit.require(len(cases) == 1 and cases[0].get("name", "").startswith(READONLY_METHOD) and not any(cases[0].find(key) is not None for key in ("failure", "error", "skipped")),
                  "Different or unsuccessful readonly recovery test")
    return proof


def admission_inputs(args):
    proofs = [proof_file({"path": str(audit.REPO / "tools/prepare_d103_equity_style_increment_isolated.py"), "sha256": BASE_SHA}),
              proof_file({"path": str(audit.REPO / "tools/prepare_d103_equity_style_isolated.py"), "sha256": recovery.PRODUCER_SHA}),
              proof_file({"path": str(audit.REPO / "tools/reconcile_d103_initial_source_readonly.py"), "sha256": base.RECOVERY_SHA})]
    reconciled = fixture.load(args.initial_recovery, args.initial_recovery_sha256)
    audit.require(reconciled.get("status") == "VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION" and reconciled.get("task_id") == "D103" and
        reconciled.get("original_insert_ack") == "UNKNOWN" and reconciled.get("source_delivery_exact_verified") is True and
        reconciled.get("stable_physical_and_wal_versions") is True and reconciled.get("java_source_read_admission_recommended") is True, "Initial readonly source delivery proof must retain UNKNOWN")
    for field in ("new_source_submissions", "retries", "DDL", "DML", "claim_writes", "old_receipt_writes"):
        audit.require(type(reconciled.get(field)) is int and reconciled[field] == 0, "Original source reconciliation must remain SELECT-only")
    failed, preflight, whole, initial, claims, original_proofs = recovery.failed_inputs(recovery.FAILED, recovery.FAILED_SHA)
    proofs.extend(original_proofs)
    audit.require(reconciled["whole_real_source"] == failed["whole_real_source"] and reconciled["initial_capture"] == failed["initial_capture"] and
        reconciled["held_increment_capture"] == failed["held_increment_capture"] and reconciled["private_target_attestation"] == failed["private_target_attestation"] and
        reconciled["private_before"] == reconciled["private_after"] and reconciled["formal_before"] == reconciled["formal_after"] == failed["formal_before"], "Original full source fixture lineage/frontier differs")
    sender = reconciled["sender_completion_proof"]["review"]
    sender_proof = recovery.completion_proof(sender["path"], sender["sha256"], failed, claims)
    proofs.extend([proof_file(sender), proof_file(sender_proof["executor_completion"]), proof_file(sender_proof["ownership"]["producer_source"])])
    java = fixture.load(args.java_readonly_recovery, args.java_readonly_recovery_sha256)
    audit.require(Path(args.java_readonly_recovery).resolve() == READONLY_RECEIPT.resolve(), "Exact actual readonly recovery receipt path required")
    validate_readonly_receipt(java, args.initial_recovery, args.initial_recovery_sha256)
    audit.require(java["preflight_sha256"] == failed["preflight_evidence"]["sha256"] and java["formal_before"] == java["formal_after"], "Fresh recovery binds another preflight or unstable formal frontier")
    original_gate, original_gate_proofs, scope = validate_original_failure(java["failed_initial_gate"])
    proofs.extend(original_gate_proofs)
    audit.require(proof_file(original_gate["initial_source_recovery"]) == {"path": str(Path(args.initial_recovery).resolve()), "sha256": args.initial_recovery_sha256},
                  "Original failed Java stage binds another source recovery")
    created = fixture.load(original_gate["create_claim"]["path"], original_gate["create_claim"]["sha256"])
    audit.require(created.get("task_id") == "D103" and created.get("ack") == "ACKNOWLEDGED" and created.get("automatic_retry") is False and
        created["snapshot"]["targetId"] == java["actual_target"]["targetId"], "Original missing-only Java target CREATE ACK identity differs")
    identity_proof = proof_file(java["jvm_identity_evidence"])
    identity = fixture.load(identity_proof["path"], identity_proof["sha256"])
    audit.require(identity.get("task_id") == "D103" and identity.get("purpose") == "SELECT-only initial receipt recovery" and type(identity.get("jvm_pid")) is int and
        identity["jvm_pid"] == java["jvm_pid"] and fixture.normalize_birth(identity["jvm_birth_utc"]) == fixture.normalize_birth(java["jvm_birth_utc"]),
        "Fresh prewritten readonly JVM identity differs")
    proofs.append(identity_proof)
    historical = fixture.load(original_gate["ledger_snapshot"]["path"], original_gate["ledger_snapshot"]["sha256"])
    actual_ledger = inspect_original_ledger(java["original_run_ids"], java["actual_target"]["targetId"])
    frontier_proof = validate_original_frontier(java, historical, actual_ledger)
    scope["original_ledger_frontier"] = frontier_proof
    gate = fixture.load(args.terminal_admission, args.terminal_admission_sha256)
    audit.require(type(gate.get("protocol_version")) is int and gate["protocol_version"] == 2 and gate.get("task_id") == "D103" and
        gate.get("decision") == "accepted_for_bounded_source_increment_after_readonly_recovery" and gate.get("retry_forbidden") is True, "Explicit protocol2 scoped increment admission required")
    bindings = {"initial_recovery": {"path": str(Path(args.initial_recovery).resolve()), "sha256": args.initial_recovery_sha256},
                "java_readonly_recovery": {"path": str(Path(args.java_readonly_recovery).resolve()), "sha256": args.java_readonly_recovery_sha256},
                "original_initial_failure": proof_file(java["failed_initial_gate"])}
    for name, expected in bindings.items():
        audit.require(proof_file(gate[name]) == expected, "Terminal protocol2 binds another original/recovery stage")
    audit.require(gate["target"] == reconciled["private_target_attestation"] and gate["bounded_source"] == failed["held_increment_capture"], "Original target or August capture differs")
    process = gate["java_process"]
    audit.require(type(process.get("pid")) is int and process["pid"] == java["jvm_pid"] and fixture.normalize_birth(process["birth_utc"]) == fixture.normalize_birth(java["jvm_birth_utc"]) and
        recovery.explicit_utc(process["stopped_observed_at"]) > recovery.explicit_utc(java["finished_at"]), "Only the known fresh readonly JVM STOP is admissible")
    native = fixture.load(process["native_evidence"]["path"], process["native_evidence"]["sha256"])
    audit.require(native.get("pid") == process["pid"] and fixture.normalize_birth(native["birth_utc"]) == fixture.normalize_birth(process["birth_utc"]) and
        native.get("original_identity_present") is False and recovery.explicit_utc(native["observed_at"]) == recovery.explicit_utc(process["stopped_observed_at"]), "Fresh readonly JVM actual native STOP evidence differs")
    base.validate_original_java_absent(native["matches"], process["pid"], process["birth_utc"], process["stopped_observed_at"])
    executor = fixture.load(gate["executor_completion"]["path"], gate["executor_completion"]["sha256"])
    audit.require(type(executor.get("exit_code")) is int and executor["exit_code"] == 0, "Fresh readonly test actual executor exit0 required")
    junit = validate_readonly_junit(gate["junit"])
    ledger = gate["ledger"]
    audit.require(Path(ledger["path"]).resolve() == LEDGER.resolve() and Path(java["ledger"]).resolve() == LEDGER.resolve() and ledger["run_ids"] == java["original_run_ids"],
                  "Only original exact eight runs in scoped ledger may be consumed")
    role_proof = validate_original_operations(java, actual_ledger)
    terminal = fixture.load(ledger["terminal_evidence"]["path"], ledger["terminal_evidence"]["sha256"])
    audit.require(Path(terminal["ledger_path"]).resolve() == LEDGER.resolve() and terminal["run_ids"] == java["original_run_ids"] and
        terminal.get("all_entries_terminal") is True and type(terminal.get("retained_leases")) is int and terminal["retained_leases"] == 0, "Actual fresh terminal ledger inspection required")
    audit.require(all(audit.canonical_sha(terminal[key]) == audit.canonical_sha(actual_ledger[key]) for key in ("runs", "entries", "leases")), "Original ledger raw rows differ from fresh terminal evidence")
    proofs.extend([proof_file(bindings["initial_recovery"]), proof_file(bindings["java_readonly_recovery"]),
        proof_file({"path": str(Path(args.terminal_admission).resolve()), "sha256": args.terminal_admission_sha256}), proof_file(process["native_evidence"]),
        proof_file(gate["executor_completion"]), junit, proof_file(ledger["terminal_evidence"])])
    return reconciled, java, gate, preflight, whole, initial, proofs, actual_ledger, scope, role_proof


def run(args, output, result):
    audit.require(args.execute_isolated is True, "Explicit execute-isolated is required")
    reconciled, java, gate, preflight, whole, initial, proofs, ledger_before, scope, role_proof = admission_inputs(args)
    august = [row for row in whole if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") == "202608"]
    recovery.rows_from_capture(gate["bounded_source"], august)
    result.update(immutable_input_evidence=proofs, original_java_failure_scope=scope, original_operation_proof=role_proof,
        initial_recovery={"path": str(Path(args.initial_recovery).resolve()), "sha256": args.initial_recovery_sha256},
        java_readonly_recovery={"path": str(Path(args.java_readonly_recovery).resolve()), "sha256": args.java_readonly_recovery_sha256},
        terminal_admission={"path": str(Path(args.terminal_admission).resolve()), "sha256": args.terminal_admission_sha256},
        whole_real_source=reconciled["whole_real_source"], source_increment_capture=gate["bounded_source"], startup_evidence=reconciled["startup_evidence"],
        java_ledger_before=ledger_before)
    startup = reconciled["startup_evidence"]
    target = fixture.PrivateTarget(args.private_root, args.expected_pid, startup["path"], startup["sha256"])
    result["private_target_attestation"] = target.verify()
    audit.require(result["private_target_attestation"] == gate["target"], "Original private service identity differs")
    formal, private = None, None
    try:
        formal, private = audit.ReadOnlyPG(args.cancel_file), fixture.PrivateReader(target, args.cancel_file)
        result["formal_before"] = base.formal_complete(formal, preflight, whole)
        result["source_before"] = fixture.private_state(private, audit.SOURCE)
        audit.require(result["source_before"] == reconciled["private_after"][audit.SOURCE], "Initial actual source frontier changed after reconciliation")
        base.validate_java_snapshot(result["source_before"], java["source"]["snapshot"], True)
        result["source_schema_before"] = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=14)
        audit.require(result["source_schema_before"] == reconciled["private_schemas_after"][audit.SOURCE], "Original fourteen-field source schema/order changed")
        audit.validate_schema(result["source_schema_before"], result["source_before"], audit.literal_schema(audit.SOURCE_MODEL, "IndexMonthly"), True)
        result["initial_source_readback"] = fixture.strict_source_compare(fixture.source_rows(private), initial)
        result["outputs_before"] = base.private_outputs(private, initial)
        audit.require(result["outputs_before"]["tables"][audit.TARGET] == reconciled["private_after"][audit.TARGET], "Protected ordinary output was created/replaced")
        base.validate_java_snapshot(result["outputs_before"]["tables"][fixture.PRIVATE_OUTPUT], java["actual_target"])
        result["private_before"] = {audit.SOURCE: result["source_before"], **result["outputs_before"]["tables"]}
        def boundary():
            audit.check_cancel(args.cancel_file)
            for proof in proofs:
                proof_file(proof)
            fixture.validate_preflight(fixture.load(reconciled["preflight_evidence"]["path"], reconciled["preflight_evidence"]["sha256"]))
            target.verify()
            result["fresh_readonly_java_identity_absence"] = base.current_java_identity(gate["java_process"])
            audit.require(inspect_original_ledger(java["original_run_ids"], java["actual_target"]["targetId"]) == ledger_before, "Original Java ledger/events changed before source append")
            audit.require(fixture.private_state(private, audit.SOURCE) == result["source_before"], "Original source physical/WAL frontier changed")
            fixture.strict_source_compare(fixture.source_rows(private), initial)
            audit.require(base.private_outputs(private, initial) == result["outputs_before"], "Java output values/schema/frontier changed")
            audit.require(base.formal_complete(formal, preflight, whole) == result["formal_before"], "Formal source/output changed")
        base.submit_increment_once(target, august, base.CLAIM, output, result, boundary, args.cancel_file)
        result["source_after"] = fixture.wait_visible(private, 48, args.cancel_file)
        before, after = result["source_before"], result["source_after"]
        audit.require(after["physical"]["id"] == before["physical"]["id"] and after["physical"]["directoryName"] == before["physical"]["directoryName"] and
            after["physical"]["table_txn"] == before["physical"]["table_txn"] + 1 and after["wal"]["sequencerTxn"] == before["wal"]["sequencerTxn"] + 1, "Expected sole source transaction differs")
        result["source_schema_after"] = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=14)
        audit.require(result["source_schema_after"] == result["source_schema_before"], "Source schema changed during bounded append")
        actual = fixture.source_rows(private)
        result["complete_source_readback"] = fixture.strict_source_compare(actual, whole)
        result["actual_source_rows"] = actual
        result["actual_source_double_bits"] = [{field: audit.raw_bits(row[field]) for field, kind in audit.SOURCE_TYPES.items() if kind == "DOUBLE"} for row in actual]
        result["source_census_after"] = audit.source_census(actual, list(fixture.MONTHS))
        audit.require(not result["source_census_after"]["gaps"], "Actual three-month full source census incomplete")
        result["outputs_after"] = base.private_outputs(private, initial)
        result["private_after"] = {audit.SOURCE: result["source_after"], **result["outputs_after"]["tables"]}
        result["formal_after"] = base.formal_complete(formal, preflight, whole)
        result["java_ledger_after"] = inspect_original_ledger(java["original_run_ids"], java["actual_target"]["targetId"])
        result["fresh_readonly_java_identity_absence_after"] = base.current_java_identity(gate["java_process"])
        result["private_target_attestation_after"] = target.verify()
        audit.require(result["outputs_after"] == result["outputs_before"] and result["formal_after"] == result["formal_before"] and
            result["java_ledger_after"] == ledger_before and result["private_target_attestation_after"] == result["private_target_attestation"], "Protected output/formal/ledger/service changed")
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
    for name in ("initial-recovery", "java-readonly-recovery", "terminal-admission"):
        parser.add_argument("--" + name, type=Path, required=True)
        parser.add_argument("--" + name + "-sha256", required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = audit.output_path(args.output)
    result = {"protocol_version": 2, "task_id": "D103", "stage": "increment", "invocation_id": str(uuid.uuid4()), "status": "FAILED",
        "checked_at": datetime.now(timezone.utc).isoformat(), "operations": [], "automatic_retry": False, "original_initial_insert_ack": "UNKNOWN",
        "original_initial_java_receipt_status": "FAILED_FINAL_SERIALIZATION", "original_initial_jvm_pid_birth_not_recorded": True,
        "planned_source_rows": 16, "attempted_source_rows": 0, "acknowledged_source_rows": 0, "acknowledged_operations": 0,
        "DDL": 0, "formal_writes": 0, "private_output_writes": 0, "initial_resubmissions": 0, "source_insert_attempts_allowed": 1,
        "formal_mutated": False, "private_output_mutated": False, "java_materialization_verified": False,
        "known_limits": ["The failed initial source ACK stays UNKNOWN and the failed original Java receipt is not reclassified as success.", "Original publishing JVM PID/birth was not retained. Scoped completed Gradle exit1 plus frozen synchronous sender-finally-close and original terminal ledger proof establish this one historical boundary; no generic native original JVM absence is claimed.", "The new readonly recovery JVM identity/complete STOP is independently verified and does not substitute an invented original process identity.", "Only one original held sixteen-row August source INSERT is allowed; complete actual response bytes precede DML ACK recognition and no UNKNOWN retry is permitted.", "Source append is not later Java output/checkpoint acceptance. Formal June/July gaps and unconverted stored-return units are preserved."]}
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
