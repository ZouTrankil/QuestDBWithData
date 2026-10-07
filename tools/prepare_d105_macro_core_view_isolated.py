"""D105 one explicitly admitted missing-only private ordinary VIEW CREATE.

No base/source/output/ledger mutation exists. A durable UNKNOWN claim precedes
the one synchronous HTTP request; raw ACK bytes precede parsing. Never resend.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

sys.dont_write_bytecode = True
import audit_d105_macro_core_view as audit

base, common = audit.base, audit.common
require, digest = audit.require, audit.digest
SCRIPT = Path(__file__).resolve()
GUARDS = audit.REPO / "tools/test_d105_view_script_guards.py"
OUTPUT = audit.DIRECTORY / "view-isolated-acceptance-20261007.json"
CLAIM = audit.DIRECTORY / "view-create-once-20261007.claim.json"
CODE_FILES = (SCRIPT, GUARDS, Path(audit.__file__), Path(audit.legacy.__file__), Path(audit.continued.__file__), Path(base.__file__), Path(common.__file__), Path(base.native.__file__), Path(common.transport.__file__))
MAX_RESPONSE = 1024 * 1024


def evidence(item):
    require(isinstance(item, dict) and set(item) == {"path", "sha256"} and Path(item["path"]).resolve().parent == audit.DIRECTORY.resolve(), "Only exact immutable D105 commands evidence allowed")
    return audit.load_binding(item)


def save_progress(path, value):
    path = Path(path)
    old = audit.load(path, digest(path))
    require(path.parent.resolve() == audit.DIRECTORY.resolve() and old.get("invocation_id") == value.get("invocation_id"), "Only this invocation journal may be updated")
    pending = path.with_suffix(path.suffix + ".pending")
    audit.save_new(pending, value)
    os.replace(pending, path)


def save_raw(path, body):
    require(Path(path).resolve() == CLAIM.with_suffix(".response.bin").resolve() and isinstance(body, bytes), "Only this CREATE response bytes may be persisted")
    with Path(path).open("xb") as stream:
        stream.write(body); stream.flush(); os.fsync(stream.fileno())
    return {"path": str(Path(path).resolve()), "sha256": digest(path), "bytes": len(body)}


def validate_code(gate):
    require(gate.get("script") == audit.binding(SCRIPT), "Admitted CREATE implementation path/SHA changed")
    review = evidence(gate["independent_static_review"])
    require(type(review.get("protocol_version")) is int and review["protocol_version"] == 1 and review.get("task_id") == "D105" and review.get("status") == "PASS" and review.get("blockers") == [], "Independent D105 static PASS required")
    bindings = review.get("bindings")
    require(isinstance(bindings, list), "Independent current implementation/helper bindings required")
    actual = {}
    for item in bindings:
        path = Path(item.get("absolute_path", item.get("path", "")))
        if not path.is_absolute():
            path = audit.REPO / path
        path = path.resolve(strict=True)
        require(path.is_relative_to((audit.REPO / "tools").resolve()) and path not in actual and digest(path) == item.get("sha256"), "Independent tool/helper SHA or path differs")
        actual[path] = item["sha256"]
    require(all(path.resolve() in actual for path in CODE_FILES), "Complete CREATE/audit/native/transport/guards static binding set required")
    return review


def admission_inputs(args):
    gate = audit.load(args.create_admission, args.create_admission_sha256)
    require(Path(args.create_admission).resolve().parent == audit.DIRECTORY.resolve(), "Explicit new D105 coordinator admission required")
    require(type(gate.get("protocol_version")) is int and gate["protocol_version"] == 1 and gate.get("task_id") == "D105" and gate.get("decision") == "accepted_for_private_view_create_once", "Single private VIEW CREATE admission required")
    for key, expected in (("new_DDL", 1), ("new_DML", 0), ("new_ILP", 0), ("base_writes", 0), ("formal_writes", 0)):
        require(type(gate.get(key)) is int and gate[key] == expected, "Exact one-CREATE/zero-business-write scope required")
    require(gate.get("automatic_retry") is False, "UNKNOWN cannot be automatically retried")
    validate_code(gate)
    data = audit.prerequisites()
    private, formal = evidence(gate["audit"]), evidence(gate["formal_audit"])
    expected_bindings = {key: {"path": str(path.resolve()), "sha256": sha} for key, (path, sha) in audit.DATA_BINDINGS.items()}
    require(private.get("task_id") == formal.get("task_id") == "D105" and private.get("status") == "VERIFIED_ISOLATED_VIEW_ABSENCE_PREFLIGHT" and formal.get("status") == "VERIFIED_FORMAL_VIEW_READ" and private.get("d104_evidence") == formal.get("d104_evidence") == expected_bindings, "Exact D104 facts and both actual readonly audits required")
    for value in (private, formal):
        require(all(type(value.get(key)) is int and value[key] == 0 for key in ("DDL", "DML", "ILP", "base_writes", "formal_writes")) and value.get("automatic_retry") is False and value.get("ledger_mutated") is False and value.get("reference_project_mutated") is False, "Audit must contain zero mutations")
    require(private["private_before"] == private["private_after"] and private["view_metadata"] is private["view_metadata_after"] is private["view_schema"] is private["view_physical_metadata"] is None and private["actual_rows"] == [] and private["base_rows"] == data["preflight"]["expected_oracle_rows"], "Only proven missing private alias on actual final three-month base admitted")
    require(private["quiescence_before"]["ledger"] == private["quiescence_after"]["ledger"] and private["private_target_attestation"] == private["private_target_attestation_after"] == gate["target"] == data["results"]["private_target"], "Native target/original ledger preflight changed")
    require(formal["formal_before"] == formal["formal_after"] and formal["actual_rows"] == data["preflight"]["expected_oracle_rows"] and formal["view_metadata"] == formal["view_metadata_after"] and formal["view_schema"] == formal["view_schema_after"] and formal["view_physical_metadata"] == formal["view_physical_metadata_after"], "Actual stable formal view/base/oracle proof required")
    contract = audit.owner_contract()
    require(private["owner_contract"] == formal["owner_contract"] == contract, "Original owner SQL/caller identity changed")
    return gate, data, private, formal


def compare_frontier(current, previous, isolated):
    prefix = "private" if isolated else "formal"
    require(current[prefix + "_before"] == current[prefix + "_after"] == previous[prefix + "_after"], "Protected seven-table/schema frontier changed")
    for key in ("view_metadata", "view_metadata_after", "view_schema", "view_schema_after", "view_physical_metadata", "view_physical_metadata_after", "actual_rows", "base_rows", "actual_double_bits", "owner_contract", "complete_source_readback"):
        require(current[key] == previous[key], "Readonly full-value/source/view evidence changed before CREATE: " + key)


def validate_ddl(sql):
    require(isinstance(sql, str) and sql == audit.owner_contract()["private_ddl"] and ";" not in sql and len(sql.encode()) < 1024, "Only fixed missing private identity VIEW CREATE allowed")


def submit_once(target, result, boundary, cancel_file=None):
    sql = result["owner_contract"]["private_ddl"]
    validate_ddl(sql); common.check_cancel(cancel_file); boundary()
    identity = target.verify()
    require(identity == result["private_target_attestation"] and base.producer_identity() == result["producer_identity"], "Current private server/foreground producer differs")
    journal = {"task_id": "D105", "invocation_id": result["invocation_id"], "created_at": audit.utc_now(), "kind": "DDL", "table": audit.PRIVATE_VIEW, "rows": 0, "ack": "UNKNOWN", "request_started": False, "automatic_retry": False,
               "target": identity, "producer_identity": result["producer_identity"], "create_admission": result["create_admission"], "sql": sql, "sql_sha256": common.transport.sha_bytes(sql.encode())}
    audit.save_new(CLAIM, journal)  # CREATE_NEW, durable intent before HTTP.
    result["create_operation"] = {"claim_path": str(CLAIM.resolve()), "claim_sha256": digest(CLAIM), "kind": "DDL", "table": audit.PRIVATE_VIEW, "rows": 0, "ack": "UNKNOWN", "request_started": False, "sql_sha256": journal["sql_sha256"]}
    save_progress(OUTPUT, result)
    common.check_cancel(cancel_file)
    journal["request_started"] = result["create_operation"]["request_started"] = True
    result["attempted_DDL"] = 1
    save_progress(CLAIM, journal); result["create_operation"]["claim_sha256"] = digest(CLAIM); save_progress(OUTPUT, result)
    url = f"http://127.0.0.1:{base.HTTP_PORT}/exec?" + urlencode({"query": sql})
    try:
        with urlopen(url, timeout=20) as response:
            status, body = response.status, response.read(MAX_RESPONSE + 1)
    except HTTPError as failure:
        with failure:
            status, body = failure.code, failure.read(MAX_RESPONSE + 1)
    raw = save_raw(CLAIM.with_suffix(".response.bin"), body)
    journal.update(raw_response=raw, http_status=status)
    result["create_operation"].update(raw_response=raw, http_status=status)
    save_progress(CLAIM, journal); result["create_operation"]["claim_sha256"] = digest(CLAIM); save_progress(OUTPUT, result)
    require(type(status) is int and status == 200 and len(body) <= MAX_RESPONSE, "CREATE response failed/overflow; ACK UNKNOWN and resend forbidden")
    payload = json.loads(body.decode("utf-8"), object_pairs_hook=base.unique_json)
    require(payload == {"ddl": "OK"}, "Exact native DDL ACK required; UNKNOWN remains on any other payload")
    journal.update(ack="ACKNOWLEDGED", response=payload, acknowledged_at=audit.utc_now())
    save_progress(CLAIM, journal)
    result["create_operation"].update(ack="ACKNOWLEDGED", response=payload, claim_sha256=digest(CLAIM))
    result["acknowledged_DDL"] = 1; save_progress(OUTPUT, result)


def wait_view(reader, cancel_file):
    deadline = time.monotonic() + 30
    reader.visibility_deadline = deadline
    try:
        while True:
            common.check_cancel(cancel_file)
            require(time.monotonic() < deadline, "CREATE ACK view visibility deadline exceeded; no resend")
            value = audit.view_state(reader, True)
            if value is not None:
                audit.validate_view(value, True, audit.owner_contract())
                return value
            time.sleep(0.1)  # SELECT visibility polling only.
    finally:
        reader.visibility_deadline = None


def run(args, result):
    gate, data, private, formal = admission_inputs(args)
    target = audit.private_target(args.private_root, args.expected_pid, data)
    result.update(create_admission=audit.binding(args.create_admission), d104_evidence=private["d104_evidence"], private_preflight=gate["audit"], formal_preflight=gate["formal_audit"], startup_evidence=private["d104_evidence"]["startup"], private_target_attestation=target.verify(), producer_identity=base.producer_identity(), owner_contract=audit.owner_contract())
    save_progress(OUTPUT, result)  # Known OS producer birth persisted before DB I/O.
    isolated_reader, formal_reader = None, None
    try:
        result["quiescence_before"] = audit.quiescence(data)
        isolated_reader = base.PrivateReader(target, args.cancel_file); formal_reader = audit.FormalReader(args.cancel_file)
        def boundary():
            common.check_cancel(args.cancel_file)
            fresh = admission_inputs(args)
            require(fresh[0] == gate and target.verify() == result["private_target_attestation"], "Admission/service drifted at CREATE boundary")
            quiet = audit.quiescence(data)
            require(quiet["ledger"] == result["quiescence_before"]["ledger"], "Original D104 ledger drifted before CREATE")
            compare_frontier(audit.audit_target(isolated_reader, True, data, args.cancel_file, allow_missing=True), private, True)
            compare_frontier(audit.audit_target(formal_reader, False, data, args.cancel_file), formal, False)
        boundary()
        require(not CLAIM.exists() and audit.view_state(isolated_reader, True) is None, "Existing CREATE claim/view refuses automatic retry or replace")
        result["private_before"] = private["private_after"]; result["formal_before"] = formal["formal_after"]
        save_progress(OUTPUT, result)
        submit_once(target, result, boundary, args.cancel_file)
        wait_view(isolated_reader, args.cancel_file)
        result.update(audit.audit_target(isolated_reader, True, data, args.cancel_file))
        formal_after = audit.audit_target(formal_reader, False, data, args.cancel_file)
        compare_frontier(formal_after, formal, False)
        result["formal_after"] = formal_after["formal_after"]
        require(audit.protected_state(isolated_reader, True, data) == private["private_after"] == result["private_before"] == result["private_after"], "Final seven private protected tables changed during complete formal/private reads")
        require(audit.protected_state(formal_reader, False, data) == formal["formal_after"], "Final seven formal protected tables changed")
        require(audit.view_state(isolated_reader, True) == result["view_metadata_after"] and audit.view_schema(isolated_reader, True) == result["view_schema_after"] and audit.view_physical(isolated_reader, True, result["view_metadata_after"]) == result["view_physical_metadata_after"], "Final private ordinary VIEW/schema changed during formal readback")
        require(audit.view_state(formal_reader, False) == formal["view_metadata_after"] and audit.view_schema(formal_reader, False) == formal["view_schema_after"] and audit.view_physical(formal_reader, False, formal["view_metadata_after"]) == formal["view_physical_metadata_after"], "Final formal ordinary VIEW/schema changed")
        result["quiescence_after"] = audit.quiescence(data); result["private_target_attestation_after"] = target.verify()
        require(result["quiescence_before"]["ledger"] == result["quiescence_after"]["ledger"] and result["private_target_attestation"] == result["private_target_attestation_after"], "Final original ledger/current service changed")
        admission_inputs(args)
        require(digest(SCRIPT) == result["script_sha256"], "Running CREATE implementation changed")
        result.update(status="VERIFIED_ISOLATED_VIEW_READ", alias_created=True, formal_mutated=False, protected_private_tables_mutated=False, source_writes=0, output_writes=0,
                      actual_source_increment=False, actual_source_revision=False, source_window_evidence="June/July/repeat and July/August windows reuse the previously accepted D104 real August append; D105 performs no source/output writes.")
    finally:
        if isolated_reader is not None:
            isolated_reader.close()
        if formal_reader is not None:
            formal_reader.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--create-admission", type=Path, required=True)
    parser.add_argument("--create-admission-sha256", required=True)
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    require(args.execute_isolated and not OUTPUT.exists() and not CLAIM.exists(), "Explicit new single-CREATE identity required; no automatic retry")
    result = {"protocol_version": 1, "task_id": "D105", "invocation_id": str(uuid.uuid4()), "checked_at": audit.utc_now(), "status": "FAILED", "script_sha256": digest(SCRIPT), "attempted_DDL": 0, "acknowledged_DDL": 0,
              "DML": 0, "ILP": 0, "source_writes": 0, "output_writes": 0, "base_writes": 0, "formal_writes": 0, "automatic_retry": False, "owner_invoked": False, "ledger_mutated": False, "reference_project_mutated": False}
    audit.save_new(OUTPUT, result)
    try:
        run(args, result)
    except BaseException as failure:
        result["status"] = "FAILED"; result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        result["finished_at"] = audit.utc_now(); save_progress(OUTPUT, result)
    print(json.dumps({"task_id": "D105", "status": result["status"], "output": str(OUTPUT.resolve()), "sha256": digest(OUTPUT), "attempted_DDL": result["attempted_DDL"], "acknowledged_DDL": result["acknowledged_DDL"], "base_writes": 0, "formal_writes": 0}))
    return 0 if result["status"] == "VERIFIED_ISOLATED_VIEW_READ" else 1


if __name__ == "__main__":
    raise SystemExit(main())
