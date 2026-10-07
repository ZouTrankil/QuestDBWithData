"""SELECT-only D101 increment preflight after actual canonical FIRST and HIT.

No fixture, owner.read, DDL, ILP, install, retry or reset is invoked. Evidence is
new-only. An observed producer PID still present (including reuse) fails closed.
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
import subprocess
from urllib.parse import urlparse
from urllib.request import url2pathname

import psycopg2
import prepare_d101_etf_fixture as fixture


DIRECTORY = fixture.DIRECTORY.resolve()
BRIDGE_DIRECTORY = DIRECTORY / "java-owner-bridge"
FIRST = DIRECTORY / "java-readthrough-first-20261006.json"
HIT = DIRECTORY / "java-readthrough-hit-20261006.json"
INITIAL = DIRECTORY / "source-fixture-initial-20261006.json"
EXPECTED_PID = 23388
INITIAL_COUNTS = {"etf_share": 1532, "etf_daily": 4274, "etf_basic": 2958}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def file_path(value):
    require(isinstance(value, str) and value, "Explicit evidence path required")
    if value.startswith("file:"):
        parsed = urlparse(value)
        require(parsed.netloc in ("", "localhost"), "Remote evidence URI forbidden")
        value = url2pathname(parsed.path)
    return Path(value).resolve(strict=True)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path, expected_sha=None):
    path = file_path(str(path))
    require(path.is_relative_to(DIRECTORY), "Evidence must be within D101 commands")
    require(path.stat().st_size <= 64 * 1024 * 1024, "Evidence size exceeds finite cap")
    if expected_sha is not None:
        require(digest(path) == expected_sha, "Immutable evidence SHA differs")
    return json.loads(path.read_text(encoding="utf-8"))


def positive(value):
    require(type(value) is int and value > 0, "Positive exact native process PID required")
    return value


def birth(value):
    require(isinstance(value, str) and value != "UNKNOWN", "Native OS birth identity required")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "UTC native birth identity required")
    return parsed


def parent_chain(pid, root_pid, identities):
    seen = set()
    while pid != root_pid:
        require(pid not in seen and len(seen) < 64 and pid in identities,
                "Producer ancestry missing or cyclic")
        seen.add(pid)
        child = identities[pid]
        parent = positive(child["parent_pid"])
        require(parent in identities and birth(identities[parent]["process_start"]) <= birth(child["process_start"]),
                "Producer parent OS birth does not bind original launcher")
        pid = parent


def producer_proofs(stage, ledger_path, source_version, target_id):
    publications = stage["actual_owner_publications"]
    require(isinstance(publications, list) and len(publications) == 2, "Exactly two actual owner calls required")
    evidence, pids, days = [], set(), set()
    for publication in publications:
        response_path = file_path(publication["responsePath"])
        require(response_path.parent == BRIDGE_DIRECTORY, "Native owner response directory differs")
        response = load(response_path, publication["responseSha256"])
        require(response["status"] == "VERIFIED_READTHROUGH" and response["owner_invoked"] is True
                and response["owner_sender_stopped"] is True, "Actual successful original-owner response required")
        require(response["sources_fingerprint"] == source_version and response["target_id"] == target_id,
                "Actual owner source or target identity differs")
        day = response["trade_date"]
        require(day in fixture.DAYS[:2] and day not in days, "Exact initial two owner dates required")
        days.add(day)
        intent_path = file_path(response["java_intent"]["path"])
        intent_sha = response["java_intent"]["sha256"]
        intent = load(intent_path, intent_sha)
        require(intent_path.parent == BRIDGE_DIRECTORY and file_path(intent["ledger_path"]) == ledger_path
                and intent["run_id"] == stage["run"]["id"] and intent["target_id"] == target_id
                and intent["sources_fingerprint"] == source_version and intent["revision"] == 4,
                "Owner immutable intent does not bind actual successful native ledger")
        prefix = response_path.name.removesuffix(".response.json")
        started_path = BRIDGE_DIRECTORY / (prefix + ".process-started.json")
        stopped_path = BRIDGE_DIRECTORY / (prefix + ".process-stopped.json")
        request_path = BRIDGE_DIRECTORY / (prefix + ".request.json")
        started, stopped = load(started_path), load(stopped_path)
        for field in ("invocation_id", "pid", "process_start", "request_sha256", "intent_sha256", "process_tree_version"):
            require(started[field] == stopped[field], "Original native process start/stop proof differs")
        require(started["invocation_id"] == response["invocation_id"] == intent["invocation_id"]
                and started["intent_sha256"] == intent_sha and started["request_sha256"] == digest(request_path)
                and type(started["process_tree_version"]) is int and started["process_tree_version"] == 1,
                "Native proof is not bound to actual owner request/intent")
        require(all(stopped.get(field) is True for field in
                    ("exit_observed", "process_tree_stopped", "tree_observation_complete", "response_present", "bridge_identity_proved"))
                and type(stopped["exit_code"]) is int and stopped["exit_code"] == 0,
                "Complete successful native producer tree proof required")
        root_pid = positive(started["pid"])
        birth(started["process_start"])
        identities = {root_pid: {"process_start": started["process_start"]}}
        children = stopped["observed_children"]
        require(isinstance(children, list) and len(children) <= 64, "Finite original process tree required")
        child_evidence = []
        for child in children:
            pid = positive(child["pid"])
            require(pid not in identities and child["exit_observed"] is True, "Conflicting or live original producer child")
            birth(child["process_start"])
            proof_path = file_path(child["evidence_path"])
            require(proof_path.parent == BRIDGE_DIRECTORY, "Child proof directory differs")
            proof = load(proof_path, child["evidence_sha256"])
            require(proof.get("observed_descendant") is True, "Actual descendant observation required")
            for field in ("invocation_id", "pid", "process_start", "request_sha256", "intent_sha256", "process_tree_version"):
                require(proof[field] == started[field], "Child observation binds another launcher")
            require(proof["child_pid"] == pid and proof["child_process_start"] == child["process_start"]
                    and proof["parent_pid"] == child["parent_pid"], "Child native birth or ancestry differs")
            identities[pid] = child
            child_evidence.append({"path": str(proof_path), "sha256": child["evidence_sha256"]})
        for pid in identities:
            parent_chain(pid, root_pid, identities)
        actual_pid = positive(response["bridge_process"]["pid"])
        require(actual_pid == stopped["actual_bridge_pid"] and actual_pid in identities,
                "Actual interpreter does not bind observed native producer tree")
        pids.update(identities)
        evidence.append({"response_path": str(response_path), "response_sha256": digest(response_path),
                         "intent_path": str(intent_path), "intent_sha256": intent_sha,
                         "started_sha256": digest(started_path), "stopped_sha256": digest(stopped_path),
                         "observed_children": child_evidence, "native_pids": sorted(identities)})
    return evidence, pids


def require_producers_absent(pids):
    require(pids and len(pids) <= 260 and EXPECTED_PID not in pids, "Finite producer inventory required")
    # Only integer PIDs enter this fixed read-only CIM query. Command lines and credentials are never emitted.
    wanted = ",".join(str(positive(pid)) for pid in sorted(pids))
    command = ("$ErrorActionPreference='Stop';$wanted=@(" + wanted + ");"
               "$rows=@(Get-CimInstance Win32_Process | Where-Object {$wanted -contains $_.ProcessId} | "
               "Select-Object ProcessId,ParentProcessId,Name,CreationDate);"
               "ConvertTo-Json -InputObject $rows -Compress")
    executable = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    completed = subprocess.run([str(executable), "-NoProfile", "-NonInteractive", "-Command", command],
                               capture_output=True, text=True, timeout=30)
    require(completed.returncode == 0, "Authoritative native producer CIM inventory unavailable")
    current = json.loads(completed.stdout)
    require(isinstance(current, list) and not current, "Observed producer PID is still present; no source mutation allowed")
    return {"method": "authoritative Win32_Process CIM", "checked_pids": sorted(pids),
            "current_matches": current, "checked_at": datetime.now(timezone.utc).isoformat(),
            "pid_reuse_policy": "Presence also rejects reused PID; no unknown process is terminated"}


def ledger_inventory(actual):
    paths = sorted((fixture.common.REPO_ROOT / "var").glob("d101-java-*.sqlite3"))
    require(actual in [path.resolve() for path in paths] and len(paths) <= 100, "Actual finite D101 ledger inventory required")
    return {str(path.resolve()): fixture.require_quiescent_java_ledger(path) for path in paths}


def stage_inputs(first_path, hit_path, actual):
    first, hit = load(first_path), load(hit_path)
    require(first["status"] == "VERIFIED_CANONICAL_FIRST_MISSES"
            and hit["status"] == "VERIFIED_CANONICAL_HITS_ZERO_PUBLISH", "Actual successful FIRST and HIT are prerequisite")
    require(first["task_id"] == hit["task_id"] == "D101" and first["stage"] == "first" and hit["stage"] == "hit",
            "Exact D101 stage identities required")
    require(file_path(first["ledger_path"]) == file_path(hit["ledger_path"]) == actual,
            "FIRST/HIT must bind the same actual native Java ledger")
    source_version = first["source_version_before"]
    require(source_version == first["source_version_after"] == hit["source_version_before"] == hit["source_version_after"]
            and first["target_id"] == hit["target_id"], "Successful source generation or target identity changed")
    require(first["tables_after"] == hit["tables_before"] == hit["tables_after"], "HIT changed the successful five-table frontier")
    for stage, counts in ((first, (0, 2, 2, 2)), (hit, (2, 0, 0, 0))):
        for field in ("actual_owner_hits", "actual_owner_misses", "cache_submitted_rows", "coverage_submitted_rows",
                      "retained_interval_leases", "source_mutations", "formal_written_rows", "java_independent_cache_or_receipt_writes"):
            require(type(stage[field]) is int and stage[field] >= 0, "Exact native stage counters required")
        require(tuple(stage[field] for field in ("actual_owner_hits", "actual_owner_misses", "cache_submitted_rows", "coverage_submitted_rows")) == counts,
                "Actual original-owner FIRST/HIT counters differ")
        require(stage["retained_interval_leases"] == 0 and stage["source_mutations"] == 0
                and stage["formal_written_rows"] == 0 and stage["java_independent_cache_or_receipt_writes"] == 0,
                "Successful stage does not preserve publication/source boundaries")
        cfg = stage["bridge_config"]
        require(file_path(cfg["privateRoot"]) == fixture.ROOT.resolve() and cfg["expectedPid"] == EXPECTED_PID,
                "Successful stage used another private process/root")
        require(stage["source_fixture_sha256"] == digest(INITIAL), "Successful source fixture evidence changed")
    with closing(sqlite3.connect(actual.as_uri() + "?mode=ro", uri=True)) as connection:
        for stage in (first, hit):
            row = connection.execute("SELECT job_id,target_id,frozen_json FROM sync_runs WHERE id=?", (stage["run"]["id"],)).fetchone()
            require(row is not None and row[0] == "data.etf_market_overview_daily_cache" and row[1] == first["target_id"],
                    "Actual FIRST/HIT run is absent from the bound canonical ledger")
            require(json.loads(row[2])["parameters"]["source_version"] == source_version, "Actual frozen Java source vector differs")
    return first, hit


def observed_tables():
    result, raw = {}, {}
    for table in fixture.TABLES:
        value = fixture.state(table)
        rows = fixture.qwp(f"SELECT count() AS n FROM {table}")
        require(len(rows) == 1 and type(rows[0]["n"]) is int and rows[0]["n"] >= 0, "Exact actual table COUNT required")
        p, w = value["physical"], value["wal"]
        result[table] = {"table": table, "id": p["id"], "directory": p["directoryName"],
                         "physicalTxn": p["table_txn"], "metadataRows": p["table_row_count"], "actualRows": rows[0]["n"],
                         "sequenceTxn": w["sequencerTxn"], "writerTxn": w["writerTxn"],
                         "pendingRows": p["wal_pending_row_count"], "bufferedTxns": w["bufferedTxnSize"],
                         "tableSuspended": p["table_suspended"], "walSuspended": w["suspended"]}
        raw[table] = value
    return result, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--first", type=Path, default=FIRST)
    parser.add_argument("--hit", type=Path, default=HIT)
    parser.add_argument("--java-ledger", type=Path, required=True)
    parser.add_argument("--expected-pid", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.expected_pid == EXPECTED_PID, "Only the currently reviewed D101 process is admissible")
    output = args.output.resolve()
    require(output.parent == DIRECTORY, "New preflight evidence must stay in D101 commands")
    result = {"task_id": "D101", "status": "IN_PROGRESS", "purpose": "readonly increment preflight only",
              "database_writes": 0, "formal_operations": 0, "owner_invoked": False, "increment_authorized": False}
    fixture.save_new(output, result)
    try:
        actual = args.java_ledger.resolve(strict=True)
        first, hit = stage_inputs(args.first, args.hit, actual)
        result["input_evidence"] = [{"path": str(path.resolve()), "sha256": digest(path)} for path in (args.first, args.hit, INITIAL)]
        result["ledger_path"], result["target_id"] = str(actual), first["target_id"]
        result["sources_fingerprint"] = first["source_version_before"]
        target = fixture.PrivateTarget(fixture.ROOT, args.expected_pid)
        result["private_target"] = target.identity
        result["ledgers_before"] = ledger_inventory(actual)
        all_pids, proofs = set(), []
        for stage in (first, hit):
            evidence, pids = producer_proofs(stage, actual, result["sources_fingerprint"], result["target_id"])
            proofs.extend(evidence)
            all_pids.update(pids)
        result["producer_evidence"] = proofs
        result["native_processes_before"] = require_producers_absent(all_pids)
        before, raw_before = observed_tables()
        require(before == hit["tables_after"], "Current five-table state differs from successful HIT frontier")
        require(all(before[table]["actualRows"] == 2 for table in (fixture.audit.CACHE, fixture.audit.COVERAGE)), "Both publisher targets must actually contain exactly two rows")
        initial = load(INITIAL)
        require(initial["status"] == "VERIFIED_COMPLETE_REAL_SOURCE_FIXTURE", "Actual initial source fixture is unverified")
        comparisons, sources = 0, {}
        with closing(psycopg2.connect(host="127.0.0.1", port=18832, user="admin", password="quest", dbname="qdb", connect_timeout=10)) as connection:
            connection.autocommit = True
            for table in fixture.audit.SPEC.sources:
                info = initial["source_captures"][table]
                capture_path = file_path(info["path"])
                require(capture_path == DIRECTORY / f"real-source-{table}-initial-20261006.json", "Exact original initial source capture required")
                capture = load(capture_path)
                fields = list(fixture.audit.SOURCE_MODELS[table].get_questdb_schema()["schema"])
                require(capture["table"] == table and capture["fields"] == fields, "Complete original source fields differ")
                expected = fixture.validate_rows(capture["rows"], fields, capture["sha256"])
                require(len(expected) == INITIAL_COUNTS[table] == info["rows"] and capture["sha256"] == info["sha256"], "Original source capture count/SHA differs")
                actual_rows = fixture.records(connection, table, fields)
                values = fixture.strict_compare(actual_rows, expected, fields, ("timestamp", "ts_code"))
                require(fixture.source_sha(actual_rows) == capture["sha256"], "Private complete source SHA differs")
                comparisons += values
                sources[table] = {"rows": len(actual_rows), "fields": fields, "field_comparisons": values,
                                  "full_field_sha256": capture["sha256"], "capture_file_sha256": digest(capture_path)}
        require(comparisons == 136072, "Complete initial source preflight is not 136072 field values")
        after, raw_after = observed_tables()
        require(before == after and raw_before == raw_after, "Five-table physical/WAL state changed during complete SELECT preflight")
        target.verify()
        result["ledgers_after"] = ledger_inventory(actual)
        require(result["ledgers_before"] == result["ledgers_after"], "Java ledger inventory/frontier changed during preflight")
        result["native_processes_after"] = require_producers_absent(all_pids)
        result.update(status="VERIFIED_READONLY_INCREMENT_PREFLIGHT", source_field_comparisons=comparisons,
                      sources=sources, tables_before=before, tables_after=after, raw_tables_before=raw_before,
                      raw_tables_after=raw_after, stable_five_table_frontier=True,
                      checked_at=datetime.now(timezone.utc).isoformat())
    except Exception as error:
        result.update(status="FAILED", error=str(error))
    fixture.save_progress(output, result)
    print(json.dumps({"status": result["status"], "output": str(output), "database_writes": 0}))
    return 0 if result["status"].startswith("VERIFIED") else 1


if __name__ == "__main__":
    raise SystemExit(main())
