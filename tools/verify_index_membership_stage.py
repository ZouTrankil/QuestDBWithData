"""Independently compare raw membership provider receipts and actual isolated stage readback."""
from pathlib import Path
import hashlib
import json
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    folder = Path(sys.argv[1]).resolve()
    read = lambda path: json.loads(Path(path).read_text(encoding="utf-8"))
    evidence = read(folder / "stage-readback.json")
    source_review = read(root / evidence["sourceReceipt"])
    source_result = source_review["result"]
    source_bytes = (root / source_result["responseEvidence"]).read_bytes()
    assert hashlib.sha256(source_bytes).hexdigest() == source_result["sourceFingerprint"]
    source = json.loads(source_bytes)
    raw = []
    for request in source["requests"]:
        part = (root / request["receipt"]).read_bytes()
        assert hashlib.sha256(part).hexdigest() == request["sha256"]
        response = json.loads(part)
        assert response == request["response"]
        raw.extend(response["rows"])
    actual_rows = evidence["stage"]["snapshot"]["rows"]
    key = lambda row: (row["indexCode"], row["tsCode"], row["inDate"])
    actual = {key(row): row for row in actual_rows}
    assert len(actual) == len(actual_rows)
    mapping = {"l2_code": "indexCode", "ts_code": "tsCode", "name": "conName",
               "l1_name": "l1Name", "l2_name": "l2Name", "l3_name": "l3Name",
               "in_date": "inDate", "out_date": "outDate", "is_new": "isNew"}
    for row in raw:
        stored = actual[(row["l2_code"], row["ts_code"], row["in_date"])]
        for source_field, stored_field in mapping.items():
            value = stored[stored_field]
            if stored_field == "outDate" and value == "None":
                value = None
            assert row[source_field] == value, (source_field, stored["tsCode"])
    for row in evidence["before"]["rows"]:
        assert actual[key(row)] == row
    report = dict(source_rows=len(raw), actual_rows=len(actual),
                  preserved_raw_rows=len(evidence["before"]["rows"]),
                  compared_source_fields=list(mapping), missing_keys=0, mismatched_values=0,
                  passed=True, questdb_receipt=str(folder / "stage-readback.json"),
                  source_sha256=source_result["sourceFingerprint"],
                  limitation="L1/L3 source codes retained in receipt; legacy physical table has names only")
    (folder / "independent-source-values.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
