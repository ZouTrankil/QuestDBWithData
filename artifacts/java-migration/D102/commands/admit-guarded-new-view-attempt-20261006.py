"""Explicit root readmission after a proven unsubmitted failure; no database calls."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

path = audit.DIRECTORY / "coordinator-precreate-new-attempt-review-20261006.json"
review = audit.load(path, "314b3ba15781c105d94960a812d65d9117e2042d931674f8b70c9fa73379c56c")
assert review["status"] == "VERIFIED_UNSUBMITTED_PRECREATE_FAILURE_REVIEW"
assert review["decision"] == "accepted_for_new_guarded_precreate_attempt_only" and review["blockers"] == []
for file, sha in review["unchanged_bindings"].items():
    assert audit.digest(audit.evidence_path(file)) == sha
binding = {"path": review["process_identity_review"]["path"], "sha256": review["process_identity_review"]["sha256"]}
policy = audit.validate_process_review(binding)
assert len(policy["eligible_stop_times"]) == 466 and len(policy["hard_deny_pids"]) == 9
assert not audit.DDL_CLAIM.exists() and not Path(review["new_output"]).exists()
record = {"task_id": "D102", "decision": "accepted_for_one_new_guarded_private_missing_view_attempt",
          "checked_at": datetime.now(timezone.utc).isoformat(), "independent_review": {"path": str(path), "sha256": audit.digest(path)},
          "evidence": review["unchanged_bindings"], "new_output": review["new_output"],
          "private_target": review["private_target"], "fresh_identity_rule_unchanged": True,
          "earlier_actual_ddl_attempts": 0, "max_ddl_attempts": 1, "automatic_retry": False,
          "unknown_ack_policy": "Retain UNKNOWN and stop; no resend or reset", "next_task_admitted": False}
audit.save_new(audit.DIRECTORY / "coordinator-guarded-new-view-attempt-admission-20261006.json", record)
print(json.dumps({"task_id": "D102", "decision": record["decision"]}))
