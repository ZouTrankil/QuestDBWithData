"""Independently compare F016 real source and isolated QuestDB schedule readback."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def verify(directory: Path) -> dict:
    run = json.loads((directory / "schedule-readback.json").read_text(encoding="utf-8"))
    sources = sorted((directory / "source").glob("source-*.json"))
    if len(sources) != 1:
        raise ValueError("Expected one real source receipt")
    source = json.loads(sources[0].read_text(encoding="utf-8"))
    if source["endpoint"] != "stock_basic" or len(source["rows"]) != 1:
        raise ValueError("Expected one stock_basic source row")
    micros = int(datetime.fromisoformat(source["logicalDate"]).replace(
        tzinfo=timezone.utc).timestamp() * 1_000_000)
    expected = dict(source["rows"][0], snapshot_micros=micros)
    if run["rows"] != [expected]:
        raise ValueError("Full source-to-QuestDB value mismatch")
    history = run["history"]
    if len(history) != 1 or history[0]["state"] != "VERIFIED" or run["dispatchCalls"] != 1:
        raise ValueError("Schedule state or repeat-dispatch count differs")
    return {"passed": True, "matched_rows": 1, "matched_fields": list(expected),
            "mismatched_rows": 0, "duplicate_keys": 0, "dispatch_calls": 1,
            "run_id": history[0]["runId"], "source_receipt": str(sources[0].relative_to(directory))}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    directory = parser.parse_args().directory
    result = verify(directory)
    (directory / "independent-values.json").write_text(json.dumps(result, ensure_ascii=False,
        indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
