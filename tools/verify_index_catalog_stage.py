"""Independently compare the D003 candidate file, existing catalog and WAL stage receipt."""
from __future__ import annotations

import csv
import json
from collections import Counter
from pathlib import Path
import sys


PHYSICAL = (
    "index_code", "index_short_name", "index_full_name", "base_date", "base_point",
    "index_series", "sample_count", "latest_close", "return_1m", "asset_class",
    "index_hotspot", "currency", "is_cooperation", "has_tracking_product",
    "compliance_status", "index_category", "publish_date",
)
STAGE = (
    "indexCode", "indexShortName", "indexFullName", "baseDate", "basePoint",
    "indexSeries", "sampleCount", "latestClose", "return1m", "assetClass",
    "indexHotspot", "currency", "isCooperation", "hasTrackingProduct",
    "complianceStatus", "indexCategory", "publishDate",
)
NUMERIC = {"base_point", "sample_count", "latest_close", "return_1m"}


def from_csv(path: Path) -> dict[str, dict]:
    with path.open(encoding="utf-8", newline="") as stream:
        records = list(csv.reader(stream))
    assert len(records[0]) == len(PHYSICAL)
    result = {}
    for values in records[1:]:
        assert len(values) == len(PHYSICAL)
        row = dict(zip(PHYSICAL, values))
        key = row["index_code"].strip()
        if key.isdigit():
            key = key.zfill(6)
        row["index_code"] = key
        for field in PHYSICAL[1:]:
            value = row[field].strip()
            row[field] = None if not value else float(value) if field in NUMERIC else value
        assert key not in result
        result[key] = row
    return result


def from_stage(row: dict) -> dict:
    return {field: row[name] for field, name in zip(PHYSICAL, STAGE)}


def main() -> None:
    folder = Path(sys.argv[1]).resolve()
    base = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else folder.parent
    source = from_csv(base / "source-catalog.csv")
    baseline_rows = json.loads((base / "physical-baseline.json").read_text(encoding="utf-8"))["catalog"]["rows"]
    baseline = {row["index_code"]: row for row in baseline_rows}
    receipts = list(folder.glob("java_index_catalog_stage_*-verified.json"))
    assert len(receipts) == 1
    stage_rows = json.loads(receipts[0].read_text(encoding="utf-8"))["snapshot"]["rows"]
    stage = {row["indexCode"]: row for row in stage_rows}
    assert len(stage) == len(stage_rows) and len(baseline) == len(baseline_rows)
    field_differences = Counter()
    retained_differences = Counter()
    for key, expected in source.items():
        if key not in stage:
            continue
        actual = from_stage(stage[key])
        field_differences.update(field for field in PHYSICAL if actual[field] != expected[field])
    retained = baseline.keys() - source.keys()
    for key in retained & stage.keys():
        actual = from_stage(stage[key])
        expected = baseline[key]
        retained_differences.update(field for field in PHYSICAL if actual[field] != expected[field])
        if stage[key]["importTime"] != expected["import_time"]:
            retained_differences.update(["import_time"])
    result = {
        "source_rows": len(source), "baseline_rows": len(baseline), "stage_rows": len(stage),
        "matched_source_keys": len(source.keys() & stage.keys()),
        "missing_source_keys": sorted(source.keys() - stage.keys()),
        "retained_target_only_keys": len(retained & stage.keys()),
        "missing_retained_keys": sorted(retained - stage.keys()),
        "extra_stage_keys": sorted(stage.keys() - (source.keys() | baseline.keys())),
        "source_field_differences": dict(field_differences),
        "retained_field_differences": dict(retained_differences),
        "source_compared_fields": list(PHYSICAL),
        "import_time_rule": "retained rows unchanged; changed rows carry the test import observation",
    }
    result["passed"] = (len(source) == 2343 and len(baseline) == 2274 and len(stage) == 2803
                        and not result["missing_source_keys"] and not result["missing_retained_keys"]
                        and not result["extra_stage_keys"] and not field_differences
                        and not retained_differences)
    (folder / "independent-stage-values.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=True))
    assert result["passed"]


if __name__ == "__main__":
    main()
