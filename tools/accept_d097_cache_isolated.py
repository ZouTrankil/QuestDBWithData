"""D097 compatibility acceptance through the original Python publisher on D095 private QuestDB.

Only --execute-isolated permits creation of the two owner model tables and
bounded ILP batches. Source data, native MV and public alias are never mutated.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

import pandas as pd
import psycopg2
from questdb.ingress import Sender

import accept_d095_mv_isolated as fixture
import audit_d096_breadth_alias as native
from quant_platform.common.persistence.questdb_query import QuestDBQueryExecutor
from quant_platform.data.adapters.questdb.market_barometer_cache import (
    MarketBarometerReadThroughCache, SPECS, _digest, _record_digest,
)
from quant_platform.data.adapters.questdb.models.market_barometer_cache import MarketBarometerCacheCoverage

SPEC = SPECS["v_market_breadth_daily"]
CACHE, COVERAGE = SPEC.table, "market_barometer_cache_coverage"
FIELDS = (*SPEC.fields, "source_version")
COVERAGE_FIELDS = ("trade_date", "dataset_id", "source_version", "row_count", "content_digest")
DAYS = ("20260917", "20260918", "20260921")
MODELS = {CACHE: SPEC.model, COVERAGE: MarketBarometerCacheCoverage}
OUTPUT = fixture.REPO_ROOT / "artifacts/java-migration/D097/commands/cache-isolated-acceptance-20261006.json"


def canonical(value):
    if isinstance(value, (datetime, pd.Timestamp)):
        timestamp = pd.Timestamp(value)
        if timestamp.tzinfo:
            timestamp = timestamp.tz_convert("UTC").tz_localize(None)
        return timestamp.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    if isinstance(value, dict):
        return {key: canonical(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [canonical(item) for item in value]
    if value is pd.NA or value is pd.NaT:
        return None
    if hasattr(value, "item"):
        return value.item()
    return value


def query(sql):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("Private read helper accepts one SELECT only")
    return fixture.qwp(sql, isolated=True)


def one(sql):
    rows = query(sql)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one private metadata row required: {sql}")
    return rows[0]


def table_state(table):
    raw = fixture.table_snapshot(table, isolated=True)
    physical = {key: raw[key] for key in (
        "id", "directoryName", "table_txn", "table_row_count", "partitionBy", "walEnabled", "dedup",
        "designatedTimestamp", "table_suspended", "wal_pending_row_count")}
    raw = one(f"SELECT * FROM wal_tables() WHERE name='{table}'")
    wal = {key: raw[key] for key in ("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    settled = (not physical["table_suspended"] and not wal["suspended"] and
               physical["wal_pending_row_count"] == 0 and wal["bufferedTxnSize"] == 0 and
               wal["sequencerTxn"] == wal["writerTxn"])
    return {"physical": physical, "wal": wal, "settled": settled}


def snapshots():
    return {table: table_state(table) for table in MODELS}


def tables_ready():
    observed = snapshots()
    return observed if all(row["settled"] for row in observed.values()) else None


def ensure_owner_table(model):
    schema = model.get_questdb_schema()
    table = schema["table_name"]
    if table not in MODELS or MODELS[table] is not model:
        raise RuntimeError("Only the two D097 owner models may be installed")
    existing = query(f"SELECT table_name FROM tables() WHERE table_name='{table}'")
    created = not existing
    if created:
        columns = ",".join(f'{name} {kind}' for name, kind in schema["schema"].items())
        sql = (f"CREATE TABLE {table} ({columns}) TIMESTAMP({schema['timestamp_col']}) "
               f"PARTITION BY {schema['partition_by']} WAL DEDUP UPSERT KEYS({','.join(schema['dedup_keys'])})")
        fixture.qwp(sql, isolated=True)
    columns = query(f"SELECT * FROM table_columns('{table}')")
    actual = {row["column"]: row["type"] for row in columns}
    keys = {row["column"] for row in columns if row["upsertKey"]}
    designated = [row["column"] for row in columns if row["designated"]]
    physical = table_state(table)["physical"]
    if (actual != schema["schema"] or tuple(actual) != tuple(schema["schema"]) or
            keys != set(schema["dedup_keys"]) or designated != [schema["timestamp_col"]] or
            physical["partitionBy"] != schema["partition_by"] or
            not physical["walEnabled"] or not physical["dedup"]):
        raise RuntimeError(f"Existing private {table} differs from original Python model contract")
    return {"table": table, "created": created, "physical_types": actual,
            "physical_upsert_keys": sorted(keys), "schema_sha256":
            hashlib.sha256(json.dumps(schema, sort_keys=True).encode()).hexdigest()}


class OwnerClient:
    """SELECT-only PG reads and allowlisted owner-model ILP batches."""

    def __init__(self, result, output):
        self.result, self.output = result, output
        self.phase = None
        fixture.PRIVATE_TARGET.verify()
        self.connection = psycopg2.connect(host="127.0.0.1", port=18812, user="admin", password="quest",
                                           dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def fetch_df(self, sql, params=None):
        if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
            raise RuntimeError("Owner client executes a single private SELECT only")
        with self.connection.cursor() as cursor:
            cursor.execute(sql, params)
            description = cursor.description
            frame = pd.DataFrame(cursor.fetchall(), columns=[field[0] for field in description], dtype=object)
        QuestDBQueryExecutor._exact_numeric_types(frame, description)
        for field in description:
            if field[1] in {1082, 1114, 1184}:
                frame[field[0]] = pd.to_datetime(frame[field[0]], errors="raise")
        return frame

    def save(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.output.write_text(json.dumps(canonical(self.result), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def write_model(self, model, frame):
        schema = model.get_questdb_schema()
        table = schema["table_name"]
        if table not in MODELS or MODELS[table] is not model or len(frame) not in {1, 2}:
            raise RuntimeError("Unexpected model or batch exceeds D097 owner sample budget")
        prepared = model.prepare_questdb_dataframe(frame)
        if len(prepared) != len(frame):
            raise RuntimeError("Owner normalization changed batch row count")
        fixture.PRIVATE_TARGET.verify()
        submission = {"phase": self.phase, "table": table, "submitted_rows": len(prepared),
                      "transport": "private HTTP ILP", "ack": "UNKNOWN", "automatic_retry": False}
        self.result["owner_submissions"].append(submission)
        self.save()
        sender = Sender.from_conf("http::addr=127.0.0.1:19000;", auto_flush=False,
                                  retry_timeout=0, request_timeout=10000)
        try:
            sender.establish()
            sender.dataframe(prepared, table_name=table, symbols=schema["symbols"], at=schema["timestamp_col"])
            fixture.PRIVATE_TARGET.verify()
            sender.flush()
            submission["ack"] = "ACKNOWLEDGED"
            self.save()
        finally:
            sender.close(flush=False)
        return {"success": True}

    def close(self):
        self.connection.close()


def expected_records(frames, versions, days):
    cache, receipts = [], []
    for day in days:
        records = frames[day].to_dict("records")
        if len(records) != 1:
            raise RuntimeError("Original real daily frame must contain exactly one breadth row")
        cache.append({**records[0], "source_version": versions[day]})
        receipts.append({"trade_date": records[0]["trade_date"], "dataset_id": SPEC.dataset_id,
                         "source_version": versions[day], "row_count": 1,
                         "content_digest": _digest(frames[day], SPEC.fields)})
    return canonical(cache), canonical(receipts)


def existing_records_match(frames, versions):
    expected_cache, expected_receipts = expected_records(frames, versions, DAYS)
    for table, fields, keys, expected, suffix in (
        (CACHE, FIELDS, ("trade_date", "source_version"), expected_cache, ""),
        (COVERAGE, COVERAGE_FIELDS, ("trade_date", "dataset_id", "source_version"), expected_receipts,
         f" WHERE dataset_id='{SPEC.dataset_id}'"),
    ):
        rows = query(f"SELECT {','.join(fields)} FROM {table}{suffix} ORDER BY trade_date,source_version LIMIT 201")
        if len(rows) > 200:
            raise RuntimeError("Existing owner sample exceeds finite record budget; no reset will be performed")
        expected_by_key = fixture.keyed(expected, keys)
        fixture.keyed(rows, keys)
        for row in rows:
            key = tuple(row[field] for field in keys)
            if key not in expected_by_key:
                raise RuntimeError("Existing private owner generation is outside the real sample/version; no overwrite allowed")
            fixture.compare_fields([row], [expected_by_key[key]], keys, fields)
            if table == CACHE and _record_digest(row, SPEC.fields) != _record_digest(expected_by_key[key], SPEC.fields):
                raise RuntimeError("Existing private cache generation differs from exact original owner digest; no overwrite allowed")
    return {"cache_rows": table_state(CACHE)["physical"]["table_row_count"],
            "coverage_dataset_rows": len(query(f"SELECT trade_date FROM {COVERAGE} WHERE dataset_id='{SPEC.dataset_id}' LIMIT 201"))}


def verify_generation_records(frames, versions, days):
    expected_cache, expected_receipts = expected_records(frames, versions, days)
    cache_rows, receipts, verified = [], [], []
    for expected, receipt in zip(expected_cache, expected_receipts):
        day = str(expected["trade_date"])[:10].replace("-", "")
        version = expected["source_version"]
        where = f"WHERE trade_date=to_timestamp('{day}','yyyyMMdd') AND source_version='{version}'"
        actual = query(f"SELECT {','.join(FIELDS)} FROM {CACHE} {where} LIMIT 2")
        actual_receipt = query(f"SELECT {','.join(COVERAGE_FIELDS)} FROM {COVERAGE} {where} "
                               f"AND dataset_id='{SPEC.dataset_id}' LIMIT 2")
        fixture.compare_fields(actual, [expected], ("trade_date", "source_version"), FIELDS)
        fixture.compare_fields(actual_receipt, [receipt], ("trade_date", "dataset_id", "source_version"), COVERAGE_FIELDS)
        if len(actual) != 1 or len(actual_receipt) != 1:
            raise RuntimeError("Exactly one full cache and receipt key required")
        digest = _record_digest(actual[0], SPEC.fields)
        if digest != actual_receipt[0]["content_digest"]:
            raise RuntimeError("Original Python owner digest differs after isolated publication")
        verified.append({"receipt": actual_receipt[0], "cache_record": actual[0], "receipt_digest_matches": True,
                         "exact_key_row_count": 1, "actual_python_record_digest": digest,
                         "eight_field_comparisons": len(FIELDS), "current_cache_hit_certified": False})
        cache_rows.extend(actual)
        receipts.extend(actual_receipt)
    return {"cache_rows": cache_rows, "coverage_receipts": receipts, "verified_generations": verified,
            "cache_field_comparisons": len(days) * len(FIELDS),
            "receipt_field_comparisons": len(days) * len(COVERAGE_FIELDS)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, default=native.PRIVATE_ROOT)
    parser.add_argument("--expected-pid", type=int, default=16980)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if not args.execute_isolated:
        parser.error("--execute-isolated is required for the bounded original-owner publication")
    result = {"task_id": "D097", "status": "IN_PROGRESS", "checked_at": datetime.now(timezone.utc).isoformat(),
              "target": "private-127.0.0.1:19000/18812", "formal_mutated": False,
              "owner": "original MarketBarometerReadThroughCache._publish", "owner_submissions": [], "phases": [],
              "current_cache_hit_certified": False, "production_latest_certified": False,
              "java_writer_exercised": False, "java_checkpoint_exercised": False}
    client = None
    exit_code = 0
    try:
        root = args.private_root.resolve(strict=True)
        if root != native.PRIVATE_ROOT.resolve(strict=True):
            raise RuntimeError("D097 is pinned to the existing D095 private data root")
        fixture.PRIVATE_TARGET = fixture.PrivateTarget(root)
        if fixture.PRIVATE_TARGET.identity["pid"] != args.expected_pid:
            raise RuntimeError("Private listener PID differs from the requested D095 sample process")
        marker = json.loads((root / "d095-fixture.json").read_text(encoding="utf-8"))
        if marker != {"task_id": "D095", "data_root": str(root), "fixture_tables": ["stk_factor", fixture.MV]}:
            raise RuntimeError("D095 original private fixture marker differs")
        result["private_target_attestation"] = fixture.PRIVATE_TARGET.identity
        result["input_target"] = fixture.PRIVATE_TARGET.identity
        source_before = native.state(True)
        if (not native.ready(source_before) or source_before["alias"] is None or
                source_before["alias"].get("view_status") != "valid" or source_before["alias"].get("invalidation_reason") or
                native.normalized(source_before["alias"]["view_sql"]) != native.normalized(native.ALIAS_SELECT) or
                source_before["tables"]["stk_factor"]["table_row_count"] != 16658):
            raise RuntimeError("D095 real source/MV/native alias must already be present, ready and unchanged")
        result["source_mv_alias_before"] = source_before
        owner_file = fixture.SOURCE_ROOT / "quant_platform/data/adapters/questdb/market_barometer_cache.py"
        result["python_owner_file"] = str(owner_file)
        result["python_owner_file_sha256"] = hashlib.sha256(owner_file.read_bytes()).hexdigest()
        client = OwnerClient(result, args.output)
        owner = MarketBarometerReadThroughCache(client)
        frame = client.fetch_df(fixture.VIEW_SELECTS[SPEC.view].format(where=native.BOUND) + " ORDER BY trade_date")
        if (tuple(frame.columns) != SPEC.fields or len(frame) != 3 or
                tuple(pd.to_datetime(frame.trade_date).dt.strftime("%Y%m%d")) != DAYS or
                sum(frame.stock_count) != 16658):
            raise RuntimeError("Original direct-base aggregate does not cover all three real days/16658 source rows")
        raw_count = one(f"SELECT count() AS n FROM stk_factor {native.BOUND}")["n"]
        if raw_count != 16658:
            raise RuntimeError("Private real source bounded COUNT changed")
        dates = pd.DatetimeIndex(pd.to_datetime(frame.trade_date)).normalize()
        source_state = owner._source_state(SPEC, dates.min(), dates.max())
        versions = owner._versions(SPEC, source_state, fixture.VIEW_SELECTS[SPEC.view], dates)
        frames = {day: frame.loc[pd.to_datetime(frame.trade_date).dt.strftime("%Y%m%d").eq(day)].copy() for day in DAYS}
        result["original_source_frames"] = canonical(frame.to_dict("records"))
        result["source_raw_rows"] = raw_count
        result["d095_real_source_evidence"] = str(native.PROVENANCE)
        result["d095_real_source_evidence_sha256"] = hashlib.sha256(native.PROVENANCE.read_bytes()).hexdigest()
        result["original_source_partition_versions"] = versions
        result["installed_owner_tables"] = [ensure_owner_table(model) for model in MODELS.values()]
        fixture.wait_until(tables_ready, "owner table WAL settled", 30)
        result["resume_existing_records"] = existing_records_match(frames, versions)
        for name, published_days, visible_days in (
            ("first", DAYS[:2], DAYS[:2]), ("replay", DAYS[:2], DAYS[:2]), ("increment", DAYS[2:], DAYS),
        ):
            client.phase = name
            owner._publish(SPEC, {day: frames[day] for day in published_days}, versions)
            fixture.wait_until(tables_ready, f"{name} owner model WAL settled", 30)
            check = verify_generation_records(frames, versions, visible_days)
            result["phases"].append({"phase": name, "submitted_cache_rows": len(published_days),
                "submitted_coverage_rows": len(published_days), "visible_selected_keys": len(visible_days),
                "cache_snapshot": table_state(CACHE), "coverage_snapshot": table_state(COVERAGE), **check})
            if native.state(True) != source_before:
                raise RuntimeError("Real stk_factor, native MV or existing public alias changed during original publication")
            client.save()
        result["tables_before"] = snapshots()
        final = verify_generation_records(frames, versions, DAYS)
        result.update(final)
        result["range_from_inclusive"], result["range_to_exclusive"] = native.START, native.STOP
        range_rows = query(f"SELECT {','.join(FIELDS)} FROM {CACHE} {native.BOUND} "
                           "ORDER BY trade_date,source_version LIMIT 201")
        if len(range_rows) > 200:
            raise RuntimeError("Private compatibility range exceeds 200-row budget")
        expected_cache, _ = expected_records(frames, versions, DAYS)
        fixture.compare_fields(range_rows, expected_cache, ("trade_date", "source_version"), FIELDS)
        if len(range_rows) != 3 or table_state(CACHE)["physical"]["table_row_count"] != 3:
            raise RuntimeError("Private cache must contain exactly the three original real generation keys")
        result["range_rows"] = range_rows
        result["range_complete_keys"] = len(fixture.keyed(range_rows, ("trade_date", "source_version")))
        result["tables_after"] = snapshots()
        if result["tables_before"] != result["tables_after"]:
            raise RuntimeError("Final isolated SELECT audit observed cache/coverage physical/WAL changes")
        result["source_mv_alias_after"] = native.state(True)
        if source_before != result["source_mv_alias_after"]:
            raise RuntimeError("Native source/MV/alias changed during final audit")
        result["private_target_attestation"] = fixture.PRIVATE_TARGET.verify()
        result["stable_physical_and_wal_versions"] = True
        result["source_mv_alias_unchanged"] = True
        result["cache_submitted_rows"] = sum(row["submitted_rows"] for row in result["owner_submissions"] if row["table"] == CACHE)
        result["coverage_submitted_rows"] = sum(row["submitted_rows"] for row in result["owner_submissions"] if row["table"] == COVERAGE)
        result["physical_inserted_rows"] = "unknown; submitted batches and verified complete keys are recorded"
        result["physical_updated_rows"] = "unknown; duplicate replay follows owner DEDUP contract"
        result["generation_count"] = 3
        result["status"] = "VERIFIED_STORED_GENERATIONS"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    finally:
        if client is not None:
            client.close()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(canonical(result), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"task_id": "D097", "status": result["status"], "generation_count": result.get("generation_count", 0),
                      "output": str(args.output), "error": result.get("error")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
