"""Root admission for the reviewed historical OS-instance rule, not a PID whitelist."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

identity = folder / "coordinator-completed-producer-identity-review-20261006.json"
binding = audit.process_review_binding(identity, "810c456a7c024a497a375a2369000f0991779bdea8a1975a440efec006e28cad")
policy = audit.validate_process_review(binding)
assert policy["protocol_version"] == 2
assert len(policy["eligible_stop_times"]) == 466
assert policy["hard_deny_pids"] == [1960, 2504, 15144, 15328, 17572, 23980, 27172, 29156, 45192]
assert len(policy["historical_producer_pids"]) == 475
guards_path = folder / "python-historical-identity-leaf-guards-20261006.json"
guards = audit.load(guards_path)
assert guards["status"] == "PASSED" and guards["python_unique"] >= 59
assert all(guards[field] == 0 for field in ("failures", "errors", "skipped"))
assert audit.digest(repo / "tools/audit_d102_etf_view.py") == guards["script_sha256"]
assert audit.digest(repo / "tools/test_d102_script_guards.py") == guards["tests_sha256"]
review_path = folder / "coordinator-historical-identity-rule-review-20261006.json"
review = audit.load(review_path)
assert review["status"] == "PASS" and review["blockers"] == []
bound = {}
for item in review["bindings"]:
    path = audit.evidence_path(item["path"])
    assert audit.digest(path) == item["sha256"]
    bound[path] = item["sha256"]
assert bound[identity.resolve()] == binding["sha256"]
assert bound[(repo / "tools/audit_d102_etf_view.py").resolve()] == guards["script_sha256"]
assert bound[(repo / "tools/test_d102_script_guards.py").resolve()] == guards["tests_sha256"]
assert bound[guards_path.resolve()] == audit.digest(guards_path)
assert not audit.DDL_CLAIM.exists(), "No CREATE claim may be reset or retried"
paths = [identity, review_path, guards_path, folder / "python-historical-identity-leaf-guards-20261006.log",
         folder / "view-isolated-acceptance-20261006.json", folder / "view-isolated-acceptance-new-identity-20261006.json",
         folder / "failed-precreate-identity-delta-diagnostic-20261006.json", audit.GATE, audit.TYPED,
         repo / "tools/audit_d102_etf_view.py", repo / "tools/test_d102_script_guards.py"]
result = {"task_id": "D102", "checked_at": datetime.now(timezone.utc).isoformat(),
          "decision": "accepted_for_private_missing_view_with_reviewed_old_instances_only",
          "private_target": {"pid": audit.PID, "data_root": str(audit.ROOT.resolve()), "http_port": 19020, "pg_port": 18832},
          "evidence": [{"path": str(path.resolve()), "sha256": audit.digest(path)} for path in paths],
          "fresh_native_policy": "At every boundary each present historical PID must be eligible and its known UTC OS birth must be strictly later than all its verified original complete STOPs; every hard-denied PID must be absent",
          "eligible_pids": 466, "hard_denied_pids": 9, "complete_historical_pid_census": 475,
          "no_name_parent_or_executable_exemption": True, "default_and_protocol1_policy_preserved": True,
          "stable_boundaries": "Ledger contents, full historical process manifest, review binding and PID census stable; fresh current identities independently validated at both boundaries",
          "python_unique": guards["python_unique"], "max_ddl_attempts": 1,
          "source_cache_coverage_refresh_and_formal_writes": 0, "owner_invocations": 0,
          "old_failures_preserved": True, "earlier_actual_ddl_attempts": 0,
          "automatic_retry": False, "unknown_ack_policy": "Retain UNKNOWN and stop; never resend or reset",
          "new_output": "view-isolated-acceptance-history-rule-20261006.json",
          "java_live_admitted": False, "next_task_admitted": False}
audit.save_new(folder / "coordinator-private-view-history-rule-admission-20261006.json", result)
print(json.dumps({"task_id": "D102", "decision": result["decision"], "python_unique": guards["python_unique"]}))
