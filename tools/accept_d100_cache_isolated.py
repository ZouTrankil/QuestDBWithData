"""D100 compatibility acceptance through the original Python publisher on D098 private QuestDB.

Only --execute-isolated permits creation of the two owner model tables and
bounded ILP batches. Source data, native MV and public alias are never mutated.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import copy
import math
import json
from pathlib import Path

import pandas as pd
import psycopg2
from questdb.ingress import Sender

import accept_d098_mv_isolated as fixture
import audit_d099_retail_alias as native
import audit_d100_retail_cache_readonly as integrity
from quant_platform.common.persistence.questdb_query import QuestDBQueryExecutor
from quant_platform.data.adapters.questdb.market_barometer_cache import (
    MarketBarometerReadThroughCache, SPECS, _digest, _record_digest,
)
from quant_platform.data.adapters.questdb.models.market_barometer_cache import MarketBarometerCacheCoverage

SPEC = SPECS["v_retail_sentiment_daily"]
CACHE, COVERAGE = SPEC.table, "market_barometer_cache_coverage"
FIELDS = (*SPEC.fields, "source_version")
COVERAGE_FIELDS = ("trade_date", "dataset_id", "source_version", "row_count", "content_digest")
DAYS = ("20260917", "20260918", "20260921")
MODELS = {CACHE: SPEC.model, COVERAGE: MarketBarometerCacheCoverage}
DIRECTORY = fixture.common.REPO_ROOT / "artifacts/java-migration/D100/commands"
FAILED_FIRST = DIRECTORY / "cache-isolated-first-20261006.json"
FIRST = DIRECTORY / "cache-isolated-first-resume-20261006.json"
OUTPUT = DIRECTORY / "cache-isolated-acceptance-20261006.json"
CAPTURE = DIRECTORY / "java-cache-cursor-first-20261006.json"
GATE = fixture.common.REPO_ROOT / "artifacts/java-migration/D099/coordinator-review-20261006.json"
SOURCE_BOUND = f"WHERE ts >= '{native.START}' AND ts < '{native.STOP}'"
CACHE_BOUND = f"WHERE trade_date >= '{native.START}' AND trade_date < '{native.STOP}'"


def canonical(value):
    if value is pd.NA or value is pd.NaT or value is None:
        return None
    if isinstance(value, (datetime, pd.Timestamp)):
        timestamp = pd.Timestamp(value)
        if timestamp.tzinfo:
            timestamp = timestamp.tz_convert("UTC").tz_localize(None)
        return timestamp.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    if isinstance(value, dict):
        return {key: canonical(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [canonical(item) for item in value]
    if hasattr(value, "item"):
        return canonical(value.item())
    if isinstance(value, float):
        if math.isnan(value):
            return None
        if not math.isfinite(value):
            raise RuntimeError("Nonfinite numeric value cannot be recorded as cache evidence")
    return value


def compare_fields(actual, expected, keys, fields):
    return fixture.strict_compare(actual, expected, fields, keys)


def query(sql):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("Private read helper accepts one SELECT only")
    return fixture.qwp(sql)


def one(sql):
    rows = query(sql)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one private metadata row required: {sql}")
    return rows[0]


def table_state(table):
    raw = one(f"SELECT * FROM tables() WHERE table_name='{table}'")
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


def ensure_owner_table(model, result, output, allow_create):
    schema = model.get_questdb_schema()
    table = schema["table_name"]
    if table not in MODELS or MODELS[table] is not model:
        raise RuntimeError("Only the two D100 owner models may be installed")
    existing = query(f"SELECT table_name FROM tables() WHERE table_name='{table}'")
    created = not existing
    if created:
        if not allow_create:
            raise RuntimeError("Continuation cannot recreate a missing first-stage owner table")
        columns = ",".join(f'{name} {kind}' for name, kind in schema["schema"].items())
        sql = (f"CREATE TABLE {table} ({columns}) TIMESTAMP({schema['timestamp_col']}) "
               f"PARTITION BY {schema['partition_by']} WAL DEDUP UPSERT KEYS({','.join(schema['dedup_keys'])})")
        intent = {"table": table, "sql": sql, "ack": "UNKNOWN", "automatic_retry": False}
        result["ddl_submissions"].append(intent)
        fixture.save(output, canonical(result))
        fixture.qwp(sql)
        intent["ack"] = "ACKNOWLEDGED"
        fixture.save(output, canonical(result))
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
        self.connection = psycopg2.connect(host="127.0.0.1", port=18822, user="admin", password="quest",
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
        fixture.save(self.output, canonical(self.result))

    def write_model(self, model, frame):
        schema = model.get_questdb_schema()
        table = schema["table_name"]
        if table not in MODELS or MODELS[table] is not model or len(frame) not in {1, 2}:
            raise RuntimeError("Unexpected model or batch exceeds D100 owner sample budget")
        prepared = model.prepare_questdb_dataframe(frame)
        if len(prepared) != len(frame):
            raise RuntimeError("Owner normalization changed batch row count")
        prepared_records = canonical(prepared.to_dict("records"))
        if table == CACHE:
            integrity.validate_cache_records(prepared_records)
            fixture.common.keyed(prepared_records, ("trade_date", "source_version"))
            if _digest(frame, SPEC.fields) != _digest(prepared, SPEC.fields):
                raise RuntimeError("Original model normalization changed owner business digest before any send")
        else:
            validate_coverage_records(prepared_records)
            fixture.common.keyed(prepared_records, ("trade_date", "dataset_id", "source_version"))
        fixture.PRIVATE_TARGET.verify()
        submission = {"phase": self.phase, "table": table, "submitted_rows": len(prepared),
                      "transport": "private HTTP ILP", "ack": "UNKNOWN", "automatic_retry": False,
                      "planned_rows": len(prepared), "attempted_rows": len(prepared), "acknowledged_rows": 0,
                      "attempted_rows_semantics": "durable single driver flush intent; server application requires exact readback"}
        self.result["owner_submissions"].append(submission)
        self.save()
        sender = Sender.from_conf("http::addr=127.0.0.1:19010;", auto_flush=False,
                                  retry_timeout=0, request_timeout=10000)
        try:
            sender.establish()
            sender.dataframe(prepared, table_name=table, symbols=schema["symbols"], at=schema["timestamp_col"])
            fixture.PRIVATE_TARGET.verify()
            sender.flush()
            submission["ack"] = "ACKNOWLEDGED"
            submission["acknowledged_rows"] = len(prepared)
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
            raise RuntimeError("Original real daily frame must contain exactly one retail row")
        cache.append({**records[0], "source_version": versions[day]})
        receipts.append({"trade_date": records[0]["trade_date"], "dataset_id": SPEC.dataset_id,
                         "source_version": versions[day], "row_count": 1,
                         "content_digest": _digest(frames[day], SPEC.fields)})
    return canonical(cache), canonical(receipts)


def existing_records_match(frames, versions, client):
    expected_cache, expected_receipts = expected_records(frames, versions, DAYS)
    selected_counts = {}
    for table, fields, keys, expected, suffix in (
        (CACHE, FIELDS, ("trade_date", "source_version"), expected_cache, ""),
        (COVERAGE, COVERAGE_FIELDS, ("trade_date", "dataset_id", "source_version"), expected_receipts,
         f" WHERE dataset_id='{SPEC.dataset_id}'"),
    ):
        rows = canonical(client.fetch_df(f"SELECT {','.join(fields)} FROM {table}{suffix} ORDER BY trade_date,source_version LIMIT 201").to_dict("records"))
        if len(rows) > 200:
            raise RuntimeError("Existing owner sample exceeds finite record budget; no reset will be performed")
        selected_counts[table] = len(rows)
        expected_by_key = fixture.common.keyed(expected, keys)
        fixture.common.keyed(rows, keys)
        for row in rows:
            key = tuple(row[field] for field in keys)
            if key not in expected_by_key:
                raise RuntimeError("Existing private owner generation is outside the real sample/version; no overwrite allowed")
            compare_fields([row], [expected_by_key[key]], keys, fields)
            if table == CACHE and _record_digest(row, SPEC.fields) != _record_digest(expected_by_key[key], SPEC.fields):
                raise RuntimeError("Existing private cache generation differs from exact original owner digest; no overwrite allowed")
    return {"cache_rows": selected_counts[CACHE], "coverage_dataset_rows": selected_counts[COVERAGE],
            "raw_cache_table_row_count": table_state(CACHE)["physical"]["table_row_count"],
            "row_count_evidence": "complete fourteen/five-field PGWire reads with LIMIT201; metadata null is preserved"}


def verify_generation_records(frames, versions, days, client):
    expected_cache, expected_receipts = expected_records(frames, versions, days)
    cache_rows, receipts, verified = [], [], []
    for expected, receipt in zip(expected_cache, expected_receipts):
        day = str(expected["trade_date"])[:10].replace("-", "")
        version = expected["source_version"]
        where = f"WHERE trade_date=to_timestamp('{day}','yyyyMMdd') AND source_version='{version}'"
        sql = f"SELECT {','.join(FIELDS)} FROM {CACHE} {where} LIMIT 2"
        receipt_sql = f"SELECT {','.join(COVERAGE_FIELDS)} FROM {COVERAGE} {where} AND dataset_id='{SPEC.dataset_id}' LIMIT 2"
        actual = canonical(client.fetch_df(sql).to_dict("records"))
        actual_receipt = canonical(client.fetch_df(receipt_sql).to_dict("records"))
        qwp_rows = query(sql)
        transport = transport_comparison(qwp_rows, actual)
        compare_fields(actual, [expected], ("trade_date", "source_version"), FIELDS)
        compare_fields(actual_receipt, [receipt], ("trade_date", "dataset_id", "source_version"), COVERAGE_FIELDS)
        if len(actual) != 1 or len(actual_receipt) != 1:
            raise RuntimeError("Exactly one full cache and receipt key required")
        integrity.validate_cache_records(actual)
        digest = _record_digest(actual[0], SPEC.fields)
        if digest != actual_receipt[0]["content_digest"]:
            raise RuntimeError("Original Python owner digest differs after isolated publication")
        verified.append({"receipt": actual_receipt[0], "cache_record": actual[0], "receipt_digest_matches": True,
                         "exact_key_row_count": 1, "actual_python_record_digest": digest,
                         "fourteen_field_comparisons": len(FIELDS), "double_binary64_hex": integrity.float_bits(actual[0]),
                         "authoritative_numeric_transport": "PGWire binary64", "qwp_display_records": qwp_rows,
                         "qwp_transport_comparison": transport,
                         "current_cache_hit_certified": False})
        cache_rows.extend(actual)
        receipts.extend(actual_receipt)
    return {"cache_rows": cache_rows, "coverage_receipts": receipts, "verified_generations": verified,
            "cache_field_comparisons": len(days) * len(FIELDS),
            "receipt_field_comparisons": len(days) * len(COVERAGE_FIELDS)}


def transport_comparison(display, stored):
    integrity.validate_cache_records(display)
    integrity.validate_cache_records(stored)
    left = fixture.common.keyed(display, ('trade_date', 'source_version'))
    right = fixture.common.keyed(stored, ('trade_date', 'source_version'))
    if left.keys() != right.keys():
        raise RuntimeError('QWP/PG cache complete key sets differ')
    rounded, bit_exact, comparisons = [], 0, 0
    kinds = SPEC.model.get_questdb_schema()['schema']
    for key, expected in right.items():
        for field, kind in kinds.items():
            actual, value = left[key][field], expected[field]
            comparisons += 1
            if actual is None or value is None:
                if not (actual is None and value is None):
                    raise RuntimeError('QWP display null differs from authoritative PG cache value')
                continue
            if kind != 'DOUBLE':
                if actual != value:
                    raise RuntimeError('QWP nonnumeric display key/count differs from authoritative PG value')
                continue
            a, e = float(actual), float(value)
            if integrity.struct.pack('>d', a) == integrity.struct.pack('>d', e):
                bit_exact += 1
            else:
                tolerance = 1e-12 + 2 * max(math.ulp(a), math.ulp(e))
                if abs(a - e) > tolerance:
                    raise RuntimeError('QWP display drift exceeds the explicit finite serialization bound')
                rounded.append({'trade_date': key[0], 'field': field, 'qwp': a, 'pg': e,
                                'qwp_binary64_hex': integrity.struct.pack('>d', a).hex(),
                                'pg_binary64_hex': integrity.struct.pack('>d', e).hex(),
                                'absolute_delta': abs(a - e), 'display_tolerance': tolerance})
    return {'passed': True, 'field_comparisons': comparisons, 'double_bit_exact_comparisons': bit_exact,
            'qwp_display_rounding': rounded, 'primary_storage_and_owner_digest': 'PGWire binary64 exact'}


def validate_coverage_records(rows):
    for row in rows:
        if (tuple(row) != COVERAGE_FIELDS or row['dataset_id'] != SPEC.dataset_id or row['row_count'] != 1 or
                isinstance(row['row_count'], bool) or not isinstance(row['row_count'], int) or
                not isinstance(row['source_version'], str) or not integrity.HEX64.fullmatch(row['source_version']) or
                not isinstance(row['content_digest'], str) or not integrity.HEX64.fullmatch(row['content_digest'])):
            raise RuntimeError('Only complete original retail coverage keys and one daily record may be published')
        timestamp = pd.Timestamp(row['trade_date'])
        if pd.isna(timestamp) or timestamp != timestamp.normalize() or timestamp.tzinfo is None or timestamp.utcoffset().total_seconds() != 0:
            raise RuntimeError('Coverage identity requires complete UTC-midnight timestamp')


def cache_token(snapshot):
    physical, wal = snapshot['physical'], snapshot['wal']
    return f"table:{CACHE}:id:{physical['id']}:directory:{physical['directoryName']}:txn:{physical['table_txn']}:wal-seq:{wal['sequencerTxn']}"


def java_snapshot(snapshot):
    physical, wal = snapshot['physical'], snapshot['wal']
    return {'id': physical['id'], 'directory': physical['directoryName'], 'physicalTxn': physical['table_txn'],
            'rowCount': physical['table_row_count'], 'sequenceTxn': wal['sequencerTxn'], 'writerTxn': wal['writerTxn'],
            'pendingRows': physical['wal_pending_row_count'], 'bufferedTxns': wal['bufferedTxnSize'],
            'tableSuspended': physical['table_suspended'], 'walSuspended': wal['suspended']}


def source_capture_matches(capture, protected):
    if not isinstance(capture, dict):
        return False
    source, mv = protected['tables'][native.SOURCE], protected['tables'][native.MV]
    source_wal, mv_wal, state = protected['wal'][native.SOURCE], protected['wal'][native.MV], protected['mv']
    expected = {'sourceId': source['id'], 'sourceDirectory': source['directoryName'], 'sourceTableTxn': source['table_txn'],
        'sourceSeqTxn': source_wal['sequencerTxn'], 'sourceWriterTxn': source_wal['writerTxn'], 'sourceSettled': True,
        'mvId': mv['id'], 'mvDirectory': mv['directoryName'], 'mvTxn': mv['table_txn'], 'mvSeqTxn': mv_wal['sequencerTxn'],
        'mvWriterTxn': mv_wal['writerTxn'], 'mvSettled': True, 'valid': True, 'caughtUp': True,
        'refreshBaseTxn': state['refresh_base_table_txn'], 'reportedBaseTxn': state['base_table_txn'],
        'sourcePartition': source['partitionBy'], 'viewStatus': state['view_status']}
    return all(capture.get(field) == value for field, value in expected.items())


def validate_cursor_capture(capture, first_bytes, first, current):
    expected_query = {'columns': list(FIELDS), 'equalities': {}, 'rangeColumn': 'trade_date',
                      'fromInclusive': native.START, 'toExclusive': native.STOP, 'pageSize': 1, 'cursor': None}
    expected_first = sorted(first['range_rows'], key=lambda row: (row['trade_date'], row['source_version']))[0]
    expected_java_first = {**expected_first, 'trade_date': expected_first['trade_date'][:10]}
    cursor = capture.get('nextCursor', {})
    parts = capture.get('query_fingerprint_parts')
    if not isinstance(parts, list) or len(parts) != 7:
        raise RuntimeError('Actual Java query fingerprint payload required')
    if (parts[:3] != [CACHE, 1, CACHE] or not isinstance(parts[3], str) or
            parts[4] != ['trade_date', 'source_version'] or parts[5] != list(FIELDS) or
            parts[6] != ['trade_date', 'java.time.LocalDate:' + native.START, 'java.time.LocalDate:' + native.STOP]):
        raise RuntimeError('Captured fingerprint payload differs from the exact D100 read scope')
    descriptor_positions = []
    for field, kind in SPEC.model.get_questdb_schema()['schema'].items():
        nullable = 'true' if kind == 'DOUBLE' else 'false'
        descriptor = f'Column[sourceName={field}, logicalName={field}, storageName={field}, storageType={kind}, nullable={nullable},'
        if descriptor not in parts[3]:
            raise RuntimeError('Captured Java column definition differs from the original fourteen physical contracts')
        descriptor_positions.append(parts[3].index(descriptor))
    if parts[3].count('Column[') != len(FIELDS) or descriptor_positions != sorted(descriptor_positions):
        raise RuntimeError('Captured Java fingerprint projection order or cardinality differs')
    payload_digest = hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest()
    if capture.get('query_fingerprint') != payload_digest or cursor.get('queryFingerprint') != payload_digest:
        raise RuntimeError('Captured actual cursor does not match the canonical Java query payload SHA-256')
    if (capture.get('status') != 'VERIFIED_ACTUAL_FIRST_CURSOR' or
            capture.get('first_artifact_sha256') != hashlib.sha256(first_bytes).hexdigest() or
            Path(capture.get('first_artifact', '')).resolve() != FIRST.resolve() or
            capture.get('input_target') != 'attested-private-127.0.0.1:18822/19010' or
            Path(capture.get('private_data_root', '')).resolve() != fixture.ROOT.resolve() or
            capture.get('private_process_attested_before_and_after') is not True or
            capture.get('fields') != list(FIELDS) or capture.get('query') != expected_query or
            capture.get('range_from_inclusive') != native.START or capture.get('range_to_exclusive') != native.STOP or
            capture.get('cache_snapshot_before') != java_snapshot(current) or
            capture.get('cache_snapshot_after') != java_snapshot(current) or
            current != first['tables_after'][CACHE] or current['physical']['table_row_count'] != 2 or
            not source_capture_matches(capture.get('source_snapshot_before'), first['source_mv_alias_before']) or
            capture.get('source_snapshot_before') != capture.get('source_snapshot_after') or
            capture.get('actual_first_rows') != [expected_java_first] or
            not isinstance(cursor.get('queryFingerprint'), str) or not integrity.HEX64.fullmatch(cursor['queryFingerprint']) or
            cursor.get('sourceVersion') != cache_token(current) or
            cursor.get('keyValues') != [expected_java_first['trade_date'], expected_java_first['source_version']]):
        raise RuntimeError('Actual Java cursor capture is not bound to the immutable first artifact/query/current old physical generation')


def protected_ready(observed):
    alias = observed['alias']
    return (native.ready(observed) and alias is not None and alias['view_status'] == 'valid' and
            not alias.get('invalidation_reason') and alias['view_sql'].strip() == native.ALIAS_SELECT and
            observed['tables'][native.SOURCE]['id'] == 9 and observed['tables'][native.SOURCE]['table_txn'] == 36 and
            observed['wal'][native.SOURCE]['sequencerTxn'] == 36 and
            observed['tables'][native.SOURCE]['table_row_count'] == native.EXPECTED_ROWS and
            observed['tables'][native.MV]['id'] == 11 and alias['view_table_dir_name'] == f'{native.ALIAS}~12')


def reject_existing_attempt(output):
    if output.exists():
        raise RuntimeError('This stage already has evidence; preserve it and inspect explicitly rather than retrying any publisher/DDL')


def validate_empty_ddl_recovery(audit, failed_bytes, current_tables, protected, formal, identity):
    failed = json.loads(failed_bytes)
    if (audit.get('task_id') != 'D100' or audit.get('status') != 'VERIFIED_EMPTY_TABLES_AFTER_ACKNOWLEDGED_DDL_ONLY' or
            audit.get('failed_artifact_sha256') != hashlib.sha256(failed_bytes).hexdigest() or
            Path(audit.get('failed_artifact', '')).resolve() != FAILED_FIRST.resolve() or
            audit.get('database_mutations') != 0 or audit.get('failed_evidence_preserved') is not True or
            audit.get('source_mv_alias_formal_unchanged') is not True or audit.get('stable_physical_and_wal_versions') is not True or
            audit.get('private_target_before') != identity or audit.get('private_target_after') != identity or
            audit.get('expected_pid') != identity['pid'] or failed.get('status') != 'FAILED' or
            failed.get('error') != 'First publication requires empty newly installed owner sample tables; no reset or overwrite allowed' or
            failed.get('owner_submissions') != [] or audit.get('owner_submissions') != [] or
            len(failed.get('ddl_submissions', [])) != 2 or audit.get('ddl_submissions') != failed['ddl_submissions'] or
            {row['table'] for row in failed['ddl_submissions']} != set(MODELS) or
            any(row['ack'] != 'ACKNOWLEDGED' or row['automatic_retry'] for row in failed['ddl_submissions']) or
            audit.get('tables_before') != current_tables or audit.get('tables_after') != current_tables or
            not all(snapshot['settled'] for snapshot in current_tables.values()) or
            audit.get('source_mv_alias_before') != protected or audit.get('source_mv_alias_after') != protected or
            protected != failed.get('source_mv_alias_before') or
            audit.get('formal_source_mv_alias_before') != formal or audit.get('formal_source_mv_alias_after') != formal or
            formal != failed.get('formal_source_mv_alias_before')):
        raise RuntimeError('Fresh first publication requires the unchanged explicit empty-table recovery audit and preserved DDL-only failed evidence')
    selected = audit.get('selected_tables', {})
    if set(selected) != set(MODELS):
        raise RuntimeError('Recovery proof requires both complete owner tables')
    for table, fields in ((CACHE, FIELDS), (COVERAGE, COVERAGE_FIELDS)):
        row = selected[table]
        if (row.get('fields') != list(fields) or type(row.get('select_count')) is not int or row['select_count'] != 0 or
                type(row.get('full_field_selected_count')) is not int or row['full_field_selected_count'] != 0 or
                row.get('row_limit') != 201 or row.get('records') != [] or 'raw_physical_row_count' not in row or
                row.get('raw_physical_row_count') != current_tables[table]['physical']['table_row_count']):
            raise RuntimeError('Recovery SELECT COUNT and complete finite field reads must independently certify zero rows')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute-isolated', action='store_true')
    parser.add_argument('--phase', choices=('first', 'continue'), required=True)
    parser.add_argument('--private-root', type=Path, default=fixture.ROOT)
    parser.add_argument('--expected-pid', type=int, required=True)
    parser.add_argument('--startup-attestation', type=Path, required=True)
    parser.add_argument('--empty-ddl-recovery-audit', type=Path)
    args = parser.parse_args()
    if not args.execute_isolated:
        parser.error('--execute-isolated is required for bounded original-owner publication')
    output = FIRST if args.phase == 'first' else OUTPUT
    try:
        reject_existing_attempt(output)
        if args.expected_pid < 1:
            raise RuntimeError('Explicit positive expected PID required')
        if args.phase == 'first' and args.empty_ddl_recovery_audit is None:
            raise RuntimeError('Fresh first requires explicit --empty-ddl-recovery-audit; the failed canonical artifact stays preserved')
        recovery_bytes = args.empty_ddl_recovery_audit.read_bytes() if args.phase == 'first' else None
        recovery = json.loads(recovery_bytes) if recovery_bytes is not None else None
        failed_bytes = FAILED_FIRST.read_bytes()
        startup_bytes = args.startup_attestation.read_bytes()
        startup = json.loads(startup_bytes)
        if (startup.get('pid') != args.expected_pid or Path(startup.get('data_root', '')).resolve() != fixture.ROOT.resolve() or
                startup.get('http_port') != 19010 or startup.get('pg_port') != 18822 or startup.get('root_reset') is not False or
                startup.get('source_sql_mutations') != 0 or startup.get('formal_mutations') != 0):
            raise RuntimeError('Startup proof must attest the requested PID/exact unchanged private data root and both ports')
        gate = json.loads(GATE.read_text(encoding='utf-8'))
        if gate['task_id'] != 'D099' or gate['decision'] != 'accepted_for_serial_progress':
            raise RuntimeError('D099 coordinator prerequisite is not accepted')
        first_bytes = FIRST.read_bytes() if args.phase == 'continue' else None
        first = json.loads(first_bytes) if first_bytes is not None else None
        if first is not None and (first['task_id'] != 'D100' or first['status'] != 'VERIFIED_FIRST_STORED_GENERATIONS' or
                                  first['generation_count'] != 2):
            raise RuntimeError('Continuation requires the completed immutable first publisher artifact')
        capture_bytes = CAPTURE.read_bytes() if args.phase == 'continue' else None
        capture = json.loads(capture_bytes) if capture_bytes is not None else None
    except Exception as exc:
        print(json.dumps({'task_id': 'D100', 'status': 'FAILED', 'error': str(exc), 'existing_evidence_preserved': True}))
        return 1
    result = copy.deepcopy(first) if first is not None else {
        'task_id': 'D100', 'owner': 'original MarketBarometerReadThroughCache._publish', 'owner_submissions': [],
        'ddl_submissions': [], 'phases': [], 'current_cache_hit_certified': False, 'production_latest_certified': False,
        'java_writer_exercised': False, 'java_checkpoint_exercised': False, 'formal_mutated': False,
        'target': 'private-127.0.0.1:19010/18822', 'source_projection_scope': '15 necessary real physical fields of formal110; provider/universe readiness not certified'}
    result['status'], result['stage'] = 'IN_PROGRESS', args.phase
    result['checked_at'] = datetime.now(timezone.utc).isoformat()
    client = None
    exit_code = 0
    try:
        fixture.PRIVATE_TARGET = fixture.PrivateTarget(args.private_root, args.expected_pid)
        result['private_target_attestation'] = fixture.PRIVATE_TARGET.identity
        result['input_target'] = fixture.PRIVATE_TARGET.identity
        result['startup_attestation'] = {'path': str(args.startup_attestation.resolve()),
            'sha256': hashlib.sha256(startup_bytes).hexdigest(), 'expected_pid': args.expected_pid}
        formal_before = native.state(False)
        source_before = native.state(True)
        if not protected_ready(source_before):
            raise RuntimeError('The actual D098 source9:36/MV11/publicalias12 must remain valid, caught-up and unchanged')
        if first is not None and source_before != first['source_mv_alias_before']:
            raise RuntimeError('Protected source/MV/alias changed since the first publication')
        if recovery is not None:
            validate_empty_ddl_recovery(recovery, failed_bytes, snapshots(), source_before, formal_before, fixture.PRIVATE_TARGET.identity)
            result['empty_ddl_recovery_audit'] = str(args.empty_ddl_recovery_audit.resolve())
            result['empty_ddl_recovery_audit_sha256'] = hashlib.sha256(recovery_bytes).hexdigest()
            result['failed_first_artifact'] = str(FAILED_FIRST)
            result['failed_first_artifact_sha256'] = hashlib.sha256(failed_bytes).hexdigest()
        elif first.get('failed_first_artifact_sha256') != hashlib.sha256(failed_bytes).hexdigest():
            raise RuntimeError('Historical DDL-only failed evidence changed after accepted first publication')
        result['formal_source_mv_alias_before'] = formal_before
        result['source_mv_alias_before'] = source_before
        native.source_contract(True, source_before)
        owner_file = fixture.common.SOURCE_ROOT / 'quant_platform/data/adapters/questdb/market_barometer_cache.py'
        reference_files = (owner_file, owner_file.parent / 'models/market_barometer_cache.py',
                           owner_file.parent / 'market_barometer_views.py',
                           fixture.common.SOURCE_ROOT / 'quant_platform/common/persistence/questdb_model.py')
        frozen_files = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in reference_files}
        if first is not None and frozen_files != first['frozen_python_owner_files_sha256']:
            raise RuntimeError('Original Python owner/model/SQL/normalization changed since the first publication')
        result['frozen_python_owner_files_sha256'] = frozen_files
        result['python_owner_file'] = str(owner_file)
        result['python_owner_file_sha256'] = frozen_files[str(owner_file)]
        client = OwnerClient(result, output)
        owner = MarketBarometerReadThroughCache(client)
        frame = client.fetch_df(fixture.common.VIEW_SELECTS[SPEC.view].format(where=SOURCE_BOUND) + ' ORDER BY trade_date')
        if (tuple(frame.columns) != SPEC.fields or len(frame) != 3 or
                tuple(pd.to_datetime(frame.trade_date).dt.strftime('%Y%m%d')) != DAYS):
            raise RuntimeError('Original direct-base aggregate must contain exactly the three real daily buckets')
        raw_count = one(f'SELECT count() AS n FROM {native.SOURCE} {SOURCE_BOUND}')['n']
        if raw_count != native.EXPECTED_ROWS:
            raise RuntimeError('Complete bounded real source COUNT changed from23773')
        dates = pd.DatetimeIndex(pd.to_datetime(frame.trade_date)).normalize()
        source_state = owner._source_state(SPEC, dates.min(), dates.max())
        versions = owner._versions(SPEC, source_state, fixture.common.VIEW_SELECTS[SPEC.view], dates)
        frames = {day: frame.loc[pd.to_datetime(frame.trade_date).dt.strftime('%Y%m%d').eq(day)].copy() for day in DAYS}
        if first is not None and (versions != first['original_source_partition_versions'] or
                                 canonical(frame.to_dict('records')) != first['original_source_frames']):
            raise RuntimeError('Original source frames or original partition generation versions changed after first')
        result['original_source_frames'] = canonical(frame.to_dict('records'))
        result['original_source_partition_versions'] = versions
        result['original_source_partition_state'] = canonical(source_state)
        result['source_raw_rows'] = raw_count
        result['d099_real_source_evidence'] = str(native.OUTPUT)
        result['d099_real_source_evidence_sha256'] = hashlib.sha256(native.OUTPUT.read_bytes()).hexdigest()
        result['installed_owner_tables'] = [ensure_owner_table(model, result, output, False) for model in MODELS.values()]
        fixture.common.wait_until(tables_ready, 'original owner tables settled', 30)
        existing = existing_records_match(frames, versions, client)
        result['resume_existing_records'] = existing
        if args.phase == 'continue':
            current_tables = snapshots()
            if current_tables != first['tables_after']:
                raise RuntimeError('Cache or coverage physical identity/transaction/WAL changed since the first publication')
            current = current_tables[CACHE]
            validate_cursor_capture(capture, first_bytes, first, current)
            if existing['cache_rows'] != 2 or existing['coverage_dataset_rows'] != 2:
                raise RuntimeError('Continuation requires exactly the two untouched first generation keys')
            result['first_artifact'] = str(FIRST)
            result['first_artifact_sha256'] = hashlib.sha256(first_bytes).hexdigest()
            result['java_cursor_capture'] = str(CAPTURE)
            result['java_cursor_capture_sha256'] = hashlib.sha256(capture_bytes).hexdigest()
            result['captured_actual_first_cursor'] = capture['nextCursor']
        elif existing['cache_rows'] != 0 or existing['coverage_dataset_rows'] != 0:
            raise RuntimeError('First publication requires empty newly installed owner sample tables; no reset or overwrite allowed')
        stages = (('first', DAYS[:2], DAYS[:2]),) if args.phase == 'first' else (
            ('replay', DAYS[:2], DAYS[:2]), ('increment', DAYS[2:], DAYS))
        for name, published_days, visible_days in stages:
            client.phase = name
            owner._publish(SPEC, {day: frames[day] for day in published_days}, versions)
            fixture.common.wait_until(tables_ready, f'{name} original owner WAL visibility', 30)
            check = verify_generation_records(frames, versions, visible_days, client)
            result['phases'].append({'phase': name, 'submitted_cache_rows': len(published_days),
                'submitted_coverage_rows': len(published_days), 'visible_selected_keys': len(visible_days),
                'cache_snapshot': table_state(CACHE), 'coverage_snapshot': table_state(COVERAGE), **check})
            if native.state(True) != source_before or native.state(False) != formal_before:
                raise RuntimeError('Protected real source/MV/alias/formal metadata changed during original publication')
            client.save()
        visible_days = DAYS[:2] if args.phase == 'first' else DAYS
        result['tables_before'] = snapshots()
        result.update(verify_generation_records(frames, versions, visible_days, client))
        result['range_from_inclusive'], result['range_to_exclusive'] = native.START, native.STOP
        range_sql = f"SELECT {','.join(FIELDS)} FROM {CACHE} {CACHE_BOUND} ORDER BY trade_date,source_version LIMIT 201"
        rows = canonical(client.fetch_df(range_sql).to_dict("records"))
        result['qwp_range_display_records'] = query(range_sql)
        result['qwp_range_transport_comparison'] = transport_comparison(result['qwp_range_display_records'], rows)
        if len(rows) > 200:
            raise RuntimeError('Private compatibility range exceeds the200row finite budget')
        expected_cache, _ = expected_records(frames, versions, visible_days)
        compare_fields(rows, expected_cache, ('trade_date', 'source_version'), FIELDS)
        if len(rows) != len(visible_days) or table_state(CACHE)['physical']['table_row_count'] != len(visible_days):
            raise RuntimeError('Actual cache universe differs from this stage complete generation key set')
        result['range_rows'] = rows
        result['range_complete_keys'] = len(fixture.common.keyed(rows, ('trade_date', 'source_version')))
        result['tables_after'] = snapshots()
        if result['tables_before'] != result['tables_after']:
            raise RuntimeError('Final SELECT observed cache/coverage physical or WAL changes')
        result['source_mv_alias_after'] = native.state(True)
        result['formal_source_mv_alias_after'] = native.state(False)
        if source_before != result['source_mv_alias_after'] or formal_before != result['formal_source_mv_alias_after']:
            raise RuntimeError('Protected source/MV/alias/formal metadata changed during final SELECT audit')
        result['private_target_attestation'] = fixture.PRIVATE_TARGET.verify()
        result['stable_physical_and_wal_versions'] = True
        result['source_mv_alias_unchanged'] = True
        result['formal_source_mv_alias_unchanged'] = True
        result['cache_submitted_rows'] = sum(row['submitted_rows'] for row in result['owner_submissions'] if row['table'] == CACHE)
        result['coverage_submitted_rows'] = sum(row['submitted_rows'] for row in result['owner_submissions'] if row['table'] == COVERAGE)
        result['physical_inserted_rows'] = 'unknown; actual unique keys and submitted rows are recorded independently'
        result['physical_updated_rows'] = 'unknown; exact same original generation replay follows owner DEDUP'
        result['generation_count'] = len(visible_days)
        result['status'] = 'VERIFIED_FIRST_STORED_GENERATIONS' if args.phase == 'first' else 'VERIFIED_STORED_GENERATIONS'
    except Exception as exc:
        result['status'], result['error'] = 'FAILED', str(exc)
        exit_code = 1
    finally:
        if client is not None:
            client.close()
        fixture.save(output, canonical(result))
    print(json.dumps({'task_id': 'D100', 'stage': args.phase, 'status': result['status'],
                      'generation_count': result.get('generation_count', 0), 'output': str(output), 'error': result.get('error')}))
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
