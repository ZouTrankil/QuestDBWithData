"""Readonly diagnosis of a guarded pre-CREATE refusal; no mutation or retry."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

binding = audit.process_review_binding(audit.DIRECTORY / "coordinator-completed-producer-identity-review-20261006.json",
                                      "810c456a7c024a497a375a2369000f0991779bdea8a1975a440efec006e28cad")
policy = audit.validate_process_review(binding)
matches = audit.fresh_native_matches(set(policy["historical_producer_pids"]))
refused = []
for item in matches:
    end = policy["eligible_stop_times"].get(item["pid"])
    if end is None or audit.utc_identity_time(item["birth"]) <= audit.utc_identity_time(end):
        refused.append({"current": item, "complete_stop_cutoff": end,
                        "hard_denied": item["pid"] in policy["hard_deny_pids"]})
context = audit.prerequisite(audit.GATE, audit.GATE_SHA)
tables, view = audit.table_snapshot(True), audit.view_state(True)
result = {"task_id": "D102", "status": "READONLY_PRECREATE_DIAGNOSTIC", "checked_at": datetime.now(timezone.utc).isoformat(),
          "failed_evidence_sha256": audit.digest(audit.DIRECTORY / "view-isolated-acceptance-history-rule-20261006.json"),
          "process_identity_review": binding, "native_matches": matches, "currently_refused": refused,
          "private_tables": tables, "tables_equal_accepted_typed": tables == context["typed"]["tables_after"],
          "private_view": view, "create_claim_exists": audit.DDL_CLAIM.exists(), "ddl_attempts": 0,
          "owner_invocations": 0, "automatic_retry": False}
audit.save_new(Path(__file__).with_suffix(".json"), result)
print(json.dumps({"current_matches": len(matches), "currently_refused": refused,
                  "private_view": view, "create_claim_exists": result["create_claim_exists"]}))
