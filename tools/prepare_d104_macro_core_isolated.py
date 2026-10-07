"""D104 INITIAL: only six missing source tables and captured real June/July.

No macro output, provider or materializer is invoked. Every CREATE/INSERT has a
fixed new claim with UNKNOWN ACK before the only synchronous HTTP attempt. Raw
response bytes are fsynced before ACK parsing; any failure stops without resend.
The August increment requires a separate reviewed terminal Java admission/tool.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

sys.dont_write_bytecode = True
import psycopg2
import preflight_d104_macro_core_readonly as audit
import prepare_d103_equity_style_isolated as native

REPO, DIRECTORY = audit.REPO, audit.DIRECTORY
ROOT = REPO / "var/d104-isolated-questdb"
HTTP_PORT, PG_PORT = 19040, 18852
MONTHS, INITIAL_MONTHS = ("202606", "202607", "202608"), ("202606", "202607")
PRIVATE_OUTPUT = "java_d104_macro_core_monthly_acceptance"
PROTECTED = (audit.TARGET, PRIVATE_OUTPUT)
MAX_SQL_BYTES, MAX_RESPONSE_BYTES, WAL_SECONDS = 128 * 1024, 1024 * 1024, 30
OUTPUT = DIRECTORY / "source-fixture-initial-20261007.json"
INCREMENT_CONTRACT = {"implemented_in_this_initial_tool": False, "task_id": "D104", "protocol_version": 1,
    "decision": "accepted_for_bounded_source_increment", "source_month": "202608", "source_rows": 5,
    "requires": ["this complete initial receipt SHA", "actual Java June/July first/replay/resume/reconcile/write/read/cancellation receipts and SHA", "all scoped D104 ledger runs/entries terminal and zero retained interval leases", "exact known original Java producer PID+OS birth with actual completed-executor/JUnit and current original-identity absence proof", "same dedicated private source/target identities and exact initial294field/270nullablebits source plus two-month output", "all formal snapshots/captures unchanged", "original held August five-source records and whole six-source capture SHA"],
    "automatic_retry": False, "DDL": 0, "private_output_writes": 0, "formal_writes": 0}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def unique_json(items):
    value = {}
    for key, item in items:
        audit.require(key not in value, "Duplicate evidence/response key")
        value[key] = item
    return value


def load(path, expected_sha=None):
    path = Path(path).resolve(strict=True)
    audit.require(path.is_relative_to(DIRECTORY.resolve()) and path.stat().st_size <= 16 * 1024 * 1024, "Finite D104 commands evidence required")
    if expected_sha is not None:
        audit.require(isinstance(expected_sha, str) and re.fullmatch("[0-9a-f]{64}", expected_sha) and audit.digest(path) == expected_sha, "Evidence SHA differs")
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_json)


def save_progress(path, value):
    old = load(path)
    audit.require(old.get("invocation_id") == value["invocation_id"], "Only this invocation may update its own journal")
    temporary = Path(path).with_suffix(Path(path).suffix + ".pending")
    audit.save_new(temporary, value)
    os.replace(temporary, path)


def native_query(command):
    completed = subprocess.run([str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", command], capture_output=True, text=True, timeout=30)
    audit.require(completed.returncode == 0, "Exact native process proof failed")
    return json.loads(completed.stdout, object_pairs_hook=unique_json)


def producer_identity():
    pid = os.getpid()
    value = native_query(f"$ErrorActionPreference='Stop'; $procD104=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if($null -eq $procD104){{throw 'Producer identity missing'}}; [pscustomobject]@{{pid=$procD104.ProcessId;parent_pid=$procD104.ParentProcessId;name=$procD104.Name;birth_utc=$procD104.CreationDate.ToUniversalTime().ToString('o')}} | ConvertTo-Json -Compress")
    audit.require(type(value.get("pid")) is int and value["pid"] == pid and type(value.get("parent_pid")) is int and value.get("name", "").lower() in ("python.exe", "pythonw.exe"), "Known actual foreground Python producer required")
    value["birth_utc"] = native.normalize_birth(value.get("birth_utc"))
    return value


def validate_startup(value, root, pid):
    audit.require(type(pid) is int and pid > 0 and value.get("task_id") == "D104" and value.get("pid") == pid and
        Path(value.get("data_root", "")).resolve() == root and value.get("http_port") == HTTP_PORT and value.get("pg_port") == PG_PORT, "Dedicated D104 startup target differs")
    args = value.get("arguments")
    audit.require(isinstance(args, list) and "io.questdb/io.questdb.ServerMain" in args and args.count("-d") == 1, "Original QuestDB module and unique root required")
    index = args.index("-d")
    audit.require(index + 1 < len(args) and Path(args[index + 1]).resolve() == root, "Startup root differs")
    return native.normalize_birth(value.get("birth_utc"))


def validate_listeners(records, root, pid, birth):
    audit.require(isinstance(records, list) and len(records) == 2 and {row.get("port") for row in records} == {HTTP_PORT, PG_PORT}, "Exactly D104 two listener records required")
    for row in records:
        audit.require(type(row.get("pid")) is int and row["pid"] == pid and row.get("address") == "127.0.0.1" and row.get("name", "").lower() == "java.exe" and native.normalize_birth(row.get("birth")) == birth, "Native listener PID/birth/address/name differs")
        args = native.split_windows_command_line(row.get("command"))
        audit.require("io.questdb/io.questdb.ServerMain" in args and args.count("-d") == 1, "Native QuestDB module/root missing")
        index = args.index("-d")
        audit.require(index + 1 < len(args) and Path(args[index + 1]).resolve() == root, "Native listener belongs to another root")
    return {"pid": pid, "birth_utc": birth, "data_root": str(root), "host": "127.0.0.1", "http_port": HTTP_PORT, "pg_port": PG_PORT}


class PrivateTarget:
    def __init__(self, root, pid, startup, startup_sha):
        self.root = Path(root).resolve(strict=True)
        audit.require(self.root == ROOT.resolve(strict=True), "Pinned D104 private data root required")
        config = {}
        for line in (self.root / "conf/server.conf").read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1); config[key.strip()] = value.strip()
        audit.require(config.get("http.net.bind.to") == "127.0.0.1:19040" and config.get("pg.net.bind.to") == "127.0.0.1:18852", "Dedicated D104 listener config differs")
        self.pid, self.startup, self.startup_sha, self.identity = pid, Path(startup).resolve(), startup_sha, None
        self.birth = validate_startup(load(self.startup, startup_sha), self.root, pid)

    def verify(self):
        validate_startup(load(self.startup, self.startup_sha), self.root, self.pid)
        command = r"""
$ErrorActionPreference='Stop'
$listeners=@(Get-NetTCPConnection -LocalPort 19040,18852 -State Listen -ErrorAction Stop)
$records=@(foreach($port in @(19040,18852)) {
 $matches=@($listeners | Where-Object LocalPort -eq $port)
 if($matches.Count -ne 1) {throw 'Ambiguous D104 listener'}
 $listener=$matches[0]; $procD104=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
 [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$procD104.ProcessId;name=$procD104.Name;command=$procD104.CommandLine;birth=$procD104.CreationDate.ToUniversalTime().ToString('o')}
})
ConvertTo-Json -InputObject $records -Compress
"""
        value = validate_listeners(native_query(command), self.root, self.pid, self.birth)
        audit.require(self.identity is None or self.identity == value, "Private service identity drifted")
        self.identity = value
        return value


@contextmanager
def private_deadline(connection, deadline):
    previous = psycopg2.extensions.get_wait_callback()
    psycopg2.extensions.set_wait_callback(lambda value: audit.transport.wait_pg(value, deadline))
    try:
        audit.require(time.monotonic() < deadline, "Private SELECT deadline exceeded")
        yield
        audit.require(time.monotonic() < deadline, "Private SELECT deadline exceeded")
    except BaseException:
        connection.close()
        raise
    finally:
        psycopg2.extensions.set_wait_callback(previous)


class PrivateReader(audit.ReadOnlyPG):
    def __init__(self, target, cancel_file=None):
        audit.check_cancel(cancel_file); target.verify()
        self.cancel_file, self.visibility_deadline = cancel_file, None
        self.connection = psycopg2.connect(host="127.0.0.1", port=PG_PORT, user="admin", password="quest", dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def records(self, sql, params=None, cap=100):
        audit.check_cancel(self.cancel_file); audit.single_select(sql)
        audit.require(type(cap) is int and 1 <= cap <= 100, "Finite private SELECT cap required")
        deadline = time.monotonic() + 20
        if self.visibility_deadline is not None:
            deadline = min(deadline, self.visibility_deadline)
        with private_deadline(self.connection, deadline):
            with self.connection.cursor() as cursor:
                cursor.execute(sql, params)
                fields = [item[0] for item in cursor.description]
                audit.require(len(set(fields)) == len(fields), "Duplicate private fields")
                rows = cursor.fetchmany(cap + 1)
        audit.require(len(rows) <= cap, "Private SELECT truncation sentinel reached")
        audit.check_cancel(self.cancel_file)
        return [{field: audit.transport.native_value(value) for field, value in zip(fields, row)} for row in rows]


def private_state(reader, table):
    audit.require(table in (*audit.SOURCES, *PROTECTED), "Private source/output read whitelist required")
    values = reader.records("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=%s LIMIT 2", (table,), cap=2)
    audit.require(len(values) <= 1, "Private physical identity ambiguous")
    if not values:
        return {"exists": False}
    p = values[0]
    w = audit.one(reader, "SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=%s LIMIT 2", (table,))
    count = audit.exact_count(audit.one(reader, f"SELECT count() AS n FROM {table}")["n"])
    audit.require(type(p["id"]) is int and p["id"] > 0 and isinstance(p["directoryName"], str) and p["directoryName"].strip(), "Complete private identity required")
    for value in (p["wal_pending_row_count"], w["sequencerTxn"], w["writerTxn"], w["bufferedTxnSize"]):
        audit.exact_count(value)
    audit.require(type(p["table_suspended"]) is type(w["suspended"]) is bool, "Exact private suspension flags required")
    if p["table_txn"] is None:
        audit.require(count == 0 and w["sequencerTxn"] == w["writerTxn"] == 0, "Null private txn requires actual empty proof")
    else:
        audit.exact_count(p["table_txn"])
    if p["table_row_count"] is not None:
        audit.exact_count(p["table_row_count"])
    audit.require(p["table_row_count"] == count or (p["table_row_count"] is None and count == 0), "Private metadata count differs from COUNT")
    return {"exists": True, "physical": p, "wal": w, "actual_select_count": count,
        "settled": not p["table_suspended"] and not w["suspended"] and p["wal_pending_row_count"] == w["bufferedTxnSize"] == 0 and w["sequencerTxn"] == w["writerTxn"]}


def source_rows(reader, table, model):
    audit.require(table in audit.SOURCES and model["table_name"] == table, "Full source read whitelist required")
    return reader.records(f"SELECT {','.join(model['schema'])} FROM {table} ORDER BY {model['timestamp_col']} LIMIT 25", cap=24)


def strict_compare(actual, expected, table, model):
    audit.validate_rows(table, actual, model); audit.validate_rows(table, expected, model)
    audit.require(len(actual) == len(expected), "Private full source row count differs")
    for left, right in zip(actual, expected):
        for field, kind in model["schema"].items():
            if kind == "DOUBLE":
                audit.require(audit.raw_bits(left[field]) == audit.raw_bits(right[field]), "Private source binary64 differs: " + table + "." + field)
            else:
                audit.require(type(left[field]) is type(right[field]) and left[field] == right[field], "Private source native key/value differs: " + table + "." + field)
    audit.require(audit.canonical_sha(actual) == audit.canonical_sha(expected), "Private source canonical SHA differs")
    slots = len(expected) * sum(kind == "DOUBLE" for kind in model["schema"].values())
    nonnull = sum(row[field] is not None for row in expected for field, kind in model["schema"].items() if kind == "DOUBLE")
    return {"rows": len(expected), "full_field_comparisons": len(expected) * len(model["schema"]), "double_rawbit_comparisons": slots,
        "nullable_double_slot_comparisons": slots, "nonnull_double_rawbit_comparisons": nonnull, "null_double_comparisons": slots - nonnull,
        "double_rawbit_comparisons_scope": "All nullable DOUBLE slots: explicit NULL parity is distinguished from finite IEEE754 raw bits by the separate nonnull/null counts.",
        "double_tolerance": 0, "canonical_records_sha256": audit.canonical_sha(actual)}


def captured_sources(preflight):
    rows = {}
    for table in audit.SOURCES:
        item = preflight["source_captures"][table]
        path = Path(item["path"]).resolve(strict=True)
        audit.require(path.is_relative_to(DIRECTORY.resolve()) and audit.digest(path) == item["sha256"] and path.stat().st_size <= 4 * 1024 * 1024, "Full source JSONL path/SHA/size differs")
        values = []
        for line in path.read_text(encoding="utf-8").splitlines():
            record = json.loads(line, object_pairs_hook=unique_json)
            audit.require(set(record) == {"values", "raw_double_bits"}, "Full source JSONL proof shape differs")
            types = preflight["original_models"][table]["schema"]
            audit.require(list(record["values"]) == list(types) and record["raw_double_bits"] == {field: audit.raw_bits(record["values"][field]) for field, kind in types.items() if kind == "DOUBLE"}, "Captured full fields/raw bits differ")
            values.append(record["values"])
        audit.require(type(item["rows"]) is int and item["rows"] == len(values) and item["canonical_records_sha256"] == audit.canonical_sha(values), "Captured canonical source SHA/count differs")
        rows[table] = values
    audit.require(preflight["full_sources_sha256"] == audit.canonical_sha({table: preflight["source_captures"][table]["sha256"] for table in audit.SOURCES}), "Whole six-source manifest SHA differs")
    return rows


def validate_preflight(value):
    audit.require(value.get("task_id") == "D104" and value.get("status") == "VERIFIED_BOUNDED_SOURCE_ORACLE" and value.get("months") == list(MONTHS) and value.get("stable_physical_and_wal_versions") is True and value.get("formal_mutated") is False and value.get("private_mutated") is False, "Only verified real D104 June-August preflight admitted")
    audit.require(value["tables_before"] == value["tables_after"] and value["schemas"] == value["schemas_after"] and not value["source_census"]["required_component_gaps"], "Complete stable source preflight required")
    for path, expected in value["python_file_sha256"].items():
        audit.require(Path(path).resolve() in {item.resolve() for item in audit.REFERENCE_FILES} and audit.digest(path) == expected, "Original Python identity changed")
    audit.require(set(value["python_file_sha256"]) == {str(path) for path in audit.REFERENCE_FILES}, "All original files must be bound")
    for table in audit.TABLE_CLASSES:
        audit.require(value["original_models"][table] == audit.model_schema(table), "Original model schema changed")


def initial_sources(whole, models):
    initial = {table: [row for row in whole[table] if audit.month_key(row[models[table]["timestamp_col"]], table != "cn_gdp") <= INITIAL_MONTHS[-1]] for table in audit.SOURCES}
    audit.require([len(initial[table]) for table in audit.SOURCES] == [2, 2, 2, 2, 1, 14], "Exact real initial six-source 23-row shape required")
    return initial


def formal_complete(reader, preflight, whole):
    states = {table: audit.table_state(reader, table) for table in audit.TABLE_CLASSES}
    audit.require(states == preflight["tables_after"], "Formal physical/WAL/COUNT/extents changed from frozen preflight")
    schemas = {table: reader.records("SELECT * FROM table_columns(%s)", (table,), cap=100) for table in audit.TABLE_CLASSES if states[table]["exists"]}
    audit.require(schemas == preflight["schemas_after"], "Formal schema changed from frozen preflight")
    comparisons = {}
    for table in audit.SOURCES:
        current, _queries = audit.capture_source(reader, table, preflight["original_models"][table], preflight["range_from_inclusive"], preflight["range_to_exclusive"])
        comparisons[table] = strict_compare(current, whole[table], table, preflight["original_models"][table])
    rows = reader.records(f"SELECT {','.join(audit.OUTPUT_FIELDS)} FROM {audit.TARGET} WHERE month >= %s AND month < %s ORDER BY month LIMIT 13", (preflight["range_from_inclusive"], preflight["range_to_exclusive"]), cap=12) if states[audit.TARGET]["exists"] else []
    audit.require(audit.compare_output(rows, preflight["formal_existing_output_rows"])["passed"], "Formal existing output values/bits changed")
    return {"tables": states, "schemas": schemas, "full_source_comparisons": comparisons, "existing_output_rows": rows}


def create_sql(table, model):
    audit.require(table in audit.SOURCES and model["table_name"] == table, "Only source DDL admitted")
    return f"CREATE TABLE {table} (" + ','.join(name + ' ' + kind for name, kind in model["schema"].items()) + f") TIMESTAMP({model['timestamp_col']}) PARTITION BY YEAR WAL DEDUP UPSERT KEYS({','.join(model['dedup_keys'])})"


def sql_value(value, kind):
    if value is None:
        audit.require(kind != "TIMESTAMP", "Source timestamp cannot be NULL")
        return "NULL"
    if kind == "DOUBLE":
        audit.raw_bits(value)
        return repr(value)
    if kind == "TIMESTAMP":
        audit.transport.utc_stamp(value, midnight=True)
    elif kind == "STRING":
        audit.require(isinstance(value, str) and value.strip(), "Source STRING required")
    else:
        raise RuntimeError("Unsupported source write type")
    return "'" + value.replace("'", "''") + "'"


def insert_sql(table, rows, model):
    audit.require(table in audit.SOURCES and model["table_name"] == table and 1 <= len(rows) <= 14, "Only initial bounded source INSERT admitted")
    audit.validate_rows(table, rows, model)
    values = ["(" + ','.join(sql_value(row[name], kind) for name, kind in model["schema"].items()) + ")" for row in rows]
    return f"INSERT INTO {table} ({','.join(model['schema'])}) VALUES " + ','.join(values)


def claim_path(kind, table):
    audit.require(kind in ("DDL", "DML") and table in audit.SOURCES, "Fixed source mutation claim whitelist required")
    return DIRECTORY / f"source-fixture-initial-{kind.lower()}-{table}-20261007.claim.json"


def validate_ack(payload, kind, rows):
    audit.require(isinstance(payload, dict), "Native mutation response must be exact JSON object")
    if kind == "DDL":
        audit.require(rows == 0 and payload == {"ddl": "OK"}, "Exact native DDL ACK required")
    else:
        audit.require(kind == "DML" and payload.get("dml") == "OK" and set(payload) <= {"dml", "updated"}, "Exact native DML ACK required")
        if "updated" in payload:
            audit.require(type(payload["updated"]) is int and payload["updated"] == rows, "DML affected rows differ from this exact attempt")


def save_raw(path, body):
    path = Path(path)
    audit.require(path.is_relative_to(DIRECTORY.resolve()) and isinstance(body, bytes), "Owned raw response bytes required")
    with path.open("xb") as stream:
        stream.write(body); stream.flush(); os.fsync(stream.fileno())
    return {"path": str(path), "sha256": audit.digest(path), "bytes": len(body)}


def submit_once(target, kind, table, model, rows, output, result, boundary, cancel_file=None):
    audit.require(kind in ("DDL", "DML") and table in audit.SOURCES, "Only source CREATE/INSERT operations allowed")
    sql = create_sql(table, model) if kind == "DDL" else insert_sql(table, rows, model)
    row_count = 0 if kind == "DDL" else len(rows)
    audit.require(isinstance(sql, str) and ";" not in sql and len(sql.encode("utf-8")) <= MAX_SQL_BYTES, "Finite single native mutation required")
    audit.require(sum(item["kind"] == kind for item in result["operations"]) < 6 and len(result["operations"]) < 12, "At most six source DDL and six DML attempts admitted")
    audit.check_cancel(cancel_file); boundary(); identity = target.verify()
    audit.require(identity == result["private_target_attestation"] and producer_identity() == result["producer_identity"], "Writer/service identity changed at mutation boundary")
    claim = claim_path(kind, table)
    journal = {"task_id": "D104", "invocation_id": result["invocation_id"], "kind": kind, "table": table, "rows": row_count,
        "ack": "UNKNOWN", "created_at": utc_now(), "automatic_retry": False, "target": identity, "producer_identity": result["producer_identity"], "preflight_evidence": result["preflight_evidence"], "startup_evidence": result["startup_evidence"], "sql_sha256": audit.transport.sha_bytes(sql.encode("utf-8")), "source_records_sha256": None if kind == "DDL" else audit.canonical_sha(rows)}
    audit.save_new(claim, journal)
    operation = {"kind": kind, "table": table, "rows": row_count, "ack": "UNKNOWN", "claim_path": str(claim), "claim_sha256": audit.digest(claim), "sql_sha256": journal["sql_sha256"], "attempted_at": utc_now()}
    result["operations"].append(operation); result["attempted_operations"] = len(result["operations"])
    result["submitted_source_rows"] += row_count
    save_progress(output, result)
    url = f"http://127.0.0.1:{HTTP_PORT}/exec?" + urlencode({"query": sql})
    status = None
    try:
        with urlopen(url, timeout=20) as response:
            status = response.status; body = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as failure:
        with failure:
            status = failure.code; body = failure.read(MAX_RESPONSE_BYTES + 1)
    raw = save_raw(claim.with_suffix(".response.bin"), body)
    journal["raw_response"], journal["http_status"] = raw, status
    operation["raw_response"], operation["http_status"] = raw, status
    save_progress(claim, journal); operation["claim_sha256"] = audit.digest(claim); save_progress(output, result)
    audit.require(type(status) is int and status == 200 and len(body) <= MAX_RESPONSE_BYTES, "Mutation response failed or exceeded bound; ACK stays UNKNOWN, no resend")
    payload = json.loads(body.decode("utf-8"), object_pairs_hook=unique_json)
    validate_ack(payload, kind, row_count)
    journal.update(ack="ACKNOWLEDGED", ack_at=utc_now(), response=payload)
    save_progress(claim, journal)
    operation.update(ack="ACKNOWLEDGED", response=payload, claim_sha256=audit.digest(claim))
    result["acknowledged_operations"] += 1; result["acknowledged_source_rows"] += row_count
    save_progress(output, result)


def wait_visible(reader, table, count, cancel_file=None):
    deadline = time.monotonic() + WAL_SECONDS
    reader.visibility_deadline = deadline
    try:
        while True:
            audit.check_cancel(cancel_file)
            audit.require(time.monotonic() < deadline, "Source WAL/readback deadline exceeded; never resend")
            state = private_state(reader, table)
            audit.require(time.monotonic() < deadline, "Source WAL/readback deadline exceeded; never resend")
            if state.get("exists") and state["settled"] and state["actual_select_count"] == count:
                return state
            time.sleep(0.1)
    finally:
        reader.visibility_deadline = None


def run(args, output, result):
    audit.require(args.execute_isolated is True, "Explicit execute-isolated required")
    preflight = load(args.preflight, args.preflight_sha256); validate_preflight(preflight)
    whole = captured_sources(preflight); models = preflight["original_models"]
    audit.require(audit.source_census(whole, models, list(MONTHS)) == preflight["source_census"], "Complete original six-source census changed")
    initial = initial_sources(whole, models)
    oracle, proof = audit.original_oracle(initial, list(INITIAL_MONTHS))
    audit.require(audit.compare_output(oracle, preflight["expected_oracle_rows"][:2])["passed"], "Initial original oracle differs from frozen whole oracle")
    result.update(preflight_evidence={"path": str(Path(args.preflight).resolve()), "sha256": args.preflight_sha256}, startup_evidence={"path": str(Path(args.startup_attestation).resolve()), "sha256": args.startup_attestation_sha256}, whole_real_sources=preflight["source_captures"], full_sources_sha256=preflight["full_sources_sha256"], initial_expected_oracle_rows=oracle, increment_admission_contract=INCREMENT_CONTRACT)
    target = PrivateTarget(args.private_root, args.expected_pid, args.startup_attestation, args.startup_attestation_sha256)
    result["private_target_attestation"] = target.verify()
    result["producer_identity"] = producer_identity()
    save_progress(output, result)
    formal = private = None
    try:
        formal, private = audit.ReadOnlyPG(args.cancel_file), PrivateReader(target, args.cancel_file)
        result["formal_before"] = formal_complete(formal, preflight, whole)
        result["private_before"] = {table: private_state(private, table) for table in (*audit.SOURCES, *PROTECTED)}
        audit.require(all(not state["exists"] for state in result["private_before"].values()), "Initial fixture requires six missing sources and absent protected outputs; existing/UNKNOWN state cannot be reset or resent")
        for table in audit.SOURCES:
            for kind in ("DDL", "DML"):
                audit.require(not claim_path(kind, table).exists(), "Old source mutation claim exists; initial resend forbidden")
        current = dict(result["private_before"])
        def boundary():
            audit.check_cancel(args.cancel_file)
            audit.require(audit.digest(__file__) == result["script_sha256"] and all(audit.digest(path) == expected for path, expected in result["helper_sha256"].items()), "Fixture script/helper changed before mutation")
            validate_preflight(load(args.preflight, args.preflight_sha256))
            captured_sources(preflight)
            target.verify()
            audit.require(producer_identity() == result["producer_identity"], "Original producer identity changed")
            audit.require(formal_complete(formal, preflight, whole) == result["formal_before"], "Formal source/output changed before private operation")
            audit.require({table: private_state(private, table) for table in current} == current, "Private source/output frontier changed before operation")
        for table in audit.SOURCES:
            submit_once(target, "DDL", table, models[table], [], output, result, boundary, args.cancel_file)
            current[table] = wait_visible(private, table, 0, args.cancel_file)
            schema = private.records("SELECT * FROM table_columns(%s)", (table,), cap=100)
            audit.validate_schema(schema, current[table], models[table])
            submit_once(target, "DML", table, models[table], initial[table], output, result, boundary, args.cancel_file)
            current[table] = wait_visible(private, table, len(initial[table]), args.cancel_file)
            strict_compare(source_rows(private, table, models[table]), initial[table], table, models[table])
        result["private_after"] = {table: private_state(private, table) for table in current}
        result["private_schemas_after"] = {table: private.records("SELECT * FROM table_columns(%s)", (table,), cap=100) for table in audit.SOURCES}
        for table in audit.SOURCES:
            audit.validate_schema(result["private_schemas_after"][table], result["private_after"][table], models[table])
        result["actual_source_rows"] = {table: source_rows(private, table, models[table]) for table in audit.SOURCES}
        result["complete_source_readback"] = {table: strict_compare(result["actual_source_rows"][table], initial[table], table, models[table]) for table in audit.SOURCES}
        result["actual_source_double_bits"] = {table: [{field: audit.raw_bits(row[field]) for field, kind in models[table]["schema"].items() if kind == "DOUBLE"} for row in result["actual_source_rows"][table]] for table in audit.SOURCES}
        result["initial_source_census"] = audit.source_census(result["actual_source_rows"], models, list(INITIAL_MONTHS))
        result["formal_after"] = formal_complete(formal, preflight, whole)
        result["private_target_attestation_after"] = target.verify()
        result["producer_identity_after"] = producer_identity()
        audit.require(result["formal_after"] == result["formal_before"] and result["private_after"] == current and result["private_target_attestation_after"] == result["private_target_attestation"] and result["producer_identity_after"] == result["producer_identity"], "Protected formal/source/output/service boundary changed")
        audit.require(all(not result["private_after"][table]["exists"] for table in PROTECTED), "Protected output must remain absent")
        validate_preflight(load(args.preflight, args.preflight_sha256)); captured_sources(preflight)
        audit.require(result["attempted_operations"] == result["acknowledged_operations"] == 12 and result["submitted_source_rows"] == result["acknowledged_source_rows"] == 23, "All six original CREATE and six real source INSERT ACKs required")
        result["status"] = "VERIFIED_ISOLATED_SOURCE_INITIAL"
        result["source_only_rows"] = 23
        result["source_only_field_values"] = 294
        result["source_only_double_slots"] = 270
        result["finished_at"] = utc_now()
        result["producer_sender_stopped"] = True
        result["producer_sender_stop_scope"] = "All HTTP requests are foreground synchronous urlopen contexts already closed; no ILP sender/business child or retry exists. Python OS process remains alive until CLI return; this is not a native process-absence claim."
    finally:
        if audit.digest(__file__) != result["script_sha256"]:
            result["status"] = "FAILED"
            result["error"] = {"type": "ScriptChanged", "message": "Frozen fixture script changed during execution"}
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--startup-attestation", type=Path, required=True)
    parser.add_argument("--startup-attestation-sha256", required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--preflight-sha256", required=True)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    output = args.output.resolve()
    audit.require(output == OUTPUT.resolve() and not output.exists(), "Only one new initial receipt identity admitted; no overwrite/retry")
    result = {"task_id": "D104", "protocol_version": 1, "stage": "initial", "status": "FAILED", "invocation_id": str(uuid.uuid4()), "checked_at": utc_now(), "automatic_retry": False,
        "formal_writes": 0, "private_output_writes": 0, "formal_mutated": False, "reference_project_mutated": False, "private_source_only": True, "operations": [], "attempted_operations": 0, "acknowledged_operations": 0, "submitted_source_rows": 0, "acknowledged_source_rows": 0, "script_sha256": audit.digest(__file__), "helper_sha256": {str(Path(module.__file__).resolve()): audit.digest(module.__file__) for module in (audit, audit.transport, native)}}
    audit.save_new(output, result)
    try:
        run(args, output, result)
    except BaseException as failure:
        result["status"] = "FAILED"
        result["error"] = {"type": type(failure).__name__, "message": str(failure)}
        result["automatic_resend_permitted"] = False
    finally:
        for path, expected in result["helper_sha256"].items():
            if audit.digest(path) != expected:
                result["status"] = "FAILED"
                result["error"] = {"type": "HelperChanged", "message": "Frozen helper changed during execution"}
        save_progress(output, result)
    print(json.dumps({"task_id": "D104", "status": result["status"], "output": str(output), "sha256": audit.digest(output), "attempted_operations": result["attempted_operations"], "acknowledged_operations": result["acknowledged_operations"], "submitted_source_rows": result["submitted_source_rows"], "formal_writes": 0, "private_output_writes": 0, "automatic_retry": False}))
    return 0 if result["status"] == "VERIFIED_ISOLATED_SOURCE_INITIAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
