"""D103 INITIAL source fixture: two captured real months, never output writes.

Only a missing private index_monthly CREATE and one bounded source INSERT are
admitted. Each has a new durable UNKNOWN claim before its single HTTP attempt.
Increment is intentionally unavailable until a separate actual Java admission.
"""
from __future__ import annotations

import argparse
import ctypes
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

sys.dont_write_bytecode = True
import psycopg2
import preflight_d103_equity_style_readonly as audit

REPO, DIRECTORY = audit.REPO, audit.DIRECTORY
ROOT = REPO / "var/d103-isolated-questdb"
MONTHS = ("202606", "202607", "202608")
INITIAL_MONTHS = MONTHS[:2]
HTTP_PORT, PG_PORT = 19030, 18842
PRIVATE_OUTPUT = "java_d103_equity_style_monthly_acceptance"
PROTECTED_OUTPUTS = (audit.TARGET, PRIVATE_OUTPUT)
CREATE_SQL = "CREATE TABLE index_monthly (" + ",".join(name + " " + kind for name, kind in audit.SOURCE_TYPES.items()) + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL"
MAX_SQL_BYTES, MAX_HTTP_BYTES, WAL_DEADLINE_SECONDS = 60 * 1024, 1024 * 1024, 30
INCREMENT_ADMISSION_CONTRACT = {
    "executable_in_this_script_version": False,
    "stage": "increment", "trade_months": ["202608"], "source_only_rows": 16,
    "DDL": 0, "source_insert_attempts": 1, "automatic_resend": False,
    "required_new_manifest": {
        "protocol_version": 1, "task_id": "D103", "decision": "accepted_for_bounded_source_increment",
        "initial_fixture": "actual initial path plus SHA",
        "first_and_replay": "two distinct VERIFIED Java actual run summaries, paths plus SHA, same source/target IDs and native SQLite ledger",
        "ledger_proof": "actual mode=ro/query_only terminal runs, entries and zero interval leases, exact run IDs and ledger path",
        "producer_proof": "scoped D103 first/replay actual process birth/complete STOP artifacts plus fresh native absence, paths plus SHA; no D101 PID inheritance",
        "private_frontier": "exact existing32 complete source fields/bits/SHA plus unchanged two-month java_d103_equity_style_monthly_acceptance output identity/values/WAL",
        "bounded_source": "original held August16 capture path plus SHA and full48 canonical source SHA",
        "target": "same native PID/birth/root/ports; every mutation boundary rechecks the immutable manifest and actual source/output frontiers",
    },
}


def load(path, expected_sha=None):
    path = Path(path).resolve(strict=True)
    audit.require(path.is_relative_to(DIRECTORY.resolve()), "D103 evidence must stay in its commands directory")
    audit.require(path.stat().st_size <= 16 * 1024 * 1024, "Finite evidence size required")
    if expected_sha is not None:
        audit.require(isinstance(expected_sha, str) and re.fullmatch("[0-9a-f]{64}", expected_sha) and audit.digest(path) == expected_sha, "Evidence SHA differs")
    def unique(items):
        value = {}
        for key, item in items:
            audit.require(key not in value, "Duplicate JSON evidence key")
            value[key] = item
        return value
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def save_progress(path, value):
    old = load(path)
    audit.require(old.get("invocation_id") == value["invocation_id"], "Only this invocation may update its claimed journal")
    temporary = path.with_suffix(path.suffix + ".pending")
    audit.save_new(temporary, value)
    os.replace(temporary, path)


def normalize_birth(value):
    audit.require(isinstance(value, str), "Known UTC OS birth required")
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,7}))?(?:Z|\+00:00)", value)
    audit.require(match is not None, "Known UTC OS birth required")
    datetime.fromisoformat(match[1])
    return match[1] + "." + (match[2] or "").ljust(7, "0") + "Z"


def split_windows_command_line(command):
    audit.require(os.name == "nt" and isinstance(command, str), "Windows native argv inspection required")
    count = ctypes.c_int()
    split = ctypes.windll.shell32.CommandLineToArgvW
    split.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
    split.restype = ctypes.POINTER(ctypes.c_wchar_p)
    args = split(command, ctypes.byref(count))
    audit.require(bool(args), "Native process command cannot be parsed")
    try:
        return [args[index] for index in range(count.value)]
    finally:
        free = ctypes.windll.kernel32.LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(ctypes.cast(args, ctypes.c_void_p))


def validate_startup(proof, root, pid):
    audit.require(type(pid) is int and pid > 0, "Exact explicit native PID required")
    audit.require(proof.get("task_id") == "D103" and proof.get("pid") == pid and
        Path(proof.get("data_root", "")).resolve() == root and proof.get("http_port") == HTTP_PORT and proof.get("pg_port") == PG_PORT,
        "Startup proof names a different private target")
    argv = proof.get("arguments")
    audit.require(isinstance(argv, list) and "io.questdb/io.questdb.ServerMain" in argv and argv.count("-d") == 1,
                  "Startup must name the original QuestDB module and unique root")
    index = argv.index("-d")
    audit.require(index + 1 < len(argv) and Path(argv[index + 1]).resolve() == root, "Startup argv root differs")
    return normalize_birth(proof.get("birth_utc"))


def validate_listener_records(records, root, pid, birth):
    audit.require(isinstance(records, list) and len(records) == 2 and {row.get("port") for row in records} == {HTTP_PORT, PG_PORT}, "Exactly the two D103 private listeners required")
    for row in records:
        audit.require(type(row.get("pid")) is int and row["pid"] == pid and row.get("address") == "127.0.0.1" and row.get("name", "").lower() == "java.exe", "Dedicated private listener process differs")
        audit.require(normalize_birth(row.get("birth")) == birth, "Native PID was reused or restarted")
        argv = split_windows_command_line(row.get("command"))
        audit.require("io.questdb/io.questdb.ServerMain" in argv and argv.count("-d") == 1, "Native QuestDB module or unique root missing")
        index = argv.index("-d")
        audit.require(index + 1 < len(argv) and Path(argv[index + 1]).resolve() == root, "Native listener belongs to another root")
    return {"pid": pid, "birth_utc": birth, "data_root": str(root), "host": "127.0.0.1", "http_port": HTTP_PORT, "pg_port": PG_PORT}


class PrivateTarget:
    def __init__(self, root, pid, startup_path, startup_sha):
        self.root = Path(root).resolve(strict=True)
        audit.require(self.root == ROOT.resolve(strict=True), "Pinned D103 private data root required")
        config = {}
        for line in (self.root / "conf/server.conf").read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1); config[key.strip()] = value.strip()
        audit.require(config.get("http.net.bind.to") == "127.0.0.1:19030" and config.get("pg.net.bind.to") == "127.0.0.1:18842", "Private config listener mismatch")
        self.startup_path, self.startup_sha = Path(startup_path).resolve(), startup_sha
        self.pid = pid
        self.birth = validate_startup(load(self.startup_path, startup_sha), self.root, pid)
        self.identity = None

    def verify(self):
        validate_startup(load(self.startup_path, self.startup_sha), self.root, self.pid)
        command = r'''
$ErrorActionPreference='Stop'
$listeners=@(Get-NetTCPConnection -LocalPort 19030,18842 -State Listen -ErrorAction Stop)
$records=@(foreach($port in @(19030,18842)) {
  $matches=@($listeners | Where-Object LocalPort -eq $port)
  if($matches.Count -ne 1) { throw 'Ambiguous private listener' }
  $listener=$matches[0]
  $procD103=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
  [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$procD103.ProcessId;name=$procD103.Name;command=$procD103.CommandLine;birth=$procD103.CreationDate.ToUniversalTime().ToString('o')}
})
ConvertTo-Json -InputObject $records -Compress
'''
        completed = subprocess.run([str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", command],
                                   capture_output=True, text=True, timeout=30)
        audit.require(completed.returncode == 0, "Dedicated native process attestation failed")
        identity = validate_listener_records(json.loads(completed.stdout), self.root, self.pid, self.birth)
        audit.require(self.identity is None or self.identity == identity, "Private process identity drifted")
        self.identity = identity
        return identity


class PrivateReader(audit.ReadOnlyPG):
    def __init__(self, target, cancel_file=None):
        audit.check_cancel(cancel_file)
        target.verify()
        self.cancel_file = cancel_file
        self.visibility_deadline = None
        self.connection = psycopg2.connect(host="127.0.0.1", port=PG_PORT, user="admin", password="quest", dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def records(self, sql, params=None, cap=audit.CAP):
        audit.check_cancel(self.cancel_file)
        audit.single_select(sql)
        audit.require(type(cap) is int and 1 <= cap <= audit.CAP, "Finite SELECT cap required")
        deadline = time.monotonic() + 20
        if self.visibility_deadline is not None:
            deadline = min(deadline, self.visibility_deadline)
        with private_pg_deadline(self.connection, deadline):
            with self.connection.cursor() as cursor:
                cursor.execute(sql, params)
                fields = [item[0] for item in cursor.description]
                audit.require(len(set(fields)) == len(fields), "Duplicate private SELECT columns")
                rows = cursor.fetchmany(cap + 1)
        audit.require(len(rows) <= cap, "Private SELECT reached truncation sentinel")
        audit.check_cancel(self.cancel_file)
        return [{field: audit.native_value(value) for field, value in zip(fields, row)} for row in rows]


@contextmanager
def private_pg_deadline(connection, deadline):
    previous = psycopg2.extensions.get_wait_callback()
    psycopg2.extensions.set_wait_callback(lambda value: audit.wait_pg(value, deadline))
    try:
        audit.require(time.monotonic() < deadline, "Private SELECT deadline exceeded")
        yield
        audit.require(time.monotonic() < deadline, "Private SELECT deadline exceeded")
    except BaseException:
        connection.close()
        raise
    finally:
        psycopg2.extensions.set_wait_callback(previous)


def private_state(reader, table):
    audit.require(table in (audit.SOURCE, *PROTECTED_OUTPUTS), "Private source/output read whitelist required")
    physical = reader.records("SELECT id,directoryName,table_txn,table_row_count,partitionBy,designatedTimestamp,walEnabled,dedup,table_suspended,wal_pending_row_count FROM tables() WHERE table_name=%s LIMIT 2", (table,), cap=2)
    audit.require(len(physical) <= 1, "Private table identity ambiguous")
    if not physical:
        return {"exists": False}
    physical = physical[0]
    wal = audit.one(reader, "SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name=%s LIMIT 2", (table,))
    count = audit.exact_count(audit.one(reader, f"SELECT count() AS n FROM {table}")["n"])
    audit.require(type(physical["id"]) is int and physical["id"] > 0 and isinstance(physical["directoryName"], str) and physical["directoryName"].strip(), "Complete private physical identity required")
    for value in (physical["wal_pending_row_count"], wal["sequencerTxn"], wal["writerTxn"], wal["bufferedTxnSize"]):
        audit.exact_count(value)
    audit.require(type(physical["table_suspended"]) is type(wal["suspended"]) is bool, "Exact private suspension flags required")
    if physical["table_txn"] is None:
        audit.require(count == 0 and wal["sequencerTxn"] == wal["writerTxn"] == 0, "Null transaction requires independent actual empty table proof")
    else:
        audit.exact_count(physical["table_txn"])
    if physical["table_row_count"] is not None:
        audit.exact_count(physical["table_row_count"])
    audit.require(physical["table_row_count"] == count or (physical["table_row_count"] is None and count == 0), "Private raw row metadata differs from actual COUNT")
    settled = (not physical["table_suspended"] and physical["wal_pending_row_count"] == 0 and not wal["suspended"] and wal["bufferedTxnSize"] == 0 and wal["sequencerTxn"] == wal["writerTxn"])
    return {"exists": True, "physical": physical, "wal": wal, "actual_select_count": count, "settled": settled}


def wait_visible(reader, expected_rows, cancel_file=None):
    deadline = time.monotonic() + WAL_DEADLINE_SECONDS
    reader.visibility_deadline = deadline
    try:
        while True:
            audit.check_cancel(cancel_file)
            audit.require(time.monotonic() < deadline, "Private source WAL/readback deadline exceeded; do not resend")
            state = private_state(reader, audit.SOURCE)
            audit.require(time.monotonic() < deadline, "Private source WAL/readback deadline exceeded; do not resend")
            if state.get("exists") and state["settled"] and state["actual_select_count"] == expected_rows:
                return state
            time.sleep(0.1)
    finally:
        reader.visibility_deadline = None


def source_rows(reader):
    return reader.records(f"SELECT {','.join(audit.SOURCE_FIELDS)} FROM index_monthly ORDER BY trade_date,ts_code LIMIT 49", cap=48)


def strict_source_compare(actual, expected):
    audit.require(len(actual) == len(expected), "Private complete source row count differs")
    for left, right in zip(actual, expected):
        audit.require(list(left) == list(audit.SOURCE_FIELDS) and list(right) == list(audit.SOURCE_FIELDS), "All fourteen source fields required")
        for field, kind in audit.SOURCE_TYPES.items():
            if kind == "DOUBLE":
                audit.require(audit.raw_bits(left[field]) == audit.raw_bits(right[field]), "Private source DOUBLE bits differ: " + field)
            else:
                audit.require(type(left[field]) is type(right[field]) and left[field] == right[field], "Private source native value differs: " + field)
    audit.require(audit.canonical_sha(actual) == audit.canonical_sha(expected), "Complete private source canonical SHA differs")
    return {"rows": len(expected), "full_field_comparisons": len(expected) * 14, "double_rawbit_comparisons": len(expected) * 9, "double_tolerance": 0, "canonical_records_sha256": audit.canonical_sha(actual)}


def captured_source(preflight):
    capture = preflight["source_capture"]
    path = Path(capture["path"]).resolve(strict=True)
    audit.require(path.is_relative_to(DIRECTORY.resolve()) and audit.digest(path) == capture["sha256"], "Source JSONL artifact SHA differs")
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        value = json.loads(line)
        audit.require(set(value) == {"values", "raw_double_bits"}, "Complete source JSONL proof shape required")
        row = value["values"]
        audit.require(value["raw_double_bits"] == {field: audit.raw_bits(row[field]) for field, kind in audit.SOURCE_TYPES.items() if kind == "DOUBLE"}, "Captured native raw bits differ")
        rows.append(row)
    audit.require(len(rows) == capture["rows"] == 48 and capture["field_values"] == 672 and capture["physical_types"] == audit.SOURCE_TYPES and audit.canonical_sha(rows) == capture["canonical_records_sha256"], "Frozen complete48 source capture differs")
    census = audit.source_census(rows, list(MONTHS))
    audit.require(not census["gaps"] and census == preflight["source_census"], "Frozen source code/month census differs")
    return rows


def validate_preflight(proof):
    audit.require(proof.get("task_id") == "D103" and proof.get("status") == "VERIFIED_BOUNDED_SOURCE_ORACLE" and proof.get("months") == list(MONTHS) and proof.get("codes") == audit.FEATURE_CODES and proof.get("stable_physical_and_wal_versions") is True,
                  "Exact successful three-month D103 source preflight required")
    audit.require(proof["tables_before"] == proof["tables_after"] and proof["source_required_types"] == audit.SOURCE_TYPES and proof["output_required_types"] == audit.OUTPUT_TYPES, "Frozen formal schema/frontier differs")
    audit.require(proof.get("formal_mutated") is False and proof.get("private_mutated") is False and all(type(value) is int and value == 0 for value in proof["actions"].values()), "Preflight must contain actual zero side effects")
    for path, expected in proof["python_file_sha256"].items():
        audit.require(audit.digest(path) == expected, "Reference source changed after preflight")
    return captured_source(proof)


def sql_literal(value, kind):
    if value is None:
        return "NULL"
    if kind == "DOUBLE":
        audit.raw_bits(value)
        return repr(value)
    audit.require(isinstance(value, str), "Native source text/timestamp literal required")
    if kind == "TIMESTAMP":
        audit.utc_stamp(value)
    return "'" + value.replace("'", "''") + "'"


def insert_sql(rows):
    audit.require(len(rows) == 32, "Initial INSERT admits exactly the32 captured real source records")
    census = audit.source_census(rows, list(INITIAL_MONTHS))
    audit.require(not census["gaps"] and all(item["rows"] == 1 for item in census["coverage"]), "Exactly one actual code/month record required for this fixture")
    tuples = ["(" + ",".join(sql_literal(row[field], kind) for field, kind in audit.SOURCE_TYPES.items()) + ")" for row in rows]
    sql = "INSERT INTO index_monthly (" + ",".join(audit.SOURCE_FIELDS) + ") VALUES " + ",".join(tuples)
    audit.require(len(sql.encode("utf-8")) <= MAX_SQL_BYTES and ";" not in sql, "Initial SQL exceeds its finite single-statement budget")
    return sql


def validate_write(kind, sql, rows, source_records=None):
    audit.require(type(rows) is int and ((kind == "SOURCE_CREATE" and sql == CREATE_SQL and rows == 0) or
        (kind == "SOURCE_INITIAL_INSERT" and rows == 32 and source_records is not None and sql == insert_sql(source_records))),
        "Only the fixed missing-source CREATE or real32 source INSERT is admitted")
    audit.require(";" not in sql and len(sql.encode("utf-8")) <= MAX_SQL_BYTES, "Single finite mutation required")


def submit_once(target, kind, sql, rows, claim, output, result, boundary, cancel_file=None, source_records=None):
    validate_write(kind, sql, rows, source_records)
    audit.check_cancel(cancel_file)
    audit.require(not claim.exists(), "Mutation claim already exists; UNKNOWN and ACK are never automatically resent")
    url = "http://127.0.0.1:19030/exec?" + urlencode({"query": sql})
    audit.require(len(url.encode("ascii")) <= MAX_SQL_BYTES, "Encoded private HTTP request exceeds the finite budget")
    boundary(kind)
    journal = {"protocol_version": 1, "task_id": "D103", "invocation_id": result["invocation_id"], "kind": kind, "table": audit.SOURCE,
        "sql": sql, "sql_sha256": audit.sha_bytes(sql.encode("utf-8")), "rows": rows, "ack": "UNKNOWN", "attempted": False,
        "private_target": target.verify(), "created_at": datetime.now(timezone.utc).isoformat(), "automatic_retry": False}
    audit.save_new(claim, journal)
    operation = {"kind": kind, "table": audit.SOURCE, "rows": rows, "claim_path": str(claim), "intent_sha256": audit.digest(claim), "ack": "UNKNOWN", "attempted": False}
    result["operations"].append(operation)
    save_progress(output, result)
    # Reattest after durable intent, immediately before the sole write attempt.
    audit.check_cancel(cancel_file)
    boundary(kind)
    journal["attempted"] = operation["attempted"] = True
    journal["attempt_started_at"] = datetime.now(timezone.utc).isoformat()
    save_progress(claim, journal); save_progress(output, result)
    with urlopen(url, timeout=20) as response:
        body = response.read(MAX_HTTP_BYTES + 1)
        audit.require(len(body) <= MAX_HTTP_BYTES and response.status == 200, "Private mutation response failed or exceeded its cap")
    payload = json.loads(body)
    audit.require(isinstance(payload, dict) and payload.get("ddl") == "OK" and "error" not in payload, "Private mutation ACK is not confirmed")
    journal["ack"] = operation["ack"] = "ACKNOWLEDGED"
    journal["ack_at"] = datetime.now(timezone.utc).isoformat(); journal["response"] = payload
    save_progress(claim, journal)
    operation["claim_sha256"] = audit.digest(claim)
    save_progress(output, result)


def formal_snapshot(reader):
    tables = {table: audit.table_state(reader, table) for table in (audit.SOURCE, audit.TARGET)}
    schemas = {table: reader.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) for table in tables if tables[table]["exists"]}
    return {"tables": tables, "schemas": schemas}


def run_initial(args, result, output):
    preflight = load(args.preflight, args.preflight_sha256)
    captured = validate_preflight(preflight)
    target = PrivateTarget(args.private_root, args.expected_pid, args.startup_attestation, args.startup_attestation_sha256)
    result["private_target_attestation"] = target.verify()
    result["preflight_evidence"] = {"path": str(Path(args.preflight).resolve()), "sha256": args.preflight_sha256}
    result["startup_evidence"] = {"path": str(Path(args.startup_attestation).resolve()), "sha256": args.startup_attestation_sha256}
    result["whole_real_source"] = preflight["source_capture"]
    result["formal_historical_output_parity"] = preflight["formal_existing_output_parity"]
    initial = [row for row in captured if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") in INITIAL_MONTHS]
    increment = [row for row in captured if audit.utc_stamp(row["trade_date"]).strftime("%Y%m") == MONTHS[2]]
    audit.require(len(initial) == 32 and len(increment) == 16, "Actual frozen source month sizes differ")
    sql = insert_sql(initial)
    formal, private = None, None
    try:
        formal = audit.ReadOnlyPG(args.cancel_file)
        private = PrivateReader(target, args.cancel_file)
        frozen_formal = formal_snapshot(formal)
        audit.require(frozen_formal == {"tables": preflight["tables_after"], "schemas": preflight["schemas"]}, "Actual formal source/output frontier or schema changed after preflight")
        strict_source_compare(formal.records(preflight["source_select"]["sql"], tuple(preflight["source_select"]["params"])), captured)
        result["formal_before"] = frozen_formal
        result["private_before"] = {table: private_state(private, table) for table in (audit.SOURCE, *PROTECTED_OUTPUTS)}
        audit.require(result["private_before"][audit.SOURCE] == {"exists": False}, "Initial source must be missing; no replay/reset is admitted")
        protected_outputs = {table: result["private_before"][table] for table in PROTECTED_OUTPUTS}
        columns = {}
        for table, protected_output in protected_outputs.items():
            audit.require(not protected_output["exists"] or (protected_output["settled"] and protected_output["actual_select_count"] == 0), "Initial private output must be missing or actually empty with settled WAL")
            columns[table] = private.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) if protected_output["exists"] else []
            if protected_output["exists"]:
                audit.validate_schema(columns[table], protected_output, audit.literal_schema(audit.TARGET_MODEL, "EquityStyleMonthly"), False)
        result["protected_output_schema_before"] = columns
        result["initial_capture"] = audit.save_rows_new(output.with_name(output.stem + "-source-initial.jsonl"), initial, audit.SOURCE_TYPES)
        result["held_increment_capture"] = audit.save_rows_new(output.with_name(output.stem + "-source-august-held.jsonl"), increment, audit.SOURCE_TYPES)
        def boundary(kind):
            audit.check_cancel(args.cancel_file)
            validate_preflight(load(args.preflight, args.preflight_sha256))
            target.verify()
            audit.require(formal_snapshot(formal) == frozen_formal, "Formal source/output changed before fixture mutation")
            audit.require({table: private_state(private, table) for table in PROTECTED_OUTPUTS} == protected_outputs, "Private output frontier changed before source-only mutation")
            source = private_state(private, audit.SOURCE)
            if kind == "SOURCE_CREATE":
                audit.require(source == {"exists": False}, "Missing-only source CREATE precondition changed")
            else:
                audit.require(source["exists"] and source["settled"] and source["actual_select_count"] == 0 and source_rows(private) == [], "Source INSERT requires independently proven empty source")
                native = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=15)
                audit.validate_schema(native, source, audit.literal_schema(audit.SOURCE_MODEL, "IndexMonthly"), True)
        submit_once(target, "SOURCE_CREATE", CREATE_SQL, 0, DIRECTORY / "index-monthly-create-once-20261006.json", output, result, boundary, args.cancel_file)
        wait_visible(private, 0, args.cancel_file)
        result["private_source_empty_after_create"] = private_state(private, audit.SOURCE)
        submit_once(target, "SOURCE_INITIAL_INSERT", sql, 32, DIRECTORY / "index-monthly-initial-insert-once-20261006.json", output, result, boundary, args.cancel_file, source_records=initial)
        result["private_source_visible_after_insert"] = wait_visible(private, 32, args.cancel_file)
        actual = source_rows(private)
        result["source_readback"] = strict_source_compare(actual, initial)
        result["private_source_census"] = audit.source_census(actual, list(INITIAL_MONTHS))
        result["private_source_schema"] = private.records("SELECT * FROM table_columns('index_monthly') LIMIT 15", cap=15)
        audit.validate_schema(result["private_source_schema"], result["private_source_visible_after_insert"], audit.literal_schema(audit.SOURCE_MODEL, "IndexMonthly"), True)
        result["formal_after"] = formal_snapshot(formal)
        audit.require(result["formal_after"] == frozen_formal, "Formal source/output changed during private source fixture")
        result["formal_source_readback"] = strict_source_compare(formal.records(preflight["source_select"]["sql"], tuple(preflight["source_select"]["params"])), captured)
        result["private_after"] = {table: private_state(private, table) for table in (audit.SOURCE, *PROTECTED_OUTPUTS)}
        audit.require({table: result["private_after"][table] for table in PROTECTED_OUTPUTS} == protected_outputs, "Protected private output changed during source-only fixture")
        result["protected_output_schema_after"] = {table: private.records(f"SELECT * FROM table_columns('{table}') LIMIT 31", cap=31) if protected_outputs[table]["exists"] else [] for table in PROTECTED_OUTPUTS}
        audit.require(result["protected_output_schema_after"] == columns, "Protected private output schema changed")
        audit.require(result["private_after"][audit.SOURCE] == result["private_source_visible_after_insert"], "Source frontier changed during full readback")
        result["private_target_attestation_after"] = target.verify()
        audit.require(result["private_target_attestation_after"] == result["private_target_attestation"], "Private process changed during fixture")
        result["status"] = "VERIFIED_INITIAL_REAL_SOURCE_FIXTURE"
    finally:
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("initial",), required=True)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--private-root", type=Path, required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--preflight-sha256", required=True)
    parser.add_argument("--startup-attestation", type=Path, required=True)
    parser.add_argument("--startup-attestation-sha256", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--cancel-file")
    args = parser.parse_args()
    audit.require(args.execute_isolated, "Explicit source-only private execution required")
    output = audit.output_path(args.output)
    for claim_name in ("index-monthly-create-once-20261006.json", "index-monthly-initial-insert-once-20261006.json"):
        audit.require(not (DIRECTORY / claim_name).exists(), "Prior durable mutation claim blocks every automatic new attempt")
    for suffix in ("-source-initial.jsonl", "-source-august-held.jsonl"):
        audit.require(not output.with_name(output.stem + suffix).exists(), "Prior source capture blocks output reuse")
    result = {"protocol_version": 1, "task_id": "D103", "stage": "initial", "invocation_id": str(uuid.uuid4()), "checked_at": datetime.now(timezone.utc).isoformat(),
        "status": "IN_PROGRESS", "purpose": "complete real source fixture only", "operations": [], "automatic_retry": False, "planned_source_rows": 32,
        "formal_mutated": False, "private_output_mutated": False, "java_materialization_verified": False, "native_refresh_submitted": False,
        "HTTP_socket_timeout_seconds": 20, "HTTP_response_byte_cap": MAX_HTTP_BYTES, "native_PG_statement_absolute_deadline_seconds": 20, "WAL_visibility_absolute_deadline_seconds": WAL_DEADLINE_SECONDS,
        "increment_admission_contract": INCREMENT_ADMISSION_CONTRACT}
    audit.save_new(output, result)
    try:
        run_initial(args, result, output)
    except BaseException as failure:
        result["status"] = "FAILED"
        result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        result["durable_intent_source_rows"] = sum(item["rows"] for item in result["operations"] if item["kind"] == "SOURCE_INITIAL_INSERT")
        result["attempted_source_rows"] = sum(item["rows"] for item in result["operations"] if item["kind"] == "SOURCE_INITIAL_INSERT" and item["attempted"])
        result["acknowledged_source_rows"] = sum(item["rows"] for item in result["operations"] if item["kind"] == "SOURCE_INITIAL_INSERT" and item["ack"] == "ACKNOWLEDGED")
        result["acknowledged_operations"] = sum(item["ack"] == "ACKNOWLEDGED" for item in result["operations"])
        save_progress(output, result)
    print(json.dumps({"task_id": "D103", "status": result["status"], "output": str(output), "sha256": audit.digest(output),
        "attempted_source_rows": result["attempted_source_rows"], "acknowledged_source_rows": result["acknowledged_source_rows"], "acknowledged_operations": result["acknowledged_operations"], "formal_writes": 0, "private_output_writes": 0}))
    return 0 if result["status"] == "VERIFIED_INITIAL_REAL_SOURCE_FIXTURE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
