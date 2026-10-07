"""Protocol 1 gateway to the original ETF readthrough owner on attested D101.

Preview is SELECT-only. Publication requires an immutable Java intent and the
shared SQLite SLICE/SUBMITTED revision 4. No install, DDL, source write or retry.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import traceback
import uuid

import pandas as pd
import psycopg2
from questdb.ingress import Sender

import audit_d101_etf_cache_readonly as audit
import prepare_d101_etf_fixture as fixture

SPEC, CACHE, COVERAGE = audit.SPEC, audit.CACHE, audit.COVERAGE
FIELDS, RECEIPT_FIELDS = audit.FIELDS, audit.RECEIPT_FIELDS
MODELS = {CACHE: SPEC.model, COVERAGE: audit.MarketBarometerCacheCoverage}
ALL_MODELS = {**audit.SOURCE_MODELS, **MODELS}
ALLOWED_JOBS = {"data.etf_market_overview_daily_cache", "write.etf_market_overview_daily_cache"}
MAX_JSON_BYTES = 1024 * 1024


def sha(value):
    payload = json.dumps(audit.canonical(value), ensure_ascii=False, sort_keys=True,
                         separators=(",", ":"), allow_nan=False).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _unique_object(items):
    value = {}
    for key, item in items:
        if key in value:
            raise RuntimeError("Duplicate JSON object field")
        value[key] = item
    return value


def load_json(path, expected_sha=None):
    path = Path(path).resolve(strict=True)
    if not path.is_relative_to(audit.common.REPO_ROOT.resolve()):
        raise RuntimeError("Bridge artifacts and ledger must remain inside this workspace")
    data = path.read_bytes()
    if len(data) > MAX_JSON_BYTES:
        raise RuntimeError("Bridge JSON exceeds one MiB")
    actual_sha = hashlib.sha256(data).hexdigest()
    if expected_sha is not None and actual_sha != expected_sha:
        raise RuntimeError("Immutable bridge artifact SHA differs")
    return json.loads(data, object_pairs_hook=_unique_object), actual_sha


def validate_request(request):
    if (type(request.get("protocol_version")) is not int or request["protocol_version"] != 1 or
            request.get("operation") not in {"preview", "publish"} or request.get("dataset_id") != SPEC.dataset_id):
        raise RuntimeError("Only protocol1 ETF preview/publication is admitted")
    day = request.get("trade_date")
    if not isinstance(day, str) or pd.Timestamp(day).strftime("%Y-%m-%d") != day:
        raise RuntimeError("An exact one-day BusinessDate is required")
    uuid.UUID(request["invocation_id"])
    budget = request.get("max_source_rows")
    if type(budget) is not int or not 1 <= budget <= 50000:
        raise RuntimeError("Positive source budget at most50000 required")
    target = request.get("target", {})
    if (set(target) != {"private_root", "expected_pid", "http_port", "pg_port"} or
            Path(target.get("private_root", "")).resolve() != fixture.ROOT.resolve() or
            type(target.get("expected_pid")) is not int or target["expected_pid"] <= 0 or
            target.get("http_port") != 19020 or target.get("pg_port") != 18832):
        raise RuntimeError("Explicit exact D101 root, process and two private ports required")
    if request["operation"] == "publish":
        if not isinstance(request.get("intent_sha256"), str) or not audit.HEX64.fullmatch(request["intent_sha256"]):
            raise RuntimeError("Publication requires the complete immutable Java intent SHA")
        if not request.get("intent_path"):
            raise RuntimeError("Publication requires a Java intent path")


def snapshot(table):
    raw = fixture.state(table)
    physical = {field: raw["physical"][field] for field in ("id", "directoryName", "table_txn", "table_row_count",
        "table_min_timestamp", "table_max_timestamp", "partitionBy", "designatedTimestamp", "walEnabled",
        "dedup", "table_suspended", "wal_pending_row_count")}
    wal = {field: raw["wal"][field] for field in ("sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    if table in SPEC.sources and (type(physical["table_txn"]) is not int or physical["table_txn"] < 0):
        raise RuntimeError("Original source physical transaction must be initialized and exact")
    return {"physical": physical, "wal": wal, "settled": True}


def snapshots(tables):
    return {table: snapshot(table) for table in tables}


def actual_target_counts(client):
    values = {}
    for table in MODELS:
        rows = client.records(f"SELECT count() AS n FROM {table}")
        if (len(rows) != 1 or set(rows[0]) != {"n"} or type(rows[0]["n"]) is not int or
                not 0 <= rows[0]["n"] <= 9223372036854775807):
            raise RuntimeError("Actual target COUNT requires its strict nonnegative LONG")
        values[table] = rows[0]["n"]
    return values


def validate_target_counts(states, counts):
    if set(states) != set(MODELS) or set(counts) != set(MODELS):
        raise RuntimeError("Exact two target states and actual counts required")
    for table in MODELS:
        count = counts[table]
        if type(count) is not int or count < 0:
            raise RuntimeError("Actual target count cannot be absent, boolean or negative")
        p, w = states[table]["physical"], states[table]["wal"]
        txn = p["table_txn"]
        if txn is None:
            rows = p["table_row_count"]
            if (count != 0 or not (rows is None or type(rows) is int and rows == 0) or
                    type(w["sequencerTxn"]) is not int or w["sequencerTxn"] != 0 or
                    type(w["writerTxn"]) is not int or w["writerTxn"] != 0):
                raise RuntimeError("Uninitialized target requires independent actual COUNT0 and exact WAL0")
        elif type(txn) is not int or txn < 0:
            raise RuntimeError("Initialized target physical transaction must be an exact LONG")


def validate_schemas():
    result = {}
    for table, model in ALL_MODELS.items():
        contract = model.get_questdb_schema()
        rows = fixture.qwp(f"SELECT * FROM table_columns('{table}')")
        observed = snapshot(table)["physical"]
        actual = {row["column"]: row["type"] for row in rows}
        if (actual != contract["schema"] or tuple(actual) != tuple(contract["schema"]) or
                {row["column"] for row in rows if row["upsertKey"]} != set(contract["dedup_keys"]) or
                [row["column"] for row in rows if row["designated"]] != [contract["timestamp_col"]] or
                observed["partitionBy"] != contract["partition_by"] or not observed["walEnabled"] or not observed["dedup"]):
            raise RuntimeError("One of the five deployed original D101 schemas differs")
        result[table] = {"physical_types": actual, "upsert_keys": contract["dedup_keys"],
            "timestamp": contract["timestamp_col"], "partition": contract["partition_by"], "wal": True, "dedup": True}
    return result


def owner_files():
    folder = audit.common.SOURCE_ROOT / "quant_platform/data/adapters/questdb"
    files = (folder / "market_barometer_cache.py", folder / "market_barometer_views.py",
             folder / "models/market_barometer_cache.py", audit.common.SOURCE_ROOT / "quant_platform/common/persistence/questdb_model.py")
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}


def native_timestamp(value, kind):
    if kind not in {"TIMESTAMP", "TIMESTAMP_NS"}:
        raise RuntimeError("Only a verified original timestamp unit may decode metadata")
    if value is None or value is pd.NA:
        return pd.NaT
    value = audit.canonical(value)
    if type(value) is not int or not -9223372036854775807 <= value <= 9223372036854775807:
        raise RuntimeError("Metadata timestamp requires its exact finite native LONG carrier")
    result = pd.Timestamp(value, unit="ns" if kind == "TIMESTAMP_NS" else "us").as_unit("ns")
    if pd.isna(result):
        raise RuntimeError("A non-null native metadata timestamp decoded as missing")
    return result


def metadata_select(sql):
    normalized = " ".join(sql.split()).lower()
    if normalized == "select * from tables()":
        columns = fixture.qwp(sql)
        if (len(columns) != len(ALL_MODELS) or any("table_name" not in row for row in columns) or
                {row["table_name"] for row in columns} != set(ALL_MODELS)):
            raise RuntimeError("Metadata adapter requires exactly the five validated D101 tables")
        fields = tuple(columns[0])
        if any(tuple(row) != fields for row in columns) or not {"table_min_timestamp", "table_max_timestamp"}.issubset(fields):
            raise RuntimeError("Original tables metadata carrier columns differ")
        projection = [f"cast({field} AS LONG) AS {field}" if field in {"table_min_timestamp", "table_max_timestamp", "wal_max_timestamp"} else field for field in fields]
        return "SELECT " + ",".join(projection) + " FROM tables()", "tables"
    return sql, None


class OwnerClient:
    def __init__(self, target, request, result, persist):
        self.target, self.request, self.result, self.persist = target, request, result, persist
        self.preview, self.intent = None, None
        self.allowed_targets = None
        self.allowed_target_counts = None
        self.active_senders = 0
        self.sender_close_failed = False
        target.verify()
        self.connection = psycopg2.connect(host="127.0.0.1", port=18832, user="admin", password="quest",
                                          dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def fetch_df(self, sql, params=None):
        audit.single_select(sql)
        adapted, carrier = metadata_select(sql)
        try:
            with self.connection.cursor() as cursor:
                cursor.execute(adapted, params)
                description = cursor.description
                records = cursor.fetchmany(50001)
        except Exception:
            self.result["failed_readonly_select"] = {"sql": sql, "executed_sql": adapted, "parameters": audit.canonical(params)}
            raise
        if len(records) > 50000:
            raise RuntimeError("Original owner SELECT reached finite50000row sentinel")
        frame = pd.DataFrame(records, columns=[field[0] for field in description], dtype=object)
        audit.QuestDBQueryExecutor._exact_numeric_types(frame, description)
        if carrier:
            timestamp_fields = tuple(field for field in ("table_min_timestamp", "table_max_timestamp", "wal_max_timestamp") if field in frame.columns)
            for field in timestamp_fields:
                frame[field] = frame[field].astype(object)
            units = {}
            for index in frame.index:
                table = frame.at[index, "table_name"] if carrier == "tables" else carrier
                schema = ALL_MODELS[table].get_questdb_schema()
                kind = schema["schema"][schema["timestamp_col"]]
                units[table] = kind
                for field in timestamp_fields:
                    frame.at[index, field] = native_timestamp(frame.at[index, field], kind)
            for field in timestamp_fields:
                frame[field] = pd.to_datetime(frame[field], errors="raise")
            adaptation = {"original_sql": sql, "executed_sql": adapted, "native_units": units,
                "scope": "metadata LONG carrier only; original owner state/version and business SQL unchanged"}
            history = self.result.setdefault("metadata_transport_adaptations", [])
            if adaptation not in history:
                history.append(adaptation)
        for field in description:
            if field[1] in {1082, 1114, 1184}:
                frame[field[0]] = pd.to_datetime(frame[field[0]], errors="raise")
        return frame

    def records(self, sql, params=None):
        return audit.canonical(self.fetch_df(sql, params).to_dict("records"))

    def require_frozen(self):
        self.target.verify()
        if self.preview is None or self.intent is None:
            raise RuntimeError("Owner write is forbidden outside an admitted Java publication")
        if (snapshots(SPEC.sources) != self.preview["source_snapshots"] or owner_files() != self.preview["frozen_python_files_sha256"] or
                snapshots(MODELS) != self.allowed_targets or actual_target_counts(self) != self.allowed_target_counts):
            raise RuntimeError("Source, original owner or target changed before actual owner submission")
        validate_sqlite_submission(self.intent, self.preview)

    def write_model(self, model, frame):
        table = model.get_questdb_schema()["table_name"]
        if table not in MODELS or MODELS[table] is not model or len(frame) != 1:
            raise RuntimeError("Only one original ETF cache/coverage row may be submitted")
        if self.preview is None:
            raise RuntimeError("Owner write is unavailable during preview")
        prepared = model.prepare_questdb_dataframe(frame)
        records = audit.canonical(prepared.to_dict("records"))
        if len(records) != 1:
            raise RuntimeError("Owner normalization changed daily row count")
        expected = self.preview["expected_cache_records"] if table == CACHE else [self.preview["expected_receipt"]]
        fields = FIELDS if table == CACHE else RECEIPT_FIELDS
        keys = ("trade_date", "source_version") if table == CACHE else ("trade_date", "dataset_id", "source_version")
        if table == CACHE:
            audit.typed_cache_rows(records)
            if audit._digest(frame, SPEC.fields) != audit._digest(prepared, SPEC.fields):
                raise RuntimeError("Original model normalization changed the business digest")
        audit.strict_compare(records, expected, fields, keys)
        self.require_frozen()
        intent = {"table": table, "planned_rows": 1, "attempted_rows": 1, "acknowledged_rows": 0,
                  "ack": "UNKNOWN", "automatic_retry": False, "transport": "private HTTP ILP",
                  "prepared_records": records, "attempted_rows_semantics": "durable single driver flush intent; actual application requires PG readback"}
        self.result["owner_submissions"].append(intent)
        self.persist()
        sender = Sender.from_conf("http::addr=127.0.0.1:19020;", auto_flush=False, retry_timeout=0, request_timeout=10000)
        self.active_senders += 1
        try:
            sender.establish()
            schema = model.get_questdb_schema()
            sender.dataframe(prepared, table_name=table, symbols=schema["symbols"], at=schema["timestamp_col"])
            self.require_frozen()
            sender.flush()
            intent["ack"], intent["acknowledged_rows"] = "ACKNOWLEDGED", 1
            self.persist()
        finally:
            try:
                sender.close(flush=False)
            except Exception:
                self.sender_close_failed = True
                raise
            finally:
                self.active_senders -= 1
        fixture.wait(table)
        current = snapshots(MODELS)
        current_counts = actual_target_counts(self)
        validate_target_counts(current, current_counts)
        previous = self.allowed_targets
        for name in MODELS:
            if name != table and current[name] != previous[name]:
                raise RuntimeError("Other owner target changed during isolated submission")
            if name != table and current_counts[name] != self.allowed_target_counts[name]:
                raise RuntimeError("Other owner target actual count changed during isolated submission")
            if (current[name]["physical"]["id"] != previous[name]["physical"]["id"] or
                    current[name]["physical"]["directoryName"] != previous[name]["physical"]["directoryName"]):
                raise RuntimeError("Owner table identity changed after acknowledgement")
        if (current[table]["wal"]["sequencerTxn"] != previous[table]["wal"]["sequencerTxn"] + 1 or
                snapshots(SPEC.sources) != self.preview["source_snapshots"]):
            raise RuntimeError("Unexpected target WAL frontier or source change after acknowledgement")
        self.allowed_targets = current
        self.allowed_target_counts = current_counts
        return {"success": True}

    def close(self):
        self.connection.close()


def point_records(client, day, version):
    where = f"WHERE trade_date='{day}' AND source_version='{version}'"
    cache = client.records(f"SELECT {','.join(FIELDS)} FROM {CACHE} {where} LIMIT 2")
    receipts = client.records(f"SELECT {','.join(RECEIPT_FIELDS)} FROM {COVERAGE} {where} AND dataset_id='{SPEC.dataset_id}' LIMIT 2")
    if len(cache) > 1 or len(receipts) > 1:
        raise RuntimeError("Duplicate complete owner generation key")
    audit.typed_cache_rows(cache)
    return cache, receipts


def semantic_identity(value):
    # A unit remains the same after its own cache/receipt publication. Runtime
    # target frontiers and hits are checked separately before any owner call.
    return {field: value[field] for field in ("dataset_id", "trade_date", "target_id", "sources_fingerprint",
        "source_snapshots", "original_source_state", "source_version", "raw_source_census",
        "known_source_date", "expected_cache_records", "expected_receipt", "frozen_python_files_sha256",
        "original_business_sql_sha256")}


def publication_frontier(value):
    return {field: value[field] for field in ("target", "process_attestation", "target_snapshots",
        "targets_fingerprint", "cache_key_absent_before", "existing_cache_records", "existing_receipts",
        "expected_owner_hits", "expected_owner_misses", "validated_schemas", "target_actual_row_counts")}


def build_preview(request, target, client):
    source_before, target_before = snapshots(SPEC.sources), snapshots(MODELS)
    target_counts = actual_target_counts(client)
    validate_target_counts(target_before, target_counts)
    schema = validate_schemas()
    owner = audit.MarketBarometerReadThroughCache(client)
    owner._installed = True
    day = pd.Timestamp(request["trade_date"])
    dates = owner._dates(SPEC, day, day)
    if len(dates) > 1 or any(date.strftime("%Y-%m-%d") != request["trade_date"] for date in dates):
        raise RuntimeError("Original date anchor is not an exact one-day inventory")
    known = len(dates) == 1
    if dates.empty:
        # Match the actual PG temporal representation for a readonly planning
        # hash; original read() returns before producing any version/receipt.
        partitions = client.fetch_df("SELECT minTimestamp FROM table_partitions('etf_share') LIMIT 1")
        reference = pd.Timestamp(partitions.iloc[0]["minTimestamp"]) if not partitions.empty else day
        candidate = day.tz_localize(reference.tzinfo) if reference.tzinfo else day
        version_dates = pd.DatetimeIndex([candidate])
    else:
        version_dates = dates
    state = owner._source_state(SPEC, version_dates.min(), version_dates.max())
    versions = owner._versions(SPEC, state, audit.common.VIEW_SELECTS[SPEC.view], version_dates)
    version = versions[day.strftime("%Y%m%d")]
    census, total = {}, 0
    for table, model in audit.SOURCE_MODELS.items():
        fields = tuple(model.get_questdb_schema()["schema"])
        stop = (day + pd.Timedelta(days=1)).strftime("%Y-%m-%d")
        where = "" if table in SPEC.timeless_sources else f" WHERE timestamp>='{day:%Y-%m-%d}' AND timestamp<'{stop}'"
        rows = client.records(f"SELECT {','.join(fields)} FROM {table}{where} ORDER BY timestamp,ts_code LIMIT {request['max_source_rows']+1}")
        count = client.records(f"SELECT count() AS n FROM {table}{where}")[0]["n"]
        total += len(rows)
        if len(rows) != count or total > request["max_source_rows"]:
            raise RuntimeError("Complete source census exceeds frozen total budget or actual count")
        audit.strict_compare(rows, rows, fields, ("timestamp", "ts_code"))
        keys = [[row["timestamp"], row["ts_code"]] for row in rows]
        census[table] = {"fields": list(fields), "row_count": len(rows), "complete_key_count": len(keys),
            "full_field_sha256": fixture.source_sha(rows), "complete_key_sha256": sha(keys),
            "null_counts": {field: sum(row[field] is None for row in rows) for field in fields},
            "scope": "complete timeless etf_basic" if not where else "one-day complete source keys"}
    aggregate = client.fetch_df(audit.common.VIEW_SELECTS[SPEC.view].format(
        where="WHERE s.timestamp >= %s AND s.timestamp <= %s") + " ORDER BY trade_date", (request["trade_date"], request["trade_date"]))
    if tuple(aggregate.columns) != SPEC.fields or len(aggregate) > 1:
        raise RuntimeError("Original one-day business aggregate must contain four exact fields and0or1row")
    expected_cache = audit.canonical(aggregate.assign(source_version=version).to_dict("records")) if known else []
    audit.typed_cache_rows(expected_cache)
    receipt = None if not known else {"trade_date": day.tz_localize("UTC").isoformat().replace("+00:00", "Z"),
        "dataset_id": SPEC.dataset_id, "source_version": version, "row_count": len(aggregate),
        "content_digest": audit._digest(aggregate, SPEC.fields)}
    current, existing_receipts = point_records(client, request["trade_date"], version)
    if known and not expected_cache and current:
        raise RuntimeError("Known empty source has stale current generation output; zero receipt requires absent cache key")
    cached, misses = owner._load_cached(SPEC, dates, versions) if known else ({}, [])
    hits = len(dates) - len(misses)
    if hits:
        audit.strict_compare(current, expected_cache, FIELDS, ("trade_date", "source_version"))
        audit.strict_compare(existing_receipts, [receipt], RECEIPT_FIELDS, ("trade_date", "dataset_id", "source_version"))
    value = {"protocol_version": 1, "status": "PREVIEW_VERIFIED", "operation": "preview",
        "dataset_id": SPEC.dataset_id, "trade_date": request["trade_date"], "target": request["target"],
        "invocation_id": request["invocation_id"], "process_attestation": target.identity,
        "source_snapshots": source_before, "target_snapshots": target_before, "target_actual_row_counts": target_counts,
        "original_source_state": audit.canonical(state),
        "source_version": version, "raw_source_census": census, "known_source_date": known,
        "expected_cache_records": expected_cache, "expected_receipt": receipt, "cache_key_absent_before": not current,
        "existing_cache_records": current, "existing_receipts": existing_receipts,
        "expected_owner_hits": hits, "expected_owner_misses": len(misses), "frozen_python_files_sha256": owner_files(),
        "validated_schemas": schema, "installation_delegation": "existing five schemas validated; no install or view lifecycle invoked",
        "original_business_sql_sha256": hashlib.sha256(audit.common.VIEW_SELECTS[SPEC.view].encode()).hexdigest()}
    value["sources_fingerprint"], value["targets_fingerprint"] = sha(source_before), sha(target_before)
    stable_targets = {table: {"id": row["physical"]["id"], "directoryName": row["physical"]["directoryName"], "schema": schema[table]}
                      for table, row in target_before.items()}
    stable_process = {key: target.identity[key] for key in ("data_root", "host", "http_port", "pg_port")}
    value["target_id"] = "questdb-" + sha({"process": stable_process, "tables": stable_targets})
    value["source_fingerprint"] = sha(semantic_identity(value))
    source_after, target_after, target_counts_after = snapshots(SPEC.sources), snapshots(MODELS), actual_target_counts(client)
    if source_before != source_after or target_before != target_after or target_counts != target_counts_after:
        raise RuntimeError("Preview raced a source or target metadata change")
    value["target_actual_row_counts_after"] = target_counts_after
    target.verify()
    return value


def validate_submission_rows(intent, preview, entry, run, event):
    required = {"protocol_version", "invocation_id", "trade_date", "dataset_id", "preview_path", "preview_sha256",
        "ledger_path", "run_id", "slice_id", "revision", "source_fingerprint", "targets_fingerprint", "sources_fingerprint",
        "expected_cache_records", "expected_receipt", "target", "target_id"}
    if (not required.issubset(intent) or type(intent["revision"]) is not int or intent["revision"] != 4 or
            type(intent["protocol_version"]) is not int or intent["protocol_version"] != 1):
        raise RuntimeError("Complete revision4 Java publication intent required")
    for field in ("trade_date", "dataset_id", "target", "target_id", "source_fingerprint", "targets_fingerprint",
                  "sources_fingerprint", "expected_cache_records", "expected_receipt"):
        if intent[field] != preview[field]:
            raise RuntimeError("Java intent differs from frozen original owner preview")
    if (entry is None or run is None or event is None or entry["kind"] != "SLICE" or entry["state"] != "SUBMITTED" or
            entry["revision"] != 4 or entry["run_id"] != intent["run_id"] or entry["id"] != intent["slice_id"] or
            event["entry_id"] != intent["slice_id"] or event["state"] != "SUBMITTED" or event["revision"] != 4 or event["payload_json"] != entry["payload_json"] or
            run["id"] != intent["run_id"] or run["job_id"] not in ALLOWED_JOBS or run["job_version"] != 1 or run["target_id"] != intent["target_id"]):
        raise RuntimeError("Actual shared SQLite run/slice/event is not the admitted SUBMITTED intent")
    payload = json.loads(entry["payload_json"])
    evidence = json.loads(payload["responseEvidence"]) if isinstance(payload.get("responseEvidence"), str) else payload.get("responseEvidence")
    if (payload.get("sourceFingerprint") != intent["source_fingerprint"] or type(payload.get("returnedRows")) is not int or payload["returnedRows"] != 1 or
            not isinstance(evidence, dict) or evidence.get("preview_path") != intent["preview_path"] or
            evidence.get("preview_sha256") != intent["preview_sha256"]):
        raise RuntimeError("Persisted source/response evidence is not bound to the immutable preview")
    frozen = json.loads(run["frozen_json"])
    definition, params = frozen.get("definition", {}), frozen.get("parameters", {})
    if (definition.get("jobId") != run["job_id"] or definition.get("version") != 1 or
            definition.get("datasetId") != CACHE or definition.get("datasetVersion") != 1):
        raise RuntimeError("Frozen job does not bind exact ETF dataset")
    if run["job_id"] == "data.etf_market_overview_daily_cache":
        if (params.get("target_id") != preview["target_id"] or params.get("source_version") != preview["sources_fingerprint"] or
                frozen.get("mode") not in {"INCREMENTAL", "MATERIALIZE"} or
                not frozen.get("from") or not frozen.get("to") or
                not frozen["from"] <= preview["trade_date"] <= frozen["to"] or
                not 0 <= (pd.Timestamp(frozen["to"]) - pd.Timestamp(frozen["from"])).days < 31):
            raise RuntimeError("Publication date is outside the frozen data prewarm window")
    else:
        if (frozen.get("mode") != "INGEST" or frozen.get("from") is not None or frozen.get("to") is not None or
                set(params) != {"groupBatch", "memberBatch", "planFingerprint", "payloadFingerprint"} or
                any(not isinstance(params.get(field), str) or not params[field] for field in params)):
            raise RuntimeError("Typed write group requires exact INGEST prepared plan/batch binding")
        validate_prepared_input(intent, preview, evidence, params)


def validate_prepared_input(intent, preview, evidence, params):
    path, digest = intent.get("prepared_input_path"), intent.get("prepared_input_sha256")
    if (not isinstance(path, str) or not isinstance(digest, str) or not audit.HEX64.fullmatch(digest) or
            evidence.get("prepared_input_path") != path or evidence.get("prepared_input_sha256") != digest):
        raise RuntimeError("SQLite prepared caller evidence requires the immutable complete input path/SHA")
    caller, _ = load_json(path, digest)
    if (caller.get("datasetId") != CACHE or
            any(caller.get(field) != value for field, value in params.items()) or
            not isinstance(caller.get("memberId"), str) or not caller["memberId"]):
        raise RuntimeError("Prepared caller plan/batch/member differs from frozen SQLite parameters")
    rows = caller.get("rows")
    lines = caller.get("canonicalPayloadLines")
    if (not isinstance(rows, list) or not 1 <= len(rows) <= 31 or not isinstance(lines, list) or
            len(lines) != len(rows) or any(not isinstance(line, str) for line in lines)):
        raise RuntimeError("Complete bounded prepared caller records required")
    payload_hash = hashlib.sha256((CACHE + ":1:" + CACHE).encode("utf-8"))
    normalized = []
    for row, line in zip(rows, lines):
        if set(row) != set(FIELDS):
            raise RuntimeError("Typed caller requires all five DTO fields")
        day = row["trade_date"]
        if not isinstance(day, str) or pd.Timestamp(day).strftime("%Y-%m-%d") != day:
            raise RuntimeError("Prepared logical date must be an exact ISO BusinessDate")
        logical = dict(zip(FIELDS, (pd.Timestamp(day).tz_localize("UTC").isoformat().replace("+00:00", "Z"),
            row["etf_count"], row["total_share"], row["total_size_yi"], row["source_version"])))
        physical = json.loads(line, object_pairs_hook=_unique_object)
        if (tuple(physical) != FIELDS or type(physical["trade_date"]) is not int or
                physical["trade_date"] != pd.Timestamp(day).value // 1000):
            raise RuntimeError("Prepared canonical timestamp must equal UTC-midnight epoch micros")
        actual = dict(physical)
        actual["trade_date"] = pd.Timestamp(physical["trade_date"], unit="us", tz="UTC").isoformat().replace("+00:00", "Z")
        audit.typed_cache_rows([actual])
        if physical["etf_count"] > 9223372036854775807:
            raise RuntimeError("Prepared count exceeds physical LONG")
        audit.strict_compare([actual], [logical], FIELDS, ("trade_date", "source_version"))
        normalized.append(logical)
        payload_hash.update(line.encode("utf-8")); payload_hash.update(b"\n")
    if payload_hash.hexdigest() != params["payloadFingerprint"]:
        raise RuntimeError("Exact original Java canonical caller bytes differ from frozen payload fingerprint")
    audit.typed_cache_rows(normalized)
    audit.strict_compare(normalized, normalized, FIELDS, ("trade_date", "source_version"))
    days = [row["trade_date"][:10] for row in normalized]
    if len(set(days)) != len(days) or (pd.Timestamp(max(days)) - pd.Timestamp(min(days))).days >= 31:
        raise RuntimeError("Prepared input must assert one current generation per date within31days")
    selected = [row for row in normalized if row["trade_date"][:10] == preview["trade_date"]]
    audit.strict_compare(selected, preview["expected_cache_records"], FIELDS, ("trade_date", "source_version"))


def validate_sqlite_submission(intent, preview):
    path = Path(intent["ledger_path"]).resolve(strict=True)
    if not path.is_relative_to((audit.common.REPO_ROOT / "var").resolve()):
        raise RuntimeError("Shared ledger must be a real workspace var SQLite file")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        entry = connection.execute("SELECT * FROM sync_entries WHERE id=?", (intent["slice_id"],)).fetchone()
        run = connection.execute("SELECT * FROM sync_runs WHERE id=?", (intent["run_id"],)).fetchone()
        event = connection.execute("SELECT * FROM sync_events WHERE entry_id=? AND revision=4", (intent["slice_id"],)).fetchone()
        validate_submission_rows(intent, preview, entry, run, event)
        require_uncancelled_lineage(connection, run)


def require_uncancelled_lineage(connection, run):
    seen = set()
    while run is not None:
        identity = run["id"]
        if not isinstance(identity, str) or not identity or identity in seen or len(seen) >= 64:
            raise RuntimeError("Actual Java run parent chain is cyclic or unbounded")
        seen.add(identity)
        cancelled = connection.execute("SELECT count() FROM sync_cancellations WHERE run_id=?", (identity,)).fetchone()[0]
        if type(cancelled) is not int or cancelled != 0:
            raise RuntimeError("Actual Java run or parent is cancelled before original owner submission")
        parent = run["parent_run_id"]
        if parent is None:
            return
        if not isinstance(parent, str) or not parent:
            raise RuntimeError("Actual Java parent run identity is invalid")
        run = connection.execute("SELECT id,parent_run_id FROM sync_runs WHERE id=?", (parent,)).fetchone()
        if run is None or run["id"] != parent:
            raise RuntimeError("Actual Java parent run is missing or mismatched")
    raise RuntimeError("Actual Java publication run is absent")


def admit_publication(request, preview, intent, fresh):
    if (not preview.get("known_source_date") or preview.get("status") != "PREVIEW_VERIFIED" or
            preview.get("source_fingerprint") != sha(semantic_identity(preview)) or
            sha(semantic_identity(preview)) != sha(semantic_identity(fresh)) or
            publication_frontier(preview) != publication_frontier(fresh)):
        raise RuntimeError("Unknown anchor or changed frozen publication preview is not publishable")
    for field in ("trade_date", "dataset_id", "target"):
        if request[field] != preview[field] or intent.get(field) != preview[field]:
            raise RuntimeError("Publication request and Java intent differ from preview")
    if request["invocation_id"] != intent.get("invocation_id"):
        raise RuntimeError("Unique Java invocation identity differs")
    validate_sqlite_submission(intent, preview)


def execute(request, result, persist):
    validate_request(request)
    target = fixture.PrivateTarget(Path(request["target"]["private_root"]), request["target"]["expected_pid"])
    client = OwnerClient(target, request, result, persist)
    try:
        fresh = build_preview(request, target, client)
        result.update(fresh)
        result["operation"] = request["operation"]
        if request["operation"] == "preview":
            return
        intent, intent_sha = load_json(request["intent_path"], request["intent_sha256"])
        preview, preview_sha = load_json(intent["preview_path"], intent["preview_sha256"])
        admit_publication(request, preview, intent, fresh)
        result["java_intent"] = {"path": request["intent_path"], "sha256": intent_sha,
            "ledger_path": intent["ledger_path"], "run_id": intent["run_id"], "slice_id": intent["slice_id"], "revision": 4}
        result["preview_path"], result["preview_sha256"] = intent["preview_path"], preview_sha
        client.preview, client.intent, client.allowed_targets = preview, intent, preview["target_snapshots"]
        client.allowed_target_counts = preview["target_actual_row_counts"]
        owner = audit.MarketBarometerReadThroughCache(client)
        owner._installed = True
        claim = fixture.DIRECTORY / ("owner-invocation-" + request["invocation_id"] + ".json")
        fixture.save_new(claim, {"invocation_id": request["invocation_id"], "intent_sha256": intent_sha,
            "run_id": intent["run_id"], "slice_id": intent["slice_id"], "ack": "UNKNOWN", "automatic_retry": False})
        fixture.save_progress(claim, {"invocation_id": request["invocation_id"], "intent_sha256": intent_sha,
            "run_id": intent["run_id"], "slice_id": intent["slice_id"], "ack": "UNKNOWN", "automatic_retry": False})
        result["owner_invocation_claim"] = str(claim)
        result["owner_invoked"] = True
        persist()
        actual = owner.read(SPEC.view, start_date=request["trade_date"], end_date=request["trade_date"])
        returned = audit.canonical(actual.frame.assign(source_version=preview["source_version"]).to_dict("records"))
        audit.strict_compare(returned, preview["expected_cache_records"], FIELDS, ("trade_date", "source_version"))
        if actual.hits != preview["expected_owner_hits"] or actual.misses != preview["expected_owner_misses"] or actual.versions != {request["trade_date"].replace("-", ""): preview["source_version"]}:
            raise RuntimeError("Actual original owner hit/miss/generation differs from preview")
        cache, receipts = point_records(client, request["trade_date"], preview["source_version"])
        audit.strict_compare(cache, preview["expected_cache_records"], FIELDS, ("trade_date", "source_version"))
        audit.strict_compare(receipts, [preview["expected_receipt"]], RECEIPT_FIELDS, ("trade_date", "dataset_id", "source_version"))
        if audit._digest(pd.DataFrame(cache).reindex(columns=SPEC.fields), SPEC.fields) != receipts[0]["content_digest"]:
            raise RuntimeError("Exact original owner digest differs from persisted receipt")
        after_sources, after_targets = snapshots(SPEC.sources), snapshots(MODELS)
        after_counts = actual_target_counts(client)
        validate_target_counts(after_targets, after_counts)
        if (after_sources != preview["source_snapshots"] or after_targets != client.allowed_targets or
                after_counts != client.allowed_target_counts or owner_files() != preview["frozen_python_files_sha256"]):
            raise RuntimeError("Original owner source/target/code changed at final PG verification")
        if not cache and (not preview["cache_key_absent_before"] or len(cache) != 0):
            raise RuntimeError("Empty receipt requires actual cache key absence before and after")
        target.verify()
        result.update({"status": "VERIFIED_READTHROUGH", "actual_owner_hits": actual.hits, "actual_owner_misses": actual.misses,
            "actual_cache_records": cache, "actual_receipt": receipts[0], "cache_key_absent_after": not cache,
            "source_snapshots_after": after_sources, "target_snapshots_after": after_targets,
            "target_actual_row_counts_after": after_counts,
            "exact_owner_digest_verified": True, "source_unchanged": True,
            "cache_submitted_rows": sum(row["attempted_rows"] for row in result["owner_submissions"] if row["table"] == CACHE),
            "coverage_submitted_rows": sum(row["attempted_rows"] for row in result["owner_submissions"] if row["table"] == COVERAGE)})
    finally:
        client.close()
        result["owner_sender_stopped"] = client.active_senders == 0 and not client.sender_close_failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--response", type=Path, required=True)
    args = parser.parse_args()
    output = args.response.resolve()
    if not output.is_relative_to(fixture.DIRECTORY.resolve()):
        parser.error("New-only response must remain in D101 command evidence")
    result = {"protocol_version": 1, "status": "IN_PROGRESS", "owner_invoked": False, "owner_submissions": [],
        "formal_mutated": False, "source_mutated": False, "ddl_operations": 0, "automatic_retry": False,
        "bridge_process": {"pid": os.getpid(), "started_at": datetime.now(timezone.utc).isoformat(), "exit_observed": False},
        "owner_sender_stopped": True}
    fixture.save_new(output, result)
    def persist():
        fixture.save_progress(output, audit.canonical(result))
    exit_code = 0
    try:
        request, request_sha = load_json(args.request)
        result["request_path"], result["request_sha256"] = str(args.request.resolve()), request_sha
        execute(request, result, persist)
    except Exception as exc:
        result["status"] = "IN_DOUBT" if result["owner_invoked"] else "FAILED_PRECONDITION"
        result["error_code"], result["error"] = type(exc).__name__, str(exc)
        result["error_traceback"] = traceback.format_exc()
        exit_code = 1
    finally:
        result["metadata_transport_adaptation_applied"] = bool(result.get("metadata_transport_adaptations"))
        result["timestamp_transport_evidence"] = {name: {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for name, path in {
                "initial_failed_preview": fixture.DIRECTORY / "owner-preview-initial-response-20261006.json",
                "initial_failed_preview_request": fixture.DIRECTORY / "owner-preview-initial-request-20261006.json",
                "readonly_native_carrier_diagnostic": fixture.DIRECTORY / "owner-timestamp-carrier-diagnostic-20261006.json",
                "native_metadata_failed_preview": fixture.DIRECTORY / "owner-preview-native-metadata-response-20261006.json",
                "readonly_wal_native_carrier_diagnostic": fixture.DIRECTORY / "owner-wal-timestamp-diagnostic-20261006.json"
            }.items() if path.is_file() and path != output}
        result["bridge_process"]["terminal_response_written"] = True
        result["bridge_process"]["exit_code"] = exit_code
        persist()
    print(json.dumps({"status": result["status"], "response": str(output)}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
