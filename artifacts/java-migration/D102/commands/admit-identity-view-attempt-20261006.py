"""Root's explicit new pre-CREATE admission; reads evidence only."""
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
identity = folder / "coordinator-precreate-process-identity-review-20261006.json"
binding = audit.process_review_binding(identity, "4e3dccf0fc6c4f12c86954bcd49cffff8ee2a1980905e8f491d35932c7dee753")
assert len(audit.validate_process_review(binding)) == 7
assert sha(repo / "tools/audit_d102_etf_view.py") == "841fa6ff015ef8d584b5d1e861da615670adc55583f799727ec41ffeaca89300"
assert sha(repo / "tools/test_d102_script_guards.py") == "69eb4ac1e80c91fd711c4c945cc65fa6bd4b2554cb0249a624d0a662bb6cf35f"
guards = audit.load(folder / "python-identity-guards-20261006.json")
assert guards["status"] == "PASSED" and guards["python_unique"] == 59
assert all(guards[field] == 0 for field in ("failures", "errors", "skipped"))
review_path = folder / "coordinator-identity-admission-delta-review-20261006.json"
assert sha(review_path) == "cb5ee503443a557a36568225e3f3b9ba66ad8e99b3d8068d56e63a321aafad22"
review = audit.load(review_path)
assert review["status"] == "PASS" and review["blockers"] == []
for item in review["bindings"]:
    assert sha(repo / item["path"]) == item["sha256"]
formal = audit.load(folder / "view-formal-readonly-audit-20261006.json")
assert formal["status"] == "VERIFIED_FORMAL_READONLY_VIEW_PARITY"
assert not audit.DDL_CLAIM.exists(), "No previous CREATE claim may be reset or retried"
evidence = [identity, review_path, folder / "python-identity-guards-20261006.json",
            folder / "python-identity-guards-20261006.log", folder / "view-formal-readonly-audit-20261006.json",
            folder / "view-isolated-acceptance-20261006.json", folder / "failed-precreate-process-diagnostic-20261006.json",
            repo / "tools/audit_d102_etf_view.py", repo / "tools/test_d102_script_guards.py", audit.GATE, audit.TYPED]
result = {"task_id": "D102", "checked_at": datetime.now(timezone.utc).isoformat(),
          "decision": "accepted_for_new_private_missing_view_attempt_only",
          "scope": "One fixed original CREATE VIEW on attested D101 private target if missing; current reused PIDs must exactly match independently approved OS identities at each boundary",
          "private_target": {"pid": audit.PID, "data_root": str(audit.ROOT.resolve()), "http_port": 19020, "pg_port": 18832},
          "evidence": [{"path": str(path.resolve()), "sha256": sha(path)} for path in evidence],
          "python_unique": 59, "reviewed_current_identities": 7, "original_completed_identities": 8,
          "source_rows": 11667, "source_full_fields": 164180, "formal_writes": 0,
          "source_cache_coverage_or_owner_writes": 0, "max_ddl_attempts": 1,
          "prior_failure_retained": True, "prior_failure_was_before_create": True,
          "automatic_retry": False, "unknown_ack_policy": "Retain UNKNOWN claim and stop; never resend or reset",
          "new_output": "view-isolated-acceptance-new-identity-20261006.json",
          "java_live_admitted": False, "next_task_admitted": False}
audit.save_new(folder / "coordinator-private-view-identity-readmission-20261006.json", result)
print(json.dumps({"task_id": "D102", "decision": result["decision"]}))
