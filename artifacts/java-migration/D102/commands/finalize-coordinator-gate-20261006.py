"""Root's final D102 gate and allowed status-only serial completion updates."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
docs = repo / "docs/migration-tasks-20260929"
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
load = lambda path: json.loads(path.read_text(encoding="utf-8"))
ready_path = folder / "coordinator-final-readiness-review-20261006.json"
data_path = folder / "coordinator-view-data-review-20261006.json"
assert sha(ready_path) == "33f678bc88eb79e05a6da5c5526b4eabf52827c5cfcf12b10c8a73d5c25c8ede"
assert sha(data_path) == "abf7341aaceb0aa504dedd3ccaf66347202499f95e705dd056fcd3810c301617"
ready, data = load(ready_path), load(data_path)
assert ready["status"] == data["status"] == "PASS" and ready["blockers"] == data["blockers"] == []
assert ready["decision"] == "accepted_for_serial_progress_recommendation"
assert data["recommendation"] == "accepted_for_coordinator_serial_gate"
bound, mutable_before = {}, []
for item in data["evidence"] + ready["bindings"]:
    path = Path(item["path"])
    if not path.is_absolute():
        path = repo / path
    assert sha(path) == item["sha256"], str(path)
    if item.get("mutable_coordinator_document"):
        mutable_before.append(item)
    else:
        bound[str(path.resolve())] = item["sha256"]
bound[str(ready_path)] = sha(ready_path)
bound[str(data_path)] = sha(data_path)
private = load(folder / "view-isolated-acceptance-history-rule-new-attempt-20261006.json")
live = load(folder / "java-view-read-acceptance-20261006.json")
inventory = load(folder / "unique-test-inventory-final-20261006.json")
claim = load(folder / "v_etf_market_overview_daily-create-once.json")
assert private["status"] == "VERIFIED_ISOLATED_VIEW_READ" and private["isolated"]["alias_created"]
assert live["status"] == "VERIFIED_PRIVATE_AND_FORMAL_BOUNDED_VIEW_READ"
assert inventory["java_unique"] == 130 and inventory["python_unique"] == 81
assert all(inventory[field] == 0 for field in ("failures", "errors", "skipped"))
assert claim["ack"] == "ACKNOWLEDGED" and claim["automatic_retry"] is False
assert live["actual_source_revision_during_D102_test"] is False
assert private["formal_mutated"] is False and private["owner_invoked"] is False
result_path = docs / "results/D102.json"
result = load(result_path)
assert result["data_validation_status"] == "verified" and result["coordinator_gate"] == "pending_final_review"
gate_path = folder.parent / "coordinator-review-20261006.json"
gate = {"task_id": "D102", "dataset_id": "v_etf_market_overview_daily", "checked_at": datetime.now(timezone.utc).isoformat(),
    "decision": "accepted_for_serial_progress", "implementation_status": "implemented", "data_validation_status": "verified",
    "human_review": "pending_review", "blockers": [], "execution_mode": "direct_local_serial",
    "next_task": "D103", "next_task_may_start": True,
    "delivery_matrix": result["delivery_matrix"], "java_unique": 130, "python_unique": 81,
    "private_source_rows": 11667, "private_source_full_field_values": 164180,
    "ordinary_view_creates": 1, "create_ack": "ACKNOWLEDGED", "business_data_writes": 0,
    "formal_writes": 0, "owner_invocations": 0, "actual_D102_source_revision": False,
    "double_scope": result["double_scope"], "metadata_transport_scope": result["metadata_transport_scope"],
    "identity_admission": result["identity_admission"], "historical_precreate_failed_attempts_retained": 3,
    "evidence": [{"path": path, "sha256": digest} for path, digest in sorted(bound.items())],
    "mutable_documents_before_gate": mutable_before,
    "scope": "Bounded ordinary VIEW over original physical share/daily inputs; target-local exact SQL/view/typed parity. No source sync routing change, global bit stability, provider universe, full-history freshness, production cutover or D101 formal receipt repair certification"}
with gate_path.open("x", encoding="utf-8") as output:
    json.dump(gate, output, ensure_ascii=False, indent=2)
    output.write("\n")
result.update(coordinator_gate="accepted_for_serial_progress", next_task="D103", next_task_admitted=True,
              blocker=None, updated_at=gate["checked_at"])
for path in ["artifacts/java-migration/D102/commands/coordinator-final-readiness-review-20261006.json",
             "artifacts/java-migration/D102/coordinator-review-20261006.json"]:
    if path not in result["evidence"]:
        result["evidence"].append(path)
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
mapping_path = folder.parent / "mapping-contract-20261006.json"
mapping = load(mapping_path)
mapping["status"] = "VERIFIED_BOUNDED_VIEW_READ"
mapping["coordinator_gate"] = "accepted_for_serial_progress"
mapping_path.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
card_path = docs / "11-derived/D102-v_etf_market_overview_daily.md"
text = card_path.read_text(encoding="utf-8")
text = text.replace("- 状态：实际隔离及Java验收通过，待最终协调gate；D103未准入，人工pending_review。",
    "- 状态：verified（隔离验收）；协调gate accepted_for_serial_progress，按序准许D103，人工pending_review。")
text = text.replace("最终独立复核与根协调gate待完成，人工pending_review，D103仍未准入。",
    "最终独立复核与根协调gate通过，accepted_for_serial_progress，按序准许D103，人工pending_review。")
text += "\n最终协调记录：[D102 gate](../../../artifacts/java-migration/D102/coordinator-review-20261006.json)。\n"
card_path.write_text(text, encoding="utf-8")
readme_path = folder.parent / "README.md"
text = readme_path.read_text(encoding="utf-8")
text = text.replace("状态：私有视图创建与实际 Java/正式只读验收通过，最终独立复核与协调 gate 待完成。人工复核 pending_review，D103 未准入。",
    "状态：verified（隔离验收）；最终独立复核与协调 gate 通过，accepted_for_serial_progress。人工复核 pending_review，按序准许 D103。")
text += "\n最终协调记录：[D102 gate](coordinator-review-20261006.json)。\n"
readme_path.write_text(text, encoding="utf-8")
register_path = docs / "completion-register.md"
text = register_path.read_text(encoding="utf-8")
lines = text.splitlines()
assert sum(line.startswith("| D102 |") for line in lines) == 1
for index, line in enumerate(lines):
    if line.startswith("| D102 |"):
        lines[index] = line.replace("实际验收通过，待协调gate", "verified（隔离验收）").replace(
            "实际数据通过，待最终独立/协调复核", "独立数据/交付及协调复核通过；accepted_for_serial_progress")
register_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
status_path = docs / "execution-status.md"
text = status_path.read_text(encoding="utf-8").replace("## D102 实际验收通过，待最终协调（2026-10-06）",
    "## D102 verified，按序准许 D103（2026-10-06）")
text = text.replace("最终独立数据/交付复核及根协调gate待完成，D103未准入，人工pending_review。",
    "最终独立数据/交付复核及根协调gate通过，accepted_for_serial_progress，按序准许D103，人工pending_review。剩余主线D103–D184共82项，Q仍须独立准入。")
status_path.write_text(text, encoding="utf-8")
manifest_path = docs / "manifest.json"
manifest = load(manifest_path)
manifest["current_thread_scope"]["remaining_mainline"] = "D103-D184"
task = next(item for item in manifest["tasks"] if item["id"] == "D102")
assert task["status"] == "in_progress"
task["status"] = "verified"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"task_id": "D102", "decision": gate["decision"], "gate_sha256": sha(gate_path),
                  "next_task": "D103", "rehashed_immutable_files": len(bound), "human_review": "pending_review"}))
