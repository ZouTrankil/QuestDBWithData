"""D105 bounded formal/private ordinary VIEW audit; every database call SELECT.

No view fallback, source/output writer, refresh, provider or registration exists.
Python reference files are read as text only. PGWire preserves native binary64.
"""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sqlite3
import sys

sys.dont_write_bytecode = True
import prepare_d104_macro_core_increment_isolated as legacy

base, common, continued = legacy.base, legacy.audit, legacy.continued
REPO = common.REPO
DIRECTORY = REPO / "artifacts/java-migration/D105/commands"
D104 = REPO / "artifacts/java-migration/D104"
ROOT, PID, BIRTH = base.ROOT, 43084, "2026-10-06T16:04:24.433055Z"
VIEW, BASE = "v_macro_core_monthly", "macro_core_monthly"
PRIVATE_VIEW, PRIVATE_BASE = "java_d105_v_macro_core_monthly_acceptance", base.PRIVATE_OUTPUT
FIELDS, TYPES = common.OUTPUT_FIELDS, common.OUTPUT_TYPES
MONTHS, START, STOP = ("202606", "202607", "202608"), "2026-06-01", "2026-09-01"
OWNER = common.REFERENCE / "sql/questdb/derived/create_monthly_derived_views.sql"
CALLER = common.REFERENCE / "src/quant_platform/data/application/queries/macro.py"
FORMAL_OUTPUT = DIRECTORY / "view-formal-readonly-audit-20261007.json"
PRIVATE_OUTPUT = DIRECTORY / "view-isolated-preflight-20261007.json"
GATE = D104 / "coordinator-review-20261007.json"
GATE_SHA = "a5fd14527a068185f222abf54292c0f8532a50d8ae2eb49f14e4fc6b369d1bde"
DATA_BINDINGS = {
    "gate": (GATE, GATE_SHA),
    "results": (REPO / "docs/migration-tasks-20260929/results/D104.json", "438b7b1baa6427d64a25198171b237fc4ee0b5436f7a5c68db305958e3f80fdf"),
    "java_final": (D104 / "commands/java-increment-acceptance-20261007.json", "b1aec9b83be584059cbe2aad5d18240a14167915b5c37ad9f02568e586c318ca"),
    "terminal": (D104 / "commands/increment-java-process-and-ledger-review-20261007.json", "9242676d38e609336efd4b2c0276e4d43fa5ea1d981e1e993adc5689625251db"),
    "preflight": (D104 / "commands/macro-core-readonly-preflight-20261007.json", "fc4b839c44254aee9f221e1246c5279738cf33704eb12749f2f70691347f530b"),
    "startup": (D104 / "commands/private-server-start-20261007.json", "9dccda7261b680798924e7632c4f8dd646c15e8f0442e737b80ba701f36f51f7"),
}
require, digest, raw_bits = common.require, common.digest, common.raw_bits


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def binding(path):
    path = Path(path).resolve(strict=True)
    return {"path": str(path), "sha256": digest(path)}


def load(path, expected_sha):
    path = Path(path).resolve(strict=True)
    require(path.is_relative_to(REPO.resolve()) and path.stat().st_size <= 32 * 1024 * 1024 and re.fullmatch("[0-9a-f]{64}", str(expected_sha)) and digest(path) == expected_sha, "Immutable workspace evidence path/SHA/size differs")
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=base.unique_json)


def load_binding(item):
    require(isinstance(item, dict) and set(item) == {"path", "sha256"}, "Exact immutable evidence binding required")
    return load(item["path"], item["sha256"])


def save_new(path, value):
    path = Path(path).resolve()
    require(path.parent == DIRECTORY.resolve(), "Only new D105 commands evidence allowed")
    path.parent.mkdir(parents=True, exist_ok=True)
    common.save_new(path, value)


def owner_contract():
    text = OWNER.read_text(encoding="utf-8-sig")
    matches = re.findall(r"CREATE\s+OR\s+REPLACE\s+VIEW\s+v_macro_core_monthly\s+AS\s*\(\s*(.*?)\s*\)\s*;", text, re.I | re.S)
    require(len(matches) == 1 and normalize_sql(matches[0]) == "select * from macro_core_monthly", "Unique original identity-projection VIEW SQL required")
    return {"owner_sql": matches[0].strip(), "owner_sql_sha256": common.transport.sha_bytes(matches[0].strip().encode()), "owner_file": binding(OWNER), "caller_file": binding(CALLER),
            "private_select": "SELECT * FROM " + PRIVATE_BASE, "private_ddl": "CREATE VIEW " + PRIVATE_VIEW + " AS (SELECT * FROM " + PRIVATE_BASE + ")",
            "adaptation": "Only ordinary view/base identifiers replaced; missing-only CREATE instead of original CREATE OR REPLACE. No business computation/filter/refresh.",
            "caller": "MacroQueryService whitelist and fetch_view_first broad-exception base fallback; audit has no fallback.", "partition": "N/A", "view_wal": "N/A", "view_upsert": "N/A", "job_checkpoint_writer": "N/A"}


def normalize_sql(sql):
    require(isinstance(sql, str) and sql.strip() and ";" not in sql, "Ordinary view definition must be one unmodified SELECT")
    value = sql.strip()
    if value.startswith("(") and value.endswith(")"):
        value = value[1:-1].strip()
    return " ".join(value.split()).lower()


def prerequisites():
    data = {name: load(path, sha) for name, (path, sha) in DATA_BINDINGS.items()}
    gate, result, java, terminal = (data[key] for key in ("gate", "results", "java_final", "terminal"))
    require(gate["task_id"] == result["task_id"] == "D104" and gate["decision"] == result["coordinator_gate"] == "accepted_for_serial_progress" and gate["data_validation_status"] == result["data_validation_status"] == "verified" and gate["next_task"] == "D105" and gate["blockers"] == [], "D104 accepted predecessor facts required")
    require(java["status"] == "VERIFIED_ISOLATED_INCREMENTAL" and java["actual_target"]["tableId"] == 15 and java["actual_target"]["physicalTxn"] == 5 and java["actual_target"]["rowCount"] == 3 and java["formal_mutated"] is False, "Exact actual three-month D104 target required")
    require(terminal["status"] == "VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER" and terminal["java_receipt"] == binding(DATA_BINDINGS["java_final"][0]) and terminal["retained_leases"] == 0, "D104 final known JVM/terminal ledger proof required")
    data["ledger"] = load_binding(terminal["ledger_terminal"])
    data["native_stop"] = load_binding(terminal["native"])
    require(data["native_stop"]["original_identity_present"] is False and data["native_stop"]["pid"] == java["jvm_pid"] and base.native.normalize_birth(data["native_stop"]["birth_utc"]) == base.native.normalize_birth(java["jvm_birth_utc"]), "Exact original stopped D104 JVM birth required")
    require(data["ledger"]["all_entries_terminal"] is True and data["ledger"]["leases"] == [] and data["ledger"]["initial_operations_preserved"] is True, "Final original D104 history must stay terminal")
    require(data["preflight"]["status"] == "VERIFIED_BOUNDED_SOURCE_ORACLE" and data["preflight"]["months"] == list(MONTHS), "D104 exact real June-August oracle required")
    return data


class FormalReader(common.ReadOnlyPG):
    def __init__(self, cancel_file=None):
        super().__init__(cancel_file)
        self.visibility_deadline = None


def private_target(root, pid, data):
    require(type(pid) is int and pid == PID and Path(root).resolve(strict=True) == ROOT.resolve(strict=True), "Pinned original D104 private root and current PID required")
    target = base.PrivateTarget(root, pid, *DATA_BINDINGS["startup"])
    identity = target.verify()
    require(base.native.normalize_birth(identity["birth_utc"]) == base.native.normalize_birth(BIRTH) and identity == data["results"]["private_target"], "Exact known D104 server birth/root/ports required")
    attestations = data["java_final"].get("native_attestations")
    require(isinstance(attestations, list) and attestations, "Actual final D104 native listener attestations required")
    for item in attestations:
        require(item.get("attestation_child_stopped") is True and base.validate_listeners(item.get("records"), ROOT.resolve(strict=True), PID, base.native.normalize_birth(BIRTH)) == identity, "Final Java native server identity differs")
    return target


def ledger_snapshot():
    with closing(sqlite3.connect(continued.LEDGER.resolve(strict=True).as_uri() + "?mode=ro", uri=True, timeout=5)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON"); connection.execute("BEGIN")
        result = {}
        for key, (table, order) in legacy.SQLITE_TABLES.items():
            result[key] = [dict(row) for row in connection.execute(f"SELECT * FROM {table} ORDER BY {order} LIMIT 1001").fetchall()]
            require(len(result[key]) <= 1000, "Finite scoped SQLite table census required")
        connection.rollback()
    require(not result["leases"] and all(row["state"] in ("VERIFIED", "VERIFIED_EMPTY", "CANCELLED") for row in result["entries"]), "No unverified D104 entries/retained leases admitted")
    return result


def quiescence(data):
    ledger = ledger_snapshot()
    require(all(ledger[key] == data["ledger"][key] for key in legacy.SQLITE_TABLES), "All five original D104 SQLite tables must be exactly unchanged")
    absence = continued.original_absent({"pid": data["java_final"]["jvm_pid"], "birth_utc": data["java_final"]["jvm_birth_utc"]})
    return {"ledger": ledger, "original_jvm_identity_absence": absence}


def protected_state(reader, isolated, data):
    tables = (*common.SOURCES, PRIVATE_BASE if isolated else BASE)
    states = {table: continued.private_state(reader, table) for table in tables}
    schemas = {}
    for table in tables:
        model = data["preflight"]["original_models"][BASE if table == PRIVATE_BASE else table]
        schemas[table] = reader.records("SELECT * FROM table_columns(%s) LIMIT 101", (table,), cap=100)
        common.validate_schema(schemas[table], states[table], model)
        require(states[table]["settled"], "Source/base WAL must remain settled")
    require(states == {table: continued.private_state(reader, table) for table in tables}, "Seven-table metadata/COUNT changed during schema read")
    if isolated:
        java = data["java_final"]
        legacy.validate_java_source(java["source"]["snapshot"], states, data["preflight"]["original_models"])
        p, w, expected = states[PRIVATE_BASE]["physical"], states[PRIVATE_BASE]["wal"], java["actual_target"]
        actual = {"tableId": p["id"], "directory": p["directoryName"], "physicalTxn": p["table_txn"], "walTxn": w["writerTxn"], "sequenceTxn": w["sequencerTxn"], "writerTxn": w["writerTxn"], "pendingRows": p["wal_pending_row_count"], "bufferedTxns": w["bufferedTxnSize"], "metadataRowCount": p["table_row_count"], "rowCount": states[PRIVATE_BASE]["actual_select_count"]}
        require(all(type(expected[key]) is type(value) and expected[key] == value for key, value in actual.items()), "Private base identity/counters must equal actual final D104 target")
    return {"tables": states, "schemas": schemas}


def view_state(reader, isolated):
    name = PRIVATE_VIEW if isolated else VIEW
    rows = reader.records("SELECT * FROM views() WHERE view_name=%s LIMIT 2", (name,), cap=2)
    require(len(rows) <= 1, "Ordinary view identity ambiguous")
    return rows[0] if rows else None


def validate_view(value, isolated, contract):
    name, source = (PRIVATE_VIEW, PRIVATE_BASE) if isolated else (VIEW, BASE)
    require(value is not None and value.get("view_name") == name and value.get("view_status") == "valid" and value.get("invalidation_reason") is None and normalize_sql(value.get("view_sql")) == "select * from " + source and all(isinstance(value.get(key), str) and value[key].strip() for key in ("view_table_dir_name", "view_status_update_time")), "View missing/invalid/definition or physical history differs; no base fallback")


def view_schema(reader, isolated):
    rows = reader.records("SELECT * FROM table_columns(%s) LIMIT 10", (PRIVATE_VIEW if isolated else VIEW,), cap=9)
    require([row["column"] for row in rows] == list(FIELDS) and {row["column"]: row["type"] for row in rows} == TYPES, "Exact ordered nine-field VIEW schema required")
    require(all(type(row["designated"]) is type(row["upsertKey"]) is bool and row["designated"] == (row["column"] == "month") and row["upsertKey"] is False for row in rows), "Actual ordinary VIEW timestamp and no-UPSERT flags differ")
    return rows  # Native flags are evidence; ordinary-view UPSERT/WAL are N/A.


def view_physical(reader, isolated, metadata):
    name = PRIVATE_VIEW if isolated else VIEW
    rows = reader.records("SELECT id,directoryName,partitionBy,walEnabled,dedup,matView,designatedTimestamp FROM tables() WHERE table_name=%s LIMIT 2", (name,), cap=2)
    require(len(rows) == (0 if metadata is None else 1), "Ordinary VIEW native identity missing/ambiguous")
    if not rows:
        return None
    value = rows[0]
    require(type(value.get("id")) is int and value["id"] > 0 and value.get("directoryName") == metadata["view_table_dir_name"] and value.get("partitionBy") == "N/A" and value.get("designatedTimestamp") == "month" and type(value.get("walEnabled")) is bool and value.get("dedup") is False and value.get("matView") is False, "Actual ordinary VIEW native metadata differs")
    return value


def finite_rows(reader, table, start=START, stop=STOP):
    require(table in (VIEW, BASE, PRIVATE_VIEW, PRIVATE_BASE), "Only fixed D105 view/base SELECT targets allowed")
    require(isinstance(start, str) and isinstance(stop, str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-01", start) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-01", stop), "Exact first-calendar-day monthly interval required")
    upper_date = datetime.fromisoformat(stop)
    last = upper_date.year * 12 + upper_date.month - 2
    _months, lower, upper = common.month_window(start[:7].replace("-", ""), f"{last // 12:04d}{last % 12 + 1:02d}")
    require((lower, upper) == (start, stop), "At most twelve closed months in exact interval required")
    sql = f"SELECT {','.join(FIELDS)} FROM {table} WHERE month >= %s AND month < %s ORDER BY month LIMIT 13"
    rows = reader.records(sql, (lower, upper), cap=12)
    require(len(rows) <= 12, "At most twelve result months admitted")
    keys = []
    for row in rows:
        require(tuple(row) == FIELDS, "All nine explicit ordered values required")
        keys.append(common.month_key(row["month"]))
        require(lower <= row["month"][:10] < upper, "Returned month outside exact requested interval")
        for field in FIELDS[1:]:
            raw_bits(row[field])
    require(keys == sorted(set(keys)), "Duplicate/unordered monthly view keys refused")
    return rows


def compare(left, right, expected):
    proof = common.compare_output(left, right)
    require(proof["passed"] and common.compare_output(left, expected)["passed"], "Actual view/base/original frozen oracle values differ (tolerance0)")
    proof["nonnull_double_rawbit_comparisons"] = sum(row[field] is not None for row in left for field in FIELDS[1:])
    proof["null_double_comparisons"] = proof["nullable_double_slot_comparisons"] - proof["nonnull_double_rawbit_comparisons"]
    return proof


def read_windows(reader, isolated, data, include_view=True):
    source, name = (PRIVATE_BASE, PRIVATE_VIEW) if isolated else (BASE, VIEW)
    oracle = data["preflight"]["expected_oracle_rows"]
    phases = []
    for label, start, stop, expected in (("initial_june_july", START, "2026-08-01", oracle[:2]), ("repeat_june_july", START, "2026-08-01", oracle[:2]), ("july_august_window", "2026-07-01", STOP, oracle[1:]), ("full_june_august", START, STOP, oracle)):
        rows = finite_rows(reader, source, start, stop)
        views = finite_rows(reader, name, start, stop) if include_view else []
        phases.append({"phase": label, "from_inclusive": start, "to_exclusive": stop, "base_rows": rows, "actual_rows": views, "view_read_performed": include_view,
                       "parity": compare(views, rows, expected) if include_view else None, "base_oracle_parity": compare(rows, rows, expected), "source_writes": 0, "output_writes": 0})
    return phases


def audit_target(reader, isolated, data, cancel_file=None, allow_missing=False):
    common.check_cancel(cancel_file)
    contract = owner_contract()
    before = protected_state(reader, isolated, data); view_before = view_state(reader, isolated)
    if view_before is None:
        require(isolated and allow_missing, "Formal/view acceptance requires actual VIEW; no fallback")
        schema = None
    else:
        validate_view(view_before, isolated, contract); schema = view_schema(reader, isolated)
    physical = view_physical(reader, isolated, view_before)
    phases = read_windows(reader, isolated, data, include_view=view_before is not None)
    source_parity = None
    if isolated:
        whole = base.captured_sources(data["preflight"])
        source_parity = {table: base.strict_compare(base.source_rows(reader, table, data["preflight"]["original_models"][table]), whole[table], table, data["preflight"]["original_models"][table]) for table in common.SOURCES}
    view_after = view_state(reader, isolated)
    schema_after = view_schema(reader, isolated) if view_after is not None else None
    physical_after = view_physical(reader, isolated, view_after)
    after = protected_state(reader, isolated, data)
    require(view_before == view_after and schema == schema_after and physical == physical_after and before == after, "Ordinary view/schema/protected seven-table frontier drifted during full readback")
    actual = phases[-1]["actual_rows"]
    return {"status": "VERIFIED_ISOLATED_VIEW_ABSENCE_PREFLIGHT" if view_before is None else "VERIFIED_ISOLATED_VIEW_READ" if isolated else "VERIFIED_FORMAL_VIEW_READ",
            "private_before" if isolated else "formal_before": before, "private_after" if isolated else "formal_after": after, "view_metadata": view_before, "view_metadata_after": view_after, "view_schema": schema, "view_schema_after": schema_after, "view_physical_metadata": physical, "view_physical_metadata_after": physical_after,
            "owner_contract": contract, "phases": phases, "actual_rows": actual, "actual_double_bits": [{field: raw_bits(row[field]) for field in FIELDS[1:]} for row in actual], "complete_source_readback": source_parity,
            "rows": 3 if view_before is not None else 0, "base_rows": phases[-1]["base_rows"], "full_field_comparisons": 27 if view_before is not None else 0,
            "nullable_double_slot_comparisons": 24 if view_before is not None else 0, "nonnull_double_rawbit_comparisons": 22 if view_before is not None else 0, "null_double_comparisons": 2 if view_before is not None else 0,
            "double_tolerance": 0, "actual_source_increment": False, "actual_source_revision": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("formal", "private"), required=True)
    parser.add_argument("--private-root", type=Path)
    parser.add_argument("--expected-pid", type=int)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--cancel-file")
    args = parser.parse_args(); isolated = args.scope == "private"
    output = args.output or (PRIVATE_OUTPUT if isolated else FORMAL_OUTPUT)
    require(output.resolve().parent == DIRECTORY.resolve() and not output.exists(), "CREATE_NEW D105 readonly audit identity required")
    result = {"task_id": "D105", "status": "FAILED", "checked_at": utc_now(), "scope": args.scope, "actions": ["SELECT only"], "DDL": 0, "DML": 0, "ILP": 0, "base_writes": 0, "formal_writes": 0, "ledger_mutated": False, "reference_project_mutated": False, "automatic_retry": False, "script_sha256": digest(__file__)}
    reader = None
    try:
        data = prerequisites(); result["d104_evidence"] = {key: {"path": str(path.resolve()), "sha256": sha} for key, (path, sha) in DATA_BINDINGS.items()}
        if isolated:
            require(args.private_root is not None and args.expected_pid is not None, "Private audit requires explicit root/currentPID")
            target = private_target(args.private_root, args.expected_pid, data)
            result["private_target_attestation"] = target.verify(); result["quiescence_before"] = quiescence(data)
            reader = base.PrivateReader(target, args.cancel_file)
        else:
            require(args.private_root is None and args.expected_pid is None, "Formal audit has no private/native target arguments")
            reader = FormalReader(args.cancel_file)
        result.update(audit_target(reader, isolated, data, args.cancel_file, allow_missing=isolated))
        if isolated:
            result["quiescence_after"] = quiescence(data); result["private_target_attestation_after"] = target.verify()
            require(result["quiescence_before"]["ledger"] == result["quiescence_after"]["ledger"], "Readonly audit must not mutate original five SQLite tables")
        require(digest(__file__) == result["script_sha256"], "Audit implementation changed during execution")
    except BaseException as failure:
        result["status"] = "FAILED"; result["error"] = {"type": type(failure).__name__, "message": str(failure)}
    finally:
        if reader is not None:
            reader.close()
        save_new(output, result)
    print(json.dumps({"task_id": "D105", "status": result["status"], "output": str(output.resolve()), "sha256": digest(output), "DDL": 0, "base_writes": 0, "formal_writes": 0}))
    return 0 if result["status"].startswith("VERIFIED_") else 1


if __name__ == "__main__":
    raise SystemExit(main())
