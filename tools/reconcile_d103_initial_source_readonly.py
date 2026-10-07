"""D103 one failed initial source delivery, reconciled by SELECT only.

The original INSERT ACK stays UNKNOWN. A coordinator-observed completed shell
and frozen synchronous request ownership are required for Java source admission.
No claim, failed receipt, DDL, DML, publisher or source fixture is rewritten.
"""
from __future__ import annotations

import argparse
import ast
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import prepare_d103_equity_style_isolated as fixture

audit = fixture.audit
DIRECTORY = fixture.DIRECTORY
FAILED = DIRECTORY / "source-fixture-initial-20261006.json"
FAILED_SHA = "13f05b7bd5586450b66f694d1e6b4582de26cb3aea6d9ede5898acd32be54ac8"
PRODUCER_SHA = "bb3fc799711ebf3637feca63f9fe29304341bfd9d4bbbab6b001b8f2722e6210"
CLAIM_SHAS = {"SOURCE_CREATE": "e6093b23285aafabdb2b5352a686dc3c2c501f5bf420e1c15ea808975c335877",
              "SOURCE_INITIAL_INSERT": "3601c017b74ad57964eb08599ab5e609fdf9f32e5d9aaa8602993bc84680a0d1"}


def explicit_utc(value):
    audit.require(isinstance(value, str) and (value.endswith("Z") or value.endswith("+00:00")), "Evidence timestamp must explicitly name UTC")
    return audit.utc_stamp(value[:-6] + "Z" if value.endswith("+00:00") else value)


def evidence(path, expected=None):
    path = Path(path).resolve(strict=True)
    audit.require(path.is_relative_to(DIRECTORY.resolve()) or path == (audit.REPO / "tools/prepare_d103_equity_style_isolated.py").resolve(), "Evidence must remain in the scoped D103 files")
    actual = audit.digest(path)
    audit.require(expected is None or actual == expected, "Immutable evidence SHA changed")
    return {"path": str(path), "sha256": actual}


def rows_from_capture(item, expected):
    path = Path(item["path"]).resolve(strict=True)
    evidence(path, item["sha256"])
    parsed = []
    for line in path.read_text(encoding="utf-8").splitlines():
        payload = json.loads(line)
        audit.require(set(payload) == {"values", "raw_double_bits"}, "Original full capture proof shape required")
        row = payload["values"]
        audit.require(payload["raw_double_bits"] == {field: audit.raw_bits(row[field]) for field, kind in audit.SOURCE_TYPES.items() if kind == "DOUBLE"}, "Original source raw bits changed")
        parsed.append(row)
    audit.require(item["physical_types"] == audit.SOURCE_TYPES and item["rows"] == len(parsed) and item["field_values"] == len(parsed) * 14 and item["canonical_records_sha256"] == audit.canonical_sha(parsed), "Original captured source content SHA differs")
    fixture.strict_source_compare(parsed, expected)
    return parsed


def failed_inputs(path, expected_sha):
    audit.require(Path(path).resolve() == FAILED.resolve() and expected_sha == FAILED_SHA, "This reconciliation is scoped to the retained actual initial failure")
    failed = fixture.load(path, expected_sha)
    audit.require(failed.get("task_id") == "D103" and failed.get("stage") == "initial" and failed.get("status") == "FAILED" and
        failed.get("automatic_retry") is False and failed.get("attempted_source_rows") == 32 and failed.get("acknowledged_source_rows") == 0,
        "Expected failed32-row UNKNOWN initial attempt required")
    audit.require(failed.get("error") == {"type": "RuntimeError", "message": "Private mutation ACK is not confirmed"}, "Failure is not the reviewed ACK recognition failure")
    operations = failed.get("operations")
    audit.require(isinstance(operations, list) and len(operations) == 2 and [item.get("kind") for item in operations] == ["SOURCE_CREATE", "SOURCE_INITIAL_INSERT"], "Exactly one CREATE and one initial INSERT attempt required")
    preflight_item = failed["preflight_evidence"]
    preflight = fixture.load(preflight_item["path"], preflight_item["sha256"])
    whole = fixture.validate_preflight(preflight)
    initial = [row for row in whole if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") in fixture.INITIAL_MONTHS]
    august = [row for row in whole if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") == "202608"]
    rows_from_capture(failed["initial_capture"], initial)
    rows_from_capture(failed["held_increment_capture"], august)
    audit.require(failed["whole_real_source"] == preflight["source_capture"], "Whole48 source provenance changed")
    proofs = [evidence(path, expected_sha), evidence(preflight_item["path"], preflight_item["sha256"]), evidence(failed["whole_real_source"]["path"], failed["whole_real_source"]["sha256"]),
              evidence(failed["initial_capture"]["path"], failed["initial_capture"]["sha256"]), evidence(failed["held_increment_capture"]["path"], failed["held_increment_capture"]["sha256"])]
    claims = {}
    for operation in operations:
        kind = operation["kind"]
        proof = evidence(operation["claim_path"], CLAIM_SHAS[kind])
        claim = fixture.load(proof["path"], proof["sha256"])
        ack = "ACKNOWLEDGED" if kind == "SOURCE_CREATE" else "UNKNOWN"
        count = 0 if kind == "SOURCE_CREATE" else 32
        audit.require(claim.get("protocol_version") == 1 and claim.get("task_id") == "D103" and claim.get("kind") == kind and claim.get("invocation_id") == failed["invocation_id"] and
            claim.get("table") == "index_monthly" and type(claim.get("rows")) is int and claim["rows"] == count and claim.get("ack") == operation.get("ack") == ack and
            claim.get("attempted") is operation.get("attempted") is True and claim.get("automatic_retry") is False and claim.get("private_target") == failed["private_target_attestation"],
            "Original mutation identity, attempt or ACK differs")
        expected_sql = fixture.CREATE_SQL if kind == "SOURCE_CREATE" else fixture.insert_sql(initial)
        audit.require(claim.get("sql") == expected_sql and claim.get("sql_sha256") == audit.sha_bytes(expected_sql.encode("utf-8")), "Original sole mutation SQL no longer matches captured real source")
        if kind == "SOURCE_CREATE":
            audit.require(operation.get("claim_sha256") == proof["sha256"] and claim.get("response") == {"ddl": "OK"}, "Original actual CREATE ACK proof differs")
        else:
            audit.require("response" not in claim and "ack_at" not in claim and "claim_sha256" not in operation, "Lost INSERT response must not be invented or ACK upgraded")
        claims[kind] = {"proof": proof, "body": claim}
        proofs.append(proof)
    startup = failed["startup_evidence"]
    fixture.load(startup["path"], startup["sha256"])
    proofs.append(evidence(startup["path"], startup["sha256"]))
    return failed, preflight, whole, initial, claims, proofs


def frozen_synchronous_ownership(path, expected_sha):
    proof = evidence(path, expected_sha)
    audit.require(expected_sha == PRODUCER_SHA, "Actual failed producer source version required")
    tree = ast.parse(Path(path).read_text(encoding="utf-8-sig"))
    functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
    submit = functions["submit_once"]
    calls = [node for node in ast.walk(submit) if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "urlopen"]
    audit.require(len(calls) == 1 and any(isinstance(node, ast.With) and any(item.context_expr is calls[0] for item in node.items) for node in ast.walk(submit)), "Actual single synchronous HTTP context ownership changed")
    audit.require(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name) and node.func.value.id == "response" and node.func.attr == "read" for node in ast.walk(submit)), "Response body was not synchronously read by the sender")
    initial_calls = [node for node in ast.walk(functions["run_initial"]) if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "submit_once"]
    audit.require(len(initial_calls) == 2 and [ast.literal_eval(node.args[1]) for node in initial_calls] == ["SOURCE_CREATE", "SOURCE_INITIAL_INSERT"], "Actual initial business operation scope changed")
    imports = [alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names]
    audit.require(not any(name.split(".")[0] in {"threading", "multiprocessing", "concurrent", "asyncio"} for name in imports), "Unexpected background submission ownership")
    process_calls = [node.func.attr for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name) and node.func.value.id == "subprocess"]
    audit.require(process_calls == ["run"], "Only synchronous native attestation subprocess ownership is admitted")
    return {"producer_source": proof, "single_synchronous_http_context": True, "synchronous_body_read_before_ACK_check": True,
            "initial_mutation_kinds": ["SOURCE_CREATE", "SOURCE_INITIAL_INSERT"], "no_background_business_submission_in_frozen_producer": True,
            "native_attestation_subprocess_run_synchronous": True, "writer_pid_birth_not_available": True,
            "scope": "Frozen task-scoped initial CLI and coordinator-observed complete shell; no fabricated sender PID0 and no general writer/lease recovery shortcut."}


def completion_proof(path, expected_sha, failed, claims):
    proof = fixture.load(path, expected_sha)
    audit.require(proof.get("protocol_version") == 1 and proof.get("task_id") == "D103" and proof.get("decision") == "accepted_for_initial_source_readonly_reconciliation", "Explicit coordinator sender-completion review required")
    required = {"failed_fixture": evidence(FAILED, FAILED_SHA), "create_claim": claims["SOURCE_CREATE"]["proof"], "insert_claim": claims["SOURCE_INITIAL_INSERT"]["proof"]}
    for field, expected in required.items():
        item = proof[field]
        audit.require(evidence(item["path"], item["sha256"]) == expected, "Completion review binds another actual initial attempt")
    audit.require(proof.get("retry_forbidden") is True and proof.get("raw_insert_response_not_retained") is True and proof.get("original_insert_ack") == "UNKNOWN", "Unknown ACK and forbidden retry must remain explicit")
    owner = proof["ownership"]
    fields = ("single_synchronous_http_attempt", "http_context_closed_before_exit", "native_subprocess_run_synchronous", "no_business_writer_child", "no_threaded_or_background_submission", "producer_pid_birth_not_recorded")
    audit.require(all(owner.get(field) is True for field in fields), "Coordinator must resolve this exact synchronous ownership boundary")
    source = proof["producer_code"]
    ownership = frozen_synchronous_ownership(source["path"], source["sha256"])
    shell = proof["shell_completion"]
    audit.require(type(shell.get("session_id")) is int and shell["session_id"] == 21749 and type(shell.get("exit_code")) is int and shell["exit_code"] == 1 and shell.get("status") == "COMPLETED", "Actual original executor session21749 completion/exit1 required")
    stopped = explicit_utc(shell["observed_at"])
    attempted = explicit_utc(claims["SOURCE_INITIAL_INSERT"]["body"]["attempt_started_at"])
    audit.require(stopped > attempted, "Shell completion predates the original sole INSERT attempt")
    shell_evidence = shell["evidence"]
    raw = fixture.load(shell_evidence["path"], shell_evidence["sha256"])
    audit.require(type(raw.get("session_id")) is int and raw["session_id"] == 21749 and type(raw.get("exit_code")) is int and raw["exit_code"] == 1,
                  "Bound actual executor completion evidence required; boolean alone is insufficient")
    audit.require(attempted < explicit_utc(raw["observed_at"]) <= stopped, "Actual executor completion chronology differs")
    raw_output = raw["output"]
    audit.require(raw_output.get("task_id") == "D103" and raw_output.get("status") == "FAILED" and
        Path(raw_output["output"]).resolve() == FAILED.resolve() and raw_output.get("sha256") == FAILED_SHA and
        type(raw_output.get("attempted_source_rows")) is int and raw_output["attempted_source_rows"] == 32 and
        type(raw_output.get("acknowledged_source_rows")) is int and raw_output["acknowledged_source_rows"] == 0 and
        type(raw_output.get("acknowledged_operations")) is int and raw_output["acknowledged_operations"] == 1 and
        type(raw_output.get("formal_writes")) is int and raw_output["formal_writes"] == 0 and
        type(raw_output.get("private_output_writes")) is int and raw_output["private_output_writes"] == 0,
        "Executor stdout must bind this unchanged failed receipt and original ACK counts")
    return {"review": evidence(path, expected_sha), "executor_completion": evidence(shell_evidence["path"], shell_evidence["sha256"]), "shell_completion": shell,
            "ownership": ownership, "no_sender_pid_birth_invented": True, "original_insert_ack": "UNKNOWN", "retry_forbidden": True}


def run(args, result):
    helper = evidence(audit.REPO / "tools/prepare_d103_equity_style_isolated.py", PRODUCER_SHA)
    failed, preflight, whole, initial, claims, proofs = failed_inputs(args.failed_receipt, args.failed_receipt_sha256)
    proofs.append(helper)
    result["immutable_input_evidence"] = proofs
    result["initial_capture"] = failed["initial_capture"]
    result["held_increment_capture"] = failed["held_increment_capture"]
    result["whole_real_source"] = failed["whole_real_source"]
    result["preflight_evidence"] = failed["preflight_evidence"]
    result["startup_evidence"] = failed["startup_evidence"]
    result["formal_historical_output_parity"] = failed["formal_historical_output_parity"]
    if not args.diagnostic_only:
        result["sender_completion_proof"] = completion_proof(args.sender_completion, args.sender_completion_sha256, failed, claims)
        proofs.extend([result["sender_completion_proof"]["review"], result["sender_completion_proof"]["executor_completion"], result["sender_completion_proof"]["ownership"]["producer_source"]])
    startup = failed["startup_evidence"]
    target = fixture.PrivateTarget(args.private_root, args.expected_pid, startup["path"], startup["sha256"])
    result["private_target_attestation"] = target.verify()
    audit.require(result["private_target_attestation"] == failed["private_target_attestation"], "Current private service identity differs from the original attempt")
    formal, private = None, None
    try:
        formal = audit.ReadOnlyPG(args.cancel_file)
        private = fixture.PrivateReader(target, args.cancel_file)
        before = fixture.formal_snapshot(formal)
        result["formal_before"] = before
        audit.require(before == failed["formal_before"] == {"tables": preflight["tables_after"], "schemas": preflight["schemas"]}, "Formal source/output identity/count/schema changed since the failed fixture")
        result["formal_source_full_readback_before"] = fixture.strict_source_compare(formal.records(preflight["source_select"]["sql"], tuple(preflight["source_select"]["params"])), whole)
        output_sql = f"SELECT {','.join(audit.OUTPUT_FIELDS)} FROM equity_style_monthly WHERE month >= %s AND month < %s ORDER BY month LIMIT 4"
        output_params = (preflight["range_from_inclusive"], preflight["range_to_exclusive"])
        output_before = formal.records(output_sql, output_params, cap=3)
        result["formal_output_full_readback_before"] = audit.compare_output(output_before, preflight["formal_existing_output_rows"])
        audit.require(result["formal_output_full_readback_before"]["passed"], "Bounded formal output fields/bits changed from the original preflight")
        result["formal_output_rows_before"] = output_before
        result["private_before"] = {table: fixture.private_state(private, table) for table in (audit.SOURCE, *fixture.PROTECTED_OUTPUTS)}
        source = result["private_before"][audit.SOURCE]
        audit.require(source["exists"] and source["settled"] and source["actual_select_count"] == 32, "Original source is not a settled exact32-row delivery")
        original_empty = failed["private_source_empty_after_create"]
        audit.require(source["physical"]["id"] == original_empty["physical"]["id"] and source["physical"]["directoryName"] == original_empty["physical"]["directoryName"], "Source physical table was replaced after the failed attempt")
        audit.require(source["physical"]["table_txn"] == source["wal"]["sequencerTxn"] == source["wal"]["writerTxn"] == 1, "Expected sole initial source transaction frontier differs")
        schemas = {}
        for table in (audit.SOURCE, *fixture.PROTECTED_OUTPUTS):
            state = result["private_before"][table]
            schemas[table] = private.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) if state["exists"] else []
            if table == audit.SOURCE:
                audit.validate_schema(schemas[table], state, audit.literal_schema(audit.SOURCE_MODEL, "IndexMonthly"), True)
            else:
                audit.require(state == failed["private_before"][table] and schemas[table] == failed["protected_output_schema_before"][table], "Protected private output identity/schema/COUNT changed after the initial failure")
        result["private_schemas_before"] = schemas
        actual = fixture.source_rows(private)
        result["source_delivery_readback"] = fixture.strict_source_compare(actual, initial)
        result["actual_source_rows"] = actual
        result["actual_source_double_bits"] = [{field: audit.raw_bits(row[field]) for field, kind in audit.SOURCE_TYPES.items() if kind == "DOUBLE"} for row in actual]
        result["source_census"] = audit.source_census(actual, list(fixture.INITIAL_MONTHS))
        audit.require(not result["source_census"]["gaps"], "Current source code/month coverage incomplete")
        result["formal_source_full_readback_after"] = fixture.strict_source_compare(formal.records(preflight["source_select"]["sql"], tuple(preflight["source_select"]["params"])), whole)
        output_after = formal.records(output_sql, output_params, cap=3)
        result["formal_output_full_readback_after"] = audit.compare_output(output_after, output_before)
        audit.require(result["formal_output_full_readback_after"]["passed"], "Bounded formal output fields/bits changed during reconciliation")
        result["formal_output_rows_after"] = output_after
        result["formal_after"] = fixture.formal_snapshot(formal)
        result["private_after"] = {table: fixture.private_state(private, table) for table in (audit.SOURCE, *fixture.PROTECTED_OUTPUTS)}
        result["private_schemas_after"] = {table: private.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) if result["private_after"][table]["exists"] else [] for table in (audit.SOURCE, *fixture.PROTECTED_OUTPUTS)}
        audit.require(result["formal_after"] == before and result["private_after"] == result["private_before"] and result["private_schemas_after"] == schemas, "Actual source/output/formal frontier changed during read-only reconciliation")
        result["private_target_attestation_after"] = target.verify()
        audit.require(result["private_target_attestation_after"] == result["private_target_attestation"], "Private native service identity changed during readback")
        for item in proofs:
            evidence(item["path"], item["sha256"])
        fixture.validate_preflight(fixture.load(failed["preflight_evidence"]["path"], failed["preflight_evidence"]["sha256"]))
        if not args.diagnostic_only:
            completion_proof(args.sender_completion, args.sender_completion_sha256, failed, claims)
        result["source_delivery_exact_verified"] = True
        result["stable_physical_and_wal_versions"] = True
        result["java_source_read_admission_recommended"] = not args.diagnostic_only
        result["status"] = "VERIFIED_READONLY_SOURCE_DIAGNOSTIC_AWAITING_SENDER_COMPLETION" if args.diagnostic_only else "VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION"
    finally:
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--failed-receipt", type=Path, default=FAILED)
    parser.add_argument("--failed-receipt-sha256", required=True)
    parser.add_argument("--sender-completion", type=Path)
    parser.add_argument("--sender-completion-sha256")
    parser.add_argument("--diagnostic-only", action="store_true")
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    if not args.diagnostic_only and not (args.sender_completion and args.sender_completion_sha256):
        parser.error("Source admission requires the new coordinator sender-completion path and SHA; otherwise use diagnostic-only")
    output = audit.output_path(args.output)
    result = {"protocol_version": 1, "task_id": "D103", "checked_at": datetime.now(timezone.utc).isoformat(), "status": "FAILED",
        "purpose": "read-only verification of the delivered initial real source, preserving lost ACK", "diagnostic_only": args.diagnostic_only,
        "original_insert_ack": "UNKNOWN", "original_create_ack": "ACKNOWLEDGED", "original_acknowledged_source_rows": 0,
        "new_source_submissions": 0, "retries": 0, "DDL": 0, "DML": 0, "claim_writes": 0, "old_receipt_writes": 0, "formal_mutated": False,
        "private_output_mutated": False, "java_materialization_verified": False, "source_delivery_exact_verified": False, "java_source_read_admission_recommended": False,
        "known_limits": ["Actual INSERT response was not retained; no payload is reconstructed and no UNKNOWN ACK is upgraded.", "Original sender PID/birth was not recorded. Source admission uses the explicit coordinator observation of completed shell session21749 exit1 plus independently checked frozen synchronous single-request ownership; no PID0 or fabricated native absence is used.", "Settled exact source delivery is not Java output materialization, a checkpoint, or permission to append August.", "Formal missing June/July output remains an unchanged historical diagnostic; no repair is performed."]}
    try:
        run(args, result)
    except BaseException as failure:
        result["status"] = "FAILED"
        result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        audit.save_new(output, result)
    print(json.dumps({"task_id": "D103", "status": result["status"], "output": str(output), "sha256": audit.digest(output), "source_delivery_exact_verified": result["source_delivery_exact_verified"],
        "original_insert_ack": "UNKNOWN", "new_source_submissions": 0, "retries": 0, "java_source_read_admission_recommended": result["java_source_read_admission_recommended"]}))
    return 0 if result["status"].startswith("VERIFIED") else 1


if __name__ == "__main__":
    raise SystemExit(main())
