"""Copy complete bounded real ETF sources to the dedicated D101 QuestDB.

This is a source fixture, never a Java publisher acceptance. Initial and
increment are separate, new evidence identities. No retry/reset/replacement.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sqlite3
import time
from urllib.parse import urlencode
from urllib.request import urlopen

import psycopg2
from psycopg2.extras import execute_values

import audit_d101_etf_cache_readonly as audit
import accept_d095_mv_isolated as common
from accept_d098_mv_isolated import strict_compare

ROOT = common.REPO_ROOT / "var/d101-isolated-questdb"
DIRECTORY = common.REPO_ROOT / "artifacts/java-migration/D101/commands"
FORMAL_AUDIT = DIRECTORY / "cache-readonly-audit-20261006.json"
TABLES = (*audit.SPEC.sources, audit.CACHE, audit.COVERAGE)
DAYS = ("2026-09-17", "2026-09-18", "2026-09-21")
CAP = 50000
INCREMENT_INITIAL_COUNTS = {"etf_share": 1532, "etf_daily": 4274, "etf_basic": 2958}
INCREMENT_POLICY = {"trade_dates": ["2026-09-21"], "tables": ["etf_share", "etf_daily"],
                    "max_rows": 2903, "max_batches": 7, "batch_rows": 500}


def source_sha(rows):
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False, separators=(",", ":"),
                                    allow_nan=False).encode("utf-8")).hexdigest()


def save_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def save_progress(path, value):
    # The invocation claims its new path before operations; only its own journal
    # is subsequently updated. Unknown is durable before each driver call.
    temp = path.with_suffix(path.suffix + ".pending")
    with temp.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def validate_listener_records(records, root, expected_pid):
    if type(expected_pid) is not int or expected_pid <= 0:
        raise RuntimeError("Positive exact private PID required")
    if not isinstance(records, list) or len(records) != 2:
        raise RuntimeError("Exactly two private listeners required")
    if {item.get("port") for item in records} != {19020, 18832}:
        raise RuntimeError("D101 listener ports differ")
    for item in records:
        if (item.get("address") != "127.0.0.1" or item.get("pid") != expected_pid
                or item.get("name", "").lower() != "java.exe"):
            raise RuntimeError("Dedicated loopback process identity differs")
        args = common.split_windows_command_line(item.get("command", ""))
        positions = [i for i, arg in enumerate(args) if arg == "-d"]
        if len(positions) != 1 or positions[0] + 1 >= len(args):
            raise RuntimeError("Explicit unique QuestDB data root required")
        if Path(args[positions[0] + 1]).resolve() != root:
            raise RuntimeError("Listener belongs to another data root")
        if "io.questdb/io.questdb.ServerMain" not in args:
            raise RuntimeError("Expected QuestDB module required")
    return {"pid": expected_pid, "data_root": str(root), "host": "127.0.0.1",
            "http_port": 19020, "pg_port": 18832}


class PrivateTarget:
    def __init__(self, root, expected_pid):
        self.root = Path(root).resolve(strict=True)
        if self.root != ROOT.resolve(strict=True):
            raise RuntimeError("Pinned D101 workspace root required")
        config = dict(line.split("=", 1) for line in
                      (self.root / "conf/server.conf").read_text(encoding="utf-8-sig").splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
        if config.get("http.net.bind.to") != "127.0.0.1:19020" or config.get("pg.net.bind.to") != "127.0.0.1:18832":
            raise RuntimeError("Dedicated configuration ports required")
        marker = json.loads((self.root / "d101-fixture.json").read_text(encoding="utf-8"))
        if marker != {"task_id": "D101", "data_root": str(self.root), "fixture_tables": list(TABLES)}:
            raise RuntimeError("Exact D101 fixture owner marker required")
        self.pid, self.identity = expected_pid, None
        self.verify()

    def verify(self):
        command = r'''
$ErrorActionPreference='Stop'
$listeners=@(Get-NetTCPConnection -LocalPort 19020,18832 -State Listen -ErrorAction Stop)
$records=@(foreach($port in @(19020,18832)) {
  $matches=@($listeners | Where-Object LocalPort -eq $port)
  if($matches.Count -ne 1) { throw 'Ambiguous private listener' }
  $listener=$matches[0]
  $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
  [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$proc.ProcessId;name=$proc.Name;command=$proc.CommandLine}
})
ConvertTo-Json -InputObject $records -Compress
'''
        completed = subprocess.run([str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"),
                                    "-NoProfile", "-NonInteractive", "-Command", command],
                                   capture_output=True, text=True, timeout=30)
        if completed.returncode:
            raise RuntimeError("Cannot attest dedicated D101 process")
        value = validate_listener_records(json.loads(completed.stdout), self.root, self.pid)
        if self.identity is not None and self.identity != value:
            raise RuntimeError("Private sender process changed")
        self.identity = value
        return value


def qwp(sql):
    audit.single_select(sql)
    with urlopen("http://127.0.0.1:19020/exec?" + urlencode({"query": sql, "limit": "0,50001"}), timeout=30) as response:
        payload = json.loads(response.read())
    if "error" in payload or "dataset" not in payload:
        raise RuntimeError("Private SELECT failed")
    return [dict(zip((column["name"] for column in payload["columns"]), row)) for row in payload["dataset"]]


def state(table):
    physical = qwp(f"SELECT * FROM tables() WHERE table_name='{table}'")
    wal = qwp(f"SELECT * FROM wal_tables() WHERE name='{table}'")
    if len(physical) != 1 or len(wal) != 1:
        raise RuntimeError("One physical table and WAL frontier required")
    p, w = physical[0], wal[0]
    if (p["table_suspended"] or p["wal_pending_row_count"] != 0 or w["suspended"]
            or w["bufferedTxnSize"] != 0 or w["writerTxn"] != w["sequencerTxn"]):
        raise RuntimeError("Private source WAL unsettled")
    return {"physical": p, "wal": w}


def settled(table):
    try:
        state(table)
        return True
    except RuntimeError:
        return False


def wait(table):
    deadline = time.monotonic() + 30
    while not settled(table):
        if time.monotonic() >= deadline:
            raise RuntimeError("Private WAL visibility deadline exceeded")
        time.sleep(0.1)


def records(connection, table, fields):
    with connection.cursor() as cursor:
        cursor.execute(f"SELECT {','.join(fields)} FROM {table} ORDER BY timestamp,ts_code LIMIT {CAP + 1}")
        rows = [dict(zip(fields, row)) for row in cursor.fetchall()]
    if len(rows) > CAP:
        raise RuntimeError("Private full source reached sentinel")
    return audit.canonical(rows)


def validate_rows(rows, fields, expected_sha=None):
    if len(rows) > CAP or any(tuple(row) != tuple(fields) for row in rows):
        raise RuntimeError("Complete fields and finite source required")
    strict_compare(rows, rows, fields, ("timestamp", "ts_code"))
    if expected_sha is not None and source_sha(rows) != expected_sha:
        raise RuntimeError("Actual formal source differs from frozen complete capture")
    return rows


def require_quiescent_java_ledger(path):
    path = Path(path).resolve(strict=True)
    if not path.is_relative_to((common.REPO_ROOT / "var").resolve()) or not path.is_file():
        raise RuntimeError("Increment requires the actual D101 Java ledger within workspace var")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.execute("PRAGMA query_only=ON")
        entries = connection.execute("SELECT kind,state FROM sync_entries").fetchall()
        runs = connection.execute("SELECT job_id FROM sync_runs").fetchall()
        if not runs or any(row[0] not in {"data.etf_market_overview_daily_cache", "write.etf_market_overview_daily_cache", "group.prepared_writes"} for row in runs):
            raise RuntimeError("Only the populated canonical D101 Java ledger is admissible")
        if any(state not in {"VERIFIED", "VERIFIED_EMPTY", "PARTIAL", "FAILED", "CANCELLED"} for _, state in entries):
            raise RuntimeError("Active or uncertain Java sender blocks source fixture changes")
        if connection.execute("SELECT count(*) FROM sync_interval_locks").fetchone()[0] != 0:
            raise RuntimeError("Retained Java lease blocks source fixture changes")
    return {"path": str(path), "runs": len(runs), "entries": len(entries), "active_or_unknown": 0, "leases": 0}



def require_verified_increment_runs(ledger, stages):
    with closing(sqlite3.connect(ledger.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.execute("PRAGMA query_only=ON")
        for stage in stages:
            run_id = stage["run"]["id"]
            run = connection.execute("SELECT state FROM sync_entries WHERE id=? AND kind='RUN' AND run_id=?",
                                     (run_id, run_id)).fetchone()
            slices = connection.execute("SELECT state FROM sync_entries WHERE kind='SLICE' AND run_id=?", (run_id,)).fetchall()
            if run != ("VERIFIED",) or len(slices) != 2 or any(row != ("VERIFIED",) for row in slices):
                raise RuntimeError("FIRST and complete new HIT must each be actual VERIFIED runs with two VERIFIED slices")


def require_increment_captures(helpers, preflight, initial):
    if initial.get("status") != "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE":
        raise RuntimeError("Verified original initial source fixture required")
    for table in audit.SPEC.sources:
        source = preflight["sources"][table]
        info = initial["source_captures"][table]
        path = helpers.file_path(info["path"])
        if path != DIRECTORY / f"real-source-{table}-initial-20261006.json":
            raise RuntimeError("Exact original complete initial source capture required")
        if helpers.digest(path) != source["capture_file_sha256"]:
            raise RuntimeError("Initial capture file changed after preflight")
        capture = helpers.load(path)
        fields = list(audit.SOURCE_MODELS[table].get_questdb_schema()["schema"])
        if (capture["table"] != table or capture["fields"] != fields or source["fields"] != fields
                or source["rows"] != INCREMENT_INITIAL_COUNTS[table] or info["rows"] != INCREMENT_INITIAL_COUNTS[table]
                or source["full_field_sha256"] != capture["sha256"] or capture["sha256"] != info["sha256"]):
            raise RuntimeError("Complete initial source capture contract differs")
        if len(validate_rows(capture["rows"], fields, capture["sha256"])) != INCREMENT_INITIAL_COUNTS[table]:
            raise RuntimeError("Complete original initial source rows differ")


def admit_increment(args):
    # Import only for increment: the SELECT-only helper imports this module, so eager import would cycle.
    import preflight_d101_increment_sources_readonly as helpers
    for field in ("java_ledger", "preflight", "preflight_sha256", "admission", "admission_sha256"):
        if not getattr(args, field, None):
            raise RuntimeError("Increment requires preflight and explicit coordinator admission paths and SHAs")
    if any(not isinstance(value, str) or len(value) != 64 or any(ch not in "0123456789abcdef" for ch in value)
           for value in (args.preflight_sha256, args.admission_sha256)):
        raise RuntimeError("Exact lowercase SHA256 preflight and admission bindings required")
    preflight = helpers.load(args.preflight, args.preflight_sha256)
    admission = helpers.load(args.admission, args.admission_sha256)
    actual = args.java_ledger.resolve(strict=True)
    expected_private = {"pid": 23388, "data_root": str(ROOT.resolve()), "host": "127.0.0.1", "http_port": 19020, "pg_port": 18832}
    if (type(args.expected_pid) is not int or args.expected_pid != 23388
            or admission.get("task_id") != "D101" or admission.get("protocol_version") != 1
            or type(admission.get("protocol_version")) is not int
            or admission.get("decision") != "accepted_for_isolated_source_increment"
            or admission.get("private_target") != expected_private or preflight.get("private_target") != expected_private
            or admission.get("increment") != INCREMENT_POLICY
            or admission.get("protected_tables") != [audit.CACHE, audit.COVERAGE]
            or admission.get("script_sha256") != helpers.digest(Path(__file__))):
        raise RuntimeError("Explicit coordinator admission does not freeze this exact private source increment")
    if (preflight.get("task_id") != "D101" or preflight.get("status") != "VERIFIED_READONLY_INCREMENT_PREFLIGHT"
            or any(type(preflight.get(field)) is not int or preflight[field] != 0 for field in ("database_writes", "formal_operations"))
            or preflight.get("owner_invoked") is not False or preflight.get("stable_five_table_frontier") is not True
            or type(preflight.get("source_field_comparisons")) is not int or preflight["source_field_comparisons"] != 136072
            or preflight.get("tables_before") != preflight.get("tables_after")
            or preflight.get("raw_tables_before") != preflight.get("raw_tables_after")
            or preflight.get("ledgers_before") != preflight.get("ledgers_after")):
        raise RuntimeError("Successful complete stable SELECT-only preflight required")
    if (helpers.file_path(admission["java_ledger_path"]) != actual or helpers.file_path(preflight["ledger_path"]) != actual
            or admission["target_id"] != preflight["target_id"] or admission["sources_fingerprint"] != preflight["sources_fingerprint"]
            or helpers.file_path(admission["preflight"]["path"]) != args.preflight.resolve()
            or admission["preflight"]["sha256"] != args.preflight_sha256):
        raise RuntimeError("Coordinator admission binds another actual preflight, ledger, source or target")
    expected_inputs = {helpers.file_path(item["path"]): item["sha256"] for item in preflight["input_evidence"]}
    paths = []
    for field in ("first", "hit", "initial_source_fixture"):
        item = admission[field]
        path = helpers.file_path(item["path"])
        if expected_inputs.get(path) != item["sha256"] or helpers.digest(path) != item["sha256"]:
            raise RuntimeError("FIRST, complete HIT or source fixture evidence SHA differs")
        paths.append(path)
    if len(expected_inputs) != 3 or paths[2] != DIRECTORY / "source-fixture-initial-20261006.json":
        raise RuntimeError("Exactly FIRST, complete new HIT and original initial fixture inputs required")
    first, hit = helpers.stage_inputs(paths[0], paths[1], actual)
    if first["run"]["id"] == hit["run"]["id"] or hit["target_id"] != admission["target_id"]:
        raise RuntimeError("A distinct complete new HIT must bind the same actual target")
    require_verified_increment_runs(actual, (first, hit))
    initial = helpers.load(paths[2]); require_increment_captures(helpers, preflight, initial)
    proofs, pids = [], set()
    for stage in (first, hit):
        evidence, native = helpers.producer_proofs(stage, actual, admission["sources_fingerprint"], admission["target_id"])
        proofs.extend(evidence); pids.update(native)
    if proofs != preflight["producer_evidence"]:
        raise RuntimeError("Actual FIRST/HIT original producer proofs changed after preflight")
    for table in (audit.CACHE, audit.COVERAGE):
        if preflight["tables_after"][table]["actualRows"] != 2:
            raise RuntimeError("Both publisher targets must remain the original two rows")
    return {"helpers": helpers, "actual": actual, "preflight": preflight, "admission": admission,
            "pids": pids, "input_paths": [args.preflight.resolve(), args.admission.resolve(), *paths],
            "input_shas": [args.preflight_sha256, args.admission_sha256, *[admission[k]["sha256"] for k in ("first", "hit", "initial_source_fixture")]]}


def increment_boundary(context, target, initial=False):
    helpers = context["helpers"]
    for path, expected in zip(context["input_paths"], context["input_shas"]):
        if helpers.digest(path) != expected:
            raise RuntimeError("Frozen increment admission evidence changed")
    target.verify()
    ledgers = helpers.ledger_inventory(context["actual"])
    if ledgers != context["preflight"]["ledgers_after"]:
        raise RuntimeError("Any D101 ledger inventory or terminal frontier changed")
    native = helpers.require_producers_absent(context["pids"])
    observed, raw = helpers.observed_tables()
    if initial and (observed != context["preflight"]["tables_after"] or raw != context["preflight"]["raw_tables_after"]):
        raise RuntimeError("Current five-table source/target frontier drifted after preflight")
    for table in (audit.CACHE, audit.COVERAGE):
        if observed[table] != context["preflight"]["tables_after"][table] or raw[table] != context["preflight"]["raw_tables_after"][table] or observed[table]["actualRows"] != 2:
            raise RuntimeError("Protected two-row cache/coverage changed before source-only increment")
    target.verify()
    return {"checked_at": datetime.now(timezone.utc).isoformat(), "ledgers": ledgers, "native_processes": native,
            "protected_targets": {table: raw[table] for table in (audit.CACHE, audit.COVERAGE)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("initial", "increment"), required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-ledger", type=Path)
    parser.add_argument("--preflight", type=Path)
    parser.add_argument("--preflight-sha256")
    parser.add_argument("--admission", type=Path)
    parser.add_argument("--admission-sha256")
    args = parser.parse_args()
    if args.stage == "increment" and not all((args.java_ledger, args.preflight, args.preflight_sha256, args.admission, args.admission_sha256)):
        parser.error("Increment requires explicit preflight and coordinator admission paths and SHAs")
    if not args.execute_isolated:
        parser.error("Explicit bounded isolated fixture execution required")
    output = args.output.resolve()
    if not output.is_relative_to(DIRECTORY.resolve()):
        parser.error("Output must stay within D101 command evidence")
    result = {"task_id": "D101", "purpose": "real source fixture only", "stage": args.stage,
              "status": "IN_PROGRESS", "checked_at": datetime.now(timezone.utc).isoformat(),
              "formal_mutated": False, "java_publisher_verified": False, "operations": [], "automatic_retry": False}
    save_new(output, result)
    target, formal, private = None, None, None
    try:
        increment = admit_increment(args) if args.stage == "increment" else None
        if increment is not None:
            result["increment_admission"] = {"path": str(args.admission.resolve()), "sha256": args.admission_sha256,
                                             "preflight_path": str(args.preflight.resolve()), "preflight_sha256": args.preflight_sha256,
                                             "bound_ledger": str(increment["actual"]), "policy": INCREMENT_POLICY}
        gate = json.loads((common.REPO_ROOT / "artifacts/java-migration/D100/coordinator-review-20261006.json").read_text(encoding="utf-8"))
        if gate.get("decision") != "accepted_for_serial_progress":
            raise RuntimeError("D100 coordinator prerequisite required")
        frozen = json.loads(FORMAL_AUDIT.read_text(encoding="utf-8"))
        plan = frozen["bounded_actual_source_plan"]
        if plan["status"] != "BOUNDED_REAL_SOURCE_READY" or plan["days"] != list(DAYS):
            raise RuntimeError("Exact bounded real source plan required")
        before = audit.snapshots()
        if before != frozen["tables_after"]:
            raise RuntimeError("Formal physical/WAL snapshot changed since source audit")
        formal = audit.ReadOnlyClient()
        captured = {}
        for table in audit.SPEC.sources:
            info = plan["sources"][table]
            fields = info["fields"]
            where = "" if table in audit.SPEC.timeless_sources else " WHERE " + " OR ".join(f"timestamp='{day}'" for day in DAYS)
            rows = formal.records(f"SELECT {','.join(fields)} FROM {table}{where} ORDER BY timestamp,ts_code LIMIT {CAP+1}")
            validate_rows(rows, fields, info["full_field_capture_sha256"])
            if len(rows) != info["full_field_selected_count"]:
                raise RuntimeError("Formal full count differs")
            captured[table] = rows
        if sum(map(len, captured.values())) != 11667 or audit.snapshots() != before:
            raise RuntimeError("Complete real source budget or physical identity differs")
        result["formal_before"] = before
        target = PrivateTarget(ROOT, args.expected_pid)
        result["target"] = target.identity
        if args.stage == "increment":
            if args.java_ledger is None:
                raise RuntimeError("Increment requires explicit quiescent Java owner ledger")
            result["java_ledger_quiescence"] = require_quiescent_java_ledger(args.java_ledger)
            result["increment_initial_boundary"] = increment_boundary(increment, target, initial=True)
            planned_increment = {table: [row for row in captured[table] if row["timestamp"][:10] == "2026-09-21"]
                                 for table in INCREMENT_POLICY["tables"]}
            if (sum(map(len, planned_increment.values())) != 2903
                    or sum((len(rows) + 499) // 500 for rows in planned_increment.values()) != 7):
                raise RuntimeError("Exact real 9/21 source-only increment must contain 2903 rows in seven batches")
        present = {row["table_name"] for row in qwp("SELECT table_name FROM tables()")}
        if args.stage == "initial" and present or args.stage == "increment" and present != set(TABLES):
            raise RuntimeError("Initial must be fresh; increment requires exact five existing tables")
        private = psycopg2.connect(host="127.0.0.1", port=18832, user="admin", password="quest", dbname="qdb", connect_timeout=10)
        private.autocommit = True
        for table in TABLES:
            schema = frozen["schemas"][table]
            types = schema["physical_types"]
            if args.stage == "initial":
                sql = f"CREATE TABLE {table} (" + ",".join(f"{field} {kind}" for field, kind in types.items()) + ")"
                sql += f" TIMESTAMP({schema['designated_timestamp']}) PARTITION BY {schema['partition']} WAL DEDUP UPSERT KEYS(" + ",".join(schema["physical_upsert_keys"]) + ")"
                op = {"kind": "DDL", "table": table, "sql": sql, "ack": "UNKNOWN"}
                result["operations"].append(op)
                save_progress(output, result)
                target.verify()
                with private.cursor() as cursor:
                    cursor.execute(sql)
                op["ack"] = "ACKNOWLEDGED"
                save_progress(output, result)
            actual = qwp(f"SELECT * FROM table_columns('{table}')")
            if ({row["column"]: row["type"] for row in actual} != types
                    or tuple(row["column"] for row in actual) != tuple(types)
                    or [row["column"] for row in actual if row["designated"]] != [schema["designated_timestamp"]]
                    or {row["column"] for row in actual if row["upsertKey"]} != set(schema["physical_upsert_keys"])):
                raise RuntimeError("Private full schema/key differs")
            wait(table)
            observed = state(table)["physical"]
            if (observed["partitionBy"] != schema["partition"] or not observed["walEnabled"]
                    or not observed["dedup"] or observed["designatedTimestamp"] != schema["designated_timestamp"]):
                raise RuntimeError("Private partition/WAL/designated timestamp differs")
        target_before = {table: state(table) for table in (audit.CACHE, audit.COVERAGE)}
        if args.stage == "initial" and any(qwp(f"SELECT count() AS n FROM {table}")[0]["n"] != 0 for table in target_before):
            raise RuntimeError("New publisher targets must actually be empty")
        result["publisher_targets_before"] = target_before
        result["source_captures"] = {}
        for table in audit.SPEC.sources:
            fields = plan["sources"][table]["fields"]
            all_rows = captured[table]
            expected_before = [] if args.stage == "initial" else all_rows if table in audit.SPEC.timeless_sources else [row for row in all_rows if row["timestamp"][:10] in DAYS[:2]]
            strict_compare(records(private, table, fields), expected_before, fields, ("timestamp", "ts_code"))
            rows = all_rows if table in audit.SPEC.timeless_sources and args.stage == "initial" else [] if table in audit.SPEC.timeless_sources else [row for row in all_rows if row["timestamp"][:10] in (DAYS[:2] if args.stage == "initial" else DAYS[2:])]
            capture_path = DIRECTORY / f"real-source-{table}-{args.stage}-20261006.json"
            save_new(capture_path, {"table": table, "fields": fields, "rows": rows, "sha256": source_sha(rows), "formal_identity": before[table]})
            result["source_captures"][table] = {"path": str(capture_path), "rows": len(rows), "sha256": source_sha(rows)}
            for offset in range(0, len(rows), 500):
                batch = rows[offset:offset+500]
                boundary = increment_boundary(increment, target) if args.stage == "increment" else None
                op = {"kind": "SOURCE_INSERT", "table": table, "offset": offset, "rows": len(batch), "ack": "UNKNOWN"}
                if boundary is not None:
                    op["increment_boundary"] = boundary
                result["operations"].append(op)
                save_progress(output, result)
                target.verify()
                if args.stage == "increment":
                    require_quiescent_java_ledger(args.java_ledger)
                with private.cursor() as cursor:
                    execute_values(cursor, f"INSERT INTO {table} ({','.join(fields)}) VALUES %s", [tuple(row[field] for field in fields) for row in batch], page_size=500)
                op["ack"] = "ACKNOWLEDGED"
                save_progress(output, result)
            wait(table)
            strict_compare(records(private, table, fields), expected_before + rows, fields, ("timestamp", "ts_code"))
        result["formal_after"] = audit.snapshots()
        if result["formal_after"] != before:
            raise RuntimeError("Formal source/target changed during private fixture")
        result["private_after"] = {table: state(table) for table in TABLES}
        if any(result["private_after"][table] != value for table, value in target_before.items()):
            raise RuntimeError("Cache/receipt publisher target changed during source-only fixture")
        target.verify()
        if increment is not None:
            result["increment_final_boundary"] = increment_boundary(increment, target)
        result["status"] = "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE"
    except Exception as exc:
        result["status"], result["error"] = "FAILED", str(exc)
    finally:
        if formal is not None:
            formal.close()
        if private is not None:
            private.close()
        save_progress(output, result)
    print(json.dumps({"status": result["status"], "output": str(output), "error": result.get("error")}))
    return 0 if result["status"].startswith("VERIFIED") else 1


if __name__ == "__main__":
    raise SystemExit(main())
