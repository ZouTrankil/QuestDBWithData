"""D098 formal SELECT-only audit of retail sentiment MV and its real bounded source."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

import accept_d095_mv_isolated as formal

SOURCE, MV, ALIAS = "l2_daily_features", "mv_retail_sentiment_daily_v1", "v_retail_sentiment_daily"
START, STOP = "2026-09-17", "2026-09-25"
SOURCE_TYPES = {"ts": "TIMESTAMP", "symbol": "SYMBOL", "gmm_retail_ratio": "DOUBLE",
    "mean_retail_entropy": "DOUBLE", "retail_total_amount": "DOUBLE", "retail_funds_net_inflow": "DOUBLE",
    "mean_rel_aggro": "DOUBLE", "q1_count": "LONG", "q3_count": "LONG", "wash_trade_ratio": "DOUBLE",
    "spoof_count": "LONG", "fake_support_count": "LONG", "fake_pressure_count": "LONG",
    "mfi_score": "DOUBLE", "main_net_inflow": "DOUBLE"}
SOURCE_FIELDS = tuple(SOURCE_TYPES)
OUTPUT_TYPES = {"trade_date": "TIMESTAMP", "avg_retail_ratio": "DOUBLE", "avg_retail_entropy": "DOUBLE",
    "total_retail_amount_yi": "DOUBLE", "total_retail_net_inflow_yi": "DOUBLE", "avg_rel_aggro": "DOUBLE",
    "total_q1": "LONG", "total_q3": "LONG", "avg_wash_trade_ratio": "DOUBLE", "total_spoof_count": "LONG",
    "total_manipulation_count": "LONG", "avg_mfi_score": "DOUBLE", "total_main_net_yi": "DOUBLE"}
OUTPUT_FIELDS = tuple(OUTPUT_TYPES)
OWNER_SQL = formal.VIEW_SELECTS[ALIAS].format(where="")
OUTPUT = formal.REPO_ROOT / "artifacts/java-migration/D098/commands/mv-readonly-audit-20261006.json"


def source_sha(rows):
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def query(sql):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("D098 formal audit accepts one SELECT only")
    return formal.qwp(sql)


def one(sql):
    rows = query(sql)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one formal metadata/result row required: {sql}")
    return rows[0]


def snapshot():
    result = {"tables": {}, "wal": {}}
    for table in (SOURCE, MV):
        raw = formal.table_snapshot(table)
        result["tables"][table] = {key: raw[key] for key in (
            "id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp", "table_max_timestamp",
            "partitionBy", "walEnabled", "dedup", "designatedTimestamp", "table_suspended", "wal_pending_row_count")}
        raw = one(f"SELECT * FROM wal_tables() WHERE name='{table}'")
        result["wal"][table] = {key: raw[key] for key in ("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    result["mv"] = one(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
    result["alias_readonly"] = query(f"SELECT * FROM views() WHERE view_name='{ALIAS}'")
    return result


def compare_daily(actual, expected):
    actual_keys = formal.keyed(actual, ("trade_date",))
    expected_keys = formal.keyed(expected, ("trade_date",))
    common = sorted(actual_keys.keys() & expected_keys.keys())
    compared = []
    for key in common:
        try:
            values = formal.compare_fields([actual_keys[key]], [expected_keys[key]], ("trade_date",), OUTPUT_FIELDS)
            compared.append({"trade_date": key[0], "passed": True, "field_comparisons": values})
        except RuntimeError as exc:
            compared.append({"trade_date": key[0], "passed": False, "error": str(exc)})
    missing = sorted(key[0] for key in expected_keys.keys() - actual_keys.keys())
    additional = sorted(key[0] for key in actual_keys.keys() - expected_keys.keys())
    return {"fields": list(OUTPUT_FIELDS), "matched_dates": compared, "missing_mv_dates": missing,
            "additional_mv_dates": additional, "field_comparisons": sum(row.get("field_comparisons", 0) for row in compared),
            "passed": bool(expected) and not missing and not additional and all(row["passed"] for row in compared)}


def audit(result):
    before = snapshot()
    result["snapshot_before"] = before
    columns = query(f"SELECT * FROM table_columns('{SOURCE}')")
    required = {row["column"]: row["type"] for row in columns if row["column"] in SOURCE_TYPES}
    keys = {row["column"] for row in columns if row["upsertKey"]}
    designated = [row["column"] for row in columns if row["designated"]]
    physical = before["tables"][SOURCE]
    if (required != SOURCE_TYPES or keys != {"ts", "symbol"} or designated != ["ts"] or
            physical["partitionBy"] != "DAY" or not physical["walEnabled"] or not physical["dedup"]):
        raise RuntimeError("D098 real source required field types or DAY/WAL/complete DEDUP contract drifted")
    result["source_needed_types"] = required
    result["source_needed_columns"] = [row for row in columns if row["column"] in SOURCE_TYPES]
    result["source_full_physical_column_count"] = len(columns)
    result["source_complete_key"] = ["ts", "symbol"]
    output_columns = query(f"SELECT * FROM table_columns('{MV}')")
    actual_output = {row["column"]: row["type"] for row in output_columns}
    if tuple(actual_output) != OUTPUT_FIELDS or actual_output != OUTPUT_TYPES:
        raise RuntimeError("D098 native output thirteen-field schema drifted")
    mv_physical = before["tables"][MV]
    if (mv_physical["partitionBy"] != "MONTH" or not mv_physical["walEnabled"] or mv_physical["dedup"] or
            any(row["upsertKey"] for row in output_columns)):
        raise RuntimeError("Native MV MONTH/WAL/no physical DEDUP contract drifted")
    result["output_types"] = actual_output
    result["output_columns"] = output_columns
    state = before["mv"]
    result["definition_matches_python_exact"] = state["view_sql"].strip() == OWNER_SQL.strip()
    result["actual_native_sql"] = state["view_sql"]
    result["actual_native_sql_sha256"] = hashlib.sha256(state["view_sql"].encode()).hexdigest()
    result["python_owner_sql_sha256"] = hashlib.sha256(OWNER_SQL.encode()).hexdigest()
    if (not result["definition_matches_python_exact"] or state["base_table_name"] != SOURCE or
            state["refresh_type"] != "timer" or state["timer_interval"] != 1 or state["timer_interval_unit"] != "MINUTE"):
        raise RuntimeError("Formal native SQL/base/timer differs from actual Python registered version")
    bound = f"WHERE ts >= '{START}' AND ts < '{STOP}'"
    count = one(f"SELECT count() AS n FROM {SOURCE} {bound}")["n"]
    if not count or count > 50000:
        raise RuntimeError("D098 bounded real source requires 1..50000 rows")
    raw = query(f"SELECT {','.join(SOURCE_FIELDS)} FROM {SOURCE} {bound} ORDER BY ts,symbol")
    if len(raw) != count or len(formal.keyed(raw, ("ts", "symbol"))) != count:
        raise RuntimeError("Full bounded real source extraction differs from COUNT or complete unique keys")
    stats = one("SELECT count() AS n," + ",".join(f"count({name}) AS n_{name}" for name in SOURCE_FIELDS) +
                f" FROM {SOURCE} {bound}")
    result["source_bounded_rows"] = count
    result["source_required_field_count_including_keys"] = len(SOURCE_FIELDS)
    result["source_metric_field_count"] = len(SOURCE_FIELDS) - 2
    result["source_required_field_values"] = len(raw) * len(SOURCE_FIELDS)
    result["source_complete_key_count"] = count
    result["source_strict_capture_sha256"] = source_sha(raw)
    result["source_null_counts"] = {name: count - stats[f"n_{name}"] for name in SOURCE_FIELDS}
    result["source_sample_records"] = raw[:3]
    daily = query(f"SELECT ts AS trade_date,count() AS n FROM {SOURCE} {bound} SAMPLE BY 1d ALIGN TO CALENDAR ORDER BY trade_date")
    if len(daily) < 3 or sum(row["n"] for row in daily) != count:
        raise RuntimeError("Full bounded source does not supply at least three real daily buckets")
    result["source_daily_counts"] = daily
    expected = query(formal.VIEW_SELECTS[ALIAS].format(where=bound) + " ORDER BY trade_date")
    observed = query(f"SELECT {','.join(OUTPUT_FIELDS)} FROM {MV} "
                     f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}' ORDER BY trade_date")
    result["direct_base_daily_rows"] = expected
    result["mv_daily_rows"] = observed
    result["daily_parity"] = compare_daily(observed, expected)
    after = snapshot()
    result["snapshot_after"] = after
    result["source_mv_alias_metadata_stable"] = before == after
    if before != after:
        raise RuntimeError("Source/MV/alias metadata changed during formal SELECT audit; rerun read-only")
    result["formal_mv_valid"] = state["view_status"] == "valid" and not state["invalidation_reason"]
    result["formal_mv_caught_up"] = state["refresh_base_table_txn"] == before["wal"][SOURCE]["sequencerTxn"]
    result["formal_target_verified"] = result["formal_mv_valid"] and result["formal_mv_caught_up"] and result["daily_parity"]["passed"]
    result["status"] = "READ_ONLY_AUDIT"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    result = {"task_id": "D098", "checked_at": datetime.now(timezone.utc).isoformat(), "formal_mutated": False,
              "database_operations": "SELECT only", "range_start_inclusive": START, "range_stop_exclusive": STOP,
              "alias_scope": "metadata audit only; D099 alias migration is outside this task"}
    exit_code = 0
    try:
        audit(result)
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"task_id": "D098", "status": result["status"], "source_bounded_rows": result.get("source_bounded_rows"),
                      "formal_mv_valid": result.get("formal_mv_valid"), "daily_parity": result.get("daily_parity"),
                      "output": str(args.output), "error": result.get("error")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
