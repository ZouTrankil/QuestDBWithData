"""D104: bounded formal SELECT capture and original Python AST-only oracle.

No reference module is imported. The public builder's DDL/write tail is excluded
from the compiled AST. No provider, publisher, private DB or retry is invoked.
"""
from __future__ import annotations

import argparse
import ast
import copy
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import sys
from types import SimpleNamespace
from typing import Any, Dict

import pandas as pd

sys.dont_write_bytecode = True
import preflight_d103_equity_style_readonly as transport

REPO = Path(__file__).resolve().parents[1]
REFERENCE = Path("D:/work/fund_2/back-monitor")
DIRECTORY = REPO / "artifacts/java-migration/D104/commands"
OWNER = REFERENCE / "src/quant_platform/data/adapters/materializers/macro_core_monthly.py"
MODEL_DIRECTORY = REFERENCE / "src/quant_platform/data/adapters/questdb/models/macro"
TABLE_CLASSES = {"cn_cpi": "CnCpi", "cn_ppi": "CnPpi", "cn_pmi": "CnPmi", "cn_m": "CnM", "cn_gdp": "CnGdp", "sf_month": "SfMonth", "macro_core_monthly": "MacroCoreMonthly"}
SOURCES = tuple(TABLE_CLASSES)[:-1]
TARGET = "macro_core_monthly"
OUTPUT_FIELDS = ("month", "cpi_yoy", "ppi_yoy", "pmi_mfg", "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy")
OUTPUT_TYPES = {field: "TIMESTAMP" if field == "month" else "DOUBLE" for field in OUTPUT_FIELDS}
REQUIRED = ("cpi_yoy", "ppi_yoy", "pmi_mfg", "m2_yoy", "social_financing_stock", "new_rmb_loan")
QUERY_FIELDS = {"cn_cpi": ("month", "nt_yoy"), "cn_ppi": ("month", "ppi_yoy"), "cn_pmi": ("month", "pmi010000"), "cn_m": ("month", "m2_yoy"), "cn_gdp": ("report_date", "gdp_yoy"), "sf_month": ("month", "inc_month", "stk_endval")}
ORIGINAL_QUERIES = {table: f"SELECT {', '.join(fields)} FROM {table} ORDER BY {fields[0]}" for table, fields in QUERY_FIELDS.items()}
REFERENCE_FILES = (OWNER, *(MODEL_DIRECTORY / f"{table}.py" for table in TABLE_CLASSES),
    REFERENCE / "src/quant_platform/data/derived/macro/macro_core_monthly.py",
    REFERENCE / "src/quant_platform/data/derived/workflows.py",
    REFERENCE / "src/quant_platform/data/adapters/materializers/catalog.py",
    REFERENCE / "src/quant_platform/data/adapters/materializers/registry.py",
    REFERENCE / "src/quant_platform/data/derived/regime/features_monthly.py",
    REFERENCE / "src/quant_platform/data/application/queries/macro.py")
MAX_MONTHS, PRIOR_OBSERVATIONS = 12, 12
require = transport.require
digest = transport.digest
canonical_sha = transport.canonical_sha
raw_bits = transport.raw_bits
save_new = transport.save_new
save_rows_new = transport.save_rows_new
single_select = transport.single_select
check_cancel = transport.check_cancel
month_window = transport.month_window
ReadOnlyPG = transport.ReadOnlyPG
exact_count = transport.exact_count
one = transport.one


def model_schema(table):
    """Execute only the import-free, call-free original schema method AST."""
    require(table in TABLE_CLASSES, "D104 model whitelist required")
    path = MODEL_DIRECTORY / f"{table}.py"
    tree = ast.parse(path.read_text(encoding="utf-8-sig"))
    classes = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == TABLE_CLASSES[table]]
    require(len(classes) == 1, "Unique original model class required")
    cls = classes[0]
    names = [node.target.id for node in cls.body if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name)]
    methods = [node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == "get_questdb_schema"]
    require(len(methods) == 1, "Unique original schema method required")
    method = copy.deepcopy(methods[0]); method.decorator_list = []
    require(not any(isinstance(node, (ast.Call, ast.Import, ast.ImportFrom, ast.With, ast.Try)) for node in ast.walk(method)), "Original schema AST must remain call-free and import-free")
    namespace = {"Any": Any, "Dict": Dict}
    module = ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[]))
    exec(compile(module, str(path), "exec"), namespace)
    result = namespace[method.name](SimpleNamespace(model_fields=dict.fromkeys(names)))
    require(list(result["schema"]) == names and result["table_name"] == table, "Original ordered model fields/schema differ")
    require(result["partition_by"] == "YEAR" and result["dedup_keys"] == [result["timestamp_col"]], "Original YEAR and timestamp DEDUP contract drifted")
    return result


def table_state(reader, table):
    require(table in TABLE_CLASSES, "D104 native table whitelist required")
    rows = reader.records("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=%s LIMIT 2", (table,), cap=2)
    require(len(rows) <= 1, "Ambiguous table physical identity")
    if not rows:
        require(table == TARGET, "Required actual macro source is absent")
        return {"exists": False}
    physical = rows[0]
    wal = one(reader, "SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=%s LIMIT 2", (table,))
    timestamp = "report_date" if table == "cn_gdp" else "month"
    extent = one(reader, f"SELECT count() AS n,min({timestamp}) AS min_timestamp,max({timestamp}) AS max_timestamp FROM {table}")
    count = exact_count(extent["n"])
    require(type(physical["id"]) is int and physical["id"] > 0 and isinstance(physical["directoryName"], str) and physical["directoryName"].strip(), "Complete table identity required")
    for value in (physical["wal_pending_row_count"], wal["sequencerTxn"], wal["writerTxn"], wal["bufferedTxnSize"]):
        exact_count(value)
    require(type(physical["table_suspended"]) is type(wal["suspended"]) is bool, "Exact suspension flags required")
    require(not physical["table_suspended"] and not wal["suspended"] and physical["wal_pending_row_count"] == wal["bufferedTxnSize"] == 0 and wal["sequencerTxn"] == wal["writerTxn"], "Macro source/target WAL must be settled")
    if physical["table_row_count"] is not None:
        exact_count(physical["table_row_count"])
    require(physical["table_row_count"] == count or (physical["table_row_count"] is None and count == 0), "Metadata count differs from actual COUNT")
    if physical["table_txn"] is None:
        require(table == TARGET and count == 0 and wal["sequencerTxn"] == wal["writerTxn"] == 0, "Null transaction permitted only for genuinely empty new output")
    else:
        exact_count(physical["table_txn"])
    return {"exists": True, "physical": physical, "wal": wal, "actual_select_count": count, "extent": extent, "settled": True}


def validate_schema(columns, state, model):
    require(state["exists"], "Native schema requires actual table")
    require([row["column"] for row in columns] == list(model["schema"]), "Actual full ordered columns differ from original model")
    require({row["column"]: row["type"] for row in columns} == model["schema"], "Actual full physical types differ from original model")
    require(all(type(row["designated"]) is type(row["upsertKey"]) is bool for row in columns), "Exact native schema flags required")
    require([row["column"] for row in columns if row["designated"]] == [model["timestamp_col"]], "Actual designated timestamp differs")
    require([row["column"] for row in columns if row["upsertKey"]] == model["dedup_keys"], "Actual timestamp UPSERT key differs")
    p = state["physical"]
    require(p["partitionBy"] == "YEAR" and p["walEnabled"] is True and p["dedup"] is True and p["designatedTimestamp"] == model["timestamp_col"], "Actual macro YEAR/WAL/DEDUP layout differs")


def month_key(value, monthly=True):
    return transport.utc_stamp(value, midnight=True, first_of_month=monthly).strftime("%Y%m")


def validate_rows(table, rows, model):
    fields = model["schema"]
    timestamp = model["timestamp_col"]
    keys, physical_keys, nulls = [], set(), {field: 0 for field in fields}
    for row in rows:
        require(list(row) == list(fields), "All ordered original physical fields required")
        month = month_key(row[timestamp], table != "cn_gdp")
        require(row[timestamp] not in physical_keys and month not in keys, "Duplicate physical or normalized month key is ambiguous")
        physical_keys.add(row[timestamp]); keys.append(month)
        for field, kind in fields.items():
            value = row[field]
            if value is None:
                nulls[field] += 1
                require(kind != "TIMESTAMP" and field != "quarter", "Actual business key cannot be NULL")
            elif kind == "DOUBLE":
                raw_bits(value)
            elif kind == "TIMESTAMP":
                transport.utc_stamp(value, midnight=True, first_of_month=table != "cn_gdp")
            elif kind == "STRING":
                require(isinstance(value, str) and value.strip(), "Actual nonempty STRING required")
            else:
                raise RuntimeError("Unsupported macro physical type")
        if table == "cn_gdp":
            stamp = transport.utc_stamp(row[timestamp], midnight=True)
            require(row["quarter"] == f"{stamp.year}Q{(stamp.month - 1) // 3 + 1}" and stamp.month in (3, 6, 9, 12), "GDP quarter/report month disagree")
    require(keys == sorted(keys), "Chronological source rows required")
    return {"rows": len(rows), "field_values": len(rows) * len(fields), "double_slots": len(rows) * sum(kind == "DOUBLE" for kind in fields.values()), "normalized_months": keys, "null_counts": nulls, "duplicate_physical_keys": 0, "duplicate_normalized_months": 0}


def source_census(captured, models, months):
    summaries = {table: validate_rows(table, captured[table], models[table]) for table in SOURCES}
    required = {"cn_cpi": ("nt_yoy",), "cn_ppi": ("ppi_yoy",), "cn_pmi": ("pmi010000",), "cn_m": ("m2_yoy",), "sf_month": ("inc_month", "stk_endval")}
    gaps = []
    for table, fields in required.items():
        keyed = {month_key(row["month"]): row for row in captured[table]}
        for month in months:
            if month not in keyed:
                gaps.append({"table": table, "month": month, "reason": "missing_required_month"})
            else:
                for field in fields:
                    if keyed[month][field] is None:
                        gaps.append({"table": table, "month": month, "field": field, "reason": "null_required_component"})
    for table in SOURCES:
        actual = summaries[table]["normalized_months"]
        require(all(month in months or (table == "sf_month" and month < months[0]) for month in actual), "Source outside frozen window/context")
    sf = summaries["sf_month"]["normalized_months"]
    prior = [month for month in sf if month < months[0]]
    require(len(prior) == PRIOR_OBSERVATIONS, "Acceptance sample requires exactly twelve preceding physical SF observations")
    observed = prior + [month for month in sf if month in months]
    calendar_gaps = []
    for left, right in zip(observed, observed[1:]):
        if int(right[:4]) * 12 + int(right[4:]) - (int(left[:4]) * 12 + int(left[4:])) != 1:
            calendar_gaps.append({"after": left, "before": right})
    return {"tables": summaries, "rows": sum(item["rows"] for item in summaries.values()), "field_values": sum(item["field_values"] for item in summaries.values()), "double_slots": sum(item["double_slots"] for item in summaries.values()), "required_component_gaps": gaps, "sf_prior_observation_months": prior, "sf_context_calendar_gaps": calendar_gaps, "sf_lag_unit": "12 physical observations before dedup, not necessarily 12 calendar months; gaps are diagnosed and not filled", "upstream_java_acceptance_claimed": False}


def original_oracle(captured, months, owner=OWNER):
    """Original load/merge/completeness AST, with bounded SELECT-only frame shim."""
    tree = ast.parse(Path(owner).read_text(encoding="utf-8-sig"))
    names = ("_normalize_month_column", "_deduplicate_monthly_frame", "_retain_complete_macro_months", *("_load_" + table for table in SOURCES))
    nodes = [copy.deepcopy(node) for name in names for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name]
    require(len(nodes) == len(names), "Original pure/load functions missing")
    required = [node for node in tree.body if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "_REQUIRED_MONTHLY_FIELDS" for target in node.targets)]
    require(len(required) == 1 and ast.literal_eval(required[0].value) == REQUIRED, "Original monthly completeness contract drifted")
    builder = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "build_macro_core_monthly"]
    require(len(builder) == 1, "Original builder missing")
    original = builder[0]
    cut = [index for index, node in enumerate(original.body) if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and isinstance(node.value.func.value, ast.Name) and node.value.func.value.id == "ddl_manager"]
    require(len(cut) == 1 and original.body[cut[0]].value.func.attr == "ensure_table_exists", "Unique original DDL boundary required")
    pure_builder = copy.deepcopy(original); pure_builder.name = "_readonly_original_merge"
    pure_builder.body = pure_builder.body[:cut[0]] + [ast.Return(value=ast.Name(id="result", ctx=ast.Load()))]
    module = ast.fix_missing_locations(ast.Module(body=[copy.deepcopy(required[0]), *nodes, pure_builder], type_ignores=[]))
    require(not any(isinstance(node, (ast.Import, ast.ImportFrom)) or (isinstance(node, ast.Name) and node.id in ("questdb_client", "ddl_manager", "logging")) for node in ast.walk(module)), "Readonly AST cannot retain reference clients, logging initialization or publisher")
    calls = []
    def bounded_fetch(sql):
        single_select(sql)
        matches = [table for table, original_sql in ORIGINAL_QUERIES.items() if sql == original_sql]
        require(len(matches) == 1 and matches[0] not in calls, "Only one exact original SELECT per macro source allowed")
        table = matches[0]; calls.append(table)
        return pd.DataFrame([{field: row[field] for field in QUERY_FIELDS[table]} for row in captured[table]], columns=QUERY_FIELDS[table])
    namespace = {"pd": pd, "_fetch_df": bounded_fetch, "logger": SimpleNamespace(info=lambda *_args: None), "MacroCoreMonthly": SimpleNamespace(get_questdb_schema=lambda: {"schema": OUTPUT_TYPES})}
    exec(compile(module, str(owner), "exec"), namespace)
    frame = namespace[pure_builder.name]()
    require(calls == list(SOURCES), "Original six fetches must execute exactly once in original order")
    output = []
    for record in frame.to_dict("records"):
        require(record["month"] in months, "Original completeness boundary emitted context/outside month")
        row = {"month": f"{record['month'][:4]}-{record['month'][4:]}-01T00:00:00Z"}
        for field in OUTPUT_FIELDS[1:]:
            row[field] = None if pd.isna(record[field]) else float(record[field])
            raw_bits(row[field])
        output.append(row)
    proof = {"original_owner_source_sha256": digest(owner), "original_functions_ast_sha256": canonical_sha(ast.dump(module, include_attributes=False)), "original_function_names": [*names, "build_macro_core_monthly prefix before original ensure_table_exists"], "original_queries": ORIGINAL_QUERIES, "original_query_sha256": {table: transport.sha_bytes(sql.encode("utf-8")) for table, sql in ORIGINAL_QUERIES.items()}, "pandas_version": pd.__version__, "bounded_fetch_adaptation": "Original six exact all-history SELECT strings are captured by a read-only DataFrame shim. Four monthly sources and GDP contain only the requested months; SF contains the requested months plus exactly the preceding twelve physical observations. Original loaders, normalization, pre-dedup pct_change(12,fill_method=None), dedup, outer merge and completeness code execute unchanged. The builder's DDL/write/log tail is excluded from compiled AST; no reference imports.", "public_builder_invoked": False, "reference_module_imported": False, "DDL": 0, "writes": 0}
    return output, proof


def keyed_output(rows):
    result = {}
    for row in rows:
        require(list(row) == list(OUTPUT_FIELDS), "All nine ordered output fields required")
        key = month_key(row["month"])
        require(key not in result, "Duplicate output month")
        for field in OUTPUT_FIELDS[1:]:
            raw_bits(row[field])
        result[key] = row
    return result


def compare_output(actual, expected):
    left, right = keyed_output(actual), keyed_output(expected)
    differences = []
    for month in sorted(left.keys() | right.keys()):
        if month not in left or month not in right:
            differences.append({"month": month, "reason": "missing_output" if month not in left else "extra_output"})
            continue
        for field in OUTPUT_FIELDS[1:]:
            if raw_bits(left[month][field]) != raw_bits(right[month][field]):
                differences.append({"month": month, "field": field, "actual": left[month][field], "expected": right[month][field], "actual_raw_bits": raw_bits(left[month][field]), "expected_raw_bits": raw_bits(right[month][field])})
    count = len(left.keys() & right.keys())
    return {"passed": not differences, "actual_rows": len(left), "expected_rows": len(right), "full_field_comparisons": count * 9, "nullable_double_slot_comparisons": count * 8, "double_tolerance": 0, "differences": differences}


def capture_source(reader, table, model, start, stop):
    require(table in SOURCES and model["table_name"] == table, "D104 bounded source whitelist required")
    fields = ','.join(model["schema"])
    timestamp = model["timestamp_col"]
    bound = f"{timestamp} >= %s AND {timestamp} < %s"
    sql = f"SELECT {fields} FROM {table} WHERE {bound} ORDER BY {timestamp} LIMIT {MAX_MONTHS + 1}"
    count = exact_count(one(reader, f"SELECT count() AS n FROM {table} WHERE {bound}", (start, stop))["n"])
    require(count <= MAX_MONTHS, "Per-source bounded window exceeds twelve physical observations")
    window = reader.records(sql, (start, stop), cap=MAX_MONTHS)
    require(len(window) == count, "Finite full field SELECT differs from bounded COUNT")
    queries = [{"sql": sql, "params": [start, stop], "actual_count": count, "row_cap": MAX_MONTHS}]
    prior = []
    if table == "sf_month":
        prior_sql = f"SELECT {fields} FROM sf_month WHERE month < %s ORDER BY month DESC LIMIT {PRIOR_OBSERVATIONS}"
        prior = reader.records(prior_sql, (start,), cap=PRIOR_OBSERVATIONS)
        require(len(prior) == PRIOR_OBSERVATIONS, "Exactly twelve prior physical SF observations needed for acceptance context")
        queries.append({"sql": prior_sql, "params": [start], "actual_count": len(prior), "row_cap": PRIOR_OBSERVATIONS, "older_thirteenth_observation": "intentionally outside lag context; not truncation"})
    return sorted(prior + window, key=lambda row: row[timestamp]), queries


def audit(reader, result, first, last, output, cancel_file=None):
    months, start, stop = month_window(first, last)
    models = {table: model_schema(table) for table in TABLE_CLASSES}
    require(models[TARGET]["schema"] == OUTPUT_TYPES and list(models[TARGET]["schema"]) == list(OUTPUT_FIELDS), "Original output schema drifted")
    result.update(months=months, range_from_inclusive=start, range_to_exclusive=stop, original_models=models, required_monthly_fields=list(REQUIRED))
    before = {table: table_state(reader, table) for table in TABLE_CLASSES}
    result["tables_before"] = before
    schemas = {}
    for table, model in models.items():
        if before[table]["exists"]:
            schemas[table] = reader.records("SELECT * FROM table_columns(%s)", (table,), cap=100)
            validate_schema(schemas[table], before[table], model)
    result["schemas"] = schemas
    captured, queries = {}, {}
    for table in SOURCES:
        check_cancel(cancel_file)
        captured[table], queries[table] = capture_source(reader, table, models[table], start, stop)
    result["source_selects"] = queries
    census = source_census(captured, models, months)
    result["source_census"] = census
    oracle, proof = original_oracle(captured, months)
    result["oracle"] = proof
    result["expected_oracle_rows"] = oracle
    actual = reader.records(f"SELECT {','.join(OUTPUT_FIELDS)} FROM {TARGET} WHERE month >= %s AND month < %s ORDER BY month LIMIT {MAX_MONTHS + 1}", (start, stop), cap=MAX_MONTHS) if before[TARGET]["exists"] else []
    result["formal_existing_output_rows"] = actual
    result["formal_existing_output_parity"] = compare_output(actual, oracle)
    after = {table: table_state(reader, table) for table in TABLE_CLASSES}
    schemas_after = {table: reader.records("SELECT * FROM table_columns(%s)", (table,), cap=100) for table in schemas}
    result.update(tables_after=after, schemas_after=schemas_after)
    require(before == after and schemas == schemas_after, "Actual source/output physical, WAL, COUNT, extents or schemas changed during audit")
    check_cancel(cancel_file)
    result["stable_physical_and_wal_versions"] = True
    result["source_captures"] = {table: save_rows_new(output.with_name(output.stem + f"-{table}-source.jsonl"), captured[table], models[table]["schema"]) for table in SOURCES}
    result["full_sources_sha256"] = canonical_sha({table: result["source_captures"][table]["sha256"] for table in SOURCES})
    result["oracle_capture"] = save_rows_new(output.with_name(output.stem + "-oracle.jsonl"), oracle, OUTPUT_TYPES)
    result["source_fixture_plan"] = {"initial_months": months[:2], "increment_months": months[2:], "sf_initial_context": census["sf_prior_observation_months"], "whole_fullfield_source_copy": True, "actual_source_revision_performed": False, "status": "PROPOSAL_ONLY_REQUIRES_SEPARATE_ADMISSION"}
    require(not census["required_component_gaps"] and list(keyed_output(oracle)) == months, "Required monthly source gap/NULL or owner completeness withheld requested month; not a complete acceptance window")
    result["status"] = "VERIFIED_BOUNDED_SOURCE_ORACLE"
    result["formal_output_verified"] = result["formal_existing_output_parity"]["passed"]


def output_path(value):
    path = Path(value).resolve()
    require(path.is_relative_to(DIRECTORY.resolve()) and path.suffix == ".json", "New D104 commands JSON evidence required")
    require(not path.exists(), "Evidence already exists; use a new attempt identity")
    for suffix in (*(f"-{table}-source.jsonl" for table in SOURCES), "-oracle.jsonl"):
        require(not path.with_name(path.stem + suffix).exists(), "Source/oracle capture already exists; no overwrite")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-month", required=True)
    parser.add_argument("--to-month", required=True)
    parser.add_argument("--output", default=str(DIRECTORY / "macro-core-readonly-preflight-20261007.json"))
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = output_path(args.output)
    result = {"task_id": "D104", "status": "FAILED", "checked_at": datetime.now(timezone.utc).isoformat(), "target": {"host": "127.0.0.1", "pg_port": 8812}, "actions": {"DDL": 0, "DML": 0, "ILP": 0, "provider_requests": 0, "owner_public_builder": 0, "reference_imports": 0, "reference_writes": 0}, "formal_mutated": False, "private_mutated": False, "statement_deadline_seconds": transport.PG_DEADLINE_SECONDS, "python_file_sha256": {str(path): digest(path) for path in REFERENCE_FILES}, "transport_helper_sha256": digest(transport.__file__), "known_limits": ["Only the bounded real six-source snapshot is certified; D065-D070 Java upstream jobs remain planned and are not accepted by this tool.", "SF pct_change uses twelve preceding sorted physical observations before dedup; no missing calendar month fill, scaling or alternative loan field.", "GDP contributes only its report_date month; nullable GDP and lagged SF YoY are preserved; six monthly required components gate complete materialization.", "Existing formal output parity is separate and never repaired. No formal/provider/private writes or actual source increment are performed."]}
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
                result["error"] = {"type": "ReferenceChanged", "message": "Original Python source changed during audit"}
        if digest(transport.__file__) != result["transport_helper_sha256"]:
            result["status"] = "FAILED"
            result["error"] = {"type": "TransportChanged", "message": "Frozen readonly transport helper changed during audit"}
        save_new(output, result)
    print(json.dumps({"task_id": "D104", "status": result["status"], "output": str(output), "sha256": digest(output), "source_rows": result.get("source_census", {}).get("rows"), "source_fields": result.get("source_census", {}).get("field_values"), "formal_output_verified": result.get("formal_output_verified"), "writes": 0}, ensure_ascii=False))
    return 0 if result["status"] == "VERIFIED_BOUNDED_SOURCE_ORACLE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
