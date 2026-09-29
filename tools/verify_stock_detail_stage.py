"""Independently compare D002 source receipts to the retained QuestDB stage readback."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
import sys


SOURCE_TO_STAGE = {
    "ts_code": "tsCode", "symbol": "symbol", "name": "name", "area": "area",
    "industry": "industry", "fullname": "fullname", "enname": "enname",
    "cnspell": "cnspell", "market": "market", "exchange": "exchange",
    "curr_type": "currType", "list_status": "listStatus", "list_date": "listDate",
    "delist_date": "delistDate", "is_hs": "isHs", "act_name": "actName",
    "act_ent_type": "actEntType",
}


def canonical(value: object, field: str) -> object:
    return None if field in ("list_date", "delist_date") and value in ("None", "") else value


def main() -> None:
    folder = Path(sys.argv[1]).resolve()
    receipts = sorted(folder.glob("discovery-[LDP]-*.json"))
    stages = list(folder.glob("java_stock_detail_stage_*-verified.json"))
    assert len(receipts) == 9 and len(stages) == 1
    expected: dict[str, dict] = {}
    for receipt in receipts:
        payload = json.loads(receipt.read_text(encoding="utf-8"))
        assert payload["complete"] is True and len(payload["rows"]) < payload["sourceRowCap"] == 6000
        for row in payload["rows"]:
            key = row["ts_code"]
            assert key not in expected
            expected[key] = {field: canonical(row[field], field) for field in SOURCE_TO_STAGE}
    stage = json.loads(stages[0].read_text(encoding="utf-8"))
    actual: dict[str, dict] = {}
    for row in stage["snapshot"]["rows"]:
        key = row["tsCode"]
        assert key not in actual
        actual[key] = {field: canonical(row[storage], field)
                       for field, storage in SOURCE_TO_STAGE.items()}
    differences = Counter(field for key in expected.keys() & actual.keys()
                          for field in SOURCE_TO_STAGE if expected[key][field] != actual[key][field])
    result = {
        "source_rows": len(expected),
        "questdb_stage_readback_rows": len(actual),
        "matched_business_keys": len(expected.keys() & actual.keys()),
        "missing_keys": sorted(expected.keys() - actual.keys()),
        "extra_keys": sorted(actual.keys() - expected.keys()),
        "mismatched_fields": dict(differences),
        "compared_columns": list(SOURCE_TO_STAGE),
        "excluded_column": "update_time: local observation, not a source field",
        "stage": stage["stage"],
        "physical_snapshot_fingerprint": stage["snapshot"]["fingerprint"],
        "passed": len(expected) == len(actual) == len(expected.keys() & actual.keys()) and not differences,
    }
    (folder / "independent-stage-values.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    assert result["passed"]


if __name__ == "__main__":
    main()
