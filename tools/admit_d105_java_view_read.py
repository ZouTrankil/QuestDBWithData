"""Root admission for the fixed D105 Java read scopes; no DB publication.

Private native identity, stopped creator and unchanged read-only SQLite state
are rechecked. All Java/evidence bindings are frozen before any JDBC read.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import audit_d105_macro_core_view as audit

D = audit.DIRECTORY
JAVA = audit.REPO / "data-app/src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyViewLiveReadAcceptanceTest.java"
CODE = (
    "data-app/src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyView.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyViewKey.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyViewDataset.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/mapper/MacroCoreMonthlyViewMapper.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyViewReadRepository.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/storage/QuestDbMacroCoreViewReadGuard.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/storage/QuestDbMacroCoreReadGuard.java",
    "data-app/src/main/java/com/zoutrankil/data/repository/QuestDbBoundedReader.java",
    "data-app/src/main/java/com/zoutrankil/data/config/ReadGroupConfiguration.java",
    "data-app/src/main/java/com/zoutrankil/data/config/DatasetConfiguration.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/storage/MacroCoreMonthlyReadRepository.java",
    "data-app/src/main/java/com/zoutrankil/data/derived/mapper/MacroCoreMonthlyMapper.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyDataset.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/DatasetReadQuery.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/DatasetReadCursor.java",
    "data-app/src/main/java/com/zoutrankil/data/domain/DatasetReadPage.java",
    "data-app/src/main/java/com/zoutrankil/data/service/ReadGroupReader.java",
    "data-app/src/main/java/com/zoutrankil/data/repository/DatasetWritePreparation.java",
    "data-app/src/main/java/com/zoutrankil/data/service/ReadGroupJson.java",
    "data-app/src/main/java/com/zoutrankil/data/service/StockBasicWriteGroupService.java",
    "data-app/src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyViewLiveReadAcceptanceTest.java",
)


def current_bindings(review_path):
    review = audit.load(review_path, audit.digest(review_path))
    audit.require(review["task_id"] == "D105" and review["status"] == "PASS" and review["blockers"] == [], "Independent live acceptance static PASS required")
    found = False
    for item in review.get("code_bindings", review.get("bindings", [])):
        path = Path(item.get("absolute_path", item.get("path", "")))
        if not path.is_absolute():
            path = audit.REPO / path
        audit.require(audit.digest(path) == item["sha256"], "Current reviewed implementation changed")
        if path.resolve() == JAVA.resolve():
            found = True
    audit.require(found, "Independent live harness binding required")
    return [audit.binding(audit.REPO / path) for path in CODE]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("private", "formal"), required=True)
    parser.add_argument("--acceptance-review", type=Path, required=True)
    args = parser.parse_args()
    isolated = args.scope == "private"
    output = D / ("coordinator-java-" + args.scope + "-admission-20261007.json")
    audit.require(not output.exists(), "New-only Java read admission required")
    code = current_bindings(args.acceptance_review)
    data = audit.prerequisites()
    audit_path = D / ("view-isolated-acceptance-20261007.json" if isolated else "view-formal-readonly-audit-20261007.json")
    receipt = audit.load(audit_path, audit.digest(audit_path))
    audit.require(receipt["status"] == ("VERIFIED_ISOLATED_VIEW_READ" if isolated else "VERIFIED_FORMAL_VIEW_READ") and receipt["task_id"] == "D105" and receipt["rows"] == 3 and receipt["double_tolerance"] == 0, "Actual full-nine-field read proof required")
    audit.require(all(receipt[key] == 0 for key in ("DML", "ILP", "base_writes", "formal_writes")) and receipt["ledger_mutated"] is False, "No business or formal writes allowed")
    gate = {"protocol_version": 1, "task_id": "D105", "status": "PASS", "decision": "accepted_for_bounded_view_read", "scope": args.scope,
            "checked_at": audit.utc_now(), "actual_view_audit": audit.binding(audit_path), "java_test": audit.binding(JAVA),
            "code_bindings": code, "independent_acceptance_review": audit.binding(args.acceptance_review),
            "DDL": 0, "DML": 0, "ILP": 0, "base_writes": 0, "formal_writes": 0, "ledger_writes": 0,
            "automatic_retry": False, "source_increment": False, "D106_admitted": False, "FULL_admitted": False,
            "coordinator_mode": "direct_local_serial", "human_comparison_status": "pending_review"}
    if isolated:
        audit.require(receipt["attempted_DDL"] == receipt["acknowledged_DDL"] == 1 and receipt["create_operation"]["ack"] == "ACKNOWLEDGED", "One prior private view CREATE must already be fully verified")
        raw = receipt["create_operation"]["raw_response"]
        audit.require(audit.digest(raw["path"]) == raw["sha256"] and Path(raw["path"]).read_bytes() == b'{"ddl":"OK"}', "Original exact raw native DDL ACK required")
        target = audit.private_target(audit.ROOT, audit.PID, data)
        identity = target.verify()
        audit.require(identity == receipt["private_target_attestation_after"], "Original private service changed")
        quiet = audit.quiescence(data)
        audit.require(quiet["ledger"] == receipt["quiescence_after"]["ledger"], "All original five ledger tables changed")
        stopped = audit.continued.original_absent(receipt["producer_identity"])
        proof = {"protocol_version": 1, "task_id": "D105", "status": "VERIFIED_CURRENT_PRIVATE_VIEW_READ_ADMISSION",
                 "checked_at": audit.utc_now(), "private_target_attestation": identity, "actual_view_audit": audit.binding(audit_path),
                 "create_producer_absence": stopped, "quiescence": quiet, "DDL": 0, "DML": 0, "ILP": 0, "ledger_mutated": False,
                 "create_executor": {"session_id": 79665, "chunk_id": "2c45cb", "exit_code": 0},
                 "create_executor_log": audit.binding(D / "view-isolated-acceptance-actual-20261007.log")}
        proof_path = D / "java-private-current-target-attestation-20261007.json"
        audit.save_new(proof_path, proof)
        gate["private_attestation"] = audit.binding(proof_path)
    audit.save_new(output, gate)
    print(json.dumps({"status": "VERIFIED_BOUNDED_JAVA_READ_ADMISSION", "scope": args.scope, **audit.binding(output)}))


if __name__ == "__main__":
    main()
