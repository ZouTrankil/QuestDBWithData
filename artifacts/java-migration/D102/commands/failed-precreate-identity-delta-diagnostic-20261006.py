"""Read-only diagnostic after exact current-identity refusal; no retry."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

files = sorted((audit.D101 / "java-owner-bridge").glob("*.process-*.json"))
manifest, pids = {}, set()
for path in files:
    value = audit.load(path)
    manifest[str(path)] = audit.digest(path)
    for pid in [value.get("pid"), value.get("child_pid"), value.get("actual_bridge_pid"),
                *(child.get("pid") for child in value.get("observed_children", []))]:
        if pid is not None:
            pids.add(pid)
matches = audit.fresh_native_matches(pids)
old = audit.load(audit.DIRECTORY / "coordinator-precreate-process-identity-review-20261006.json")
approved = {item["pid"]: item for item in old["approved_native_matches"]}
delta = [{"current": item, "previous_approved": approved.get(item["pid"])}
         for item in matches if approved.get(item["pid"]) != item]
context = audit.prerequisite(audit.GATE, audit.GATE_SHA)
tables, view = audit.table_snapshot(True), audit.view_state(True)
result = {"task_id": "D102", "checked_at": datetime.now(timezone.utc).isoformat(),
          "status": "READONLY_PRECREATE_DIAGNOSTIC",
          "failed_evidence_path": str(audit.DIRECTORY / "view-isolated-acceptance-new-identity-20261006.json"),
          "failed_evidence_sha256": audit.digest(audit.DIRECTORY / "view-isolated-acceptance-new-identity-20261006.json"),
          "historical_process_files": manifest, "checked_pids": sorted(pids), "native_matches": matches,
          "identity_deltas": delta, "private_tables": tables,
          "tables_equal_accepted_typed": tables == context["typed"]["tables_after"],
          "private_view": view, "create_claim_exists": audit.DDL_CLAIM.exists(),
          "ddl_attempts": 0, "owner_invocations": 0, "automatic_retry": False, "next_task_admitted": False}
audit.save_new(Path(__file__).with_suffix(".json"), result)
print(json.dumps({"native_match_count": len(matches), "identity_deltas": delta,
                  "tables_equal_accepted_typed": result["tables_equal_accepted_typed"],
                  "private_view": view, "create_claim_exists": result["create_claim_exists"]}))
