"""Compare captured provider bytes independently with membership owner SQL readback."""
from pathlib import Path
import hashlib
import json
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    folder = Path(sys.argv[1]).resolve()
    read = lambda path: json.loads(Path(path).read_text(encoding="utf-8"))
    summary = read(folder / "owner-readback.json")
    mapping = {"l2_code": "indexCode", "ts_code": "tsCode", "name": "conName",
               "l1_name": "l1Name", "l2_name": "l2Name", "l3_name": "l3Name",
               "in_date": "inDate", "out_date": "outDate", "is_new": "isNew"}
    key = lambda row: (row["indexCode"], row["tsCode"], row["inDate"])
    reports = []
    for label in ("first", "second", "empty"):
        result = summary[label]
        proof = read(root / result["evidence"])
        source_result = proof["source"]
        captured = (root / source_result["responseEvidence"]).read_bytes()
        assert hashlib.sha256(captured).hexdigest() == source_result["sourceFingerprint"]
        source = json.loads(captured)
        assert source["complete"] is True and source["scope"] == proof["scope"]
        raw = []
        modes = []
        for request in source["requests"]:
            part = (root / request["receipt"]).read_bytes()
            assert hashlib.sha256(part).hexdigest() == request["sha256"]
            response = json.loads(part)
            assert response == request["response"]
            assert response["endpoint"] == "index_member_all"
            assert response["parameters"]["l2_code"] == proof["scope"]["l2Code"]
            mode = response["parameters"]["is_new"]
            modes.append(mode)
            assert response["completion"]["pages"] == 1
            assert response["completion"]["rows"] == len(response["rows"]) < 2000
            assert all(row["is_new"] == mode for row in response["rows"])
            raw.extend(response["rows"])
        assert modes == ["Y", "N"]
        actual_rows = proof["actual"]["rows"]
        actual = {key(row): row for row in actual_rows}
        assert len(actual) == len(actual_rows) == 7
        assert len({(r["l2_code"], r["ts_code"], r["in_date"]) for r in raw}) == len(raw)
        for row in raw:
            stored = actual[(row["l2_code"], row["ts_code"], row["in_date"])]
            for source_field, stored_field in mapping.items():
                value = stored[stored_field]
                if stored_field == "outDate" and value == "None":
                    value = None
                assert row[source_field] == value, (label, source_field, stored["tsCode"])
            assert stored["indexName"] == proof["scope"]["industryName"]
            assert stored["level"] == "L2" and stored["conCode"] is None and stored["weight"] is None
        if label != "first":
            assert proof["before"] == proof["actual"]
            assert proof["submittedStageRows"] == 0
        assert len(raw) == (0 if label == "empty" else 7)
        reports.append(dict(case=label, source_rows=len(raw), actual_rows=len(actual),
                            submitted_stage_rows=proof["submittedStageRows"],
                            source_sha256=source_result["sourceFingerprint"],
                            missing_keys=0, mismatched_values=0))
    report = dict(passed=True, cases=reports, compared_source_fields=list(mapping),
                  evidence_kind="independent comparison of captured source and actual SQL receipts",
                  limitation="Does not re-query tables after successful test cleanup")
    (folder / "independent-source-values.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
