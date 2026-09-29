"""Independently compare captured group source rows with actual QuestDB readback."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def verify(directory: Path) -> dict:
    result = json.loads((directory / "group-readback.json").read_text(encoding="utf-8"))
    expected = {}
    source_paths = sorted(directory.glob("run-*/source-*.json"))
    for path in source_paths:
        source = json.loads(path.read_text(encoding="utf-8"))
        day = datetime.fromisoformat(source["logicalDate"]).replace(tzinfo=timezone.utc)
        micros = int((day - datetime(1970, 1, 1, tzinfo=timezone.utc)).total_seconds()) * 1_000_000
        for row in source["rows"]:
            key = (micros, row["ts_code"])
            value = {name: row[name] for name in ("ts_code", "symbol", "name", "area", "industry", "list_date")}
            value["snapshot_micros"] = micros
            if key in expected and expected[key] != value:
                raise ValueError("Conflicting source revisions require separate reconciliation")
            expected[key] = value
    actual = result["readback"]
    if not expected or len(actual) != len(expected):
        raise ValueError("Nonempty source and exact readback count required")
    observed = set()
    for row in actual:
        key = (row["snapshot_micros"], row["ts_code"])
        if key in observed or expected.get(key) != row:
            raise ValueError("Duplicate key, missing source, or full value mismatch")
        observed.add(key)
    if result["first"]["state"] != "PARTIAL" or result["resumed"]["state"] != "VERIFIED":
        raise ValueError("Expected partial-to-new-verified group recovery")
    if result["resumedExecutedJobs"] != ["sample.second"]:
        raise ValueError("Already verified first child was executed again")
    if not result["resumed"]["members"][0]["reused"]:
        raise ValueError("Missing explicit completed child reuse")
    return {"passed": True, "matched_rows": len(actual), "compared_columns": list(actual[0]),
            "source_files": [str(path.relative_to(directory)) for path in source_paths],
            "readback_file": "group-readback.json", "mismatched_rows": 0,
            "duplicate_keys": 0, "missing_keys": 0, "resumed_executed_jobs": result["resumedExecutedJobs"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence_directory", type=Path)
    args = parser.parse_args()
    proof = verify(args.evidence_directory)
    (args.evidence_directory / "independent-values.json").write_text(
        json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(proof, ensure_ascii=False))
