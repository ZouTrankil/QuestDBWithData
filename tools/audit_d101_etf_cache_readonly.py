"""D101 formal SELECT-only audit of ETF sources and five stored generations.

Uses the original owner SQL, version calculation and digests without invoking
read(), install(), _publish(), any DDL or ILP. PGWire values are authoritative.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import logging
import math
import os
from pathlib import Path
import re
import struct
import sys
import types

import pandas as pd
import psycopg2

import accept_d095_mv_isolated as common

# The reference checkout stays read-only, including its import-time file logger.
log_module = types.ModuleType("config.logger")
log_module.get_logger = logging.getLogger
for name in ("app_logger", "sync_logger", "db_logger", "api_logger"):
    setattr(log_module, name, logging.getLogger("d101-audit"))
for name in ("debug", "info", "warning", "error", "critical"):
    setattr(log_module, name, getattr(logging.getLogger("d101-audit"), name))
sys.modules.setdefault("config.logger", log_module)

from quant_platform.common.persistence.questdb_query import QuestDBQueryExecutor
from quant_platform.data.adapters.questdb.market_barometer_cache import (
    MarketBarometerReadThroughCache, SPECS, _digest, _record_digest, _serialized_record,
)
from quant_platform.data.adapters.questdb.models.etf.market_data import ETFBasic, EtfDaily, ETFShare
from quant_platform.data.adapters.questdb.models.market_barometer_cache import MarketBarometerCacheCoverage
from accept_d098_mv_isolated import save, strict_compare

SPEC = SPECS["v_etf_market_overview_daily"]
CACHE, COVERAGE = SPEC.table, "market_barometer_cache_coverage"
FIELDS = (*SPEC.fields, "source_version")
RECEIPT_FIELDS = ("trade_date", "dataset_id", "source_version", "row_count", "content_digest")
SOURCE_MODELS = {"etf_basic": ETFBasic, "etf_daily": EtfDaily, "etf_share": ETFShare}
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
SOURCE_CAP = 50000
START, STOP = "2026-09-10", "2026-09-24"
OUTPUT = common.REPO_ROOT / "artifacts/java-migration/D101/commands/cache-readonly-audit-20261006.json"


def single_select(sql):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("D101 audit accepts one SELECT only")


def query(sql):
    single_select(sql)
    return common.qwp(sql)


def one(sql):
    rows = query(sql)
    if len(rows) != 1:
        raise RuntimeError("Exactly one metadata/count row required")
    return rows[0]


def canonical(value):
    if value is None or value is pd.NA or value is pd.NaT:
        return None
    if isinstance(value, (datetime, pd.Timestamp)):
        timestamp = pd.Timestamp(value)
        timestamp = timestamp.tz_localize("UTC") if timestamp.tzinfo is None else timestamp.tz_convert("UTC")
        return timestamp.isoformat().replace("+00:00", "Z")
    if isinstance(value, dict):
        return {key: canonical(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [canonical(item) for item in value]
    if hasattr(value, "item"):
        return canonical(value.item())
    if isinstance(value, float):
        if math.isnan(value):
            return None
        if not math.isfinite(value):
            raise RuntimeError("Nonfinite source/cache number cannot certify integrity")
    return value


class ReadOnlyClient:
    def __init__(self):
        if os.environ.get("APP_QUESTDB_HOST") != "127.0.0.1":
            raise RuntimeError("Formal audit is pinned to configured localhost")
        self.connection = psycopg2.connect(host="127.0.0.1", port=8812,
            user=os.environ["APP_QUESTDB_USERNAME"], password=os.environ["APP_QUESTDB_PASSWORD"],
            dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def fetch_df(self, sql, params=None):
        single_select(sql)
        with self.connection.cursor() as cursor:
            cursor.execute(sql, params)
            description = cursor.description
            frame = pd.DataFrame(cursor.fetchall(), columns=[field[0] for field in description], dtype=object)
        QuestDBQueryExecutor._exact_numeric_types(frame, description)
        for field in description:
            if field[1] in {1082, 1114, 1184}:
                frame[field[0]] = pd.to_datetime(frame[field[0]], errors="raise")
        return frame

    def records(self, sql):
        return canonical(self.fetch_df(sql).to_dict("records"))

    def close(self):
        self.connection.close()


def table_state(table):
    raw = one(f"SELECT * FROM tables() WHERE table_name='{table}'")
    physical = {field: raw[field] for field in ("id", "directoryName", "table_txn", "table_row_count",
        "table_min_timestamp", "table_max_timestamp", "partitionBy", "designatedTimestamp", "walEnabled",
        "dedup", "table_suspended", "wal_pending_row_count")}
    raw = one(f"SELECT * FROM wal_tables() WHERE name='{table}'")
    wal = {field: raw[field] for field in ("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    settled = (not physical["table_suspended"] and physical["wal_pending_row_count"] == 0 and
        not wal["suspended"] and wal["bufferedTxnSize"] == 0 and wal["sequencerTxn"] == wal["writerTxn"])
    return {"physical": physical, "wal": wal, "settled": settled}


def snapshots():
    return {table: table_state(table) for table in (CACHE, COVERAGE, *SPEC.sources)}


def schemas(before):
    result = {}
    models = {CACHE: SPEC.model, COVERAGE: MarketBarometerCacheCoverage, **SOURCE_MODELS}
    for table, model in models.items():
        expected = model.get_questdb_schema()
        columns = query(f"SELECT * FROM table_columns('{table}')")
        kinds = {row["column"]: row["type"] for row in columns}
        keys = [row["column"] for row in columns if row["upsertKey"]]
        designated = [row["column"] for row in columns if row["designated"]]
        actual = before[table]["physical"]
        if (kinds != expected["schema"] or tuple(kinds) != tuple(expected["schema"]) or
                set(keys) != set(expected["dedup_keys"]) or designated != [expected["timestamp_col"]] or
                actual["partitionBy"] != expected["partition_by"] or not actual["walEnabled"] or not actual["dedup"]):
            raise RuntimeError(f"Formal {table} differs from the complete original model schema/key/WAL/partition")
        result[table] = {"physical_types": kinds, "physical_upsert_keys": keys,
            "designated_timestamp": designated[0], "partition": actual["partitionBy"], "wal": True, "dedup": True,
            "actual_select_count": one(f"SELECT count() AS n FROM {table}")["n"]}
    return result


def current_versions(owner, dates):
    dates = pd.DatetimeIndex(pd.to_datetime(dates)).tz_localize(None).normalize().unique()
    state = owner._source_state(SPEC, dates.min(), dates.max())
    versions = owner._versions(SPEC, state, common.VIEW_SELECTS[SPEC.view], dates)
    return canonical(state), versions


def typed_cache_rows(rows):
    for row in rows:
        if tuple(row) != FIELDS or not isinstance(row["source_version"], str) or not HEX64.fullmatch(row["source_version"]):
            raise RuntimeError("All five physical fields and complete lowercase SHA generation required")
        date = pd.Timestamp(row["trade_date"])
        if pd.isna(date) or date != date.normalize() or date.tzinfo is None or date.utcoffset().total_seconds() != 0:
            raise RuntimeError("ETF cache requires exact UTC-midnight date")
        if type(row["etf_count"]) is not int or row["etf_count"] < 0:
            raise RuntimeError("ETF count is required, exact and nonnegative LONG")
        for field in ("total_share", "total_size_yi"):
            value = row[field]
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)):
                raise RuntimeError("ETF totals are nullable finite DOUBLE")


def source_plan(client, owner, result):
    bound = f"WHERE s.timestamp >= '{START}' AND s.timestamp < '{STOP}'"
    sql = common.VIEW_SELECTS[SPEC.view].format(where=bound) + " ORDER BY trade_date LIMIT 15"
    candidates = client.records(sql)
    if len(candidates) >= 15:
        raise RuntimeError("Fourteen-day direct source aggregation reached finite day sentinel")
    selected = [row for row in candidates if type(row["etf_count"]) is int and row["etf_count"] > 0][-3:]
    result["bounded_source_candidates"] = {"from_inclusive": START, "to_exclusive": STOP,
        "original_sql": sql, "aggregates": candidates, "selected_nonempty_three_days": selected}
    if len(selected) != 3:
        raise RuntimeError("This bounded actual inner join does not offer three nonempty source days")
    days = [pd.Timestamp(row["trade_date"]).strftime("%Y-%m-%d") for row in selected]
    state, versions = current_versions(owner, days)
    plan = {"status": "BOUNDED_REAL_SOURCE_READY", "days": days, "aggregate_rows": selected,
        "source_version_state": state, "original_python_versions": versions,
        "etf_basic_global_version_participation": True, "sources": {}}
    for table, model in SOURCE_MODELS.items():
        fields = tuple(model.get_questdb_schema()["schema"])
        where = "" if table in SPEC.timeless_sources else " WHERE " + " OR ".join(f"timestamp='{day}'" for day in days)
        source_sql = f"SELECT {','.join(fields)} FROM {table}{where} ORDER BY timestamp,ts_code LIMIT {SOURCE_CAP + 1}"
        count = one(f"SELECT count() AS n FROM {table}{where}")["n"]
        rows = client.records(source_sql)
        if len(rows) > SOURCE_CAP or len(rows) != count:
            raise RuntimeError(f"{table}: full real sample exceeds cap or actual full-field count differs")
        if any(row["timestamp"] is None or not row["ts_code"] for row in rows):
            raise RuntimeError("Complete source (timestamp,ts_code) identity must be non-null")
        keys = common.keyed(rows, ("timestamp", "ts_code"))
        numeric_nulls = {field: sum(row[field] is None for row in rows) for field in fields}
        payload = json.dumps(rows, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        plan["sources"][table] = {"fields": list(fields), "physical_types": model.get_questdb_schema()["schema"],
            "actual_select_count": count, "full_field_selected_count": len(rows), "complete_key_count": len(keys),
            "field_comparisons_if_fixture_copied": len(rows) * len(fields), "row_cap": SOURCE_CAP,
            "full_field_capture_sha256": hashlib.sha256(payload).hexdigest(), "null_counts": numeric_nulls,
            "sample_first_two_rows": rows[:2], "scope": "complete global basic snapshot" if not where else "complete exact three-day source keys"}
    result["bounded_actual_source_plan"] = plan


def stored_generations(client, owner, result):
    receipts = client.records(f"SELECT {','.join(RECEIPT_FIELDS)} FROM {COVERAGE} "
        f"WHERE dataset_id='{SPEC.dataset_id}' ORDER BY trade_date DESC,source_version DESC LIMIT 5")
    result["selected_receipts"] = receipts
    result["selected_receipt_count"] = len(receipts)
    outcomes = []
    if not receipts:
        result["generation_results"] = []
        return
    state, versions = current_versions(owner, [row["trade_date"] for row in receipts])
    result["source_current_partition_state"] = state
    result["source_current_partition_versions_for_selected_dates"] = versions
    first = min(pd.Timestamp(row["trade_date"]) for row in receipts)
    stop = max(pd.Timestamp(row["trade_date"]) for row in receipts) + pd.Timedelta(days=1)
    result["range_from_inclusive"], result["range_to_exclusive"] = first.strftime("%Y-%m-%d"), stop.strftime("%Y-%m-%d")
    rows = client.records(f"SELECT {','.join(FIELDS)} FROM {CACHE} WHERE trade_date>='{first:%Y-%m-%d}' "
        f"AND trade_date<'{stop:%Y-%m-%d}' ORDER BY trade_date,source_version LIMIT 201")
    result["range_rows"] = rows
    if len(rows) > 200:
        raise RuntimeError("Historical generation range reaches the200row sentinel")
    typed_cache_rows(rows)
    result["range_complete_keys"] = len(common.keyed(rows, ("trade_date", "source_version")))
    common.keyed(receipts, ("trade_date", "dataset_id", "source_version"))
    for receipt in receipts:
        item = {"receipt": receipt, "passed": False}
        try:
            if (tuple(receipt) != RECEIPT_FIELDS or receipt["dataset_id"] != SPEC.dataset_id or
                    type(receipt["row_count"]) is not int or receipt["row_count"] not in {0, 1} or
                    not isinstance(receipt["source_version"], str) or not HEX64.fullmatch(receipt["source_version"]) or
                    not isinstance(receipt["content_digest"], str) or not HEX64.fullmatch(receipt["content_digest"])):
                raise RuntimeError("Original ETF receipt requires complete typed key/version/digest and0or1daily rows")
            day = pd.Timestamp(receipt["trade_date"]).strftime("%Y-%m-%d")
            sql = f"SELECT {','.join(FIELDS)} FROM {CACHE} WHERE trade_date='{day}' AND source_version='{receipt['source_version']}' LIMIT 2"
            actual = client.records(sql)
            item["cache_rows"], item["exact_key_row_count"] = actual, len(actual)
            if len(actual) != receipt["row_count"]:
                raise RuntimeError("Receipt row_count differs from complete generation key")
            typed_cache_rows(actual)
            item["five_field_reread_comparisons"] = strict_compare(client.records(sql), actual, FIELDS, ("trade_date", "source_version"))
            digest = _digest(pd.DataFrame(actual).reindex(columns=SPEC.fields), SPEC.fields)
            item["actual_python_dataframe_digest"] = digest
            if actual:
                item["cache_record"] = actual[0]
                item["original_python_serialized_record"] = _serialized_record(actual[0], SPEC.fields)
                item["actual_python_record_digest"] = _record_digest(actual[0], SPEC.fields)
                item["double_binary64_hex"] = {field: None if actual[0][field] is None else struct.pack(">d", float(actual[0][field])).hex()
                    for field in ("total_share", "total_size_yi")}
                item["field_python_types"] = {field: type(actual[0][field]).__name__ for field in FIELDS}
            item["receipt_digest_matches"] = digest == receipt["content_digest"]
            item["current_partition_version"] = versions[day.replace("-", "")]
            item["stored_version_equals_observed_current_partition_version"] = receipt["source_version"] == item["current_partition_version"]
            item["current_cache_hit_certified"] = False
            if not item["receipt_digest_matches"]:
                raise RuntimeError("Original four-field Python digest differs from stored ETF receipt; no repair permitted")
            item["passed"] = True
        except Exception as exc:
            item["error"] = str(exc)
        outcomes.append(item)
    result["generation_results"] = outcomes
    result["verified_generations"] = [row for row in outcomes if row["passed"]]
    result["failed_generations"] = [row for row in outcomes if not row["passed"]]
    result["verified_generation_count"] = len(result["verified_generations"])
    result["failed_generation_count"] = len(result["failed_generations"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    owner_path = common.SOURCE_ROOT / "quant_platform/data/adapters/questdb/market_barometer_cache.py"
    result = {"task_id": "D101", "status": "IN_PROGRESS", "checked_at": datetime.now(timezone.utc).isoformat(),
        "target": "formal-localhost:8812/9000", "database_operations": "SELECT only", "formal_mutated": False,
        "private_mutated": False, "authoritative_numeric_transport": "PGWire binary64", "cache_fields": list(FIELDS),
        "digest_fields": list(SPEC.fields), "complete_cache_key": ["trade_date", "source_version"],
        "spec_sources": list(SPEC.sources), "spec_timeless_sources": list(SPEC.timeless_sources),
        "python_owner_file": str(owner_path), "python_owner_file_sha256": hashlib.sha256(owner_path.read_bytes()).hexdigest(),
        "original_business_sql": common.VIEW_SELECTS[SPEC.view],
        "original_business_sql_sha256": hashlib.sha256(common.VIEW_SELECTS[SPEC.view].encode()).hexdigest(),
        "current_cache_hit_certified": False, "production_latest_certified": False,
        "ownership_basis": "Active primary ETF readthrough; parent will decide Java publisher transition; no retained decision inferred"}
    client, exit_code = None, 0
    try:
        gate = json.loads((common.REPO_ROOT / "artifacts/java-migration/D100/coordinator-review-20261006.json").read_text(encoding="utf-8"))
        if gate["task_id"] != "D100" or gate["decision"] != "accepted_for_serial_progress":
            raise RuntimeError("D100 serial coordinator prerequisite not accepted")
        before = snapshots()
        result["tables_before"] = before
        result["schemas"] = schemas(before)
        if not all(snapshot["settled"] for snapshot in before.values()):
            raise RuntimeError("ETF sources/cache/coverage must all be WAL settled")
        client = ReadOnlyClient()
        owner = MarketBarometerReadThroughCache(client)
        source_plan(client, owner, result)
        stored_generations(client, owner, result)
        after = snapshots()
        result["tables_after"] = after
        result["stable_physical_and_wal_versions"] = before == after
        if before != after:
            raise RuntimeError("Formal source/cache/coverage physical identity/txn/WAL changed during read-only audit")
        if result.get("failed_generation_count", 0):
            raise RuntimeError("Historical stored ETF receipt integrity mismatch; all diagnostics retained")
        if result["selected_receipt_count"] != 5:
            result["status"] = "INSUFFICIENT_STORED_GENERATION_EVIDENCE"
            exit_code = 1
        else:
            result["status"] = "VERIFIED_STORED_GENERATIONS_AND_BOUNDED_REAL_SOURCES"
    except Exception as exc:
        result["status"], result["error"] = "FAILED", str(exc)
        exit_code = 1
    finally:
        if client is not None:
            client.close()
        save(args.output, canonical(result))
    print(json.dumps({"task_id": "D101", "status": result["status"], "verified_generations": result.get("verified_generation_count", 0),
        "failed_generations": result.get("failed_generation_count", 0), "output": str(args.output), "error": result.get("error")}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
