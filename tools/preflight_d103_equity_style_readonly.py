"""D103 bounded, SELECT-only source capture and original Python AST oracle.

The reference checkout is never imported. No source provider, DDL, publisher,
owner build entrypoint or private database is invoked by this tool.
"""
from __future__ import annotations

import argparse
import ast
from contextlib import contextmanager
from datetime import date, datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import select as socket_select
import struct
import sys
import time
from typing import Optional

import pandas as pd
import psycopg2

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[1]
REFERENCE = Path("D:/work/fund_2/back-monitor")
DIRECTORY = REPO / "artifacts/java-migration/D103/commands"
SOURCE, TARGET = "index_monthly", "equity_style_monthly"
OWNER = REFERENCE / "src/quant_platform/data/adapters/materializers/equity_style_monthly.py"
SOURCE_MODEL = REFERENCE / "src/quant_platform/data/adapters/questdb/models/index/monthly.py"
TARGET_MODEL = REFERENCE / "src/quant_platform/data/adapters/questdb/models/index/equity_style_monthly.py"
REFERENCE_FILES = (OWNER, SOURCE_MODEL, TARGET_MODEL,
    REFERENCE / "src/quant_platform/data/derived/market/equity_style_monthly.py",
    REFERENCE / "src/quant_platform/data/derived/workflows.py",
    REFERENCE / "src/quant_platform/data/adapters/materializers/registry.py",
    REFERENCE / "src/quant_platform/data/adapters/questdb/index_data.py",
    REFERENCE / "src/quant_platform/data/application/index_data.py")
MAX_MONTHS, CAP, PG_DEADLINE_SECONDS = 12, 16 * 31 * 12, 20
SOURCE_FIELDS = ("ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount", "layer", "bucket", "update_time")
SOURCE_TYPES = dict(zip(SOURCE_FIELDS, ("SYMBOL", "TIMESTAMP", *(["DOUBLE"] * 9), "SYMBOL", "SYMBOL", "TIMESTAMP")))
FEATURE_CODES = {
    "hs300_ret_1m": "000300.SH", "zz500_ret_1m": "000905.SH", "all_a_ret_1m": "000985.SH",
    "cs1000_ret_1m": "000852.SH", "value_ret_1m": "000920.SH", "growth_ret_1m": "000921.SH",
    "energy_ret_1m": "000986.SH", "materials_ret_1m": "000987.SH", "industrials_ret_1m": "000988.SH",
    "consumer_discretionary_ret_1m": "000989.SH", "consumer_staples_ret_1m": "000990.SH",
    "healthcare_ret_1m": "000991.SH", "financials_ret_1m": "000992.SH", "it_ret_1m": "000993.SH",
    "telecom_ret_1m": "000994.SH", "utilities_ret_1m": "000995.SH",
}
OUTPUT_FIELDS = ("month", "hs300_ret_1m", "zz500_ret_1m", "all_a_ret_1m", "cs1000_ret_1m", "small_large_ret_1m", "mid_large_ret_1m", "growth_ret_1m", "value_ret_1m", "growth_value_ret_1m", *tuple(FEATURE_CODES)[6:], *(name.removesuffix("_ret_1m") + "_vs_all_a_1m" for name in tuple(FEATURE_CODES)[6:]))
OUTPUT_TYPES = {field: "TIMESTAMP" if field == "month" else "DOUBLE" for field in OUTPUT_FIELDS}


def require(value, message):
    if not value:
        raise RuntimeError(message)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def digest(path):
    return sha_bytes(Path(path).read_bytes())


def canonical_sha(value):
    return sha_bytes(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8"))


def single_select(sql):
    require(isinstance(sql, str) and sql.split() and sql.split(None, 1)[0].upper() == "SELECT" and ";" not in sql,
            "D103 permits one SELECT only")


def month_window(first, last, today=None):
    for value in (first, last):
        require(isinstance(value, str) and re.fullmatch(r"[0-9]{6}", value), "YYYYMM month required")
        try:
            date(int(value[:4]), int(value[4:]), 1)
        except ValueError as failure:
            raise RuntimeError("Invalid month") from failure
    lower, upper = int(first[:4]) * 12 + int(first[4:]) - 1, int(last[:4]) * 12 + int(last[4:]) - 1
    require(0 <= upper - lower < MAX_MONTHS, "Ordered window of at most twelve months required")
    today = today or datetime.now(timezone.utc).date()
    require(last < today.strftime("%Y%m"), "Current or future partial month cannot certify closed history")
    months = [f"{number // 12:04d}{number % 12 + 1:02d}" for number in range(lower, upper + 1)]
    stop = upper + 1
    return months, f"{first[:4]}-{first[4:]}-01", f"{stop // 12:04d}-{stop % 12 + 1:02d}-01"


def check_cancel(path):
    require(path is None or not Path(path).exists(), "D103 cancelled before SELECT or evidence output")


def wait_pg(connection, deadline):
    while True:
        remaining = deadline - time.monotonic()
        require(remaining > 0, "D103 PG execution/read deadline exceeded")
        state = connection.poll()
        if state == psycopg2.extensions.POLL_OK:
            return
        if state == psycopg2.extensions.POLL_READ:
            ready = socket_select.select([connection.fileno()], [], [], remaining)
        elif state == psycopg2.extensions.POLL_WRITE:
            ready = socket_select.select([], [connection.fileno()], [], remaining)
        else:
            raise RuntimeError("Unexpected PG polling state")
        require(any(ready), "D103 PG execution/read deadline exceeded")


@contextmanager
def pg_deadline(connection):
    deadline = time.monotonic() + PG_DEADLINE_SECONDS
    previous = psycopg2.extensions.get_wait_callback()
    psycopg2.extensions.set_wait_callback(lambda value: wait_pg(value, deadline))
    try:
        yield
        require(time.monotonic() < deadline, "D103 PG execution/read deadline exceeded")
    except BaseException:
        connection.close()
        raise
    finally:
        psycopg2.extensions.set_wait_callback(previous)


def native_value(value):
    if isinstance(value, datetime):
        require(value.tzinfo is None or value.utcoffset().total_seconds() == 0, "Native TIMESTAMP must be a UTC carrier")
        return value.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
    if isinstance(value, float):
        require(math.isfinite(value), "Nonfinite native DOUBLE is not a valid source value")
    return value


class ReadOnlyPG:
    def __init__(self, cancel_file=None):
        check_cancel(cancel_file)
        require(os.environ.get("APP_QUESTDB_HOST") == "127.0.0.1", "Formal SELECT target must be configured localhost")
        self.cancel_file = cancel_file
        self.connection = psycopg2.connect(host="127.0.0.1", port=8812, dbname="qdb", connect_timeout=10,
            user=os.environ["APP_QUESTDB_USERNAME"], password=os.environ["APP_QUESTDB_PASSWORD"])
        self.connection.autocommit = True

    def records(self, sql, params=None, cap=CAP):
        check_cancel(self.cancel_file)
        single_select(sql)
        require(type(cap) is int and 1 <= cap <= CAP, "Finite SELECT row cap required")
        with pg_deadline(self.connection):
            with self.connection.cursor() as cursor:
                cursor.execute(sql, params)
                columns = [item[0] for item in cursor.description]
                require(len(set(columns)) == len(columns), "Duplicate SELECT fields")
                rows = cursor.fetchmany(cap + 1)
        require(len(rows) <= cap, "SELECT row sentinel reached; source completeness unknown")
        check_cancel(self.cancel_file)
        return [{name: native_value(value) for name, value in zip(columns, row)} for row in rows]

    def close(self):
        self.connection.close()


def one(reader, sql, params=None):
    rows = reader.records(sql, params, cap=1)
    require(len(rows) == 1, "Exactly one metadata/count row required")
    return rows[0]


def exact_count(value):
    require(type(value) is int and 0 <= value <= 9223372036854775807, "Exact nonnegative COUNT required")
    return value


def table_state(reader, table):
    require(table in (SOURCE, TARGET), "D103 table whitelist required")
    rows = reader.records("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=%s LIMIT 2", (table,), cap=2)
    require(len(rows) <= 1, "Ambiguous table identity")
    if not rows:
        require(table == TARGET, "Actual index_monthly source is absent")
        return {"exists": False}
    physical = rows[0]
    wal = one(reader, "SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=%s LIMIT 2", (table,))
    count = exact_count(one(reader, f"SELECT count() AS n FROM {table}")["n"])
    require(type(physical["id"]) is int and physical["id"] > 0 and isinstance(physical["directoryName"], str) and physical["directoryName"].strip(), "Complete physical source/target identity required")
    for value in (physical["wal_pending_row_count"], wal["sequencerTxn"], wal["writerTxn"], wal["bufferedTxnSize"]):
        exact_count(value)
    require(type(physical["table_suspended"]) is type(wal["suspended"]) is bool, "Exact suspension flags required")
    if physical["table_row_count"] is not None:
        exact_count(physical["table_row_count"])
    require(not physical["table_suspended"] and physical["wal_pending_row_count"] == 0 and not wal["suspended"] and wal["bufferedTxnSize"] == 0 and wal["sequencerTxn"] == wal["writerTxn"], "Source/target WAL must be settled")
    require(physical["table_row_count"] == count or (physical["table_row_count"] is None and count == 0), "Raw metadata row count differs from actual COUNT")
    if physical["table_txn"] is None:
        require(table == TARGET and count == 0 and wal["sequencerTxn"] == wal["writerTxn"] == 0, "Only an actually empty new target may retain null transaction metadata")
    else:
        require(type(physical["table_txn"]) is int and physical["table_txn"] >= 0, "Physical transaction must be exact")
    return {"exists": True, "physical": physical, "wal": wal, "actual_select_count": count, "settled": True}


def literal_schema(path, class_name):
    tree = ast.parse(Path(path).read_text(encoding="utf-8-sig"))
    cls = next((node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name), None)
    require(cls is not None, "Original model class missing")
    method = next((node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == "get_questdb_schema"), None)
    require(method is not None, "Original schema method missing")
    returns = [node for node in method.body if isinstance(node, ast.Return)]
    require(len(returns) == 1, "Original model schema is no longer one literal return")
    return ast.literal_eval(returns[0].value)


def validate_schema(columns, state, expected, source):
    require(state["exists"], "Schema validation requires an existing table")
    require([row["column"] for row in columns] == list(expected["schema"]), "Native column order differs from the original full schema")
    require({row["column"]: row["type"] for row in columns} == expected["schema"], "Native full-field schema types drifted")
    require([row["column"] for row in columns if row["designated"]] == [expected["timestamp_col"]], "Designated timestamp differs")
    require({row["column"] for row in columns if row["upsertKey"]} == set(expected.get("dedup_keys", [])), "Physical UPSERT keys differ")
    physical = state["physical"]
    require(physical["partitionBy"] == "YEAR" and physical["walEnabled"] is True and physical["dedup"] is (not source), "Expected YEAR/WAL and source DEDUP=false or output DEDUP=true")


def utc_stamp(value, midnight=False, first_of_month=False):
    require(isinstance(value, str) and value.endswith("Z"), "Explicit native UTC timestamp required")
    try:
        stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as failure:
        raise RuntimeError("Invalid timestamp") from failure
    require(stamp.utcoffset().total_seconds() == 0, "UTC timestamp required")
    if midnight:
        require(stamp.hour == stamp.minute == stamp.second == stamp.microsecond == 0, "Business date must be UTC midnight")
    if first_of_month:
        require(stamp.day == 1, "Month carrier must be the first day")
    return stamp


def source_census(rows, months):
    coverage = {(month, code): [] for month in months for code in FEATURE_CODES.values()}
    keys = set()
    for row in rows:
        require(list(row) == list(SOURCE_FIELDS), "Complete fourteen ordered source fields required")
        code = row["ts_code"]
        require(code in FEATURE_CODES.values(), "Unsupported source code")
        stamp = utc_stamp(row["trade_date"], midnight=True)
        key = (code, row["trade_date"])
        require(key not in keys, "Duplicate full source key makes last-value selection ambiguous")
        keys.add(key)
        month = stamp.strftime("%Y%m")
        require((month, code) in coverage, "Source row outside the frozen month window")
        for field, kind in SOURCE_TYPES.items():
            value = row[field]
            if kind == "DOUBLE" and value is not None:
                require(type(value) is float and math.isfinite(value), "Nullable source DOUBLE must retain finite PG binary64")
            elif kind == "SYMBOL":
                require(value is None or isinstance(value, str), "Nullable SYMBOL must be text")
            elif kind == "TIMESTAMP" and value is not None:
                utc_stamp(value)
        coverage[month, code].append(row)
    require(rows == sorted(rows, key=lambda row: (row["trade_date"], row["ts_code"])), "Original chronological date/code ordering required")
    details, gaps = [], []
    for (month, code), candidates in coverage.items():
        nonnull = [row for row in candidates if row["pct_chg"] is not None]
        entry = {"month": month, "ts_code": code, "rows": len(candidates), "null_pct_chg_rows": len(candidates) - len(nonnull), "chosen_trade_date": nonnull[-1]["trade_date"] if nonnull else None,
                 "chosen_pct_chg": nonnull[-1]["pct_chg"] if nonnull else None}
        details.append(entry)
        if not nonnull:
            gaps.append({"month": month, "ts_code": code, "reason": "missing_code_month" if not candidates else "all_null_pct_chg"})
    return {"rows": len(rows), "field_values": len(rows) * 14, "duplicate_keys": 0, "coverage": details, "gaps": gaps, "multiple_trade_date_groups": [item for item in details if item["rows"] > 1], "strict_capture_sha256": canonical_sha(rows)}


def original_oracle(rows, first, last):
    source = OWNER.read_text(encoding="utf-8-sig")
    tree = ast.parse(source)
    assignment = next((node for node in tree.body if isinstance(node, ast.Assign) and any(isinstance(name, ast.Name) and name.id == "INDEX_FEATURE_CODES" for name in node.targets)), None)
    require(assignment is not None and ast.literal_eval(assignment.value) == FEATURE_CODES, "Original feature-code binding changed")
    target_schema = literal_schema(TARGET_MODEL, "EquityStyleMonthly")
    require(target_schema["schema"] == OUTPUT_TYPES and list(target_schema["schema"]) == list(OUTPUT_FIELDS), "Original thirty-column output schema changed")
    names = ("_load_index_monthly_returns", "_build_feature_frame")
    nodes = [next((node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name), None) for name in names]
    require(all(nodes), "Original pure oracle functions missing")
    for node in nodes:
        require(not any(isinstance(item, (ast.Import, ast.ImportFrom)) for item in ast.walk(node)), "Oracle function unexpectedly imports code")
        require(not any(isinstance(item, ast.Attribute) and item.attr in ("write_model", "ensure_table_exists", "create_table_if_not_exists") for item in ast.walk(node)), "Oracle unexpectedly gained a write path")
    query_codes = "', '".join(FEATURE_CODES.values())
    original_query = f"SELECT ts_code, trade_date, pct_chg FROM index_monthly WHERE ts_code IN ('{query_codes}') ORDER BY trade_date, ts_code"
    calls = []
    class FrameClient:
        def fetch_df(self, sql):
            single_select(sql)
            require(" ".join(sql.split()) == original_query, "Only the original fixed owner SELECT is admitted to the bounded frame shim")
            calls.append(sql)
            return pd.DataFrame([{field: row[field] for field in ("ts_code", "trade_date", "pct_chg")} for row in rows], columns=("ts_code", "trade_date", "pct_chg"))
    class SchemaOnlyModel:
        @staticmethod
        def get_questdb_schema():
            return target_schema
    namespace = {"__builtins__": {"list": list}, "pd": pd, "Optional": Optional, "INDEX_FEATURE_CODES": FEATURE_CODES, "questdb_client": FrameClient(), "EquityStyleMonthly": SchemaOnlyModel}
    module = ast.Module(body=nodes, type_ignores=[])
    exec(compile(module, str(OWNER), "exec"), namespace)
    frame = namespace["_load_index_monthly_returns"](start_month=first, end_month=last)
    result = namespace["_build_feature_frame"](frame)
    require(len(calls) == 1, "Original oracle must fetch the bounded frame exactly once")
    output = []
    for record in result.to_dict("records"):
        normalized = {}
        for field in OUTPUT_FIELDS:
            value = record[field]
            if field == "month":
                normalized[field] = f"{value[:4]}-{value[4:]}-01T00:00:00Z"
            else:
                normalized[field] = None if pd.isna(value) else float(value)
                require(normalized[field] is None or math.isfinite(normalized[field]), "Oracle produced a nonfinite value")
        output.append(normalized)
    return output, {"original_owner_source_sha256": digest(OWNER), "original_functions_ast_sha256": canonical_sha(ast.dump(module, include_attributes=False)), "original_function_names": list(names), "original_query": calls[0], "original_query_sha256": sha_bytes(calls[0].encode("utf-8")), "bounded_fetch_adaptation": "Original owner SELECT has no date predicates and normally fetches all history. The audited finite full native frame is projected to ts_code/trade_date/pct_chg by a schema-only client; original AST load/pivot and feature functions execute unchanged. No reference module imports or build/write entrypoint.", "pandas_version": pd.__version__}


def raw_bits(value):
    require(value is None or (type(value) is float and math.isfinite(value)), "Nullable finite binary64 required")
    return None if value is None else struct.pack(">d", value).hex()


def rows_by_key(rows, fields, key):
    result = {}
    for row in rows:
        require(list(row) == list(fields), "Full ordered output fields required")
        stamp = utc_stamp(row[key], midnight=True, first_of_month=True)
        normalized_key = stamp.strftime("%Y%m")
        require(normalized_key not in result, "Duplicate monthly output key")
        for field in fields[1:]:
            raw_bits(row[field])
        result[normalized_key] = row
    return result


def compare_output(actual, expected):
    left, right = rows_by_key(actual, OUTPUT_FIELDS, "month"), rows_by_key(expected, OUTPUT_FIELDS, "month")
    differences = []
    for key in sorted(left.keys() | right.keys()):
        if key not in left or key not in right:
            differences.append({"month": key, "reason": "missing_output" if key not in left else "extra_output"})
            continue
        for field in OUTPUT_FIELDS[1:]:
            a, b = raw_bits(left[key][field]), raw_bits(right[key][field])
            if a != b:
                differences.append({"month": key, "field": field, "actual": left[key][field], "expected": right[key][field], "actual_raw_bits": a, "expected_raw_bits": b})
    return {"passed": not differences, "expected_rows": len(right), "actual_rows": len(left), "full_field_comparisons": len(left.keys() & right.keys()) * 30, "double_rawbit_comparisons": len(left.keys() & right.keys()) * 29, "double_tolerance": 0, "differences": differences}


def output_path(value):
    path = Path(value).resolve()
    require(path.is_relative_to(DIRECTORY.resolve()) and path.suffix == ".json", "New evidence must be a D103 commands JSON")
    require(not path.exists(), "Evidence is immutable; choose a new output identity")
    return path


def save_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n"); stream.flush(); os.fsync(stream.fileno())


def save_rows_new(path, rows, types):
    require(not path.exists(), "Captured JSONL already exists; no overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            payload = {"values": row, "raw_double_bits": {field: raw_bits(row[field]) for field, kind in types.items() if kind == "DOUBLE"}}
            stream.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")
        stream.flush(); os.fsync(stream.fileno())
    return {"path": str(path), "sha256": digest(path), "rows": len(rows), "field_values": len(rows) * len(types), "canonical_records_sha256": canonical_sha(rows), "physical_types": types, "JSONL_shape": "values=all ordered physical columns; raw_double_bits=big-endian IEEE754 hex or explicit null"}


def audit(reader, result, first, last, output, cancel_file=None):
    months, start, stop = month_window(first, last)
    result.update({"months": months, "range_from_inclusive": start, "range_to_exclusive": stop, "codes": FEATURE_CODES,
        "output_key": ["month"], "source_business_key": ["ts_code", "trade_date"], "source_required_types": SOURCE_TYPES, "output_required_types": OUTPUT_TYPES})
    source_schema = literal_schema(SOURCE_MODEL, "IndexMonthly")
    target_schema = literal_schema(TARGET_MODEL, "EquityStyleMonthly")
    require(source_schema["schema"] == SOURCE_TYPES and list(source_schema["schema"]) == list(SOURCE_FIELDS), "Full original source schema changed")
    before = {table: table_state(reader, table) for table in (SOURCE, TARGET)}
    result["tables_before"] = before
    result["schemas"] = {}
    for table, model in ((SOURCE, source_schema), (TARGET, target_schema)):
        if before[table]["exists"]:
            columns = reader.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31)
            result["schemas"][table] = columns
            validate_schema(columns, before[table], model, source=table == SOURCE)
    placeholders = ",".join(["%s"] * 16)
    bound = f"ts_code IN ({placeholders}) AND trade_date >= %s AND trade_date < %s"
    params = (*FEATURE_CODES.values(), start, stop)
    sql = f"SELECT {','.join(SOURCE_FIELDS)} FROM {SOURCE} WHERE {bound} ORDER BY trade_date,ts_code LIMIT {CAP + 1}"
    result["source_select"] = {"sql": sql, "params": list(params), "row_cap": CAP, "statement_deadline_seconds": PG_DEADLINE_SECONDS}
    count = exact_count(one(reader, f"SELECT count() AS n FROM {SOURCE} WHERE {bound}", params)["n"])
    require(count <= CAP, "Bounded source COUNT exceeds the finite row budget")
    rows = reader.records(sql, params)
    require(len(rows) == count, "Full SELECT does not match actual bounded COUNT")
    census = source_census(rows, months)
    result["source_census"] = census
    require(not census["gaps"], "Missing or all-null code/month; incomplete materialization sample")
    check_cancel(cancel_file)
    oracle, original = original_oracle(rows, first, last)
    result["oracle"] = original
    require([utc_stamp(row["month"], midnight=True, first_of_month=True).strftime("%Y%m") for row in oracle] == months, "Oracle month coverage incomplete")
    result["expected_oracle_rows"] = oracle
    if before[TARGET]["exists"]:
        actual = reader.records(f"SELECT {','.join(OUTPUT_FIELDS)} FROM {TARGET} WHERE month >= %s AND month < %s ORDER BY month LIMIT {MAX_MONTHS + 1}", (start, stop), cap=MAX_MONTHS)
    else:
        actual = []
    result["formal_existing_output_rows"] = actual
    result["formal_existing_output_parity"] = compare_output(actual, oracle)
    after = {table: table_state(reader, table) for table in (SOURCE, TARGET)}
    result["tables_after"] = after
    require(before == after, "Source or target physical/WAL frontier changed during SELECT-only audit")
    result["stable_physical_and_wal_versions"] = True
    result["source_capture"] = save_rows_new(output.with_name(output.stem + "-source.jsonl"), rows, SOURCE_TYPES)
    result["oracle_capture"] = save_rows_new(output.with_name(output.stem + "-oracle.jsonl"), oracle, OUTPUT_TYPES)
    result["source_fixture_plan"] = {"initial_months": months[:2], "increment_months": months[2:], "requires_three_months_for_full_live_scenario": len(months) >= 3,
        "actual_source_revision_performed": False, "proposal": "Copy the first two fully captured real months into an independently attested private source; Java materialize and replay them; after its terminal ledger review append the separately captured real third month without changing earlier values, then Java incremental overlap and full readback. Fixture execution requires separate coordinator admission."}
    result["status"] = "VERIFIED_BOUNDED_SOURCE_ORACLE"
    result["formal_output_verified"] = result["formal_existing_output_parity"]["passed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-month", required=True)
    parser.add_argument("--to-month", required=True)
    parser.add_argument("--output", default=str(DIRECTORY / "equity-style-readonly-preflight-20261006.json"))
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = output_path(args.output)
    for suffix in ("-source.jsonl", "-oracle.jsonl"):
        require(not output.with_name(output.stem + suffix).exists(), "Capture output exists; use a new attempt identity")
    result = {"task_id": "D103", "checked_at": datetime.now(timezone.utc).isoformat(), "status": "FAILED", "formal_mutated": False, "private_mutated": False,
        "actions": {"DDL": 0, "ILP": 0, "provider_requests": 0, "owner_build_invocations": 0, "reference_imports": 0, "reference_writes": 0},
        "target": {"host": "127.0.0.1", "pg_port": 8812}, "python_file_sha256": {str(path): digest(path) for path in REFERENCE_FILES},
        "known_limits": ["Bounded actual source only; no provider universe or full-history completeness certification.", "Formal output parity is reported separately and is not repaired; source-oracle verification alone is not a formal migration PASS.", "Percentage-point values and original 000920.SH value / 000921.SH growth bindings are preserved; no index catalog renaming or division by100.", "Private third-month append is only proposed; no actual source increment or Java materialization is performed by this SELECT-only preflight."]}
    reader = None
    try:
        month_window(args.from_month, args.to_month)
        check_cancel(args.cancel_file)
        reader = ReadOnlyPG(args.cancel_file)
        audit(reader, result, args.from_month, args.to_month, output, args.cancel_file)
    except BaseException as failure:
        result["status"] = "FAILED"
        result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        if reader is not None:
            reader.close()
        for path, expected in result["python_file_sha256"].items():
            if digest(path) != expected:
                result["status"] = "FAILED"
                result["error"] = {"type": "ReferenceChanged", "message": "Reference source SHA changed during audit"}
        save_new(output, result)
    print(json.dumps({"task_id": "D103", "status": result["status"], "output": str(output), "sha256": digest(output), "source_rows": result.get("source_census", {}).get("rows"), "formal_output_verified": result.get("formal_output_verified"), "writes": 0}, ensure_ascii=False))
    return 0 if result["status"] == "VERIFIED_BOUNDED_SOURCE_ORACLE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
