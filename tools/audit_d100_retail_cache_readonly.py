"""D100 formal SELECT-only audit of five stored retail-cache generations.

A stored generation's receipt integrity does not certify a current cache hit.
This script never invokes read(), install() or a publisher/lifecycle operation.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import logging
import math
import struct
import sys
import types
from pathlib import Path
import re

import pandas as pd

import accept_d095_mv_isolated as formal
# Reference Python is read-only; suppress only its import-time file logging.
log_module = types.ModuleType("config.logger")
log_module.get_logger = logging.getLogger
for name in ("app_logger", "sync_logger", "db_logger", "api_logger"):
    setattr(log_module, name, logging.getLogger("d100-audit"))
for name in ("debug", "info", "warning", "error", "critical"):
    setattr(log_module, name, getattr(logging.getLogger("d100-audit"), name))
sys.modules.setdefault("config.logger", log_module)
from quant_platform.data.adapters.questdb.market_barometer_cache import (
    MarketBarometerReadThroughCache, SPECS, _record_digest, _digest, _serialized_record,
)

SPEC = SPECS["v_retail_sentiment_daily"]
CACHE = SPEC.table
COVERAGE = "market_barometer_cache_coverage"
FIELDS = (*SPEC.fields, "source_version")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
OUTPUT = formal.REPO_ROOT / "artifacts/java-migration/D100/commands/cache-readonly-audit-20261006.json"


class InsufficientEvidence(RuntimeError):
    pass


def query(sql):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("D100 accepts a single SELECT statement only")
    return formal.qwp(sql)


def one(sql):
    rows = query(sql)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one metadata/result row required: {sql}")
    return rows[0]


def table_state(name):
    row = one(f"SELECT * FROM tables() WHERE table_name='{name}'")
    table = {field: row[field] for field in (
        "id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp",
        "table_max_timestamp", "partitionBy", "walEnabled", "dedup", "designatedTimestamp",
        "table_suspended", "wal_pending_row_count")}
    row = one(f"SELECT * FROM wal_tables() WHERE name='{name}'")
    wal = {field: row[field] for field in ("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    settled = (not table["table_suspended"] and not wal["suspended"] and
               table["wal_pending_row_count"] == 0 and wal["bufferedTxnSize"] == 0 and
               wal["sequencerTxn"] == wal["writerTxn"])
    return {"physical": table, "wal": wal, "settled": settled}


def snapshots():
    return {name: table_state(name) for name in (CACHE, COVERAGE, *SPEC.sources)}


def schema_check(before):
    columns = query(f"SELECT * FROM table_columns('{CACHE}')")
    expected = SPEC.model.get_questdb_schema()
    actual = {row["column"]: row["type"] for row in columns}
    keys = [row["column"] for row in columns if row["upsertKey"]]
    designated = [row["column"] for row in columns if row["designated"]]
    physical = before[CACHE]["physical"]
    if (tuple(row["column"] for row in columns) != FIELDS or actual != expected["schema"] or
            set(keys) != set(expected["dedup_keys"]) or designated != [expected["timestamp_col"]] or
            physical["partitionBy"] != "MONTH" or not physical["walEnabled"] or not physical["dedup"]):
        raise RuntimeError("D100 fourteen-field MONTH/WAL/complete DEDUP physical contract drifted")
    verify_coverage_schema(before)
    return {"physical_types": actual, "columns": list(FIELDS), "physical_upsert_keys": keys,
            "designated_timestamp": designated[0], "partition": "MONTH", "wal": True, "dedup": True}


def float_bits(row):
    return {field: None if row[field] is None else struct.pack(">d", float(row[field])).hex()
            for field, kind in SPEC.model.get_questdb_schema()["schema"].items() if kind == "DOUBLE"}


def validate_cache_records(rows):
    kinds = SPEC.model.get_questdb_schema()["schema"]
    for row in rows:
        if tuple(row) != FIELDS or not isinstance(row["source_version"], str) or not HEX64.fullmatch(row["source_version"]):
            raise RuntimeError("Complete fourteen-field cache record and lowercase SHA-256 version required")
        timestamp = pd.Timestamp(row["trade_date"])
        if pd.isna(timestamp) or timestamp != timestamp.normalize() or timestamp.tzinfo is None or timestamp.utcoffset().total_seconds() != 0:
            raise RuntimeError("Cache identity requires a complete UTC-midnight timestamp")
        for field, kind in kinds.items():
            value = row[field]
            if kind == "LONG" and (not isinstance(value, int) or isinstance(value, bool) or value < 0):
                raise RuntimeError(f"Required LONG counts must be exact and nonnegative: {field}")
            if kind == "DOUBLE" and value is not None and (isinstance(value, bool) or
                    not isinstance(value, (int, float)) or not math.isfinite(value)):
                raise RuntimeError(f"Nullable DOUBLE must be finite: {field}")


def strict_compare(actual, expected, fields, keys):
    from accept_d098_mv_isolated import strict_compare as frozen_compare
    return frozen_compare(actual, expected, fields, keys)


def verify_coverage_schema(before):
    from quant_platform.data.adapters.questdb.models.market_barometer_cache import MarketBarometerCacheCoverage
    expected = MarketBarometerCacheCoverage.get_questdb_schema()
    columns = query(f"SELECT * FROM table_columns('{COVERAGE}')")
    actual = {row["column"]: row["type"] for row in columns}
    physical = before[COVERAGE]["physical"]
    if (actual != expected["schema"] or tuple(actual) != tuple(expected["schema"]) or
            {row["column"] for row in columns if row["upsertKey"]} != set(expected["dedup_keys"]) or
            [row["column"] for row in columns if row["designated"]] != [expected["timestamp_col"]] or
            physical["partitionBy"] != expected["partition_by"] or not physical["walEnabled"] or not physical["dedup"]):
        raise RuntimeError("Shared owner coverage schema or complete generation key drifted")


def naive_timestamp(value):
    timestamp = pd.Timestamp(value)
    return timestamp.tz_localize(None) if timestamp.tzinfo else timestamp


def selected_receipts():
    receipts = query("SELECT trade_date,dataset_id,source_version,row_count,content_digest "
                     f"FROM {COVERAGE} WHERE dataset_id='{SPEC.dataset_id}' AND row_count > 0 "
                     "ORDER BY trade_date DESC,source_version DESC LIMIT 5")
    if len(receipts) != 5:
        raise InsufficientEvidence(f"Exactly five nonempty stored receipts required; available={len(receipts)}")
    seen = set()
    for receipt in receipts:
        date = naive_timestamp(receipt["trade_date"])
        if pd.isna(date) or date != date.normalize():
            raise RuntimeError("Breadth receipt must use the complete midnight daily timestamp")
        if (receipt["dataset_id"] != SPEC.dataset_id or receipt["row_count"] != 1 or
                not isinstance(receipt["source_version"], str) or not HEX64.fullmatch(receipt["source_version"]) or
                not isinstance(receipt["content_digest"], str) or not HEX64.fullmatch(receipt["content_digest"])):
            raise RuntimeError("Receipt dataset, single daily row count or complete digest/version differs from owner")
        key = (receipt["trade_date"], receipt["source_version"])
        if key in seen:
            raise RuntimeError("Duplicate complete coverage generation key")
        seen.add(key)
    return receipts


def current_source_partitions(before, receipts):
    state, recorded = {}, {}
    dates = pd.DatetimeIndex([naive_timestamp(row["trade_date"]) for row in receipts]).unique()
    for table in SPEC.sources:
        partitions = query("SELECT name,numRows,minTimestamp,maxTimestamp,seqTxn "
                           f"FROM table_partitions('{table}') ORDER BY name")
        selected, normalized = [], []
        for raw in partitions:
            row = dict(raw)
            row["minTimestamp"] = naive_timestamp(row["minTimestamp"])
            row["maxTimestamp"] = naive_timestamp(row["maxTimestamp"])
            if row["maxTimestamp"] >= dates.min() and row["minTimestamp"] <= dates.max():
                selected.append(raw)
                normalized.append(row)
        state[table] = (before[table]["physical"]["id"], normalized)
        recorded[table] = {"physical_id": state[table][0], "selected_partitions": selected,
                           "settled": before[table]["settled"]}
    versions = MarketBarometerReadThroughCache._versions(
        SPEC, state, formal.VIEW_SELECTS[SPEC.view], dates)
    return recorded, versions


def exact_rows(receipt):
    day = naive_timestamp(receipt["trade_date"]).strftime("%Y%m%d")
    return query(f"SELECT {','.join(FIELDS)} FROM {CACHE} "
                 f"WHERE trade_date = to_timestamp('{day}','yyyyMMdd') "
                 f"AND source_version = '{receipt['source_version']}' LIMIT 2")


def audit(result):
    before = snapshots()
    result["tables_before"] = before
    result["schema"] = schema_check(before)
    if not before[CACHE]["settled"] or not before[COVERAGE]["settled"]:
        raise RuntimeError("Stored cache and coverage WAL must be settled")
    receipts = selected_receipts()
    result["latest_five_stored_days"] = query("SELECT DISTINCT trade_date FROM " + COVERAGE +
        f" WHERE dataset_id='{SPEC.dataset_id}' AND row_count > 0 ORDER BY trade_date DESC LIMIT 5")
    result["selected_receipt_count"] = len(receipts)
    result["selected_receipts"] = receipts
    partitions, current_versions = current_source_partitions(before, receipts)
    result["source_current_partition_state"] = partitions
    result["source_current_partition_versions_for_selected_dates"] = current_versions
    result["source_current_partition_version_usable"] = all(before[name]["settled"] for name in SPEC.sources)
    first = min(naive_timestamp(row["trade_date"]) for row in receipts)
    stop = max(naive_timestamp(row["trade_date"]) for row in receipts) + pd.Timedelta(days=1)
    result["range_from_inclusive"] = first.strftime("%Y-%m-%d")
    result["range_to_exclusive"] = stop.strftime("%Y-%m-%d")
    range_rows = query(f"SELECT {','.join(FIELDS)} FROM {CACHE} "
                       f"WHERE trade_date >= '{result['range_from_inclusive']}' "
                       f"AND trade_date < '{result['range_to_exclusive']}' "
                       "ORDER BY trade_date,source_version LIMIT 201")
    result["range_rows"] = range_rows
    result["range_row_budget"] = 200
    result["range_rows_truncated"] = len(range_rows) > 200
    if len(range_rows) > 200:
        raise RuntimeError("Bounded compatibility range exceeds 200 rows; LIMIT 201 sentinel reached")
    result["range_complete_keys"] = len(formal.keyed(range_rows, ("trade_date", "source_version")))
    outcomes, verified, failed = [], [], []
    for receipt in receipts:
        item = {"receipt": receipt, "passed": False}
        try:
            rows = exact_rows(receipt)
            item["exact_key_row_count"] = len(rows)
            item["cache_rows"] = rows
            if len(rows) != receipt["row_count"]:
                raise RuntimeError("Stored receipt row_count differs from exact complete cache key")
            row = rows[0]
            item["cache_record"] = row
            if (tuple(row) != FIELDS or row["trade_date"] != receipt["trade_date"] or
                    row["source_version"] != receipt["source_version"]):
                raise RuntimeError("Eight-field projection or complete timestamp/source_version key differs")
            validate_cache_records(rows)
            item["double_binary64_hex"] = float_bits(row)
            actual_digest = _record_digest(row, SPEC.fields)
            item["actual_python_record_digest"] = actual_digest
            item["actual_python_dataframe_digest"] = _digest(pd.DataFrame(rows).reindex(columns=SPEC.fields), SPEC.fields)
            item["original_python_serialized_record"] = _serialized_record(row, SPEC.fields)
            item["field_python_types"] = {field: type(row[field]).__name__ for field in FIELDS}
            item["receipt_digest_matches"] = actual_digest == receipt["content_digest"]
            item["fields"] = list(FIELDS)
            item["fourteen_field_reread_comparisons"] = strict_compare(
                exact_rows(receipt), rows, FIELDS, ("trade_date", "source_version"))
            current_version = current_versions[naive_timestamp(receipt["trade_date"]).strftime("%Y%m%d")]
            item["current_partition_version"] = current_version
            item["stored_version_equals_observed_current_partition_version"] = receipt["source_version"] == current_version
            item["current_cache_hit_certified"] = False
            if not item["receipt_digest_matches"]:
                raise RuntimeError("Python original thirteen-field _record_digest differs from stored receipt")
            item["passed"] = True
            verified.append(item)
        except Exception as exc:
            item["error"] = str(exc)
            failed.append(item)
        outcomes.append(item)
    after = snapshots()
    result["tables_after"] = after
    result["generation_results"] = outcomes
    result["verified_generations"] = verified
    result["failed_generations"] = failed
    result["generation_count"] = len(outcomes)
    result["verified_generation_count"] = len(verified)
    result["failed_generation_count"] = len(failed)
    result["fourteen_field_reread_comparisons"] = sum(row.get("fourteen_field_reread_comparisons", 0) for row in outcomes)
    result["original_python_digest_comparisons"] = sum("actual_python_record_digest" in row for row in outcomes)
    result["stable_physical_and_wal_versions"] = before == after
    if before != after:
        raise RuntimeError("Cache, coverage or source physical identity/transaction/WAL changed during SELECT audit")
    if failed:
        raise RuntimeError(f"{len(failed)}/{len(outcomes)} stored generations fail original Python receipt integrity")
    result["status"] = "VERIFIED_STORED_GENERATIONS"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    owner = formal.SOURCE_ROOT / "quant_platform/data/adapters/questdb/market_barometer_cache.py"
    result = {"task_id": "D100", "checked_at": datetime.now(timezone.utc).isoformat(),
              "database_operations": "SELECT only", "formal_mutated": False, "writes": "none",
              "target": "formal-127.0.0.1:9000", "cache_table": CACHE, "dataset_id": SPEC.dataset_id,
              "python_owner_file": str(owner), "python_owner_file_sha256": hashlib.sha256(owner.read_bytes()).hexdigest(),
              "digest_fields": list(SPEC.fields), "complete_cache_key": ["trade_date", "source_version"],
              "current_cache_hit_certified": False, "production_latest_certified": False,
              "scope": "Latest five nonempty already stored coverage generations; no read/publish path invoked",
              "disposition_basis": "Native v_retail_sentiment_daily reads bypass this cache; nonnative compatibility/rollback reads may publish via Python readthrough owner"}
    exit_code = 0
    try:
        audit(result)
    except Exception as exc:
        result["status"] = "INSUFFICIENT_EVIDENCE" if isinstance(exc, InsufficientEvidence) else "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    from accept_d098_mv_isolated import save
    save(args.output, result)
    print(json.dumps({"task_id": "D100", "status": result["status"], "generation_count": result.get("generation_count", 0),
                      "current_cache_hit_certified": False, "output": str(args.output), "error": result.get("error")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
