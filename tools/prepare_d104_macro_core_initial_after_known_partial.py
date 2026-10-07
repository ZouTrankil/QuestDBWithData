"""D104 continuation after two known CPI ACKs and a readonly visibility race.

The original failed initial receipt and two claims are immutable. This tool may
only create/insert the five never-submitted missing sources. UNKNOWN is never
admitted or retried. Metadata/COUNT visibility drift is polled with SELECT only.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

sys.dont_write_bytecode = True
import prepare_d104_macro_core_isolated as base

audit = base.audit
DIRECTORY, ROOT, PROTECTED = base.DIRECTORY, base.ROOT, base.PROTECTED
REMAINING = audit.SOURCES[1:]
FAILED = DIRECTORY / "source-fixture-initial-20261007.json"
FAILED_SHA = "516195048104e45c61ed0d7ff5796f32eca6d72dabc493c16b2a3f1e3667c534"
BASE_SHA = "23389881d8c6b8401dfb8b482378f6cdbe911f79e0b72269e46dcc6573388922"
OUTPUT = DIRECTORY / "source-fixture-initial-after-known-partial-20261007.json"
LEDGER = audit.REPO / "var/d104-java-acceptance.sqlite3"


def binding(item):
    audit.require(isinstance(item, dict) and set(item) == {"path", "sha256"}, "Exact immutable evidence binding required")
    value = base.load(item["path"], item["sha256"])
    return value


def private_state(reader, table):
    """Two metadata/WAL observations bracket independent COUNT under one bound."""
    audit.require(table in (*audit.SOURCES, *PROTECTED), "Private read whitelist required")
    deadline = time.monotonic() + base.WAL_SECONDS
    previous_deadline = reader.visibility_deadline
    if previous_deadline is not None:
        deadline = min(deadline, previous_deadline)
    reader.visibility_deadline = deadline
    sql = "SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=%s LIMIT 2"
    wal_sql = "SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=%s LIMIT 2"
    try:
        while True:
            audit.check_cancel(reader.cancel_file)
            audit.require(time.monotonic() < deadline, "Metadata/COUNT stable observation deadline exceeded; no mutation resend")
            first = reader.records(sql, (table,), cap=2)
            audit.require(len(first) <= 1, "Private metadata identity ambiguous")
            if not first:
                after = reader.records(sql, (table,), cap=2)
                if not after:
                    return {"exists": False}
                audit.require(len(after) == 1, "Private metadata identity ambiguous")
            else:
                p = first[0]
                w = audit.one(reader, wal_sql, (table,))
                count = audit.exact_count(audit.one(reader, f"SELECT count() AS n FROM {table}")["n"])
                after = reader.records(sql, (table,), cap=2)
                wal_after = reader.records(wal_sql, (table,), cap=2)
                audit.require(len(after) == 1 and after[0]["id"] == p["id"] and after[0]["directoryName"] == p["directoryName"], "Physical table disappeared or was replaced during stable observation")
                if first == after and wal_after == [w]:
                    audit.require(type(p["id"]) is int and p["id"] > 0 and isinstance(p["directoryName"], str) and p["directoryName"].strip(), "Complete private physical identity required")
                    for value in (p["wal_pending_row_count"], w["sequencerTxn"], w["writerTxn"], w["bufferedTxnSize"]):
                        audit.exact_count(value)
                    audit.require(type(p["table_suspended"]) is type(w["suspended"]) is bool, "Exact suspension flags required")
                    if p["table_txn"] is not None:
                        audit.exact_count(p["table_txn"])
                    if p["table_row_count"] is not None:
                        audit.exact_count(p["table_row_count"])
                    count_consistent = p["table_row_count"] == count or (p["table_row_count"] is None and count == 0)
                    null_txn_consistent = p["table_txn"] is not None or (count == 0 and w["sequencerTxn"] == w["writerTxn"] == 0)
                    if count_consistent and null_txn_consistent:
                        return {"exists": True, "physical": p, "wal": w, "actual_select_count": count,
                            "settled": not p["table_suspended"] and not w["suspended"] and p["wal_pending_row_count"] == w["bufferedTxnSize"] == 0 and w["sequencerTxn"] == w["writerTxn"]}
            reader.generation_observation_retries = getattr(reader, "generation_observation_retries", 0) + 1
            time.sleep(0.1)
    finally:
        reader.visibility_deadline = previous_deadline


def wait_visible(reader, table, count):
    deadline = time.monotonic() + base.WAL_SECONDS
    reader.visibility_deadline = deadline
    try:
        while True:
            audit.check_cancel(reader.cancel_file)
            audit.require(time.monotonic() < deadline, "Bounded WAL visibility deadline exceeded; no resend")
            state = private_state(reader, table)
            if state.get("exists") and state["settled"] and state["actual_select_count"] == count:
                return state
            time.sleep(0.1)
    finally:
        reader.visibility_deadline = None


def final_private_frontier(reader, current, before_readback):
    fresh = {table: private_state(reader, table) for table in current}
    audit.require(fresh == current == before_readback, "Private source/output frontier changed during full readback/oracle/final closure")
    return fresh


def validate_known_operations(failed, initial, models):
    audit.require(failed.get("task_id") == "D104" and failed.get("status") == "FAILED" and failed.get("script_sha256") == BASE_SHA and
        failed.get("error") == {"type": "RuntimeError", "message": "Null private txn requires actual empty proof"} and failed.get("automatic_retry") is False,
        "Only the preserved known-ACK visibility failure may be continued")
    for field, expected in (("attempted_operations", 2), ("acknowledged_operations", 2), ("submitted_source_rows", 2), ("acknowledged_source_rows", 2)):
        audit.require(type(failed.get(field)) is int and failed[field] == expected, "Original partial operation counts differ")
    operations = failed["operations"]
    audit.require([(item.get("table"), item.get("kind"), item.get("ack"), item.get("rows")) for item in operations] == [("cn_cpi", "DDL", "ACKNOWLEDGED", 0), ("cn_cpi", "DML", "ACKNOWLEDGED", 2)], "Only the two known CPI ACKs admitted; UNKNOWN cannot be continued")
    proofs = []
    for operation in operations:
        kind = operation["kind"]
        path = Path(operation["claim_path"]).resolve(strict=True)
        audit.require(path == base.claim_path(kind, "cn_cpi").resolve(), "Original fixed CPI claim path differs")
        claim = base.load(path, operation["claim_sha256"])
        sql = base.create_sql("cn_cpi", models["cn_cpi"]) if kind == "DDL" else base.insert_sql("cn_cpi", initial["cn_cpi"], models["cn_cpi"])
        audit.require(claim["task_id"] == "D104" and claim["invocation_id"] == failed["invocation_id"] and claim["ack"] == "ACKNOWLEDGED" and
            claim["producer_identity"] == failed["producer_identity"] and claim["target"] == failed["private_target_attestation"] and claim["preflight_evidence"] == failed["preflight_evidence"] and
            claim["startup_evidence"] == failed["startup_evidence"] and claim["sql_sha256"] == operation["sql_sha256"] == audit.transport.sha_bytes(sql.encode("utf-8")), "Original CPI claim identity/source/SQL differs")
        audit.require(claim["source_records_sha256"] == (None if kind == "DDL" else audit.canonical_sha(initial["cn_cpi"])), "Original CPI record SHA differs")
        raw = operation["raw_response"]
        raw_path = Path(raw["path"]).resolve(strict=True)
        audit.require(raw_path == path.with_suffix(".response.bin") and raw == claim["raw_response"] and audit.digest(raw_path) == raw["sha256"], "Original raw CPI response SHA differs")
        body = raw_path.read_bytes()
        audit.require(len(body) == raw["bytes"] and operation["http_status"] == claim["http_status"] == 200, "Original CPI raw response byte count/status differs")
        payload = json.loads(body.decode("utf-8"), object_pairs_hook=base.unique_json)
        base.validate_ack(payload, kind, operation["rows"])
        audit.require(payload == claim["response"] == operation["response"], "Original CPI known ACK response differs")
        proofs.extend([{"path": str(path), "sha256": operation["claim_sha256"]}, {"path": str(raw_path), "sha256": raw["sha256"]}])
    return proofs


def original_absent(producer):
    pid = producer["pid"]
    audit.require(type(pid) is int and pid > 0, "Known original producer PID required")
    birth = base.native.normalize_birth(producer["birth_utc"])
    value = base.native_query(f"$ErrorActionPreference='Stop'; $taskMatches=@(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}' | ForEach-Object {{[pscustomobject]@{{pid=$_.ProcessId;birth_utc=$_.CreationDate.ToUniversalTime().ToString('o');name=$_.Name}}}}); @{{matches=$taskMatches}}|ConvertTo-Json -Depth 4 -Compress")
    audit.require(isinstance(value["matches"], list) and len(value["matches"]) <= 1, "Original producer native match ambiguous")
    for row in value["matches"]:
        audit.require(row["pid"] == pid and base.native.normalize_birth(row["birth_utc"]) > birth, "Original producer is still alive or current identity unknown")
    return {"producer_identity": producer, "original_identity_present": False, "matches": value["matches"], "observed_at": base.utc_now()}


def admission_inputs(args):
    audit.require(audit.digest(base.__file__) == BASE_SHA, "Frozen original initial tool changed")
    failed = base.load(args.failed_initial, args.failed_initial_sha256)
    audit.require(Path(args.failed_initial).resolve() == FAILED.resolve() and args.failed_initial_sha256 == FAILED_SHA, "Only the actual retained initial failure admitted")
    gate = base.load(args.partial_admission, args.partial_admission_sha256)
    audit.require(gate.get("protocol_version") == 1 and type(gate["protocol_version"]) is int and gate.get("task_id") == "D104" and gate.get("decision") == "accepted_for_remaining_five_source_initial_only", "Explicit remaining-five admission required")
    audit.require(gate["failed_initial"] == {"path": str(FAILED.resolve()), "sha256": FAILED_SHA} and gate["preflight"] == failed["preflight_evidence"] and gate["startup"] == failed["startup_evidence"] and gate["target"] == failed["private_target_attestation"], "Admission original/source/service bindings differ")
    for field, count in (("new_DDL", 5), ("new_DML", 5), ("new_source_rows", 21), ("cpi_resubmissions", 0), ("formal_writes", 0), ("private_output_writes", 0)):
        audit.require(type(gate.get(field)) is int and gate[field] == count, "Exact continuation mutation scope required")
    audit.require(gate.get("automatic_retry") is False, "Continuation automatic retry forbidden")
    partial = binding(gate["partial_audit"])
    audit.require(partial.get("task_id") == "D104" and partial.get("status") == "VERIFIED_KNOWN_ACKNOWLEDGED_INITIAL_CPI_BY_READONLY_REVIEW" and partial["failed_initial"] == gate["failed_initial"] and
        partial["preflight"] == gate["preflight"] and partial["startup"] == gate["startup"] and partial["target"] == gate["target"] and partial["private_before"] == partial["private_after"] and
        partial["formal_before"] == partial["formal_after"] == failed["formal_before"] and partial.get("retry_forbidden") is True and partial.get("unknown_ack_admitted") is False, "Independent readonly partial source review differs")
    stop = binding(gate["native_stop"])
    execution = binding(gate["executor_completion"])
    audit.require(gate["native_stop"] == partial["native_stop"] and gate["executor_completion"] == partial["executor_completion"] and
        stop["producer_identity"] == failed["producer_identity"] and stop["original_identity_present"] is False and execution.get("task_id") == "D104" and
        type(execution.get("exit_code")) is int and execution["exit_code"] == 1 and execution.get("status") == "COMPLETED" and execution.get("session_id") == 41796 and execution.get("chunk_id") == "0c35a3", "Actual known original producer completion proof required")
    original_birth = base.native.normalize_birth(failed["producer_identity"]["birth_utc"])
    audit.require(all(row["pid"] == failed["producer_identity"]["pid"] and base.native.normalize_birth(row["birth_utc"]) > original_birth for row in stop["matches"]), "Historical stop still contains original identity")
    log = execution["log"]
    audit.require(Path(log["path"]).resolve().is_relative_to(DIRECTORY.resolve()) and audit.digest(log["path"]) == log["sha256"], "Original executor log changed")
    preflight = binding(gate["preflight"]); base.validate_preflight(preflight)
    whole = base.captured_sources(preflight); models = preflight["original_models"]
    initial = base.initial_sources(whole, models)
    old_proofs = validate_known_operations(failed, initial, models)
    audit.require(partial["original_known_ack_operations"] == failed["operations"] and partial["original_producer"] == failed["producer_identity"], "Independent old operation provenance differs")
    audit.require(partial["private_after"]["cn_cpi"]["settled"] and partial["private_after"]["cn_cpi"]["actual_select_count"] == 2 and all(not partial["private_after"][table]["exists"] for table in (*REMAINING, *PROTECTED)), "Only existing CPI2 and five missing sources/output absence admitted")
    base.strict_compare(partial["existing_cpi_rows"], initial["cn_cpi"], "cn_cpi", models["cn_cpi"])
    return gate, partial, failed, preflight, whole, initial, old_proofs


def claim_path(kind, table):
    audit.require(kind in ("DDL", "DML") and table in REMAINING, "Only five remaining source claims admitted; CPI never resubmitted")
    return DIRECTORY / f"source-fixture-initial-remaining-{kind.lower()}-{table}-20261007.claim.json"


def submit_once(target, kind, table, model, rows, output, result, boundary, cancel_file=None):
    audit.require(table in REMAINING and kind in ("DDL", "DML"), "Continuation cannot send CPI or output mutations")
    sql = base.create_sql(table, model) if kind == "DDL" else base.insert_sql(table, rows, model)
    count = 0 if kind == "DDL" else len(rows)
    audit.require(";" not in sql and len(sql.encode("utf-8")) <= base.MAX_SQL_BYTES and sum(item["kind"] == kind for item in result["remaining_operations"]) < 5, "Bounded remaining mutation budget required")
    audit.check_cancel(cancel_file); boundary(); target_id = target.verify()
    audit.require(target_id == result["private_target_attestation"] and base.producer_identity() == result["producer_identity"], "Dedicated service/producer identity differs")
    claim = claim_path(kind, table)
    journal = {"task_id": "D104", "invocation_id": result["invocation_id"], "stage": "remaining_initial_after_known_partial", "kind": kind, "table": table, "rows": count,
        "ack": "UNKNOWN", "created_at": base.utc_now(), "automatic_retry": False, "target": target_id, "producer_identity": result["producer_identity"], "partial_admission": result["partial_admission"],
        "failed_initial": result["failed_initial"], "sql_sha256": audit.transport.sha_bytes(sql.encode("utf-8")), "source_records_sha256": None if kind == "DDL" else audit.canonical_sha(rows)}
    audit.save_new(claim, journal)
    operation = {"kind": kind, "table": table, "rows": count, "ack": "UNKNOWN", "claim_path": str(claim), "claim_sha256": audit.digest(claim), "sql_sha256": journal["sql_sha256"]}
    result["remaining_operations"].append(operation); result["new_attempted_operations"] += 1; result["new_submitted_source_rows"] += count
    base.save_progress(output, result)
    url = f"http://127.0.0.1:{base.HTTP_PORT}/exec?" + urlencode({"query": sql})
    try:
        with urlopen(url, timeout=20) as response:
            status = response.status; body = response.read(base.MAX_RESPONSE_BYTES + 1)
    except HTTPError as failure:
        with failure:
            status = failure.code; body = failure.read(base.MAX_RESPONSE_BYTES + 1)
    raw = base.save_raw(claim.with_suffix(".response.bin"), body)
    journal.update(raw_response=raw, http_status=status)
    operation.update(raw_response=raw, http_status=status)
    base.save_progress(claim, journal); operation["claim_sha256"] = audit.digest(claim); base.save_progress(output, result)
    audit.require(type(status) is int and status == 200 and len(body) <= base.MAX_RESPONSE_BYTES, "Native mutation error/overflow: ACK stays UNKNOWN and resend forbidden")
    payload = json.loads(body.decode("utf-8"), object_pairs_hook=base.unique_json)
    base.validate_ack(payload, kind, count)
    journal.update(ack="ACKNOWLEDGED", ack_at=base.utc_now(), response=payload)
    base.save_progress(claim, journal)
    operation.update(ack="ACKNOWLEDGED", response=payload, claim_sha256=audit.digest(claim))
    result["new_acknowledged_operations"] += 1; result["new_acknowledged_source_rows"] += count
    base.save_progress(output, result)


def run(args, output, result):
    audit.require(args.execute_isolated is True, "Explicit remaining-source execution required")
    gate, partial, failed, preflight, whole, initial, old_proofs = admission_inputs(args)
    result.update(failed_initial=gate["failed_initial"], original_initial_status="FAILED_PRESERVED", partial_admission={"path": str(Path(args.partial_admission).resolve()), "sha256": args.partial_admission_sha256},
        partial_readonly_audit=gate["partial_audit"], preflight_evidence=gate["preflight"], startup_evidence=gate["startup"], original_known_ack_operations=failed["operations"], original_claim_and_raw_response_bindings=old_proofs,
        whole_real_sources=preflight["source_captures"], full_sources_sha256=preflight["full_sources_sha256"], increment_admission_contract=base.INCREMENT_CONTRACT)
    target = base.PrivateTarget(args.private_root, args.expected_pid, gate["startup"]["path"], gate["startup"]["sha256"])
    result["private_target_attestation"] = target.verify()
    audit.require(result["private_target_attestation"] == gate["target"], "Admission private service differs")
    result["producer_identity"] = base.producer_identity(); base.save_progress(output, result)
    formal = private = None
    try:
        result["original_producer_native_absence"] = original_absent(failed["producer_identity"])
        audit.require(not LEDGER.exists(), "Java ledger already exists; source preparation cannot overlap Java")
        formal, private = audit.ReadOnlyPG(args.cancel_file), base.PrivateReader(target, args.cancel_file)
        result["formal_before"] = base.formal_complete(formal, preflight, whole)
        result["private_before"] = {table: private_state(private, table) for table in (*audit.SOURCES, *PROTECTED)}
        audit.require(result["formal_before"] == partial["formal_after"] and result["private_before"] == partial["private_after"], "Actual partial/formal frontier changed since independent review")
        base.strict_compare(base.source_rows(private, "cn_cpi", preflight["original_models"]["cn_cpi"]), initial["cn_cpi"], "cn_cpi", preflight["original_models"]["cn_cpi"])
        current = dict(result["private_before"])
        for table in REMAINING:
            for kind in ("DDL", "DML"):
                audit.require(not base.claim_path(kind, table).exists() and not claim_path(kind, table).exists(), "Remaining source was previously submitted; no resend")
        def boundary():
            audit.check_cancel(args.cancel_file)
            audit.require(audit.digest(__file__) == result["script_sha256"] and audit.digest(base.__file__) == BASE_SHA and all(audit.digest(path) == expected for path, expected in result["helper_sha256"].items()), "Frozen script/helper changed")
            admission_inputs(args); original_absent(failed["producer_identity"]); target.verify()
            audit.require(not LEDGER.exists(), "Java writer appeared during preparation")
            audit.require(base.formal_complete(formal, preflight, whole) == result["formal_before"] and {table: private_state(private, table) for table in current} == current, "Protected formal/private frontier changed")
            base.strict_compare(base.source_rows(private, "cn_cpi", preflight["original_models"]["cn_cpi"]), initial["cn_cpi"], "cn_cpi", preflight["original_models"]["cn_cpi"])
        models = preflight["original_models"]
        for table in REMAINING:
            submit_once(target, "DDL", table, models[table], [], output, result, boundary, args.cancel_file)
            current[table] = wait_visible(private, table, 0)
            schema = private.records("SELECT * FROM table_columns(%s)", (table,), cap=100)
            audit.validate_schema(schema, current[table], models[table])
            submit_once(target, "DML", table, models[table], initial[table], output, result, boundary, args.cancel_file)
            current[table] = wait_visible(private, table, len(initial[table]))
            base.strict_compare(base.source_rows(private, table, models[table]), initial[table], table, models[table])
        result["private_after"] = {table: private_state(private, table) for table in current}
        result["private_schemas_after"] = {table: private.records("SELECT * FROM table_columns(%s)", (table,), cap=100) for table in audit.SOURCES}
        for table in audit.SOURCES:
            audit.validate_schema(result["private_schemas_after"][table], result["private_after"][table], models[table])
        result["actual_source_rows"] = {table: base.source_rows(private, table, models[table]) for table in audit.SOURCES}
        result["complete_source_readback"] = {table: base.strict_compare(result["actual_source_rows"][table], initial[table], table, models[table]) for table in audit.SOURCES}
        result["actual_source_double_bits"] = {table: [{field: audit.raw_bits(row[field]) for field, kind in models[table]["schema"].items() if kind == "DOUBLE"} for row in result["actual_source_rows"][table]] for table in audit.SOURCES}
        result["initial_source_census"] = audit.source_census(result["actual_source_rows"], models, list(base.INITIAL_MONTHS))
        oracle, _proof = audit.original_oracle(result["actual_source_rows"], list(base.INITIAL_MONTHS))
        audit.require(audit.compare_output(oracle, preflight["expected_oracle_rows"][:2])["passed"], "Complete initial original Python oracle differs")
        result["initial_expected_oracle_rows"] = oracle
        result["formal_after"] = base.formal_complete(formal, preflight, whole)
        result["private_target_attestation_after"] = target.verify(); result["producer_identity_after"] = base.producer_identity()
        result["original_producer_native_absence_after"] = original_absent(failed["producer_identity"])
        result["private_after_before_full_readback"] = result["private_after"]
        result["private_after"] = final_private_frontier(private, current, result["private_after_before_full_readback"])
        audit.require(result["private_after"] == current and result["private_after"]["cn_cpi"] == result["private_before"]["cn_cpi"] and result["formal_after"] == result["formal_before"] and result["private_target_attestation_after"] == gate["target"] and result["producer_identity_after"] == result["producer_identity"] and not LEDGER.exists(), "Protected original CPI/output/formal/service/ledger changed")
        admission_inputs(args)
        audit.require(result["new_attempted_operations"] == result["new_acknowledged_operations"] == 10 and result["new_submitted_source_rows"] == result["new_acknowledged_source_rows"] == 21, "All ten remaining ACKs and21 real rows required")
        result.update(status="VERIFIED_ISOLATED_SOURCE_INITIAL", total_known_acknowledged_operations=12, total_known_acknowledged_source_rows=23, source_only_rows=23, source_only_field_values=294, source_only_double_slots=270,
            readonly_generation_observation_retries=getattr(private, "generation_observation_retries", 0), finished_at=base.utc_now(), producer_sender_stopped=True,
            producer_sender_stop_scope="All foreground synchronous HTTP contexts are closed; this is no claim of current Python OS absence. Original known producer complete/absence is separately bound.")
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
    parser.add_argument("--failed-initial", type=Path, required=True)
    parser.add_argument("--failed-initial-sha256", required=True)
    parser.add_argument("--partial-admission", type=Path, required=True)
    parser.add_argument("--partial-admission-sha256", required=True)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = args.output.resolve()
    audit.require(output == OUTPUT.resolve() and not output.exists(), "One new continuation identity required; no overwrite/retry")
    result = {"task_id": "D104", "protocol_version": 1, "stage": "remaining_initial_after_known_partial", "status": "FAILED", "invocation_id": str(uuid.uuid4()), "checked_at": base.utc_now(),
        "automatic_retry": False, "automatic_resend_permitted": False, "formal_writes": 0, "private_output_writes": 0, "formal_mutated": False, "reference_project_mutated": False, "cpi_resubmissions": 0,
        "remaining_operations": [], "new_attempted_operations": 0, "new_acknowledged_operations": 0, "new_submitted_source_rows": 0, "new_acknowledged_source_rows": 0,
        "script_sha256": audit.digest(__file__), "helper_sha256": {str(Path(module.__file__).resolve()): audit.digest(module.__file__) for module in (base, audit, audit.transport, base.native)}}
    audit.save_new(output, result)
    try:
        run(args, output, result)
    except BaseException as failure:
        result["status"] = "FAILED"; result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        if audit.digest(__file__) != result["script_sha256"] or any(audit.digest(path) != expected for path, expected in result["helper_sha256"].items()):
            result["status"] = "FAILED"; result["error"] = {"type": "FrozenCodeChanged", "message": "Frozen script/helper changed during continuation"}
        base.save_progress(output, result)
    print(json.dumps({"task_id": "D104", "status": result["status"], "output": str(output), "sha256": audit.digest(output), "old_ACK": 2 if "original_known_ack_operations" in result else 0, "new_ACK": result["new_acknowledged_operations"], "cpi_resubmissions": 0, "formal_writes": 0, "private_output_writes": 0}))
    return 0 if result["status"] == "VERIFIED_ISOLATED_SOURCE_INITIAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
