"""Summarize existing native L2 performance outputs without running any computation.

This script reads saved first-run/current manifests, JSONL and timing artifacts.
It writes real-batch-summary.json and exits 2 if an output guard fails.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

DATES = ("20260928", "20260929", "20260930")
PER_DATE_ROWS = 15
EXPECTED_ROWS = 45
GIB = 1024 ** 3


def no_constants(value):
    raise ValueError(f"Nonfinite JSON constant: {value}")


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def loads(value):
    return json.loads(value, parse_constant=no_constants, object_pairs_hook=unique_keys)


def load(path):
    return loads(path.read_text(encoding="utf-8-sig"))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def elapsed(manifest):
    start = datetime.fromisoformat(manifest["startedAt"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(manifest["updatedAt"].replace("Z", "+00:00"))
    return (end - start).total_seconds()


def stats(values):
    values = list(values)
    return {"count": len(values), "min": min(values), "median": statistics.median(values),
            "max": max(values), "sum": sum(values)} if values else None


def check(condition, message, errors):
    if not condition:
        errors.append(message)


def feature_rows(path, date, schema, errors, require_sorted=True):
    """Retain the exact UTF-8 JSON bytes, removing only a trailing CR/LF."""
    result = {}
    expected = schema["fields"]
    with path.open("rb") as source:
        for number, physical_line in enumerate(source, 1):
            line = physical_line.rstrip(b"\r\n")
            if not line:
                errors.append(f"{path}:{number}: empty JSONL row")
                continue
            row = loads(line)
            check(isinstance(row, dict), f"{path}:{number}: row is not an object", errors)
            if not isinstance(row, dict):
                continue
            key = row.get("symbol")
            check(isinstance(key, str) and re.fullmatch(r"\d{6}\.(SH|SZ|BJ)", key) is not None,
                  f"{path}:{number}: invalid canonical symbol {key!r}", errors)
            check(row.get("ts") == date, f"{path}:{number}: business date mismatch", errors)
            check(set(row) == set(expected) and len(row) == schema["fieldCount"] == 110,
                  f"{path}:{number}: frozen 110-field schema mismatch", errors)
            check(row.get("feature_version") == schema["featureVersion"],
                  f"{path}:{number}: feature version mismatch", errors)
            check(row.get("parser_version") == schema["parserVersion"],
                  f"{path}:{number}: parser version mismatch", errors)
            for field, kind in expected.items():
                if field not in row:
                    continue
                value = row[field]
                if value is None:
                    check(field not in ("ts", "symbol"), f"{path}:{number}: null identity {field}", errors)
                    continue
                valid = {"str": lambda: type(value) is str,
                         "bool": lambda: type(value) is bool,
                         "int": lambda: type(value) is int,
                         "number": lambda: type(value) in (int, float) and math.isfinite(value)}
                check(kind in valid and valid[kind](), f"{path}:{number}: invalid {kind} field {field}", errors)
            if not isinstance(key, str):
                continue
            check(key not in result, f"{path}:{number}: duplicate symbol {key}", errors)
            result[key] = line
    if require_sorted:
        check(list(result) == sorted(result), f"{path}: rows are not in canonical symbol order", errors)
    return result


def manifest_guard(manifest, date, rows, phase, errors):
    label = f"{phase}/{date}"
    check(manifest.get("date") == date, f"{label}: manifest date mismatch", errors)
    check(manifest.get("status") == "COMPLETE" and manifest.get("published") is True,
          f"{label}: manifest is not complete/published", errors)
    counts = manifest.get("counts", {})
    check(counts.get("success") == len(rows) == PER_DATE_ROWS, f"{label}: successful row count mismatch", errors)
    check(counts.get("empty") == 0 and counts.get("failed") == 0, f"{label}: empty/failed samples", errors)
    symbols = manifest.get("symbols", [])
    check(len(symbols) == PER_DATE_ROWS, f"{label}: symbol metadata count mismatch", errors)
    check({item.get("symbol") for item in symbols} == set(rows), f"{label}: metadata/output symbols differ", errors)
    check(all(item.get("status") == "SUCCESS" for item in symbols), f"{label}: unsuccessful symbol state", errors)
    actual_resumed = sum(item.get("resumed") is True for item in symbols)
    check(counts.get("resumed") == actual_resumed, f"{label}: resume metadata count mismatch", errors)
    for item in symbols:
        symbol = item.get("symbol")
        if symbol in rows:
            # Per-symbol files contain the JSON row plus LF; the aggregate strips that LF before writing.
            check(item.get("resultSha256") == hashlib.sha256(rows[symbol] + b"\n").hexdigest(),
                  f"{label}/{symbol}: per-symbol result hash mismatch", errors)
    return actual_resumed


def metric_summary(path, errors):
    data = load(path)
    check(data.get("status") == "SUCCESS" and data.get("error") is None,
          f"{path}: native timing wrapper did not succeed", errors)
    return {"artifact": str(path), "scope": data.get("includes"), "recordedAt": data.get("recordedAt"),
            "status": data.get("status"), "wallSeconds": data.get("wallSeconds"),
            "processCpuSeconds": data.get("processCpuSeconds"), "gcCount": data.get("gcCount"),
            "gcSeconds": data.get("gcMillis", 0) / 1000,
            "heapPeakGiB": data.get("heapPeakBytes", 0) / GIB,
            "rssPeakGiB": data.get("rssPeakBytes", 0) / GIB,
            "heapMaxGiB": data.get("heapMaxBytes", 0) / GIB,
            "jvmArgs": data.get("jvmArgs"), "nativeArgs": data.get("nativeArgs")}


def summarize(base):
    errors = []
    schema = load(base / "performance-schema.json")
    check(schema.get("fieldCount") == len(schema.get("fields", {})) == 110,
          "performance-schema.json must freeze 110 fields", errors)
    daily = []
    first_total = 0
    current_total = 0
    resumed_total = 0
    fingerprints = set()
    first_0928 = None
    for date in DATES:
        first_root = base / "batch-real-first-snapshot" / date
        current_root = base / "batch-real" / date
        first = load(first_root / "manifest.json")
        current = load(current_root / "manifest.json")
        first_file = first_root / "l2_daily_features.jsonl"
        current_file = current_root / "l2_daily_features.jsonl"
        first_rows = feature_rows(first_file, date, schema, errors)
        current_rows = feature_rows(current_file, date, schema, errors)
        initial_resumed = manifest_guard(first, date, first_rows, "first", errors)
        current_resumed = manifest_guard(current, date, current_rows, "current", errors)
        check(initial_resumed == 0, f"first/{date}: first snapshot unexpectedly reused checkpoints", errors)
        first_hash, current_hash = sha256(first_file), sha256(current_file)
        check(first_hash == first.get("aggregateSha256"), f"first/{date}: aggregate/manifest hash mismatch", errors)
        check(current_hash == current.get("aggregateSha256"), f"current/{date}: aggregate/manifest hash mismatch", errors)
        check(first_hash == current_hash, f"{date}: first/current aggregate bytes changed", errors)
        check(first_rows == current_rows, f"{date}: first/current per-symbol JSON bytes changed", errors)
        first_total += len(first_rows)
        current_total += len(current_rows)
        resumed_total += current_resumed
        fingerprints.update((first.get("computeFingerprint"), current.get("computeFingerprint")))
        raw_bytes = sum(int(metadata.get("bytes", 0)) for item in first["symbols"]
                        for metadata in item.get("inputFiles", {}).values())
        daily.append({"date": date, "rowCount": len(current_rows), "firstCounts": first["counts"],
                      "currentCounts": current["counts"], "recomputedCurrent": len(current_rows) - current_resumed,
                      "sourceBytes": raw_bytes, "aggregateSha256": current_hash, "aggregateUnchanged": first_hash == current_hash,
                      "firstNativeDateSeconds": elapsed(first), "currentNativeDateSeconds": elapsed(current),
                      "firstSymbolSeconds": stats(item["elapsedSeconds"] for item in first["symbols"]),
                      "currentSymbolSeconds": stats(item["elapsedSeconds"] for item in current["symbols"]),
                      "computeFingerprintUnchanged": first.get("computeFingerprint") == current.get("computeFingerprint"),
                      "symbols": sorted(current_rows), "firstManifest": str(first_root / "manifest.json"),
                      "currentManifest": str(current_root / "manifest.json"), "currentAggregate": str(current_file)})
        if date == DATES[0]:
            first_0928 = first_rows
    check(first_total == current_total == EXPECTED_ROWS, "Expected exactly 45 symbol-day outputs in first/current batches", errors)
    # Report actual reuse before enforcing this performance run's expected unchanged inputs/bytecode.
    check(resumed_total == EXPECTED_ROWS, f"Current batch reused {resumed_total}/45; {EXPECTED_ROWS - resumed_total} recomputed", errors)
    check(len(fingerprints) == 1, "Computation fingerprint changed between first/current dates", errors)

    benchmark_file = base / "benchmarks" / "w1" / "daily-features.jsonl"
    benchmark = feature_rows(benchmark_file, DATES[0], schema, errors, require_sorted=False)
    common = set(first_0928) & set(benchmark)
    identical = sum(first_0928[symbol] == benchmark[symbol] for symbol in common)
    check(set(first_0928) == set(benchmark) and identical == PER_DATE_ROWS,
          f"09/28 first/native benchmark row bytes differ: identical={identical}, common={len(common)}", errors)

    first_metrics = metric_summary(base / "batch-real-first-metrics.json", errors)
    resume_metrics = metric_summary(base / "batch-real-resume-metrics.json", errors)
    summaries = sorted((base / "batch-real").glob("run-*/native-summary.json"),
                       key=lambda path: (path.stat().st_mtime_ns, path.parent.name))
    script = None
    if summaries:
        summary_path = summaries[-1]
        raw = load(summary_path)
        check(raw.get("exitCode") == 0, f"{summary_path}: formal script native command failed", errors)
        check(raw.get("dates") == list(DATES), f"{summary_path}: unexpected script dates", errors)
        native_date_seconds = sum(day["currentNativeDateSeconds"] for day in daily)
        script = {**raw, "artifact": str(summary_path), "runDirectory": str(summary_path.parent),
                  "timingScope": "Gradle native command, including isolated compilation/startup; excludes extraction",
                  "manifestNativeDateSeconds": native_date_seconds,
                  "commandMinusNativeDateSeconds": raw["elapsedSeconds"] - native_date_seconds,
                  "resumedOutputsFromCurrentManifests": resumed_total,
                  "recomputedOutputsFromCurrentManifests": EXPECTED_ROWS - resumed_total}
    else:
        errors.append("No completed formal script run-*/native-summary.json exists")

    summary = {"generatedAt": datetime.now(timezone.utc).isoformat(),
               "scope": "Existing 45 sampled symbol-day performance artifacts for 20260928-20260930; no recomputation or Python algorithm comparison",
               "guard": {"status": "passed" if not errors else "failed", "errors": errors,
                         "frozenSchemaFields": 110, "firstRows": first_total, "currentRows": current_total,
                         "currentResumedRows": resumed_total, "currentRecomputedRows": EXPECTED_ROWS - resumed_total,
                         "aggregateChecksumPolicy": "Exact complete-file SHA-256; exact per-symbol JSON bytes excluding CR/LF",
                         "benchmark0928Rows": len(benchmark), "benchmark0928IdenticalRows": identical,
                         "computeFingerprints": sorted(str(value) for value in fingerprints)},
               "dates": daily, "nativeFirstTiming": first_metrics, "nativeDirectResumeTiming": resume_metrics,
               "latestFormalScriptNativeCommand": script}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-root", type=Path, default=Path(__file__).resolve().parent)
    options = parser.parse_args()
    base = options.base_root.resolve()
    target = base / "real-batch-summary.json"
    try:
        summary = summarize(base)
    except Exception as failure:
        summary = {"generatedAt": datetime.now(timezone.utc).isoformat(),
                   "guard": {"status": "failed", "errors": [f"{type(failure).__name__}: {failure}"]}}
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(target)
    guard = summary["guard"]
    print(json.dumps({"summary": str(target), **guard}, ensure_ascii=False))
    return 0 if guard["status"] == "passed" else 2


if __name__ == "__main__":
    sys.exit(main())
