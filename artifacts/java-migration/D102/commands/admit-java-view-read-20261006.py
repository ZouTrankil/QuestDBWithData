"""Admit root's finite Java SELECT test after the actual missing-only view audit."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
sys.path.insert(0, str(repo / "tools"))
import audit_d102_etf_view as audit

path = folder / "view-isolated-acceptance-history-rule-new-attempt-20261006.json"
stage = audit.load(path)
assert stage["status"] == "VERIFIED_ISOLATED_VIEW_READ"
assert stage["formal_mutated"] is False and stage["owner_invoked"] is False
assert all(stage[field] == 0 for field in ("source_written_rows", "cache_written_rows", "coverage_written_rows"))
private = stage["isolated"]
assert private["alias_created"] is True
assert private["tables_before"] == private["tables_after"]
assert private["view_before"] == private["view_after"]
assert private["schemas_before"] == private["schemas_after"]
assert sum(item["rows"] for item in private["source_census"].values()) == 11667
assert sum(item["full_field_values"] for item in private["source_census"].values()) == 164180
assert private["rows"] == 3 and private["matched_field_values"] == 12
assert private["view_vs_original_direct_SQL"]["passed"]
claim = audit.load(audit.DDL_CLAIM)
assert claim["ack"] == "ACKNOWLEDGED" and claim["automatic_retry"] is False
assert claim["sql_sha256"] == audit.text_sha(audit.DDL) and claim["expected_pid"] == 23388
reader = repo / "src/main/java/com/zoutrankil/data/repository/QuestDbBoundedReader.java"
live = repo / "src/test/java/com/zoutrankil/data/config/EtfMarketOverviewDailyViewLiveAcceptanceTest.java"
assert audit.digest(reader) == "ebf5ab8099f17254783b0f6697444b4e244c4ad2febe11e177b5705bca1c30af"
assert audit.digest(live) == "e1926d3de4fae648f21816ba25170b3fb1f8870f6cc6d859d9085b0f3812aa8e"
paths = [path, audit.DDL_CLAIM, audit.GATE, audit.TYPED, reader, live,
         folder / "coordinator-private-view-history-rule-admission-20261006.json",
         folder / "coordinator-reader-timeout-review-20261006.json"]
result = {"task_id": "D102", "decision": "accepted_for_one_finite_java_private_and_formal_SELECT_test",
          "checked_at": datetime.now(timezone.utc).isoformat(),
          "evidence": [{"path": str(item.resolve()), "sha256": audit.digest(item)} for item in paths],
          "scope": "D102_LIVE_READ=true; one method over three actual source days, private 18832 and formal readonly; actual page/cursor/readgroup/exact binary64 comparisons",
          "java_unique_pure_precondition": 129, "actual_source_revision_during_test": False,
          "ddl_source_cache_coverage_owner_refresh_and_formal_writes": 0,
          "next_task_admitted": False, "human_review": "pending_review"}
audit.save_new(folder / "coordinator-java-view-read-admission-20261006.json", result)
print(json.dumps({"task_id": "D102", "decision": result["decision"]}))
