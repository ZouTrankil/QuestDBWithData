"""Strict fresh 20260928 canonical Python/native Java field comparison."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path

DAY = "20260928"
SYMBOLS = ("000001.SZ", "600000.SH", "300750.SZ", "688981.SH", "510300.SH", "159915.SZ", "588000.SH")
REL_TOL, ABS_TOL = 1e-10, 1e-9
DIRECTORY = Path(__file__).resolve().parent
WORKSPACE = DIRECTORY.parents[2]


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def reject_constant(value):
    raise ValueError("Nonfinite JSON literal: " + value)


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), parse_constant=reject_constant)


def hash_file(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(2**20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def source_evidence(raw_root):
    inputs = {}
    for symbol in SYMBOLS:
        directory = raw_root / DAY / symbol
        if not directory.is_dir() and symbol in ("510300.SH", "588000.SH"):
            directory = raw_root / DAY / symbol.replace(".SH", ".SZ")
        members = {}
        for filename in ("逐笔成交.csv", "逐笔委托.csv", "行情.csv"):
            path = directory / filename
            members[filename] = {"path": str(path.resolve()), "bytes": path.stat().st_size, "sha256": hash_file(path)}
        inputs[symbol] = {"rawDirectory": str(directory.resolve()), "tables": members}
    source_root = WORKSPACE / "src/main/java/com/zoutrankil/batch"
    java_sources = [source_root / "DfcfCsvParser.java", source_root / "DfcfCsvInspector.java",
                    *sorted((source_root / "l2").glob("*.java"))]
    save(DIRECTORY / "source-input-hashes.json", {"generatedAt": datetime.now(timezone.utc).isoformat(),
                                                "date": DAY, "csvInputs": inputs,
                                                "javaSources": {str(path.relative_to(WORKSPACE)): hash_file(path) for path in java_sources}})


def compare(java_file, report_path):
    selected = {}
    for line in java_file.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line, parse_constant=reject_constant)
        if row.get("ts") == DAY and row.get("symbol") in SYMBOLS:
            if row["symbol"] in selected:
                raise ValueError("Duplicate native row: " + row["symbol"])
            selected[row["symbol"]] = row
    if set(selected) != set(SYMBOLS):
        raise ValueError("Native sample key set differs: " + str(set(selected)))
    schema = load(DIRECTORY / "python-reference/schema.json")["model_fields"]
    if len(schema) != 110:
        raise ValueError("Canonical schema is not 110 fields")
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "date": DAY,
              "canonicalApi": "KuakeSyncPipeline(source=DfcfCsvLevel2Source(rawRoot)).process_symbol_date(symbol, datetime(2026,9,28), load_snapshot=True)",
              "nativeFile": str(java_file.resolve()), "nativeFileSha256": hash_file(java_file),
              "relativeTolerance": REL_TOL, "absoluteTolerance": ABS_TOL,
              "typePolicy": "Exact type/value for int, bool, str and null; both float required for floating comparison; exact frozen field set",
              "symbols": {}, "differences": []}
    for symbol in SYMBOLS:
        reference_path = DIRECTORY / "python-reference" / f"{symbol}.json"
        expected, actual = load(reference_path), selected[symbol]
        fields, differences = [], []
        if set(expected) != set(schema):
            raise ValueError("Python reference field set differs from canonical model: " + symbol)
        for name in sorted(set(schema) | set(actual)):
            python, java = expected.get(name), actual.get(name)
            field = {"field": name, "python": python, "java": java,
                     "pythonType": type(python).__name__, "javaType": type(java).__name__}
            if name not in expected or name not in actual:
                field.update(matched=False, reason="Missing or extra field")
            elif type(python) is not type(java):
                field.update(matched=False, reason="Type differs")
            elif type(python) is float:
                field["delta"] = java - python
                field["absoluteDelta"] = abs(java - python)
                field["allowedDelta"] = max(ABS_TOL, REL_TOL * max(abs(python), abs(java)))
                field["matched"] = math.isfinite(python) and math.isfinite(java) and math.isclose(python, java, rel_tol=REL_TOL, abs_tol=ABS_TOL)
                if not field["matched"]:
                    field["reason"] = "Floating difference exceeds fixed tolerance"
            else:
                field["matched"] = python == java
                if not field["matched"]:
                    field["reason"] = "Value differs"
            fields.append(field)
            if not field["matched"]:
                difference = {"symbol": symbol, **field}
                differences.append(difference)
                report["differences"].append(difference)
        report["symbols"][symbol] = {"fieldsCompared": len(fields), "matched": sum(f["matched"] for f in fields),
                                     "referenceFile": str(reference_path.resolve()), "referenceSha256": hash_file(reference_path),
                                     "differences": differences, "fields": fields}
    report["rowsCompared"] = len(report["symbols"])
    report["fieldsCompared"] = sum(s["fieldsCompared"] for s in report["symbols"].values())
    report["matched"] = sum(s["matched"] for s in report["symbols"].values())
    report["differenceCount"] = len(report["differences"])
    save(report_path, report)
    summary = {k: v for k, v in report.items() if k != "symbols"}
    summary["symbols"] = {name: {k: v for k, v in entry.items() if k != "fields"} for name, entry in report["symbols"].items()}
    save(report_path.with_name(report_path.stem + "-summary.json"), summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if report["differenceCount"] else 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-file", type=Path, default=WORKSPACE / "var/l2-performance-validation/batch-real/20260928/l2_daily_features.jsonl")
    parser.add_argument("--report", type=Path, default=DIRECTORY / "field-comparison.json")
    parser.add_argument("--hash-inputs", action="store_true")
    args = parser.parse_args()
    if args.hash_inputs:
        source_evidence(WORKSPACE / "var/l2-performance-validation/raw")
    return compare(args.java_file, args.report)


if __name__ == "__main__":
    raise SystemExit(main())
