"""Read-only comparison of D002 discovery receipts with the current QuestDB table.

Run under the Python reference project's uv environment. Credential stays in memory.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
import sys

import psycopg2


FIELDS = (
    "ts_code", "symbol", "name", "area", "industry", "fullname", "enname",
    "cnspell", "market", "exchange", "curr_type", "list_status", "list_date",
    "delist_date", "is_hs", "act_name", "act_ent_type",
)


def normalized(row: dict) -> dict:
    copy = {field: row[field] for field in FIELDS}
    for field in ("list_date", "delist_date"):
        if copy[field] in ("None", ""):
            copy[field] = None
    return copy


def main() -> None:
    folder = Path(sys.argv[1]).resolve()
    reference = Path(sys.argv[2]).resolve()
    sys.path.insert(0, str(reference))
    from config.database import QUESTDB_CONFIG

    source: dict[str, dict] = {}
    receipts = sorted(folder.glob("discovery-[LDP]-*.json"))
    assert len(receipts) == 9, "Nine completed status/exchange slices required"
    for receipt in receipts:
        data = json.loads(receipt.read_text(encoding="utf-8"))
        assert data["endpoint"] == "stock_basic" and data["complete"] is True
        assert len(data["rows"]) < data["sourceRowCap"] == 6000
        for row in data["rows"]:
            assert row["list_status"] == data["parameters"]["list_status"]
            assert row["exchange"] == data["parameters"]["exchange"]
            key = row["ts_code"]
            assert key not in source, f"duplicate source key {key}"
            source[key] = normalized(row)

    config = QUESTDB_CONFIG
    connection = psycopg2.connect(
        host=config["host"], port=config["port"], dbname=config["database"],
        user=config["user"], password=config["password"], connect_timeout=10,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT " + ",".join(FIELDS) + " FROM stock_detail_info")
            rows = cursor.fetchall()
    finally:
        connection.close()
    actual: dict[str, dict] = {}
    for values in rows:
        row = normalized(dict(zip(FIELDS, values)))
        key = row["ts_code"]
        assert key not in actual, f"duplicate target key {key}"
        actual[key] = row
    shared = sorted(source.keys() & actual.keys())
    changed = [key for key in shared if source[key] != actual[key]]
    changes_by_field = Counter(
        field for key in changed for field in FIELDS if source[key][field] != actual[key][field]
    )
    result = {
        "query": "SELECT " + ",".join(FIELDS) + " FROM stock_detail_info",
        "read_only": True,
        "source_rows": len(source),
        "target_rows": len(actual),
        "matched_keys": len(shared),
        "same_values": len(shared) - len(changed),
        "changed_values": len(changed),
        "changed_by_field": dict(changes_by_field),
        "changed_key_sample": changed[:20],
        "source_only_keys": sorted(source.keys() - actual.keys()),
        "target_only_keys": sorted(actual.keys() - source.keys()),
        "comparison_excludes": ["update_time: local observation, not source business field"],
    }
    output = folder / "physical-compare.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in ("query", "changed_key_sample")},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
