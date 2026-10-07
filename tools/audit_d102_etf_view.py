"""D102 SELECT audit and explicit missing-only ordinary view installation.

The only admitted mutation is the original ETF CREATE VIEW on the accepted
D101 private fixture. No source, cache, coverage, refresh, or publisher write.
"""
from __future__ import annotations

import argparse
from contextlib import closing, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
import re
from pathlib import Path
import select as socket_select
import struct
import subprocess
import time
from urllib.parse import urlparse
from urllib.request import url2pathname

import psycopg2

import audit_d101_etf_cache_readonly as audit
import prepare_d101_etf_fixture as fixture

REPO = fixture.common.REPO_ROOT
DIRECTORY = REPO / "artifacts/java-migration/D102/commands"
D101 = fixture.DIRECTORY
ROOT, PID = fixture.ROOT, 23388
VIEW = "v_etf_market_overview_daily"
FIELDS = ("trade_date", "etf_count", "total_share", "total_size_yi")
TYPES = dict(zip(FIELDS, ("TIMESTAMP", "LONG", "DOUBLE", "DOUBLE")))
START, STOP = "2026-09-17", "2026-09-22"
DAYS = fixture.DAYS
CAP = 50000
PG_DEADLINE_SECONDS = 20
OWNER_TEMPLATE = fixture.common.VIEW_SELECTS[VIEW]
OWNER_SELECT = OWNER_TEMPLATE.format(where="")
DDL = f"CREATE VIEW {VIEW} AS ({OWNER_SELECT})"
GATE = REPO / "artifacts/java-migration/D101/coordinator-review-20261006.json"
GATE_SHA = "2201c05e5e05c4b056a5f3170be86ed86c230fb08e1d7dcf42f93c7228fccaef"
TYPED = D101 / "java-readthrough-typed-write-20261006.json"
DDL_CLAIM = DIRECTORY / "v_etf_market_overview_daily-create-once.json"
TABLES = fixture.TABLES
MODELS = {**audit.SOURCE_MODELS, audit.CACHE: audit.SPEC.model,
          audit.COVERAGE: audit.MarketBarometerCacheCoverage}


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def text_sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def evidence_path(value):
    if str(value).startswith("file:"):
        parsed = urlparse(str(value))
        require(parsed.netloc in ("", "localhost"), "Remote evidence is forbidden")
        value = url2pathname(parsed.path)
    path = Path(value)
    if not path.is_absolute():
        path = REPO / path
    path = path.resolve(strict=True)
    require(path.is_relative_to(REPO.resolve()), "Evidence must stay within the workspace")
    return path


def load(path, expected_sha=None):
    path = evidence_path(path)
    require(path.stat().st_size <= 64 * 1024 * 1024, "Evidence exceeds its finite size cap")
    if expected_sha is not None:
        require(digest(path) == expected_sha, "Immutable evidence SHA differs")
    def unique(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON evidence field")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def save_new(path, result):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def save_progress(path, result):
    fixture.save_progress(path, result)


def check_cancel(cancel_file=None):
    require(cancel_file is None or not Path(cancel_file).exists(), "D102 audit was cancelled")


def select(sql):
    audit.single_select(sql)


def query(sql, isolated, cancel_file=None):
    check_cancel(cancel_file)
    select(sql)
    return fixture.qwp(sql) if isolated else audit.query(sql)


def one(sql, isolated, cancel_file=None):
    rows = query(sql, isolated, cancel_file)
    require(len(rows) == 1, "Exactly one metadata/count row required")
    return rows[0]


def validate_private_target(root, pid):
    require(type(pid) is int and pid == PID and Path(root).resolve() == ROOT.resolve(),
            "Exact existing D101 private root and PID required")


def wait_pg(connection, deadline):
    # Installed psycopg2.extras.wait_select pattern, with finite socket waits.
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise psycopg2.OperationalError("D102 PG execution/read deadline exceeded")
        state = connection.poll()
        if state == psycopg2.extensions.POLL_OK:
            return
        if state == psycopg2.extensions.POLL_READ:
            ready = socket_select.select([connection.fileno()], [], [], remaining)
        elif state == psycopg2.extensions.POLL_WRITE:
            ready = socket_select.select([], [connection.fileno()], [], remaining)
        else:
            raise psycopg2.OperationalError("D102 PG unexpected polling state")
        if not any(ready):
            raise psycopg2.OperationalError("D102 PG execution/read deadline exceeded")


@contextmanager
def pg_deadline(connection):
    deadline = time.monotonic() + PG_DEADLINE_SECONDS
    previous = psycopg2.extensions.get_wait_callback()
    psycopg2.extensions.set_wait_callback(lambda value: wait_pg(value, deadline))
    try:
        yield
        if time.monotonic() >= deadline:
            raise psycopg2.OperationalError("D102 PG execution/read deadline exceeded")
    except BaseException:
        connection.close()  # Read failure is not empty; CREATE failure remains UNKNOWN.
        raise
    finally:
        psycopg2.extensions.set_wait_callback(previous)


class Reader:
    """PGWire preserves source and result binary64; every read is one SELECT."""
    def __init__(self, isolated, target=None, cancel_file=None):
        self.isolated, self.target, self.cancel_file = isolated, target, cancel_file
        check_cancel(cancel_file)
        if isolated:
            require(target is not None, "Private reader requires native attestation")
            validate_private_target(target.root, target.pid)
            target.verify()
            kwargs = dict(host="127.0.0.1", port=18832, user="admin", password="quest")
        else:
            require(os.environ.get("APP_QUESTDB_HOST") == "127.0.0.1", "Formal SELECT is pinned to localhost")
            kwargs = dict(host="127.0.0.1", port=8812, user=os.environ["APP_QUESTDB_USERNAME"],
                          password=os.environ["APP_QUESTDB_PASSWORD"])
        self.connection = psycopg2.connect(**kwargs, dbname="qdb", connect_timeout=10)
        self.connection.autocommit = True

    def records(self, sql, params=None, cap=CAP):
        check_cancel(self.cancel_file)
        select(sql)
        require(type(cap) is int and 1 <= cap <= CAP, "Finite positive SELECT cap required")
        with pg_deadline(self.connection):
            with self.connection.cursor() as cursor:
                cursor.execute(sql, params)
                fields = [item[0] for item in cursor.description]
                require(len(set(fields)) == len(fields), "Duplicate SELECT column names")
                rows = cursor.fetchmany(cap + 1)
        require(len(rows) <= cap, "SELECT reached the row-cap sentinel; completeness is unknown")
        return audit.canonical([dict(zip(fields, row)) for row in rows])

    def create_view(self, sql):
        check_cancel(self.cancel_file)
        require(self.isolated is True and self.target is not None and sql == DDL,
                "Only the fixed private ordinary CREATE VIEW is admitted")
        validate_private_target(self.target.root, self.target.pid)
        self.target.verify()
        with pg_deadline(self.connection):
            with self.connection.cursor() as cursor:
                cursor.execute(sql)  # One attempt. An exception leaves durable UNKNOWN.

    def close(self):
        self.connection.close()


def table_snapshot(isolated, cancel_file=None):
    result = {}
    for table in TABLES:
        p = one(f"SELECT * FROM tables() WHERE table_name='{table}'", isolated, cancel_file)
        w = one(f"SELECT * FROM wal_tables() WHERE name='{table}'", isolated, cancel_file)
        count = one(f"SELECT count() AS n FROM {table}", isolated, cancel_file)["n"]
        require(type(count) is int and 0 <= count <= 9223372036854775807, "Exact actual table COUNT required")
        value = {"table": table, "id": p["id"], "directory": p["directoryName"],
                 "physicalTxn": p["table_txn"], "metadataRows": p["table_row_count"], "actualRows": count,
                 "sequenceTxn": w["sequencerTxn"], "writerTxn": w["writerTxn"],
                 "pendingRows": p["wal_pending_row_count"], "bufferedTxns": w["bufferedTxnSize"],
                 "tableSuspended": p["table_suspended"], "walSuspended": w["suspended"]}
        validate_snapshot(value, source=table in audit.SPEC.sources)
        result[table] = value
    return result


def validate_snapshot(value, source=False):
    for field in ("id", "sequenceTxn", "writerTxn", "pendingRows", "bufferedTxns", "actualRows"):
        require(type(value[field]) is int and value[field] >= 0, "Strict nonnegative physical/WAL/count LONG required")
    require(value["id"] > 0 and isinstance(value["directory"], str) and value["directory"], "Physical identity required")
    txn, raw_rows = value["physicalTxn"], value["metadataRows"]
    require(raw_rows is None or type(raw_rows) is int and raw_rows >= 0, "Raw metadata rows must preserve explicit null or LONG")
    require(not source or raw_rows is not None, "Source physical row count must be initialized")
    if txn is None:
        require(not source and value["actualRows"] == value["sequenceTxn"] == value["writerTxn"] == 0
                and raw_rows in (None, 0), "Only an actual empty target may have an uninitialized transaction")
    else:
        require(type(txn) is int and txn >= 0, "Source/initialized target transaction must be exact LONG")
    require(value["pendingRows"] == value["bufferedTxns"] == 0 and value["sequenceTxn"] == value["writerTxn"]
            and value["tableSuspended"] is False and value["walSuspended"] is False, "Source or target WAL is unsettled")


def schemas(isolated, cancel_file=None):
    result = {}
    for table, model in MODELS.items():
        expected = model.get_questdb_schema()
        rows = query(f"SELECT * FROM table_columns('{table}')", isolated, cancel_file)
        physical = one(f"SELECT * FROM tables() WHERE table_name='{table}'", isolated, cancel_file)
        actual = {row["column"]: row["type"] for row in rows}
        require(len(actual) == len(rows) and tuple(actual) == tuple(expected["schema"]) and actual == expected["schema"]
                and {row["column"] for row in rows if row["upsertKey"]} == set(expected["dedup_keys"])
                and [row["column"] for row in rows if row["designated"]] == [expected["timestamp_col"]]
                and physical["partitionBy"] == expected["partition_by"] and physical["walEnabled"] is True
                and physical["dedup"] is True, "Original full D101 schema/key/partition/WAL drifted")
        result[table] = actual
    return result


def view_state(isolated, cancel_file=None):
    rows = query(f"SELECT * FROM views() WHERE view_name='{VIEW}'", isolated, cancel_file)
    require(len(rows) <= 1, "Ambiguous ordinary view identity")
    return rows[0] if rows else None


def validate_view(value):
    require(value is not None and value.get("view_name") == VIEW and value.get("view_status") == "valid"
            and not value.get("invalidation_reason") and isinstance(value.get("view_sql"), str)
            and value["view_sql"].strip() == OWNER_SELECT.strip()
            and all(isinstance(value.get(field), str) and value[field].strip()
                    for field in ("view_table_dir_name", "view_status_update_time")),
            "Ordinary ETF view is missing, invalid, lacks physical/status history, or its original SQL differs")


def view_schema(isolated, cancel_file=None):
    rows = query(f"SELECT * FROM table_columns('{VIEW}')", isolated, cancel_file)
    actual = {row["column"]: row["type"] for row in rows}
    require(len(rows) == len(FIELDS) and tuple(actual) == FIELDS and actual == TYPES, "Exact four-field view schema required")
    return actual


def keyed(rows):
    require(isinstance(rows, list) and 0 < len(rows) <= 3, "Finite three-day nonempty view result required")
    result = {}
    for row in rows:
        require(tuple(row) == FIELDS, "Explicit complete four-field row required")
        value = row["trade_date"]
        require(isinstance(value, str) and value, "UTC business date cannot be null/blank")
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(timestamp.tzinfo is not None and timestamp.utcoffset().total_seconds() == 0
                and timestamp.hour == timestamp.minute == timestamp.second == timestamp.microsecond == 0,
                "View identity must be exact UTC midnight")
        day = timestamp.strftime("%Y-%m-%d")
        require(day in DAYS and day not in result, "Unexpected or duplicate daily identity")
        require(type(row["etf_count"]) is int and 0 <= row["etf_count"] <= 9223372036854775807, "Exact nonnegative ETF count LONG required")
        for field in FIELDS[2:]:
            n = row[field]
            require(n is None or type(n) is float and math.isfinite(n), "Nullable DOUBLE must preserve finite binary64")
        result[day] = row
    require(tuple(sorted(result)) == DAYS, "One complete result for each actual source day required")
    return result


def compare(left, right):
    actual, expected = keyed(left), keyed(right)
    differences = []
    for day in DAYS:
        for field in FIELDS:
            a, b = actual[day][field], expected[day][field]
            equal = a is None and b is None if a is None or b is None else (
                struct.pack(">d", a) == struct.pack(">d", b) if field in FIELDS[2:] else
                (a[:10] == b[:10] if field == "trade_date" else a == b))
            if not equal:
                differences.append({"date": day, "field": field, "left": a, "right": b})
    return {"passed": not differences, "field_comparisons": 12, "double_raw_bit_comparisons": 6,
            "double_tolerance": 0, "mismatches": differences}


def raw_bits(rows):
    return [{"trade_date": row["trade_date"], **{field: None if row[field] is None else
            int.from_bytes(struct.pack(">d", row[field]), "big", signed=True) for field in FIELDS[2:]}}
            for row in rows]


def validate_qwp_diagnostic(value):
    for item in value["mismatches"]:
        a, b = item["left"], item["right"]
        require(item["field"] in FIELDS[2:] and type(a) is float and type(b) is float
                and abs(a-b) <= 2 * max(math.ulp(a), math.ulp(b)),
                "QWP changed a date/count/null or exceeded decimal binary64 serialization rounding")
        item["decimal_serialization_ulp_distance_bound"] = 2


def utc_identity_time(value):
    # Preserve nanosecond ordering; datetime alone truncates the saved OS proof.
    require(isinstance(value, str), "Producer OS birth/stop time must be a known UTC string")
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z", value)
    require(match is not None, "Producer OS birth/stop time must be exact UTC ISO")
    second = datetime.strptime(match[1], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    return second, int((match[2] or "").ljust(9, "0") or "0")


def native_identity(value):
    require(isinstance(value, dict) and type(value.get("pid")) is int and value["pid"] > 0
            and value["pid"] != PID and type(value.get("parent_pid")) is int and value["parent_pid"] >= 0
            and isinstance(value.get("name"), str) and value["name"].strip(), "Exact native producer identity required")
    utc_identity_time(value.get("birth"))
    require(value.get("executable") is None or isinstance(value["executable"], str), "Native executable must be text or explicit null")
    return {field: value.get(field) for field in ("pid", "parent_pid", "name", "birth", "executable")}


def fresh_native_matches(pids):
    wanted = ",".join(map(str, sorted(pids)))
    command = ("$ErrorActionPreference='Stop';$wanted=@(" + wanted + ");"
               "$rows=@(Get-CimInstance Win32_Process | Where-Object {$wanted -contains $_.ProcessId} | "
               "ForEach-Object {@{pid=[long]$_.ProcessId;parent_pid=[long]$_.ParentProcessId;name=$_.Name;"
               "birth=$(if ($null -eq $_.CreationDate) {$null} else {$_.CreationDate.ToUniversalTime().ToString('o')});"
               "executable=$_.ExecutablePath}});ConvertTo-Json -InputObject $rows -Compress")
    executable = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    result = subprocess.run([str(executable), "-NoProfile", "-NonInteractive", "-Command", command],
                            capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, "Authoritative native producer CIM unavailable")
    values = json.loads(result.stdout)
    require(isinstance(values, list) and len(values) <= len(pids), "Finite native producer inventory required")
    matches = [native_identity(value) for value in values]
    require(len({item["pid"] for item in matches}) == len(matches)
            and all(item["pid"] in pids for item in matches), "Unexpected or duplicate native producer PID")
    return sorted(matches, key=lambda item: item["pid"])


def process_review_binding(path=None, sha=None):
    require((path is None) == (sha is None), "Process identity review path and SHA must be supplied together")
    if path is None:
        return None
    require(isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{64}", sha), "Explicit process identity review SHA required")
    path = evidence_path(path)
    require(path.parent == DIRECTORY.resolve(), "Process identity review must stay within D102 commands")
    binding = {"path": str(path), "sha256": sha}
    validate_process_review(binding)
    return binding


def validate_process_review(binding, manifest=None):
    review = load(binding["path"], binding["sha256"])
    protocol = review.get("protocol_version")
    scopes = {1: ("VERIFIED_INDEPENDENT_REUSED_PID_IDENTITIES", "accepted_for_new_precreate_attempt_only"),
              2: ("VERIFIED_INDEPENDENT_COMPLETED_PRODUCER_IDENTITIES", "accepted_for_fresh_birth_after_complete_stop_only")}
    require(type(protocol) is int and protocol in scopes and review.get("task_id") == "D102"
            and (review.get("status"), review.get("decision")) == scopes[protocol] and review.get("blockers") == [],
            "Process identity review is not explicitly accepted for a new pre-CREATE attempt")
    require(evidence_path(review["gate_path"]) == GATE.resolve() and review["gate_sha256"] == GATE_SHA,
            "Process identity review must bind the accepted D101 gate")
    require(load(GATE, GATE_SHA).get("decision") == "accepted_for_serial_progress", "Accepted D101 gate changed")
    target = review["private_target"]
    require(target == {"pid": PID, "data_root": str(ROOT.resolve()), "host": "127.0.0.1", "http_port": 19020, "pg_port": 18832},
            "Process identity review private target differs")
    failed_path = DIRECTORY / ("view-isolated-acceptance-20261006.json" if protocol == 1 else "view-isolated-acceptance-new-identity-20261006.json")
    diagnostic_path = DIRECTORY / ("failed-precreate-process-diagnostic-20261006.json" if protocol == 1 else "failed-precreate-identity-delta-diagnostic-20261006.json")
    require(evidence_path(review["failed_evidence_path"]) == failed_path.resolve()
            and evidence_path(review["diagnostic_path"]) == diagnostic_path.resolve(), "Original failed/diagnostic paths differ")
    failed = load(failed_path, review["failed_evidence_sha256"])
    diagnostic = load(diagnostic_path, review["diagnostic_sha256"])
    require(failed.get("status") == "FAILED" and failed.get("owner_invoked") is False
            and failed.get("formal_mutated") is False and "alias_submission" not in failed.get("isolated", {})
            and all(failed.get(field) == 0 for field in ("source_written_rows", "cache_written_rows", "coverage_written_rows")),
            "Original pre-CREATE failure must have no submitted mutation")
    require(diagnostic.get("status") == "READONLY_PRECREATE_DIAGNOSTIC"
            and diagnostic.get("failed_evidence_sha256") == review["failed_evidence_sha256"]
            and diagnostic.get("tables_equal_accepted_typed") is True and diagnostic.get("private_view") is None
            and diagnostic.get("create_claim_exists") is False and diagnostic.get("ddl_attempts") == 0
            and diagnostic.get("owner_invocations") == 0, "Readonly pre-CREATE diagnostic does not prove an unsubmitted failure")
    frozen = review["historical_process_files"]
    require(isinstance(frozen, dict) and 0 < len(frozen) <= 5000
            and frozen == diagnostic["historical_process_files"], "Review must bind the full original process manifest")
    files = sorted((D101 / "java-owner-bridge").glob("*.process-*.json"))
    actual = {str(path): digest(path) for path in files}
    require(actual == frozen and (manifest is None or manifest == frozen), "Historical process-file inventory or SHA changed")
    values = {path: load(path, sha) for path, sha in frozen.items()}
    response_identities = {}
    hard_deny = set()
    census = set()
    if protocol == 1:
        approved_values = review["approved_native_matches"]
        require(isinstance(approved_values, list) and 0 < len(approved_values) <= 4096, "Finite explicit reused PID identities required")
        approved = {item["pid"]: item for item in map(native_identity, approved_values)}
        require(len(approved) == len(approved_values)
                and sorted(approved.values(), key=lambda item: item["pid"]) == sorted(map(native_identity, diagnostic["native_matches"]), key=lambda item: item["pid"]),
                "Approved native identities must exactly match the independent diagnostic")
    else:
        def pid_set(field):
            entries = review[field]
            require(isinstance(entries, list) and len(entries) <= 4096
                    and all(type(pid) is int and pid > 0 and pid != PID for pid in entries)
                    and entries == sorted(set(entries)), "Exact sorted finite historical PID classification required")
            return set(entries)
        eligible, hard_deny, census = map(pid_set, ("eligible_pids", "hard_deny_pids", "historical_producer_pids"))
        require(eligible and not eligible.intersection(hard_deny) and eligible.union(hard_deny) == census,
                "Eligible and hard-denied PIDs must exactly partition the full historical census")
        require(evidence_path(review["typed_evidence_path"]) == TYPED.resolve(), "Accepted typed frontier path differs")
        typed = load(TYPED, review["typed_evidence_sha256"])
        require(typed.get("status") == "VERIFIED_TYPED_OWNER_ASSERTIONS_ZERO_PUBLISH"
                and typed.get("retained_interval_leases") == 0 and typed["tables_before"] == typed["tables_after"],
                "Completed-producer review must bind the actual accepted typed frontier")
        responses = review["historical_response_evidence"]
        starts = {value["invocation_id"]: (path, value) for path, value in values.items() if path.endswith(".process-started.json")}
        require(isinstance(responses, list) and 0 < len(responses) == len(starts) <= 4096,
                "Every historical START must have a frozen response SHA")
        for proof in responses:
            invocation = proof["invocation_id"]
            require(invocation in starts and invocation not in response_identities, "Unknown or duplicate historical response invocation")
            start_path, start = starts[invocation]
            expected_path = Path(start_path).with_name(Path(start_path).name.removesuffix(".process-started.json") + ".response.json")
            require(evidence_path(proof["path"]) == expected_path, "Historical response path does not belong to its START")
            response = load(proof["path"], proof["sha256"])
            pid = proof["actual_bridge_pid"]
            require(type(pid) is int and pid > 0 and pid != PID and response.get("invocation_id") == invocation
                    and response["bridge_process"]["pid"] == pid, "Historical response actual bridge PID differs")
            response_identities[invocation] = pid
        reconstructed = set(response_identities.values())
        for value in values.values():
            occurrences = [value.get("pid"), value.get("child_pid"), value.get("actual_bridge_pid"),
                           *(child.get("pid") for child in value.get("observed_children", []))]
            for pid in occurrences:
                if pid is not None:
                    require(type(pid) is int and pid > 0 and pid != PID, "Exact historical producer PID required")
                    reconstructed.add(pid)
        require(reconstructed == census, "Historical process/response PID census is incomplete")
        # An incomplete invocation denies every one of its original instances,
        # even when that PID also occurs in another successfully stopped tree.
        for invocation, (path, start) in starts.items():
            stop_path = str(Path(path).with_name(Path(path).name.removesuffix(".process-started.json") + ".process-stopped.json"))
            require(stop_path in values, "Historical START has no terminal STOP evidence")
            stop = values[stop_path]
            complete = (type(stop.get("process_tree_version")) is int and stop["process_tree_version"] == 1
                        and all(stop.get(flag) is True for flag in ("exit_observed", "process_tree_stopped", "tree_observation_complete", "response_present", "bridge_identity_proved")))
            if not complete:
                bad_pids = {response_identities[invocation], start["pid"]}
                for value in values.values():
                    if value.get("invocation_id") == invocation:
                        bad_pids.update(pid for pid in [value.get("pid"), value.get("child_pid"), value.get("actual_bridge_pid"),
                                        *(child.get("pid") for child in value.get("observed_children", []))] if pid is not None)
                require(bad_pids.issubset(hard_deny), "An incomplete/UNKNOWN invocation must remain hard denied")
        approved = {pid: None for pid in eligible}
    originals = review["original_identities"]
    require(isinstance(originals, list) and 0 < len(originals) <= 4096, "Complete original OS identity proofs required")
    proved, proved_invocations, stop_times = set(), {}, {}
    for item in originals:
        pid, birth = item["pid"], item["original_birth"]
        require(type(pid) is int and pid in approved and (pid, birth) not in proved, "Unknown or duplicate original producer identity")
        require(item.get("complete_stop_verified") is True, "Original producer completion proof missing")
        if protocol == 1:
            require(item.get("current_birth_strictly_later") is True and item["current_birth"] == approved[pid]["birth"],
                    "Original producer completion/reuse proof missing")
        start_path, stop_path = str(evidence_path(item["started_path"])), str(evidence_path(item["complete_stop_path"]))
        require(frozen.get(start_path) == item["started_sha256"] and frozen.get(stop_path) == item["complete_stop_sha256"],
                "Original START/STOP SHA is not in the full manifest")
        start, stop = values[start_path], values[stop_path]
        invocation = item["invocation_id"]
        require(start["invocation_id"] == stop["invocation_id"] == invocation
                and start["pid"] == stop["pid"] and start["process_start"] == stop["process_start"]
                and type(stop.get("process_tree_version")) is int and stop["process_tree_version"] == 1
                and all(stop.get(flag) is True for flag in ("exit_observed", "process_tree_stopped", "tree_observation_complete", "response_present", "bridge_identity_proved"))
                and stop["observed_at"] == item["original_stopped_at"], "Original complete STOP proof differs")
        require(utc_identity_time(birth) <= utc_identity_time(item["original_stopped_at"]), "Original complete STOP predates OS birth")
        if protocol == 1:
            require(utc_identity_time(item["original_stopped_at"]) < utc_identity_time(approved[pid]["birth"]),
                    "Current OS birth must be strictly later than original complete STOP")
        previous = stop_times.get(pid)
        if previous is None or utc_identity_time(previous) < utc_identity_time(item["original_stopped_at"]):
            stop_times[pid] = item["original_stopped_at"]
        children = stop["observed_children"]
        require(isinstance(children, list) and len(children) <= 64, "Finite complete original child tree required")
        identities = {stop["pid"]: [stop["process_start"]]}
        identity_keys = {(stop["pid"], utc_identity_time(stop["process_start"]))}
        for child in children:
            require(type(child.get("pid")) is int and child["pid"] > 0 and child["pid"] != stop["pid"]
                    and child.get("exit_observed") is True and type(child.get("parent_pid")) is int
                    and (protocol == 2 or child["pid"] not in identities),
                    "Original child identity/exit must be complete and unique")
            key = (child["pid"], utc_identity_time(child.get("process_start")))
            require(key not in identity_keys, "Duplicate original child OS identity")
            identity_keys.add(key)
            child_path = str(evidence_path(child["evidence_path"]))
            require(frozen.get(child_path) == child["evidence_sha256"], "Original child sidecar SHA changed")
            side = values[child_path]
            require(side["invocation_id"] == invocation and side["pid"] == stop["pid"]
                    and side["process_start"] == stop["process_start"] and side["observed_descendant"] is True
                    and side["child_pid"] == child["pid"] and side["child_process_start"] == child["process_start"]
                    and side["parent_pid"] == child["parent_pid"], "Original child sidecar identity differs")
            identities.setdefault(child["pid"], []).append(child["process_start"])
        for child in children:
            parent, visited = child["parent_pid"], {child["pid"]}
            while True:
                require(parent in identities and parent not in visited, "Original child parent chain is incomplete")
                require(len(identities[parent]) == 1, "Original parent PID has ambiguous OS births")
                if parent == stop["pid"]:
                    break
                visited.add(parent)
                parent = next(value["parent_pid"] for value in children if value["pid"] == parent)
        require(len(identities.get(stop["actual_bridge_pid"], [])) == 1 and stop["actual_bridge_pid"] == item["actual_bridge_pid"],
                "Original actual bridge PID is unknown or has ambiguous OS births")
        if item["role"] == "root":
            require(pid == start["pid"] and birth == start["process_start"], "Original root birth differs")
        else:
            require(item["role"] == "child", "Unknown original process role")
            child_path = str(evidence_path(item["observed_path"]))
            require(frozen.get(child_path) == item["observed_sha256"] and birth in identities.get(pid, [])
                    and values[child_path]["child_pid"] == pid and values[child_path]["child_process_start"] == birth,
                    "Original reviewed child birth/sidecar differs")
        for kind in ("request", "response"):
            proof = item[kind + "_evidence"]
            body = load(proof["path"], proof["sha256"])
            require(body.get("invocation_id") == invocation, "Original request/response invocation differs")
            if kind == "request":
                require(proof["sha256"] == start["request_sha256"] == stop["request_sha256"], "Original request SHA differs")
            else:
                require(body["bridge_process"]["pid"] == stop["actual_bridge_pid"], "Original response actual bridge PID differs")
        proved.add((pid, birth))
        proved_invocations[(pid, birth)] = invocation
    # Every historical occurrence of an admitted PID must have a reviewed known birth.
    seen = set()
    for value in values.values():
        occurrences = [(value.get("pid"), value.get("process_start")),
                       (value.get("child_pid"), value.get("child_process_start"))]
        occurrences += [(child.get("pid"), child.get("process_start")) for child in value.get("observed_children", [])]
        bridge_pid = value.get("actual_bridge_pid")
        if bridge_pid in approved:
            bridge_births = {birth for pid, birth in occurrences if pid == bridge_pid}
            require(len(bridge_births) == 1, "Historical actual bridge lacks a unique known OS birth")
            occurrences.append((bridge_pid, next(iter(bridge_births))))
        for pid, birth in occurrences:
            if pid in approved:
                utc_identity_time(birth)
                require((pid, birth) in proved and (protocol == 1 or proved_invocations[(pid, birth)] == value.get("invocation_id")),
                        "Unreviewed original birth or unknown producer start")
                seen.add((pid, birth))
    require(seen == proved and {pid for pid, birth in proved} == set(approved), "Original identity census is incomplete")
    if protocol == 2:
        for invocation, pid in response_identities.items():
            if pid in approved:
                require(any(original_pid == pid and proof_invocation == invocation for (original_pid, birth), proof_invocation in proved_invocations.items()),
                        "Historical response bridge lacks a complete reviewed OS identity")
        return {"protocol_version": 2, "eligible_stop_times": stop_times,
                "hard_deny_pids": sorted(hard_deny), "historical_producer_pids": sorted(census)}
    return approved


def approve_native_matches(matches, approved=None):
    if approved is None:
        require(not matches, "Native producer still present; default PID reuse refuses")
        return
    if approved.get("protocol_version") == 2:
        for value in matches:
            identity = native_identity(value)
            stop = approved["eligible_stop_times"].get(identity["pid"])
            require(stop is not None and identity["pid"] not in approved["hard_deny_pids"]
                    and utc_identity_time(identity["birth"]) > utc_identity_time(stop),
                    "Present producer lacks exhaustive complete STOP proof or its fresh birth is not strictly later")
        return
    for value in matches:
        require(approved.get(value["pid"]) == value, "Present producer is not the exact explicitly reviewed OS identity")


def same_quiescence(before, after):
    if before.get("process_identity_policy") == "fresh_birth_after_complete_stop":
        fields = ("ledgers", "process_file_hashes", "checked_producer_pids", "process_identity_review", "process_identity_policy")
        return all(before.get(field) == after.get(field) for field in fields)
    return before == after


def prerequisite(gate_path, gate_sha, process_identity_review=None, process_identity_review_sha256=None):
    process_binding = process_review_binding(process_identity_review, process_identity_review_sha256)
    require(evidence_path(gate_path) == GATE.resolve() and gate_sha == GATE_SHA, "Pinned accepted D101 coordinator gate and SHA required")
    gate = load(gate_path, gate_sha)
    require(gate.get("task_id") == "D101" and gate.get("decision") == "accepted_for_serial_progress"
            and gate.get("next_task") == "D102" and gate.get("next_task_may_start") is True, "D102 serial prerequisite is not accepted")
    bindings = {evidence_path(item["path"]): item["sha256"] for item in gate["evidence"]}
    typed = load(TYPED, bindings[TYPED.resolve()])
    require(typed["status"] == "VERIFIED_TYPED_OWNER_ASSERTIONS_ZERO_PUBLISH" and typed["retained_interval_leases"] == 0
            and typed["tables_before"] == typed["tables_after"], "Actual D101 typed target frontier must be verified")
    captured, proofs = {}, []
    for stage in ("initial", "increment"):
        path = D101 / f"source-fixture-{stage}-20261006.json"
        source = load(path, bindings[path.resolve()])
        require(source["status"] == "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE", "Verified complete original source fixture required")
        proofs.append({"path": str(path), "sha256": digest(path)})
        for table in audit.SPEC.sources:
            info = source["source_captures"][table]
            path = D101 / f"real-source-{table}-{stage}-20261006.json"
            require(evidence_path(info["path"]) == path.resolve(), "Exact original source capture path required")
            capture = load(path)
            fields = tuple(audit.SOURCE_MODELS[table].get_questdb_schema()["schema"])
            require(capture["table"] == table and tuple(capture["fields"]) == fields and len(capture["rows"]) == info["rows"]
                    and capture["sha256"] == info["sha256"] == fixture.source_sha(capture["rows"]), "Full source capture SHA/fields/count differs")
            captured.setdefault(table, []).extend(capture["rows"])
    for table, rows in captured.items():
        rows.sort(key=lambda row: (row["timestamp"], row["ts_code"]))
        require(len({(row["timestamp"], row["ts_code"]) for row in rows}) == len(rows), "Duplicate complete source identity")
    require(sum(map(len, captured.values())) == 11667, "Exactly 11667 accepted real source rows required")
    return {"gate": gate, "typed": typed, "captures": captured, "source_evidence": proofs,
            "gate_sha256": gate_sha, "typed_sha256": bindings[TYPED.resolve()], "process_identity_review": process_binding}


def quiescence(context, cancel_file=None):
    check_cancel(cancel_file)
    paths = sorted((REPO / "var").glob("d101-java-*.sqlite3"))
    actual = evidence_path(context["typed"]["ledger_path"])
    require(actual in [path.resolve() for path in paths] and 1 <= len(paths) <= 100, "Actual finite D101 ledger inventory required")
    ledgers = {str(path.resolve()): fixture.require_quiescent_java_ledger(path) for path in paths}
    folder = D101 / "java-owner-bridge"
    files = sorted(folder.glob("*.process-*.json"))
    require(0 < len(files) <= 5000, "Finite actual bridge process inventory required")
    pids, manifest = set(), {}
    for path in files:
        value = load(path)
        manifest[str(path)] = digest(path)
        if path.name.endswith(".process-started.json"):
            stopped = path.with_name(path.name.removesuffix(".process-started.json") + ".process-stopped.json")
            require(stopped.is_file(), "A bridge launcher has no terminal process evidence")
        for pid in [value.get("pid"), value.get("child_pid"), value.get("actual_bridge_pid"),
                    *(child.get("pid") for child in value.get("observed_children", []))]:
            if pid is None:
                continue
            require(type(pid) is int and pid > 0 and pid != PID, "Exact distinct producer PID required")
            pids.add(pid)
    require(0 < len(pids) <= 4096, "Finite actual producer PID set required")
    binding = context.get("process_identity_review")
    approved = validate_process_review(binding, manifest) if binding is not None else None
    protocol2 = approved is not None and approved.get("protocol_version") == 2
    if protocol2:
        pids = set(approved["historical_producer_pids"])
    matches = fresh_native_matches(pids)
    approve_native_matches(matches, approved)
    return {"ledgers": ledgers, "process_file_hashes": manifest, "checked_producer_pids": sorted(pids),
            "native_current_matches": matches, "native_method": "fresh authoritative Win32_Process CIM with UTC OS birth; no command lines",
            "process_identity_review": binding,
            "process_identity_policy": "fresh_birth_after_complete_stop" if protocol2 else "exact_current_identity_or_absent",
            "historical_false_STOP_policy": "Old evidence retained. Default requires all PIDs absent; protocol1 requires exact approved identities; protocol2 requires fresh birth strictly after all old complete STOPs and hard-denied PIDs absent."}


def source_rows(reader, isolated, context):
    census = {}
    for table, model in audit.SOURCE_MODELS.items():
        fields = tuple(model.get_questdb_schema()["schema"])
        where = "" if table == "etf_basic" else f" WHERE timestamp >= '{START}' AND timestamp < '{STOP}'"
        rows = reader.records(f"SELECT {','.join(fields)} FROM {table}{where} ORDER BY timestamp,ts_code LIMIT {CAP+1}")
        require(rows and all(tuple(row) == fields for row in rows), "Full bounded source rows/fields required")
        require(all(row["timestamp"] and row["ts_code"] for row in rows)
                and len({(row["timestamp"], row["ts_code"]) for row in rows}) == len(rows), "Full nonempty source identity required")
        if isolated:
            fixture.strict_compare(rows, context["captures"][table], fields, ("timestamp", "ts_code"))
            require(fixture.source_sha(rows) == fixture.source_sha(context["captures"][table]), "Current exact full source capture SHA differs")
        census[table] = {"fields": list(fields), "rows": len(rows), "full_field_values": len(rows)*len(fields),
                         "full_field_sha256": fixture.source_sha(rows)}
    return census


def audit_target(reader, isolated, context, result, cancel_file=None):
    before = table_snapshot(isolated, cancel_file)
    result["tables_before"] = before
    before_view = view_state(isolated, cancel_file)
    result["view_before"] = before_view
    validate_view(before_view)
    result["schemas_before"] = schemas(isolated, cancel_file)
    result["view_schema"] = view_schema(isolated, cancel_file)
    if isolated:
        require(before == context["typed"]["tables_after"], "Private five-table frontier differs from accepted D101 typed state")
    result["source_census"] = source_rows(reader, isolated, context)
    view_sql = f"SELECT {','.join(FIELDS)} FROM {VIEW} WHERE trade_date >= %s AND trade_date < %s ORDER BY trade_date LIMIT 4"
    direct_sql = OWNER_TEMPLATE.format(where="WHERE s.timestamp >= %s AND s.timestamp < %s") + " ORDER BY trade_date LIMIT 4"
    view = reader.records(view_sql, (START, STOP), cap=3)
    direct = reader.records(direct_sql, (START, STOP), cap=3)
    result["pg_view_rows"], result["pg_direct_source_rows"] = view, direct
    result["pg_view_double_raw_bits"], result["pg_direct_source_double_raw_bits"] = raw_bits(view), raw_bits(direct)
    result["view_vs_original_direct_SQL"] = compare(view, direct)
    require(result["view_vs_original_direct_SQL"]["passed"], "PG four-field view/direct-source parity failed")
    result["qwp_view_rows"] = query(view_sql.replace("%s", "'{}'", 2).format(START, STOP), isolated, cancel_file)
    result["qwp_direct_source_rows"] = query(direct_sql.replace("%s", "'{}'", 2).format(START, STOP), isolated, cancel_file)
    # QWP cannot certify binary64; its diagnostics never loosen PG business parity.
    result["qwp_vs_pg"] = {"view": compare(result["qwp_view_rows"], view), "direct_source": compare(result["qwp_direct_source_rows"], direct)}
    for diagnostic in result["qwp_vs_pg"].values():
        validate_qwp_diagnostic(diagnostic)
    result["transport_authority"] = "PGWire exact binary64 business parity with tolerance0. QWP decimal serialization at most2ULP is retained diagnostically and never replaces exact PG parity."
    after = table_snapshot(isolated, cancel_file)
    result["tables_after"], result["view_after"] = after, view_state(isolated, cancel_file)
    result["schemas_after"] = schemas(isolated, cancel_file)
    require(before == after and before_view == result["view_after"] and result["schemas_before"] == result["schemas_after"]
            and result["view_schema"] == view_schema(isolated, cancel_file), "Source/view/cache/coverage/schema changed during bounded parity")
    result.update(target_validated=True, source_and_target_frontiers_unchanged=True,
                  actual_view_sql_sha256=text_sha(before_view["view_sql"]), rows=3, matched_field_values=12)


def ensure_view(reader, target, context, output, result, cancel_file=None, journal=None):
    check_cancel(cancel_file)
    target.verify()
    if context.get("process_identity_review") is not None:
        validate_process_review(context["process_identity_review"])
    require(load(GATE, GATE_SHA)["decision"] == "accepted_for_serial_progress", "Accepted gate changed")
    existing = view_state(True, cancel_file)
    if DDL_CLAIM.exists():
        require(load(DDL_CLAIM).get("ack") == "ACKNOWLEDGED", "Prior CREATE acknowledgement UNKNOWN; no automatic retry or recovery")
    if existing is not None:
        validate_view(existing)
        result["alias_created"] = False
        return
    require(not DDL_CLAIM.exists(), "The immutable CREATE attempt already exists; no resubmission")
    before = table_snapshot(True, cancel_file)
    require(before == context["typed"]["tables_after"], "Five protected physical/count/WAL frontiers changed")
    schemas(True, cancel_file)
    source_rows(reader, True, context)
    quiet = quiescence(context, cancel_file)
    result["quiescence_before_ddl"] = quiet
    require(before == table_snapshot(True, cancel_file) and view_state(True, cancel_file) is None, "Source/view changed immediately before CREATE")
    target.verify()
    check_cancel(cancel_file)
    claim = {"task_id": "D102", "sql": DDL, "sql_sha256": text_sha(DDL), "ack": "UNKNOWN", "automatic_retry": False,
             "gate_sha256": GATE_SHA, "expected_pid": PID, "protected_tables_before": before,
             "process_identity_review": context.get("process_identity_review")}
    save_new(DDL_CLAIM, claim)
    result["alias_submission"] = claim
    save_progress(output, journal if journal is not None else result)
    reader.create_view(DDL)
    claim["ack"] = "ACKNOWLEDGED"
    save_progress(DDL_CLAIM, claim)
    save_progress(output, journal if journal is not None else result)
    require(table_snapshot(True, cancel_file) == before, "Protected tables changed during ordinary view CREATE")
    validate_view(view_state(True, cancel_file))
    result["alias_created"] = True


def python_evidence():
    paths = [fixture.common.SOURCE_ROOT / "quant_platform/data/adapters/questdb" / name for name in
             ("market_barometer_views.py", "market_barometer_cache.py", "market_barometer.py", "derived.py")]
    paths += [fixture.common.SOURCE_ROOT / "quant_platform/data/adapters/materializers/market_barometer.py",
              fixture.common.SOURCE_ROOT / "quant_platform/data/application/market_barometer.py"]
    return {str(path): digest(path) for path in paths}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, default=ROOT)
    parser.add_argument("--expected-pid", type=int)
    parser.add_argument("--coordinator-gate", type=Path, default=GATE)
    parser.add_argument("--coordinator-gate-sha256", default=GATE_SHA)
    parser.add_argument("--process-identity-review", type=Path)
    parser.add_argument("--process-identity-review-sha256")
    parser.add_argument("--cancel-file", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output is None:
        args.output = DIRECTORY / ("view-isolated-acceptance-20261006.json" if args.execute_isolated else
                                  "view-formal-readonly-audit-20261006.json")
    require(args.output.resolve().parent == DIRECTORY.resolve(), "New evidence must remain in D102 commands")
    if args.execute_isolated:
        validate_private_target(args.private_root, args.expected_pid)
    if args.cancel_file:
        require(args.cancel_file.resolve().is_relative_to(REPO.resolve()), "Cancellation evidence must stay in workspace")
    result = {"task_id": "D102", "status": "IN_PROGRESS", "checked_at": datetime.now(timezone.utc).isoformat(),
              "formal_mutated": False, "source_written_rows": 0, "cache_written_rows": 0, "coverage_written_rows": 0,
              "owner_invoked": False, "mv_refresh_submitted": False, "automatic_retry": False,
              "original_python_SQL": OWNER_SELECT, "original_python_SQL_sha256": text_sha(OWNER_SELECT),
              "original_template_sha256": text_sha(OWNER_TEMPLATE), "view_fields": list(FIELDS), "view_types": TYPES,
              "range_from_inclusive": START, "range_to_exclusive": STOP, "expected_source_days": list(DAYS),
              "transport_deadlines": {"pg_connect_seconds": 10, "pg_execution_and_read_seconds": PG_DEADLINE_SECONDS,
                                      "private_http_seconds": 30, "formal_http_seconds": 90,
                                      "pg_timeout_action": "Close own connection; SELECT failure or durable CREATE UNKNOWN; no SET or automatic retry"},
              "formal": {}, "execute_isolated": args.execute_isolated,
              "scope": "Ordinary view over real etf_share/etf_daily JOIN; no cache hit, provider universe, full-history or formal freshness certification"}
    save_new(args.output.resolve(), result)
    try:
        check_cancel(args.cancel_file)
        context = prerequisite(args.coordinator_gate, args.coordinator_gate_sha256,
                               args.process_identity_review, args.process_identity_review_sha256)
        result["d101_evidence"] = {k: context[k] for k in ("gate_sha256", "typed_sha256", "source_evidence", "process_identity_review")}
        owner_hashes = result["python_callchain_file_sha256"] = python_evidence()
        with closing(Reader(False, cancel_file=args.cancel_file)) as reader:
            audit_target(reader, False, context, result["formal"], args.cancel_file)
        result["status"] = "VERIFIED_FORMAL_READONLY_VIEW_PARITY"
        if args.execute_isolated:
            target = fixture.PrivateTarget(args.private_root, args.expected_pid)
            isolated = result["isolated"] = {"private_target_attestation": target.identity}
            quiet_before = quiescence(context, args.cancel_file)
            with closing(Reader(True, target, args.cancel_file)) as reader:
                ensure_view(reader, target, context, args.output.resolve(), isolated, args.cancel_file, journal=result)
                audit_target(reader, True, context, isolated, args.cancel_file)
            isolated["quiescence_after"] = quiescence(context, args.cancel_file)
            require(same_quiescence(quiet_before, isolated["quiescence_after"]), "D101 ledgers or producer evidence changed during D102")
            isolated["private_target_attestation_after"] = target.verify()
            result["formal_tables_after_isolated"] = table_snapshot(False, args.cancel_file)
            result["formal_view_after_isolated"] = view_state(False, args.cancel_file)
            require(result["formal_tables_after_isolated"] == result["formal"]["tables_after"]
                    and result["formal_view_after_isolated"] == result["formal"]["view_after"], "Formal metadata changed during isolated audit")
            result["status"] = "VERIFIED_ISOLATED_VIEW_READ"
        require(owner_hashes == python_evidence(), "Original Python SQL/callchain changed during audit")
        save_progress(args.output.resolve(), result)
        code = 0
    except Exception as exc:
        result.update(status="FAILED", error_class=type(exc).__name__, error=str(exc))
        save_progress(args.output.resolve(), result)
        code = 1
    print(json.dumps({"task_id": "D102", "status": result["status"], "output": str(args.output.resolve()),
                      "formal_mutated": False, "owner_invoked": False, "error": result.get("error")}, ensure_ascii=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
