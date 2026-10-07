"""D105 actual stopped-reader and final SELECT-only dependency closure.

Root provides actual executor completion; the original PID/birth must be absent.
The seven-table/view values and all five D104 SQLite tables are rechecked, with
no schema/business/ledger writes and no recovery or automatic CREATE retry.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import audit_d105_macro_core_view as audit
import prepare_d105_macro_core_view_isolated as creator

D = audit.DIRECTORY


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-session-id", type=int, required=True)
    parser.add_argument("--private-chunk-id", required=True)
    parser.add_argument("--formal-session-id", type=int, required=True)
    parser.add_argument("--formal-chunk-id", required=True)
    parser.add_argument("--exit-code", type=int, required=True)
    args = parser.parse_args()
    audit.require(args.exit_code == 0, "Both actual Java executors must have completed successfully")
    output = D / "java-readers-process-and-final-readonly-review-20261007.json"
    audit.require(not output.exists(), "New-only final D105 read review required")
    data = audit.prerequisites()
    created_path = D / "view-isolated-acceptance-20261007.json"
    created = audit.load(created_path, audit.digest(created_path))
    audit.require(created["status"] == "VERIFIED_ISOLATED_VIEW_READ" and created["attempted_DDL"] == created["acknowledged_DDL"] == 1, "Exactly one verified prior private VIEW CREATE required")
    readers = {}
    for scope, session, chunk in (("private", args.private_session_id, args.private_chunk_id), ("formal", args.formal_session_id, args.formal_chunk_id)):
        path = D / ("java-view-read-" + scope + "-acceptance-20261007.json")
        receipt = audit.load(path, audit.digest(path))
        audit.require(receipt["task_id"] == "D105" and receipt["scope"] == scope and receipt["status"] == "VERIFIED_" + scope.upper() + "_BOUNDED_VIEW_READ", "Both actual successful typed read scopes required")
        audit.require(all(receipt[key] == 0 for key in ("DDL", "DML", "ILP", "base_writes", "formal_writes", "owner_invocations")) and receipt["ledger_initialized"] is False and receipt["automatic_retry"] is False, "Read acceptance must have no writes or ledger startup")
        audit.require(receipt["D104_ledger_files_before"] == receipt["D104_ledger_files_after"] and receipt["ledger_mutated"] is False and receipt["actual_source_increment_during_D105"] is receipt["actual_source_revision_during_D105"] is False, "Actual reader must preserve original ledger files and reuse the accepted source window")
        source_identity = receipt["jvm_identity"]
        identity_path = D / ("java-view-read-" + scope + "-jvm-identity-20261007.json")
        audit.require(Path(source_identity["path"]).resolve() == identity_path.resolve(), "Exact pre-connection JVM identity evidence required")
        native_identity = audit.load(identity_path, source_identity["sha256"])
        audit.require(native_identity["task_id"] == "D105" and native_identity["scope"] == scope and native_identity["saved_before_connections"] is native_identity["identity_child_stopped"] is True and native_identity["jvm_pid"] == receipt["jvm_pid"] and native_identity["jvm_birth_utc"] == receipt["jvm_birth_utc"], "Fresh original JVM evidence differs")
        values = receipt["readback"]
        audit.require(values["metadata_before"] == values["metadata_after"] and values["unique_field_comparisons"] == 27 and values["nullable_double_slot_comparisons"] == 24 and values["nonnull_double_rawbit_comparisons"] == 22 and values["null_double_comparisons"] == 2, "Complete three-row rawbit/NULL parity and stable metadata required")
        audit.require(values["first_june_july_repeat_exact"] is values["july_august_later_read_window_exact"] is values["september_empty_without_failure"] is values["changed_range_cursor_rejected"] is values["typed_write_and_replacement_rejected"] is True and values["pre_cancelled_reader_invocations"] == 0, "Actual finite read/cursor/write-rejection/cancellation checks required")
        junit = D / ("java-view-read-" + scope + "-result-20261007") / "TEST-com.zoutrankil.data.config.MacroCoreMonthlyViewLiveReadAcceptanceTest.xml"
        suite = ET.parse(junit).getroot()
        audit.require(suite.attrib["tests"] == "1" and all(suite.attrib[key] == "0" for key in ("failures", "errors", "skipped")), "Each real read invocation must have actual PASS XML")
        identity = {"pid": receipt["jvm_pid"], "birth_utc": receipt["jvm_birth_utc"]}
        stopped = audit.continued.original_absent(identity)
        readers[scope] = {"java_receipt": audit.binding(path), "junit": audit.binding(junit), "jvm_identity": audit.binding(identity_path), "original_reader_absence": stopped,
                          "executor_completion": {"session_id": session, "chunk_id": chunk, "exit_code": 0, "origin": "Root transcription of actual exec/write_stdin completion", "log": audit.binding(D / ("java-view-read-" + scope + "-actual-20261007.log"))}}
    target = audit.private_target(audit.ROOT, audit.PID, data)
    creator_stopped = audit.continued.original_absent(created["producer_identity"])
    quiet_before = audit.quiescence(data)
    private_reader, formal_reader = None, None
    try:
        private_reader = audit.base.PrivateReader(target)
        formal_reader = audit.FormalReader()
        private = audit.audit_target(private_reader, True, data)
        formal = audit.audit_target(formal_reader, False, data)
        formal_original = audit.load(audit.FORMAL_OUTPUT, audit.digest(audit.FORMAL_OUTPUT))
        creator.compare_frontier(private, created, True)
        creator.compare_frontier(formal, formal_original, False)
        audit.require(audit.protected_state(private_reader, True, data) == private["private_after"] and audit.view_state(private_reader, True) == private["view_metadata_after"] and audit.view_schema(private_reader, True) == private["view_schema_after"] and audit.view_physical(private_reader, True, private["view_metadata_after"]) == private["view_physical_metadata_after"], "Final private frontier must still match after formal read")
        audit.require(audit.protected_state(formal_reader, False, data) == formal["formal_after"] and audit.view_state(formal_reader, False) == formal["view_metadata_after"] and audit.view_schema(formal_reader, False) == formal["view_schema_after"] and audit.view_physical(formal_reader, False, formal["view_metadata_after"]) == formal["view_physical_metadata_after"], "Final formal frontier must still match")
    finally:
        if private_reader is not None:
            private_reader.close()
        if formal_reader is not None:
            formal_reader.close()
    quiet_after = audit.quiescence(data)
    audit.require(quiet_before["ledger"] == quiet_after["ledger"] == created["quiescence_after"]["ledger"], "Original five SQLite tables must remain byte-value equivalent")
    state = quiet_after["ledger"]
    audit.require([len(state[key]) for key in ("runs", "entries", "events", "groups", "leases")] == [9, 21, 85, 1, 0], "Exact accepted D104 ledger history required")
    result = {"protocol_version": 1, "task_id": "D105", "status": "VERIFIED_STOPPED_READERS_AND_FINAL_READONLY_DEPENDENCY_CLOSURE",
              "checked_at": audit.utc_now(), "readers": readers, "created_view": audit.binding(created_path),
              "creator_absence": creator_stopped, "private_target_attestation": target.verify(),
              "private": private, "formal": formal, "quiescence_before": quiet_before, "quiescence_after": quiet_after,
              "D104_ledger_counts": {key: len(state[key]) for key in ("runs", "entries", "events", "groups", "leases")},
              "retained_leases": 0, "DDL": 0, "DML": 0, "ILP": 0, "source_writes": 0, "output_writes": 0,
              "formal_writes": 0, "ledger_mutated": False, "reference_project_mutated": False,
              "actual_source_increment": False, "actual_source_revision": False, "automatic_retry": False, "D106_admitted": False,
              "script": audit.binding(__file__)}
    audit.save_new(output, result)
    print(json.dumps({"status": result["status"], **audit.binding(output)}))


if __name__ == "__main__":
    main()
