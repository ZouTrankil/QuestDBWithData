"""Refresh lightweight native L2 benchmark analysis; never runs the pipeline itself.

Usage: python -X utf8 var/l2-performance-validation/analyze_performance.py
Output checks are exact, with a frozen schema. A mismatch writes the failed guard and exits 2.
Estimates cover every archive instrument, pending the user's cleaning universe selection.
"""
from __future__ import annotations
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path
import statistics
import sys
from datetime import datetime, timezone

MIB = 1024**2
GIB = 1024**3
EXPECTED_DATES = ("20260928", "20260929", "20260930")
EXPECTED_COUNTS = {"20260928": 7926, "20260929": 7918, "20260930": 7888}
FEATURE_COLUMNS = ["constant_per_symbol", "DEAL_MiB", "ORDER_MiB", "QUOTE_MiB"]
TABLES = ["逐笔成交.csv", "逐笔委托.csv", "行情.csv"]
PLANNING_FLOOR = 0.35  # Explicit systematic planning margin, not a statistical CI.


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def key(task):
    return str(task["date"]).replace("-", ""), task["symbol"]


def stats(values):
    values = [float(v) for v in values if v is not None and float(v) >= 0]
    if not values:
        return None
    return {"n": len(values), "min": min(values), "median": statistics.median(values), "max": max(values)}


def quantile(values, fraction):
    values = sorted(values)
    position = (len(values) - 1) * fraction
    left = int(position)
    right = min(left + 1, len(values) - 1)
    return values[left] + (position - left) * (values[right] - values[left])


def job_set(report):
    return {(str(j["date"]).replace("-", ""), j["sourceSymbol"], int(j["sourceBytes"])) for j in report["jobs"]}


def summarize(report):
    rounds = [r for r in report["rounds"] if r["phase"] == "measure"]
    tasks = [t for t in report["tasks"] if t["phase"] == "measure"]
    task_rounds = {}
    for task in tasks:
        task_rounds.setdefault(task["round"], []).append(task)
    totals = []
    for ordinal, rows in sorted(task_rounds.items()):
        wall = sum(t["wallMs"] for t in rows)
        totals.append({
            "round": ordinal,
            "taskWallMs": wall,
            "threadCpuMs": sum(t["cpuMs"] for t in rows),
            "parseShareOfTaskWall": sum(t["parseWallMs"] for t in rows) / wall,
            "featureShareOfTaskWall": sum(t["featureWallMs"] for t in rows) / wall,
            "allocatedBytes": sum(max(0, t.get("allocatedBytes", 0)) for t in rows),
            "parseAllocatedBytes": sum(max(0, t.get("parseAllocatedBytes", 0)) for t in rows),
            "featureAllocatedBytes": sum(max(0, t.get("featureAllocatedBytes", 0)) for t in rows),
        })
    requested = int(report.get("options", {}).get("--rounds", len(rounds)))
    slowest = sorted(tasks, key=lambda t: t["wallMs"], reverse=True)[:5]
    summary = {
        "workers": report["workers"], "requestedMeasuredRounds": requested,
        "completedMeasuredRounds": len(rounds), "provisional": len(rounds) < requested,
        "jobCount": len(report["jobs"]), "heapMaxGiB": report["heapMaxBytes"] / GIB,
        "wallSeconds": stats(r["wallMs"] / 1000 for r in rounds),
        "symbolsPerSecond": stats(r["symbolsPerSecond"] for r in rounds),
        "sourceMiBPerSecond": stats(r["sourceMiBPerSecond"] for r in rounds),
        "processCpuSeconds": stats(r["processCpuMs"] / 1000 for r in rounds),
        "threadCpuSeconds": stats(t["threadCpuMs"] / 1000 for t in totals),
        "gcCount": stats(r["gcCount"] for r in rounds),
        "gcSeconds": stats(r["gcMillis"] / 1000 for r in rounds),
        "heapPeakGiB": stats(r["sampledHeapPeakBytes"] / GIB for r in rounds),
        "rssPeakGiB": stats(r["sampledRssPeakBytes"] / GIB for r in rounds if r.get("sampledRssPeakBytes") is not None),
        "allocatedGiB": stats(t["allocatedBytes"] / GIB for t in totals),
        "parseAllocatedGiB": stats(t["parseAllocatedBytes"] / GIB for t in totals),
        "featureAllocatedGiB": stats(t["featureAllocatedBytes"] / GIB for t in totals),
        "parseShareOfTaskWall": stats(t["parseShareOfTaskWall"] for t in totals),
        "featureShareOfTaskWall": stats(t["featureShareOfTaskWall"] for t in totals),
        "slowestObservedTasks": [{k: t[k] for k in ("date", "sourceSymbol", "symbol", "round", "wallMs", "parseWallMs", "featureWallMs", "sourceBytes")} for t in slowest],
        "generatedAt": report.get("generatedAt"),
    }
    return summary


def guard_reports(reports, root, schema):
    errors = []
    expected = schema["fields"]
    seen_hashes, compared_rows, all_rows = {}, 0, {}
    for name, report in reports.items():
        measured = [t for t in report["tasks"] if t["phase"] == "measure"]
        expected_jobs = {(str(j["date"]).replace("-", ""), j["sourceSymbol"]) for j in report["jobs"]}
        measured_rounds = [r["round"] for r in report["rounds"] if r["phase"] == "measure"]
        for ordinal in measured_rounds:
            rows = [t for t in measured if t["round"] == ordinal]
            identities = [(str(t["date"]).replace("-", ""), t["sourceSymbol"]) for t in rows]
            if len(identities) != len(set(identities)) or set(identities) != expected_jobs:
                errors.append(f"{name}: measured round {ordinal}: task set differs from explicit jobs")
        for task in measured:
            identity = key(task)
            if task.get("fieldCount") != 110:
                errors.append(f"{name}: {identity}: field count is {task.get('fieldCount')}, expected 110")
            digest = task.get("sha256", "")
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                errors.append(f"{name}: {identity}: invalid SHA-256")
            prior = seen_hashes.setdefault(identity, (digest, name))
            if prior[0] != digest:
                errors.append(f"{name}: {identity}: SHA differs from {prior[1]}")
            else:
                compared_rows += 1
        final_round = max((t["round"] for t in measured), default=None)
        final_hashes = {key(t): t["sha256"] for t in measured if t["round"] == final_round}
        path = root / "benchmarks" / name / "daily-features.jsonl"
        if final_round is None:
            continue
        if not path.exists():
            errors.append(f"{name}: missing daily-features.jsonl")
            continue
        found = set()
        for ordinal, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            row = json.loads(line, parse_constant=lambda v: (_ for _ in ()).throw(ValueError(f"nonfinite JSON literal {v}")))
            identity = (row.get("ts"), row.get("symbol"))
            if identity in found:
                errors.append(f"{name}: duplicate output key {identity}")
            found.add(identity)
            if set(row) != set(expected):
                errors.append(f"{name}: {identity}: frozen schema differs")
            for field, type_name in expected.items():
                value = row.get(field)
                if value is None and field not in ("ts", "symbol"):
                    continue
                valid = ((type_name == "str" and isinstance(value, str)) or
                         (type_name == "bool" and type(value) is bool) or
                         (type_name == "int" and type(value) is int) or
                         (type_name == "number" and type(value) in (int, float) and math.isfinite(value)))
                if not valid:
                    errors.append(f"{name}: {identity}: {field} type is {type(value).__name__}, expected {type_name}")
            if row.get("feature_version") != schema["featureVersion"] or row.get("parser_version") != schema["parserVersion"]:
                errors.append(f"{name}: {identity}: version differs from frozen schema")
            actual_hash = hashlib.sha256(line.encode("utf-8")).hexdigest()
            if actual_hash != final_hashes.get(identity):
                errors.append(f"{name}: output line {ordinal}: raw JSON SHA does not match final task")
            if identity in all_rows and row != all_rows[identity]:
                errors.append(f"{name}: {identity}: full field values differ from another run")
            all_rows[identity] = row
        if found != set(final_hashes):
            errors.append(f"{name}: final output key set differs from final measured task set")
    return {"status": "passed" if not errors else "failed", "schemaFields": 110,
            "featureVersion": schema["featureVersion"], "parserVersion": schema["parserVersion"],
            "floatTolerance": 0, "checksumPolicy": "exact SHA-256 across every measured round and worker",
            "comparedTaskResults": compared_rows, "uniqueSymbolDays": len(seen_hashes),
            "fullOutputRows": len(all_rows), "errors": errors}


def solve(matrix, target):
    """Small pivoted normal-equation solve; input columns are normalized first."""
    width = len(target)
    augmented = [list(row) + [rhs] for row, rhs in zip(matrix, target)]
    for col in range(width):
        pivot = max(range(col, width), key=lambda r: abs(augmented[r][col]))
        if abs(augmented[pivot][col]) < 1e-10:
            return None
        augmented[col], augmented[pivot] = augmented[pivot], augmented[col]
        denominator = augmented[col][col]
        augmented[col] = [value / denominator for value in augmented[col]]
        for row in range(width):
            if row == col:
                continue
            amount = augmented[row][col]
            augmented[row] = [v - amount * p for v, p in zip(augmented[row], augmented[col])]
    return [augmented[row][-1] for row in range(width)]


def fit_nnls(x, y):
    """Enumerate the 16 active sets for four coefficients, enforcing nonnegativity."""
    scales = [max(abs(row[c]) for row in x) or 1 for c in range(4)]
    normalized = [[row[c] / scales[c] for c in range(4)] for row in x]
    best = [0.0] * 4
    best_loss = sum(value * value for value in y)
    for count in range(1, 5):
        for active in itertools.combinations(range(4), count):
            gram = [[sum(row[c] * row[d] for row in normalized) for d in active] for c in active]
            rhs = [sum(row[c] * value for row, value in zip(normalized, y)) for c in active]
            beta = solve(gram, rhs)
            if beta is None or min(beta) < -1e-8:
                continue
            full = [0.0] * 4
            for index, value in zip(active, beta):
                full[index] = max(0.0, value) / scales[index]
            loss = sum((dot(row, full) - value) ** 2 for row, value in zip(x, y))
            if loss < best_loss:
                best, best_loss = full, loss
    return best


def dot(row, coefficients):
    return sum(value * beta for value, beta in zip(row, coefficients))


def vector(member):
    return [1.0] + [member.get("tables", {}).get(table, 0) / MIB for table in TABLES]


def aggregate_symbol_tasks(report):
    grouped = {}
    for task in report["tasks"]:
        if task["phase"] == "measure":
            grouped.setdefault((str(task["date"]).replace("-", ""), task["sourceSymbol"]), []).append(task)
    return {identity: {"wallSeconds": statistics.median(t["wallMs"] / 1000 for t in rows),
                       "cpuSeconds": statistics.median(t["cpuMs"] / 1000 for t in rows),
                       "rounds": len(rows), "symbol": rows[0]["symbol"]}
            for identity, rows in grouped.items()}


def model(y_name, observations, inventories, archives, round_variation):
    data = []
    for (day, symbol), timing in observations.items():
        member = inventories[day].get(symbol)
        if member is None:
            raise ValueError(f"No archive metadata for measured {day}/{symbol}")
        data.append((day, symbol, vector(member), timing[y_name]))
    if len(data) < 5:
        return {"status": "pending", "reason": "Need at least five measured symbol-days"}
    x, y = [d[2] for d in data], [d[3] for d in data]
    coefficients = fit_nnls(x, y)
    predictions = [dot(row, coefficients) for row in x]
    residuals = [actual - predicted for actual, predicted in zip(y, predictions)]
    ratios = [actual / predicted for actual, predicted in zip(y, predictions) if predicted > 1e-9]
    residual_low, residual_high = quantile(ratios, 0.1), quantile(ratios, 0.9)
    total_y = sum(y)
    leave_one = [fit_nnls(x[:i] + x[i + 1:], y[:i] + y[i + 1:]) for i in range(len(data))]
    forecast = {}
    for day, rows in archives.items():
        totals = [float(len(rows))] + [sum(row.get("tables", {}).get(table, 0) for row in rows) / MIB for table in TABLES]
        point = dot(totals, coefficients)
        loo = [dot(totals, fit) for fit in leave_one]
        lower = min(point * (1 - PLANNING_FLOOR), point * residual_low, quantile(loo, 0.1)) * round_variation[0]
        upper = max(point * (1 + PLANNING_FLOOR), point * residual_high, quantile(loo, 0.9)) * round_variation[1]
        biggest = max(rows, key=lambda row: dot(vector(row), coefficients))
        forecast[day] = {"archiveSymbols": len(rows), "sourceGiB": sum(r["csv_bytes"] for r in rows) / GIB,
                         "pointSeconds": point, "planningRangeSeconds": [max(0, lower), upper],
                         "approxMinutesRounded10": [round(point / 600) * 10, math.floor(lower / 600) * 10, math.ceil(upper / 600) * 10],
                         "leaveOneOutPointRangeSeconds": [min(loo), max(loo)],
                         "largestPredictedSymbol": biggest["raw_symbol"],
                         "largestPredictedSymbolSeconds": dot(vector(biggest), coefficients)}
    total_point = sum(f["pointSeconds"] for f in forecast.values())
    total_lower = sum(f["planningRangeSeconds"][0] for f in forecast.values())
    total_upper = sum(f["planningRangeSeconds"][1] for f in forecast.values())
    return {"status": "fitted", "response": y_name, "sampleSymbolDays": len(data),
            "units": "seconds; nonnegative coefficients for constant per symbol plus DEAL/ORDER/QUOTE MiB",
            "fitAlgorithm": "Four-column NNLS by exhaustive active-set least squares with normalized columns",
            "coefficients": dict(zip(FEATURE_COLUMNS, coefficients)),
            "maeSeconds": statistics.mean(abs(r) for r in residuals),
            "weightedAbsolutePercentageError": sum(abs(r) for r in residuals) / total_y,
            "relativeResidualQ10Q90": [residual_low, residual_high],
            "planningMarginFloor": PLANNING_FLOOR,
            "planningRangeMethod": "Envelope of +/-35% systematic margin, residual Q10/Q90 and leave-one-out workload Q10/Q90; expanded by measured round variation. Not a confidence interval.",
            "fixedCostPerSymbolSeconds": coefficients[0],
            "sampleFeatureRangesMiB": {FEATURE_COLUMNS[c]: [min(row[c] for row in x), max(row[c] for row in x)] for c in range(1, 4)},
            "sampleObservations": [{"date": d, "rawSymbol": s, "actualSeconds": actual,
                                    "predictedSeconds": prediction, "residualSeconds": actual - prediction}
                                   for (d, s, _, actual), prediction in zip(data, predictions)],
            "byDate": forecast,
            "allThreeDates": {"archiveSymbols": sum(len(rows) for rows in archives.values()),
                              "pointSeconds": total_point, "planningRangeSeconds": [total_lower, total_upper],
                              "approxMinutesRounded10": [round(total_point / 600) * 10, math.floor(total_lower / 600) * 10, math.ceil(total_upper / 600) * 10]}}


def analyze(root):
    reports = {}
    for path in sorted((root / "benchmarks").glob("*/benchmark.json")):
        name = path.parent.name
        if name == "w1" or name.startswith("optimized-w") or name.startswith("full-selected"):
            reports[name] = load(path)
    schema = load(root / "performance-schema.json")
    if schema["fieldCount"] != 110 or len(schema["fields"]) != 110 or schema["featureVersion"] != "v1.3" or schema["parserVersion"] != "dfcf_csv_v1.2":
        raise ValueError("Frozen performance schema was modified unexpectedly")
    guard = guard_reports(reports, root, schema)
    summaries = {name: summarize(report) for name, report in reports.items()}
    archives = {day: load(root / "archive-metadata" / f"{day}-symbols.json") for day in EXPECTED_DATES}
    for day, records in archives.items():
        if len(records) != EXPECTED_COUNTS[day]:
            raise ValueError(f"Archive count changed for {day}: {len(records)} != {EXPECTED_COUNTS[day]}")
    inventories = {day: {row["raw_symbol"]: row for row in rows} for day, rows in archives.items()}
    base = reports.get("optimized-w1")
    base_summary = summaries.get("optimized-w1")
    speedups = {}
    if base and base_summary["wallSeconds"]:
        b = base_summary["wallSeconds"]
        for name, report in reports.items():
            if name == "optimized-w1" or not name.startswith("optimized-w"):
                continue
            measured = summaries[name]["wallSeconds"]
            same = job_set(base) == job_set(report)
            comparable = same and base["heapMaxBytes"] == report["heapMaxBytes"] and not report.get("jfrEnabled") and report.get("options", {}).get("--stage-profile") == "false"
            if measured and comparable:
                speedups[name] = {"workers": report["workers"], "median": b["median"] / measured["median"],
                                  "range": [b["min"] / measured["max"], b["max"] / measured["min"]],
                                  "sameJobSet": same, "provisional": summaries[name]["provisional"] or base_summary["provisional"]}
            else:
                speedups[name] = {"status": "not_comparable", "sameJobSet": same}
    optimization = None
    if "w1" in reports and base and job_set(reports["w1"]) == job_set(base):
        old, new = summaries["w1"], base_summary
        if old["wallSeconds"] and new["wallSeconds"]:
            optimization = {"sameJobSet": True, "medianSpeedup": old["wallSeconds"]["median"] / new["wallSeconds"]["median"],
                            "wallReductionFraction": 1 - new["wallSeconds"]["median"] / old["wallSeconds"]["median"],
                            "allocationReductionFraction": 1 - new["allocatedGiB"]["median"] / old["allocatedGiB"]["median"]}
    wall_model = cpu_model = {"status": "pending", "reason": "optimized-w1 measured round not yet available"}
    if guard["status"] == "passed" and base and base_summary["wallSeconds"]:
        observed = aggregate_symbol_tasks(base)
        variation = [base_summary["wallSeconds"]["min"] / base_summary["wallSeconds"]["median"],
                     base_summary["wallSeconds"]["max"] / base_summary["wallSeconds"]["median"]]
        wall_model = model("wallSeconds", observed, inventories, archives, variation)
        pooled = dict(observed)
        for name, report in reports.items():
            if name.startswith("full-selected"):
                for identity, value in aggregate_symbol_tasks(report).items():
                    pooled.setdefault(identity, value)  # Keep the serial observations for overlapping keys.
        cpu_model = model("cpuSeconds", pooled, inventories, archives, [1, 1])
        cpu_model["inputPolicy"] = "optimized-w1 per-symbol medians; add new full-selected symbol-days using worker CPU only, never parallel task wall"
    parallel_forecasts = {}
    if wall_model.get("status") == "fitted":
        serial = wall_model["allThreeDates"]
        for name, speedup in speedups.items():
            if "median" not in speedup:
                continue
            point = serial["pointSeconds"] / speedup["median"]
            lower = serial["planningRangeSeconds"][0] / speedup["range"][1]
            upper = serial["planningRangeSeconds"][1] / speedup["range"][0]
            parallel_forecasts[name] = {"workers": speedup["workers"], "provisional": speedup["provisional"],
                                       "allThreeDatesPointSeconds": point, "planningRangeSeconds": [lower, upper],
                                       "approxMinutesRounded10": [round(point / 600) * 10, math.floor(lower / 600) * 10, math.ceil(upper / 600) * 10],
                                       "assumption": "Divide serial all-archive model by speedup measured on the identical selected job set; schedule scaling remains unverified"}
            parallel_forecasts[name]["byDate"] = {
                day: {"pointSeconds": forecast["pointSeconds"] / speedup["median"],
                      "planningRangeSeconds": [forecast["planningRangeSeconds"][0] / speedup["range"][1],
                                               forecast["planningRangeSeconds"][1] / speedup["range"][0]]}
                for day, forecast in wall_model["byDate"].items()}
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(), "guard": guard,
        "runs": summaries, "parserOptimization": optimization, "concurrencySpeedups": speedups,
        "serialWallModel": wall_model, "workerCpuModel": cpu_model,
        "parallelAllArchiveForecasts": parallel_forecasts,
        "universe": {"status": "unconfirmed", "estimateScope": "all archive instruments as an upper workload scenario; no stock/ETF-only filter inferred",
                     "countsByDate": EXPECTED_COUNTS, "allThreeDatesSymbolDays": sum(EXPECTED_COUNTS.values())},
        "caveats": [
            "The measured rounds exclude JVM warmup and use already-extracted files, usually warm OS cache. Extraction, cold storage, database ingestion and verification are not included.",
            "The serial wall model starts with only 15 selected symbol-days on 09/28. Even a later 45-sample CPU fit does not validate serial wall behavior on 09/29 or 09/30.",
            "Largest symbols are stragglers; same-sample speedup need not transfer to thousands of smaller symbols or a filtered stock/ETF universe.",
            "The constant and quote-size terms can be hard to separate; small-symbol fixed costs/JIT effects may dominate full-archive extrapolation.",
            "The ranges are deliberately broad planning envelopes, not statistical confidence intervals or guaranteed runtimes.",
            "Worker thread CPU excludes GC/compiler CPU. Process CPU and per-round GC are the aggregate JVM measures; do not sum overlapping per-task GC.",
            "Heap/RSS are sampled every 20 ms, and memory-pool peaks differ from simultaneous heap peak. Windows lifetime RSS includes warmup.",
            "Pending or partial runs are marked provisional. Rerun this script after the benchmark writes more complete rounds.",
        ]}


def markdown(summary):
    lines = ["# Native L2 performance summary", "", f"Guard: **{summary['guard']['status']}**; {summary['guard']['comparedTaskResults']} measured task outputs, {summary['guard']['uniqueSymbolDays']} unique symbol-days.", "", "| Run | Rounds | Median seconds (min–max) | Symbols/s | Parse % | Heap peak GiB | RSS peak GiB | GC seconds median | Allocated GiB median |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for name, run in summary["runs"].items():
        wall = run["wallSeconds"]
        if not wall:
            lines.append(f"| {name} | 0/{run['requestedMeasuredRounds']} | pending | | | | | | |")
            continue
        med = lambda field: run[field]["median"] if run[field] else 0
        maximum = lambda field: run[field]["max"] if run[field] else 0
        lines.append(f"| {name} | {run['completedMeasuredRounds']}/{run['requestedMeasuredRounds']} | {wall['median']:.1f} ({wall['min']:.1f}–{wall['max']:.1f}) | {med('symbolsPerSecond'):.3f} | {100*med('parseShareOfTaskWall'):.1f} | {maximum('heapPeakGiB'):.1f} | {maximum('rssPeakGiB'):.1f} | {med('gcSeconds'):.2f} | {med('allocatedGiB'):.1f} |")
    lines += ["", "## All-archive workload scenario", "", "The cleaning universe is unconfirmed. These forecasts include all 23,732 symbol-days in the three archives, including instruments outside stock/ETF scope. Times below exclude extraction and database ingestion.", ""]
    wall_model = summary["serialWallModel"]
    if wall_model.get("status") == "fitted":
        for day, forecast in wall_model["byDate"].items():
            point, low, high = forecast["approxMinutesRounded10"]
            lines.append(f"- {day}: serial ≈ {point} minutes, planning range {low}–{high} minutes ({forecast['archiveSymbols']} instruments).")
        point, low, high = wall_model["allThreeDates"]["approxMinutesRounded10"]
        lines.append(f"- All three dates: serial ≈ {point} minutes, planning range {low}–{high} minutes.")
        for name, forecast in summary["parallelAllArchiveForecasts"].items():
            point, low, high = forecast["approxMinutesRounded10"]
            speedup = summary["concurrencySpeedups"][name]["median"]
            lines.append(f"- {forecast['workers']} workers: ≈ {point} minutes, planning range {low}–{high} minutes; selected-job speedup {speedup:.2f}×{' (provisional)' if forecast['provisional'] else ''}.")
        lines += ["", f"Wall fit sample count: {wall_model['sampleSymbolDays']}; weighted absolute residual {100*wall_model['weightedAbsolutePercentageError']:.1f}%. The interval combines a fixed ±35% planning margin, empirical residuals, leave-one-out predictions and round variation. It is not a confidence interval."]
    else:
        lines.append("Forecast pending serial optimized measurements or a passing output guard.")
    lines += ["", "## Limits", ""] + [f"- {text}" for text in summary["caveats"]]
    if summary["guard"]["errors"]:
        lines += ["", "## Guard errors", ""] + [f"- {text}" for text in summary["guard"]["errors"]]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    try:
        summary = analyze(args.root.resolve())
    except (ValueError, KeyError, OSError) as error:
        summary = {"generatedAt": datetime.now(timezone.utc).isoformat(),
                   "guard": {"status": "failed", "errors": [str(error)]}}
        (args.root / "performance-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"ANALYSIS GUARD FAILED: {error}", file=sys.stderr)
        return 2
    (args.root / "performance-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    (args.root / "performance-summary.md").write_text(markdown(summary), encoding="utf-8")
    print(markdown(summary))
    return 0 if summary["guard"]["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
