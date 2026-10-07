"""Prepare bounded real D098 source fixtures on an attested private QuestDB.

Only fixture source/calendar INSERTs and missing owner SOURCE/MV/calendar DDL
are allowed. Native business refresh is performed later by the Java job.
"""
from __future__ import annotations

import argparse
from datetime import date, datetime, timezone
import json
import math
import os
from pathlib import Path
import struct
import subprocess
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import psycopg2
from psycopg2.extras import execute_values

import accept_d095_mv_isolated as common
import audit_d098_mv_readonly as audit

SOURCE, MV = audit.SOURCE, audit.MV
CALENDAR = "exchange_calendar"
ROOT = common.REPO_ROOT / "var/d098-isolated-questdb"
DIRECTORY = common.REPO_ROOT / "artifacts/java-migration/D098/commands"
OUTPUT = DIRECTORY / "source-fixture-20261006.json"
INITIAL_CAPTURE = DIRECTORY / "real-source-initial-20261006.json"
INCREMENT_CAPTURE = DIRECTORY / "real-source-increment-20261006.json"
CALENDAR_FIELDS = ("exchange", "cal_date", "is_open", "pretrade_date")
CALENDAR_TYPES = dict(zip(CALENDAR_FIELDS, ("SYMBOL", "TIMESTAMP", "INT", "STRING")))
PRIVATE_TARGET = None


class PrivateTarget:
    def __init__(self, root, expected_pid=None):
        self.root = root.resolve(strict=True)
        if self.root != ROOT.resolve(strict=True) or not self.root.is_relative_to((common.REPO_ROOT / "var").resolve()):
            raise RuntimeError("D098 fixture is pinned to this workspace's var/d098-isolated-questdb")
        config = {}
        for line in (self.root / "conf/server.conf").read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
        if config.get("http.net.bind.to") != "127.0.0.1:19010" or config.get("pg.net.bind.to") != "127.0.0.1:18822":
            raise RuntimeError("D098 data root does not declare the private QWP/PG listeners")
        self.expected_pid, self.identity = expected_pid, None
        self.verify()
        marker = json.loads((self.root / "d098-fixture.json").read_text(encoding="utf-8"))
        if marker != {"task_id": "D098", "data_root": str(self.root), "fixture_tables": [SOURCE, MV]}:
            raise RuntimeError("D098 private fixture marker differs from the owned source/MV declaration")

    def verify(self):
        command = r'''
$ErrorActionPreference='Stop'
$listeners=@(Get-NetTCPConnection -LocalPort 19010,18822 -State Listen -ErrorAction Stop)
$records=@(foreach($port in @(19010,18822)) {
    $matches=@($listeners | Where-Object LocalPort -eq $port)
    if($matches.Count -ne 1) { throw 'Ambiguous or missing D098 private listener' }
    $listener=$matches[0]
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$proc.ProcessId;name=$proc.Name;command=$proc.CommandLine}
})
ConvertTo-Json -InputObject $records -Compress
'''
        completed = subprocess.run([str(Path(os.environ["SystemRoot"]) /
            "System32/WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True, text=True, timeout=30)
        if completed.returncode:
            raise RuntimeError("Cannot attest D098 private listeners")
        records = json.loads(completed.stdout)
        if (not isinstance(records, list) or len(records) != 2 or
                {row["port"] for row in records} != {19010, 18822} or len({row["pid"] for row in records}) != 1):
            raise RuntimeError("Private ports must name one exact QuestDB process")
        for row in records:
            if row["address"] != "127.0.0.1" or row["name"].lower() not in {"java.exe", "questdb.exe"}:
                raise RuntimeError("Private listener must be QuestDB on IPv4 loopback")
            argv = common.split_windows_command_line(row["command"])
            positions = [index for index, value in enumerate(argv) if value == "-d"]
            if len(positions) != 1 or positions[0] + 1 >= len(argv) or Path(argv[positions[0] + 1]).resolve() != self.root:
                raise RuntimeError("Private listener process does not name the exact D098 -d data root")
        observed = {"pid": records[0]["pid"], "data_root": str(self.root), "address": "127.0.0.1",
                    "http_port": 19010, "pg_port": 18822}
        if self.expected_pid is not None and observed["pid"] != self.expected_pid:
            raise RuntimeError("Private process PID differs from --expected-pid")
        if self.identity is not None and observed != self.identity:
            raise RuntimeError("D098 private process changed during fixture preparation")
        self.identity = observed
        return observed


def qwp(sql):
    if PRIVATE_TARGET is None:
        raise RuntimeError("Attest D098 private target before any private database access")
    select = bool(sql.split()) and sql.split(None, 1)[0].upper() == "SELECT"
    if not select:
        PRIVATE_TARGET.verify()
    url = "http://127.0.0.1:19010/exec?" + urlencode({"query": sql, "limit": "0,100000"})
    with urlopen(Request(url), timeout=45) as response:
        payload = json.load(response)
    if "error" in payload:
        raise RuntimeError(f"Private D098 query failed: {payload['error']}")
    names = [column["name"] for column in payload.get("columns", [])]
    rows = [dict(zip(names, row)) for row in payload.get("dataset", [])]
    if len(rows) >= 100000:
        raise RuntimeError("Private query reached the finite source row cap")
    return rows


def strict_compare(actual, expected, fields, keys):
    for label, rows in (("actual", actual), ("expected", expected)):
        for index, row in enumerate(rows):
            if any(key not in row or row[key] is None or
                   (isinstance(row[key], str) and not row[key].strip()) for key in keys):
                raise RuntimeError(f"{label} fixture row {index} has an incomplete identity key")
    left, right = common.keyed(actual, keys), common.keyed(expected, keys)
    if left.keys() != right.keys():
        raise RuntimeError("Private fixture differs from complete real source key set")
    for key, row in right.items():
        for field in fields:
            a, e = left[key][field], row[field]
            if a is None or e is None:
                equal = a is None and e is None
            elif isinstance(a, float) or isinstance(e, float):
                equal = math.isfinite(a) and math.isfinite(e) and struct.pack(">d", float(a)) == struct.pack(">d", float(e))
            else:
                equal = a == e
            if not equal:
                raise RuntimeError(f"Exact source field drift at {key}, {field}")
    return len(expected) * len(fields)


def ensure_table(table, types, timestamp, partition, keys):
    if table not in {SOURCE, CALENDAR}:
        raise RuntimeError("Only D098 source and real calendar fixture tables may be installed")
    existing = qwp(f"SELECT table_name FROM tables() WHERE table_name='{table}'")
    if not existing:
        columns = ",".join(f"{name} {kind}" for name, kind in types.items())
        qwp(f"CREATE TABLE {table} ({columns}) TIMESTAMP({timestamp}) PARTITION BY {partition} "
            f"WAL DEDUP UPSERT KEYS({','.join(keys)})")
    columns = qwp(f"SELECT * FROM table_columns('{table}')")
    actual = {row["column"]: row["type"] for row in columns}
    identity = qwp(f"SELECT * FROM tables() WHERE table_name='{table}'")
    if (actual != types or tuple(actual) != tuple(types) or
            {row["column"] for row in columns if row["upsertKey"]} != set(keys) or
            [row["column"] for row in columns if row["designated"]] != [timestamp] or len(identity) != 1 or
            identity[0]["partitionBy"] != partition or not identity[0]["walEnabled"] or not identity[0]["dedup"]):
        raise RuntimeError(f"Existing private {table} differs from the real projected fixture contract")
    return {"created": not existing, "types": actual, "partition": partition, "dedup_keys": list(keys)}


def settled(table):
    table_rows = qwp(f"SELECT table_suspended,wal_pending_row_count FROM tables() WHERE table_name='{table}'")
    rows = qwp(f"SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name='{table}'")
    if len(rows) != 1 or len(table_rows) != 1 or rows[0]["suspended"] or table_rows[0]["table_suspended"]:
        raise RuntimeError(f"Private fixture WAL suspended or unavailable: {table}")
    return rows[0] if (rows[0]["sequencerTxn"] == rows[0]["writerTxn"] and rows[0]["bufferedTxnSize"] == 0 and
                       table_rows[0]["wal_pending_row_count"] == 0) else None


def save(output, result):
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    temporary = output.with_name(f".{output.name}.{os.getpid()}.tmp")
    try:
        with temporary.open("w", encoding="utf-8") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def insert_fixture(table, fields, rows, output, result, phase):
    if table not in {SOURCE, CALENDAR} or not rows or len(rows) > 20000:
        raise RuntimeError("Unexpected fixture table or finite batch budget exceeded")
    allowed = audit.SOURCE_FIELDS if table == SOURCE else CALENDAR_FIELDS
    if tuple(fields) != allowed:
        raise RuntimeError("Fixture INSERT fields differ from the frozen source projection")
    keys = ("ts", "symbol") if table == SOURCE else ("exchange", "cal_date")
    strict_compare(rows, rows, allowed, keys)
    PRIVATE_TARGET.verify()
    submission = {"phase": phase, "table": table, "submitted_rows": len(rows), "ack": "UNKNOWN",
                  "planned_rows": len(rows), "attempted_rows": 0, "acknowledged_rows": 0, "batches": [],
                  "submitted_rows_semantics": "legacy planned phase payload; not confirmed database rows",
                  "attempted_rows_semantics": "durable intent before one driver call; transmission/application may be unknown",
                  "acknowledged_rows_semantics": "driver returned normally; exact WAL readback is separately required",
                  "purpose": "real source fixture only; not Java materializer acceptance", "automatic_retry": False}
    result["fixture_submissions"].append(submission)
    save(output, result)
    with psycopg2.connect(host="127.0.0.1", port=18822, user="admin", password="quest", dbname="qdb", connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            values = [tuple(row[field].replace("Z", "") if isinstance(row[field], str) and
                      field in {"ts", "cal_date"} else row[field] for field in fields) for row in rows]
            for offset in range(0, len(values), 500):
                PRIVATE_TARGET.verify()
                batch_values = values[offset:offset + 500]
                batch = {"batch_index": len(submission["batches"]) + 1, "offset": offset,
                         "planned_rows": len(batch_values), "attempted_rows": len(batch_values),
                         "acknowledged_rows": 0, "ack": "UNKNOWN", "automatic_retry": False}
                submission["batches"].append(batch)
                submission["attempted_rows"] += len(batch_values)
                save(output, result)  # Persist UNKNOWN intent before the one permitted driver call.
                execute_values(cursor, f"INSERT INTO {table} ({','.join(fields)}) VALUES %s",
                               batch_values, page_size=500)
                batch["ack"] = "ACKNOWLEDGED"
                batch["acknowledged_rows"] = len(batch_values)
                submission["acknowledged_rows"] += len(batch_values)
                save(output, result)
    submission["ack"] = "ACKNOWLEDGED"
    save(output, result)
    common.wait_until(lambda: settled(table), f"{phase} real fixture WAL visibility", 30)


def capture_file(path, rows, snapshot, start, stop):
    payload = {"source": SOURCE, "fields": list(audit.SOURCE_FIELDS), "row_count": len(rows),
               "range_from_inclusive": start, "range_to_exclusive": stop, "strict_capture_sha256": audit.source_sha(rows),
               "formal_source_identity": snapshot["tables"][SOURCE], "rows": rows}
    save(path, payload)
    replayed = json.loads(path.read_text(encoding="utf-8"))["rows"]
    strict_compare(replayed, rows, audit.SOURCE_FIELDS, ("ts", "symbol"))
    if audit.source_sha(replayed) != payload["strict_capture_sha256"]:
        raise RuntimeError("Saved JSON capture does not preserve exact source field values")
    return {key: payload[key] for key in ("row_count", "strict_capture_sha256", "range_from_inclusive", "range_to_exclusive")} | {"path": str(path)}


def main():
    global PRIVATE_TARGET
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, default=ROOT)
    parser.add_argument("--expected-pid", type=int, default=37904)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if not args.execute_isolated:
        parser.error("--execute-isolated is required for real private source fixture INSERT/owner DDL")
    result = {"task_id": "D098", "status": "IN_PROGRESS", "checked_at": datetime.now(timezone.utc).isoformat(),
              "formal_mutated": False, "native_refresh_submitted": False, "java_materializer_verified": False,
              "fixture_submissions": [], "source_projection_scope": "15 necessary physical fields including complete ts/symbol keys; formal source has 110 fields"}
    exit_code = 0
    try:
        formal_before = audit.snapshot()
        formal_columns = audit.query(f"SELECT * FROM table_columns('{SOURCE}')")
        required_types = {row["column"]: row["type"] for row in formal_columns if row["column"] in audit.SOURCE_TYPES}
        if required_types != audit.SOURCE_TYPES or {row["column"] for row in formal_columns if row["upsertKey"]} != {"ts", "symbol"}:
            raise RuntimeError("Protected formal source types/complete key changed from the audited definition")
        result["formal_snapshot_before"] = formal_before
        bound = "WHERE ts >= '2026-09-17' AND ts < '2026-09-22'"
        count = audit.one(f"SELECT count() AS n FROM {SOURCE} {bound}")["n"]
        raw = audit.query(f"SELECT {','.join(audit.SOURCE_FIELDS)} FROM {SOURCE} {bound} ORDER BY ts,symbol")
        if count != 23773 or len(raw) != count or len(common.keyed(raw, ("ts", "symbol"))) != count:
            raise RuntimeError("Formal finite real source capture must cover all 23773 rows across three declared dates")
        dates = sorted({row["ts"][:10] for row in raw})
        if dates != ["2026-09-17", "2026-09-18", "2026-09-21"]:
            raise RuntimeError("Captured source differs from the three real daily partitions")
        initial = [row for row in raw if row["ts"][:10] in dates[:2]]
        increment = [row for row in raw if row["ts"][:10] == dates[2]]
        calendar_before = common.table_snapshot(CALENDAR)
        calendar = audit.query("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar "
                               "WHERE exchange='SSE' AND cal_date >= '2026-09-10' AND cal_date < '2026-09-30' ORDER BY cal_date")
        if len(initial) != 15828 or len(increment) != 7945 or len(calendar) != 20:
            raise RuntimeError("Initial/increment/calendar real sample sizes differ")
        if len(common.keyed(calendar, ("exchange", "cal_date"))) != 20:
            raise RuntimeError("Calendar receipt has duplicate complete physical keys")
        formal_after = audit.snapshot()
        calendar_after = common.table_snapshot(CALENDAR)
        if formal_before != formal_after or common.table_version(calendar_before) != common.table_version(calendar_after):
            raise RuntimeError("Protected formal source/MV/alias/calendar changed during real source extraction")
        result["formal_snapshot_after"] = formal_after
        result["formal_source_metadata_stable"] = True
        result["source_required_types"] = audit.SOURCE_TYPES
        result["initial_capture"] = capture_file(INITIAL_CAPTURE, initial, formal_after, "2026-09-17", "2026-09-19")
        result["increment_capture"] = capture_file(INCREMENT_CAPTURE, increment, formal_after, "2026-09-21", "2026-09-22")
        result["source_full_capture_rows"] = count
        result["source_full_capture_field_values"] = count * len(audit.SOURCE_FIELDS)
        result["source_full_capture_sha256"] = audit.source_sha(raw)
        result["formal_expected_daily_rows"] = audit.query(common.VIEW_SELECTS[audit.ALIAS].format(where=bound) + " ORDER BY trade_date")
        PRIVATE_TARGET = PrivateTarget(args.private_root, args.expected_pid)
        result["private_target_attestation"] = PRIVATE_TARGET.identity
        existing_tables = {row["table_name"] for row in qwp("SELECT table_name FROM tables()")}
        if existing_tables - {SOURCE, MV, CALENDAR}:
            raise RuntimeError("D098 private data root contains unrelated tables")
        result["source_table"] = ensure_table(SOURCE, audit.SOURCE_TYPES, "ts", "DAY", ("ts", "symbol"))
        result["calendar_table"] = ensure_table(CALENDAR, CALENDAR_TYPES, "cal_date", "YEAR", ("exchange", "cal_date"))
        existing_mv_definition = qwp(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
        if existing_mv_definition and (len(existing_mv_definition) != 1 or
                existing_mv_definition[0]["view_sql"].strip() != audit.OWNER_SQL.strip() or
                existing_mv_definition[0]["base_table_name"] != SOURCE or
                existing_mv_definition[0]["refresh_type"] != "timer" or
                existing_mv_definition[0]["timer_interval"] != 1 or existing_mv_definition[0]["timer_interval_unit"] != "MINUTE"):
            raise RuntimeError("Existing private native definition differs; no fixture source writes allowed")
        before_source = qwp(f"SELECT {','.join(audit.SOURCE_FIELDS)} FROM {SOURCE} ORDER BY ts,symbol")
        if before_source:
            strict_compare(before_source, initial, audit.SOURCE_FIELDS, ("ts", "symbol"))
        before_calendar = qwp("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar ORDER BY exchange,cal_date")
        if before_calendar:
            strict_compare(before_calendar, calendar, CALENDAR_FIELDS, ("exchange", "cal_date"))
        insert_fixture(SOURCE, audit.SOURCE_FIELDS, initial, args.output, result, "initial_two_real_days")
        copied = qwp(f"SELECT {','.join(audit.SOURCE_FIELDS)} FROM {SOURCE} ORDER BY ts,symbol")
        result["initial_source_field_comparisons"] = strict_compare(copied, initial, audit.SOURCE_FIELDS, ("ts", "symbol"))
        result["initial_private_source_sha256"] = audit.source_sha(copied)
        if result["initial_private_source_sha256"] != result["initial_capture"]["strict_capture_sha256"]:
            raise RuntimeError("Private initial source SHA differs from the exact formal capture")
        insert_fixture(SOURCE, audit.SOURCE_FIELDS, initial, args.output, result, "duplicate_source_fixture_replay")
        replayed = qwp(f"SELECT {','.join(audit.SOURCE_FIELDS)} FROM {SOURCE} ORDER BY ts,symbol")
        result["replay_source_field_comparisons"] = strict_compare(replayed, initial, audit.SOURCE_FIELDS, ("ts", "symbol"))
        result["replay_private_source_sha256"] = audit.source_sha(replayed)
        if result["replay_private_source_sha256"] != result["initial_capture"]["strict_capture_sha256"]:
            raise RuntimeError("Private source replay changed exact frozen field values")
        result["private_source_rows_after_replay"] = len(replayed)
        insert_fixture(CALENDAR, CALENDAR_FIELDS, calendar, args.output, result, "real_sse_calendar")
        copied_calendar = qwp("SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar ORDER BY exchange,cal_date")
        result["calendar_field_comparisons"] = strict_compare(copied_calendar, calendar, CALENDAR_FIELDS, ("exchange", "cal_date"))
        result["calendar_rows"] = len(copied_calendar)
        mv_existing = qwp(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
        if not mv_existing:
            qwp(f"CREATE MATERIALIZED VIEW {MV} REFRESH EVERY 1m AS ({audit.OWNER_SQL}) PARTITION BY MONTH")
        mv_state = qwp(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
        if len(mv_state) != 1 or mv_state[0]["view_sql"].strip() != audit.OWNER_SQL.strip():
            raise RuntimeError("Native MV does not match the exact registered Python SQL")
        result["native_mv_created"] = not mv_existing
        result["native_mv_initial_state"] = mv_state[0]
        result["private_source_snapshot"] = qwp(f"SELECT * FROM tables() WHERE table_name='{SOURCE}'")[0]
        result["native_mv_initialization_scope"] = "missing owner MV DDL only; no Python REFRESH and no Java job acceptance claim"
        result["third_real_day_inserted"] = False
        result["increment_field_values_captured"] = len(increment) * len(audit.SOURCE_FIELDS)
        result["private_target_attestation"] = PRIVATE_TARGET.verify()
        if audit.snapshot() != formal_before or common.table_version(common.table_snapshot(CALENDAR)) != common.table_version(calendar_before):
            raise RuntimeError("Formal protected data metadata changed during isolated fixture setup")
        result["status"] = "PREPARED_REAL_SOURCE"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    save(args.output, result)
    print(json.dumps({"task_id": "D098", "status": result["status"], "formal_mutated": False,
                      "initial_rows": result.get("private_source_rows_after_replay"),
                      "increment_rows_captured": result.get("increment_capture", {}).get("row_count"),
                      "output": str(args.output), "error": result.get("error")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
