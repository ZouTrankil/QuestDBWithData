"""Repeatable D095 acceptance on a locally attested private QuestDB.

Formal access is SELECT-only. DDL, replay, UPDATE and FULL refresh use a Windows
listener whose process command line names a data root inside this repo's var/.
"""
import argparse
import base64
import ctypes
import copy
import json
import math
import os
import subprocess
import sys
import time
from datetime import date
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import psycopg2
from psycopg2.extras import execute_values

SOURCE_ROOT = Path("D:/work/fund_2/back-monitor/src")
sys.path.insert(0, str(SOURCE_ROOT))
from quant_platform.data.adapters.questdb.market_barometer_views import VIEW_SELECTS

REPO_ROOT = Path(__file__).resolve().parents[1]
START, STOP = "2026-09-17", "2026-09-19"
OUTPUT = REPO_ROOT / "artifacts/java-migration/D095/commands/isolated-acceptance-20260930.json"
MV = "mv_market_breadth_daily_v1"
ROW_CAP = 100000
PRIVATE_TARGET = None


def split_windows_command_line(command):
    if os.name != "nt":
        raise RuntimeError("Private target attestation currently requires Windows")
    argc = ctypes.c_int()
    split = ctypes.windll.shell32.CommandLineToArgvW
    split.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
    split.restype = ctypes.POINTER(ctypes.c_wchar_p)
    argv = split(command, ctypes.byref(argc))
    if not argv:
        raise RuntimeError("Cannot inspect QuestDB process arguments")
    try:
        return [argv[index] for index in range(argc.value)]
    finally:
        free = ctypes.windll.kernel32.LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(ctypes.cast(argv, ctypes.c_void_p))


def validate_listener_records(records, root):
    if not isinstance(records, list) or len(records) != 2:
        raise RuntimeError("Exactly one loopback listener per private QWP/PG port is required")
    if {record["port"] for record in records} != {19000, 18812}:
        raise RuntimeError("The private listener ports differ from D095 configuration")
    if len({record["pid"] for record in records}) != 1:
        raise RuntimeError("Private QWP and PGwire must belong to the same QuestDB process")
    for record in records:
        if record["address"] != "127.0.0.1":
            raise RuntimeError("Private listener must bind only to IPv4 loopback")
        if record["name"].lower() not in ("questdb.exe", "java.exe"):
            raise RuntimeError("Private listener is not the expected QuestDB executable")
        argv = split_windows_command_line(record["command"])
        positions = [index for index, arg in enumerate(argv) if arg == "-d"]
        if len(positions) != 1 or positions[0] + 1 >= len(argv):
            raise RuntimeError("QuestDB process must explicitly name its private -d data root")
        observed_root = Path(argv[positions[0] + 1]).resolve()
        if observed_root != root:
            raise RuntimeError("Private listener belongs to a different data root")
    return {"pid": records[0]["pid"], "data_root": str(root),
            "http_port": 19000, "pg_port": 18812, "address": "127.0.0.1"}


class PrivateTarget:
    def __init__(self, root):
        self.root = root.resolve(strict=True)
        if not self.root.is_relative_to((REPO_ROOT / "var").resolve()):
            raise RuntimeError("Private QuestDB data root must be within this checkout's var/")
        config = {}
        for line in (self.root / "conf/server.conf").read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
        if (config.get("http.net.bind.to") != "127.0.0.1:19000" or
                config.get("pg.net.bind.to") != "127.0.0.1:18812"):
            raise RuntimeError("Private data root config does not declare the expected listeners")
        self.identity = None
        self.verify()

    def verify(self):
        command = r'''
$ErrorActionPreference = 'Stop'
$listeners = @(Get-NetTCPConnection -LocalPort 19000,18812 -State Listen -ErrorAction Stop)
$records = @(foreach ($port in @(19000,18812)) {
    $matches = @($listeners | Where-Object LocalPort -eq $port)
    if ($matches.Count -ne 1) { throw 'Ambiguous or missing private listener' }
    $listener = $matches[0]
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    [pscustomobject]@{port=$port; address=$listener.LocalAddress; pid=$proc.ProcessId;
                     name=$proc.Name; command=$proc.CommandLine}
})
ConvertTo-Json -InputObject $records -Compress
'''
        completed = subprocess.run(
            [str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"),
             "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True, text=True, timeout=30,
        )
        if completed.returncode:
            raise RuntimeError("Cannot attest the private QuestDB listeners")
        observed = validate_listener_records(json.loads(completed.stdout), self.root)
        if self.identity is not None and observed != self.identity:
            raise RuntimeError("Private QuestDB process changed during this acceptance run")
        self.identity = observed
        return observed

    def claim_fixture(self, existing):
        marker = self.root / "d095-fixture.json"
        expected = {"task_id": "D095", "data_root": str(self.root),
                    "fixture_tables": ["stk_factor", MV]}
        if marker.exists():
            if json.loads(marker.read_text(encoding="utf-8")) != expected:
                raise RuntimeError("Private data root has a conflicting fixture owner")
        else:
            if any(row["table_name"] not in expected["fixture_tables"] for row in existing):
                raise RuntimeError("Unclaimed data root contains unrelated tables; use a new private root")
            marker.write_text(json.dumps(expected, indent=2), encoding="utf-8")


def qwp(sql, *, isolated=False):
    port = 19000 if isolated else 9000
    host = "127.0.0.1"
    headers = {}
    is_select = bool(sql.split()) and sql.split(None, 1)[0].upper() == "SELECT"
    if isolated:
        if PRIVATE_TARGET is None:
            raise RuntimeError("Attest the private target before any isolated access")
        if not is_select:
            PRIVATE_TARGET.verify()
    else:
        if os.environ.get("APP_QUESTDB_HOST") != host:
            raise RuntimeError("Formal read is pinned to the configured local QuestDB")
        if not is_select:
            raise RuntimeError("Formal source accepts SELECT statements only")
        token = base64.b64encode((os.environ["APP_QUESTDB_USERNAME"] + ":" +
                                  os.environ["APP_QUESTDB_PASSWORD"]).encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    url = f"http://{host}:{port}/exec?" + urlencode({"query": sql, "limit": f"0,{ROW_CAP}"})
    with urlopen(Request(url, headers=headers), timeout=90) as response:
        result = json.load(response)
    if "error" in result:
        raise RuntimeError(result["error"])
    names = [column["name"] for column in result.get("columns", [])]
    rows = [dict(zip(names, row)) for row in result.get("dataset", [])]
    if len(rows) >= ROW_CAP:
        raise RuntimeError("Explicit result cap reached; the source cannot be certified complete")
    return rows


def wait_until(predicate, description, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.4)
    raise TimeoutError(description)


def source_sql():
    return VIEW_SELECTS["v_market_breadth_daily"].format(
        where=f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}'"
    ) + " ORDER BY trade_date"


def keyed(rows, fields):
    result = {}
    for row in rows:
        key = tuple(row[field] for field in fields)
        if key in result:
            raise RuntimeError(f"Duplicate complete key: {key}")
        result[key] = row
    return result


def compare_fields(actual, expected, key_fields, value_fields):
    actual_by_key, expected_by_key = keyed(actual, key_fields), keyed(expected, key_fields)
    if actual_by_key.keys() != expected_by_key.keys():
        raise RuntimeError("Isolated data has missing or additional complete keys")
    for key, row in expected_by_key.items():
        for field in value_fields:
            a, e = actual_by_key[key][field], row[field]
            equal = (a is None and e is None) if a is None or e is None else (
                math.isclose(a, e, rel_tol=1e-10, abs_tol=1e-8)
                if isinstance(a, float) or isinstance(e, float) else a == e)
            if not equal:
                raise RuntimeError(f"Field mismatch at {key}, {field}: {a} != {e}")
    return len(expected) * len(value_fields)


def compare(actual, expected):
    return compare_fields(actual, expected, ("trade_date",), (
        "stock_count", "up_count", "down_count", "flat_count", "avg_pct_change", "total_amount_yi",
    ))


def table_snapshot(table, *, isolated=False):
    rows = qwp(f"SELECT * FROM tables() WHERE table_name='{table}'", isolated=isolated)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one table required: {table}")
    return rows[0]


def table_version(row):
    return tuple((field, row.get(field)) for field in (
        "id", "table_txn", "table_row_count", "table_min_timestamp", "table_max_timestamp",
    ))


def wal_caught_up():
    rows = qwp("SELECT sequencerTxn,writerTxn,suspended FROM wal_tables() WHERE name='stk_factor'",
               isolated=True)
    if len(rows) != 1 or rows[0]["suspended"]:
        raise RuntimeError("Private stk_factor WAL is unavailable or suspended")
    return rows[0] if rows[0]["sequencerTxn"] == rows[0]["writerTxn"] else None


def replay(values):
    PRIVATE_TARGET.verify()
    with psycopg2.connect(host="127.0.0.1", port=18812, user="admin", password="quest",
                          dbname="qdb", connect_timeout=10) as conn:
        with conn.cursor() as cursor:
            for offset in range(0, len(values), 500):
                execute_values(cursor, "INSERT INTO stk_factor (trade_date,ts_code,pct_change,amount) "
                               "VALUES %s", values[offset:offset + 500], page_size=500)
    wait_until(wal_caught_up, "isolated source WAL visibility")


def main():
    global PRIVATE_TARGET, START, STOP
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-root", type=Path,
                        default=REPO_ROOT / "var/d095-isolated-questdb")
    parser.add_argument("--start", default=START)
    parser.add_argument("--stop", default=STOP)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    START, STOP = date.fromisoformat(args.start).isoformat(), date.fromisoformat(args.stop).isoformat()
    if START >= STOP:
        raise RuntimeError("Acceptance requires a nonempty date range")
    PRIVATE_TARGET = PrivateTarget(args.private_root)
    output = {"target_id": "local-isolated-127.0.0.1:19000", "range": [START, STOP],
              "formal_mutated": False, "private_target_attestation": PRIVATE_TARGET.identity}
    source_before = table_snapshot("stk_factor")
    source_select = ("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor "
                     f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}' ORDER BY trade_date,ts_code")
    source_count = qwp("SELECT count() AS n FROM stk_factor "
                       f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}'")[0]["n"]
    source_rows = qwp(source_select)
    keys = keyed(source_rows, ("trade_date", "ts_code"))
    formal_expected = qwp(source_sql())
    source_after = table_snapshot("stk_factor")
    if table_version(source_before) != table_version(source_after):
        raise RuntimeError("Formal source changed during the bounded source extraction; retry SELECTs")
    if not source_rows or len(source_rows) != source_count:
        raise RuntimeError("Source extraction is empty or differs from the full bounded COUNT")
    output.update(source_rows=len(source_rows), source_key_count=len(keys),
                  source_complete_count=source_count, source_before=source_before,
                  source_after=source_after, formal_daily_aggregate=formal_expected)

    existing = qwp("SELECT table_name FROM tables()", isolated=True)
    PRIVATE_TARGET.claim_fixture(existing)
    names = {row["table_name"] for row in existing}
    ddl_sql = VIEW_SELECTS["v_market_breadth_daily"].format(where="")
    if MV in names:
        definitions = qwp(f"SELECT view_sql FROM materialized_views() WHERE view_name='{MV}'", isolated=True)
        if len(definitions) != 1 or definitions[0]["view_sql"].strip() != ddl_sql.strip():
            raise RuntimeError("Existing private MV differs from the Python owner definition")
    if "stk_factor" in names:
        columns = qwp("SELECT \"column\",\"type\",designated,upsertKey "
                      "FROM table_columns('stk_factor')", isolated=True)
        expected_columns = [
            {"column": "trade_date", "type": "TIMESTAMP", "designated": True, "upsertKey": True},
            {"column": "ts_code", "type": "SYMBOL", "designated": False, "upsertKey": True},
            {"column": "pct_change", "type": "DOUBLE", "designated": False, "upsertKey": False},
            {"column": "amount", "type": "DOUBLE", "designated": False, "upsertKey": False},
        ]
        if columns != expected_columns:
            raise RuntimeError("Existing private source schema differs from the D095 fixture")
        qwp("TRUNCATE TABLE stk_factor", isolated=True)
    else:
        qwp("CREATE TABLE stk_factor (trade_date TIMESTAMP, ts_code SYMBOL, pct_change DOUBLE, "
            "amount DOUBLE) TIMESTAMP(trade_date) PARTITION BY MONTH WAL", isolated=True)
        qwp("ALTER TABLE stk_factor DEDUP ENABLE UPSERT KEYS(trade_date,ts_code)", isolated=True)
    values = [(row["trade_date"].replace("Z", ""), row["ts_code"], row["pct_change"], row["amount"])
              for row in source_rows]
    replay(values)
    imported = qwp("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor ORDER BY trade_date,ts_code",
                   isolated=True)
    output["source_field_comparisons"] = compare_fields(
        imported, source_rows, ("trade_date", "ts_code"), ("trade_date", "ts_code", "pct_change", "amount"))
    output["isolated_source_rows"] = len(imported)
    replay(values)
    replayed = qwp("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor ORDER BY trade_date,ts_code",
                  isolated=True)
    output["replay_field_comparisons"] = compare_fields(
        replayed, source_rows, ("trade_date", "ts_code"), ("trade_date", "ts_code", "pct_change", "amount"))
    output["idempotent_replay_rows"] = len(replayed)

    qwp(f"REFRESH MATERIALIZED VIEW {MV} FULL" if MV in names else
        f"CREATE MATERIALIZED VIEW {MV} REFRESH EVERY 1m AS ({ddl_sql}) PARTITION BY MONTH",
        isolated=True)

    def status():
        rows = qwp(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'", isolated=True)
        if len(rows) != 1:
            raise RuntimeError("Exactly one private MV required")
        return rows[0]

    def ready():
        observed = status()
        return observed if (observed["view_status"] == "valid" and
                            observed["refresh_base_table_txn"] == observed["base_table_txn"]) else None

    wait_until(ready, "initial materialized view refresh", 120)
    mv_sql = ("SELECT trade_date,stock_count,up_count,down_count,flat_count,avg_pct_change,"
              f"total_amount_yi FROM {MV} ORDER BY trade_date")
    initial_mv = qwp(mv_sql, isolated=True)
    output["initial_value_comparisons"] = compare(initial_mv, formal_expected)
    output["initial_status"] = status()
    output["initial_mv_rows"] = initial_mv

    # The fixture mutation is repeatable: every run first resets/replays the private source.
    first = next((row for row in source_rows if row["amount"] is not None and math.isfinite(row["amount"])), None)
    if first is None:
        raise RuntimeError("No real non-null amount is available to verify UPDATE invalidation")
    timestamp, code = first["trade_date"], first["ts_code"].replace("'", "''")
    qwp("UPDATE stk_factor SET amount = amount + 1 "
        f"WHERE trade_date = '{timestamp}' AND ts_code = '{code}'", isolated=True)
    output["isolated_update_key"] = {"trade_date": timestamp, "ts_code": first["ts_code"]}

    def invalid():
        observed = status()
        return observed if observed["view_status"] == "invalid" else None

    output["invalid_status"] = wait_until(invalid, "UPDATE invalidates materialized view", 90)
    modified_expected = copy.deepcopy(source_rows)
    modified_key = (first["trade_date"], first["ts_code"])
    for row in modified_expected:
        if (row["trade_date"], row["ts_code"]) == modified_key:
            row["amount"] += 1
    modified_source = qwp("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor ORDER BY trade_date,ts_code",
                          isolated=True)
    output["modified_source_field_comparisons"] = compare_fields(
        modified_source, modified_expected, ("trade_date", "ts_code"),
        ("trade_date", "ts_code", "pct_change", "amount"))
    qwp(f"REFRESH MATERIALIZED VIEW {MV} FULL", isolated=True)
    output["restored_status"] = wait_until(ready, "FULL restores isolated materialized view", 120)
    isolated_direct = qwp(source_sql(), isolated=True)
    restored_mv = qwp(mv_sql, isolated=True)
    output["restored_value_comparisons"] = compare(restored_mv, isolated_direct)
    output["restored_mv_rows"] = restored_mv
    # Leave the two-day fixture equal to its real formal source for Java acceptance.
    qwp(f"UPDATE stk_factor SET amount = {first['amount']!r} "
        f"WHERE trade_date = '{timestamp}' AND ts_code = '{code}'", isolated=True)
    wait_until(invalid, "restore source amount invalidates private MV", 90)
    qwp(f"REFRESH MATERIALIZED VIEW {MV} FULL", isolated=True)
    output["final_status"] = wait_until(ready, "restore original private fixture", 120)
    final_source = qwp("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor ORDER BY trade_date,ts_code",
                       isolated=True)
    output["final_source_field_comparisons"] = compare_fields(
        final_source, source_rows, ("trade_date", "ts_code"),
        ("trade_date", "ts_code", "pct_change", "amount"))
    output["final_mv_rows"] = qwp(mv_sql, isolated=True)
    output["final_value_comparisons"] = compare(output["final_mv_rows"], formal_expected)
    output["isolated_table_metadata"] = qwp(
        "SELECT table_name,table_row_count,partitionBy,walEnabled,dedup,table_txn "
        f"FROM tables() WHERE table_name IN ('stk_factor','{MV}')", isolated=True)
    output["private_target_attestation_after"] = PRIVATE_TARGET.verify()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: output[key] for key in (
        "source_rows", "source_field_comparisons", "replay_field_comparisons",
        "initial_value_comparisons", "restored_value_comparisons", "initial_status",
        "invalid_status", "restored_status",
    )}, ensure_ascii=False))


if __name__ == "__main__":
    main()
