"""D099 formal SELECT audit and explicit missing-only private ordinary alias acceptance.

The sole mutation is the fixed CREATE VIEW on the attested D098 private process.
No source INSERT, MV refresh, cache publisher, or formal mutation is supported.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import shutil
from pathlib import Path
import struct

import accept_d098_mv_isolated as fixture

parent = fixture.audit
SOURCE, MV, ALIAS = parent.SOURCE, parent.MV, parent.ALIAS
FIELDS, TYPES = parent.OUTPUT_FIELDS, parent.OUTPUT_TYPES
ROOT = fixture.ROOT
EXPECTED_PID = 37904
START, STOP = "2026-09-17", "2026-09-22"
EXPECTED_DAYS = ("2026-09-17", "2026-09-18", "2026-09-21")
EXPECTED_ROWS = 23773
ALIAS_SELECT = f"SELECT * FROM {MV}"
ALIAS_DDL = f"CREATE VIEW {ALIAS} AS ({ALIAS_SELECT})"
OUTPUT = fixture.common.REPO_ROOT / "artifacts/java-migration/D099/commands/alias-audit-20261006.json"
PROVENANCE = fixture.common.REPO_ROOT / "artifacts/java-migration/D098/coordinator-review-20261006.json"


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def query(sql, isolated):
    if not sql.split() or sql.split(None, 1)[0].upper() != "SELECT" or ";" in sql:
        raise RuntimeError("D099 audit accepts one SELECT only")
    return fixture.qwp(sql) if isolated else parent.query(sql)


def one(sql, isolated):
    rows = query(sql, isolated)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one metadata/result row required: {sql}")
    return rows[0]


def state(isolated):
    observed = {"tables": {}, "wal": {}}
    for table in (SOURCE, MV):
        raw = one(f"SELECT * FROM tables() WHERE table_name='{table}'", isolated)
        observed["tables"][table] = {field: raw[field] for field in (
            "id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp", "table_max_timestamp",
            "partitionBy", "walEnabled", "dedup", "designatedTimestamp", "table_suspended", "wal_pending_row_count")}
        raw = one(f"SELECT * FROM wal_tables() WHERE name='{table}'", isolated)
        observed["wal"][table] = {field: raw[field] for field in (
            "sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended")}
    raw = one(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'", isolated)
    observed["mv"] = {field: raw[field] for field in (
        "view_name", "view_status", "invalidation_reason", "base_table_name", "view_table_dir_name",
        "refresh_base_table_txn", "base_table_txn", "refresh_type", "timer_interval", "timer_interval_unit", "view_sql")}
    observed["mv"]["view_sql_sha256"] = sha(raw["view_sql"])
    observed["mv"]["definition_matches_python_exact"] = raw["view_sql"].strip() == parent.OWNER_SQL.strip()
    aliases = query(f"SELECT * FROM views() WHERE view_name='{ALIAS}'", isolated)
    if len(aliases) > 1:
        raise RuntimeError("Ambiguous ordinary alias identity")
    observed["alias"] = aliases[0] if aliases else None
    return observed


def ready(observed):
    mv = observed["mv"]
    if (mv["view_status"] != "valid" or mv["invalidation_reason"] or mv["base_table_name"] != SOURCE or
            not mv["definition_matches_python_exact"] or mv["refresh_type"] != "timer" or
            mv["timer_interval"] != 1 or mv["timer_interval_unit"] != "MINUTE"):
        return False
    for table in (SOURCE, MV):
        physical, wal = observed["tables"][table], observed["wal"][table]
        if (physical["table_suspended"] or physical["wal_pending_row_count"] != 0 or wal["suspended"] or
                wal["bufferedTxnSize"] != 0 or wal["writerTxn"] != wal["sequencerTxn"]):
            return False
    version = observed["wal"][SOURCE]["sequencerTxn"]
    return mv["refresh_base_table_txn"] == version and mv["base_table_txn"] == version


def schema(table, isolated):
    if table not in (ALIAS, MV):
        raise RuntimeError("D099 schema reads are pinned to its alias and parent")
    columns = query(f"SELECT * FROM table_columns('{table}')", isolated)
    actual = {row["column"]: row["type"] for row in columns}
    if tuple(actual) != FIELDS or actual != TYPES:
        raise RuntimeError(f"{table}: thirteen-field schema drift: {actual}")
    return actual


def rows_by_key(rows):
    for index, row in enumerate(rows):
        if tuple(row) != FIELDS or not row["trade_date"] or not isinstance(row["trade_date"], str):
            raise RuntimeError(f"Incomplete thirteen-field daily record at {index}")
        timestamp = datetime.fromisoformat(row["trade_date"].replace("Z", "+00:00"))
        if timestamp.tzinfo is None or timestamp.utcoffset().total_seconds() != 0 or timestamp.time().isoformat() != "00:00:00":
            raise RuntimeError("Daily identity must be complete UTC midnight")
        for field, kind in TYPES.items():
            value = row[field]
            if value is None or kind == "TIMESTAMP":
                continue
            if kind == "LONG" and (not isinstance(value, int) or isinstance(value, bool) or value < 0):
                raise RuntimeError(f"Nullable LONG counts must remain exact and nonnegative: {field}")
            if kind == "DOUBLE" and (isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value)):
                raise RuntimeError(f"Nullable DOUBLE must be finite: {field}")
    return fixture.common.keyed(rows, ("trade_date",))


def comparison(left, right, exact_double=False):
    result = {"passed": False, "left_rows": len(left), "right_rows": len(right), "fields": list(FIELDS),
              "field_comparisons": 0, "exact_double": exact_double,
              "double_absolute_tolerance": 0 if exact_double else 1e-8,
              "double_relative_tolerance": 0 if exact_double else 1e-10, "mismatches": []}
    try:
        actual, expected = rows_by_key(left), rows_by_key(right)
        result["missing_dates"] = sorted(key[0] for key in expected.keys() - actual.keys())
        result["additional_dates"] = sorted(key[0] for key in actual.keys() - expected.keys())
        for key in sorted(actual.keys() & expected.keys()):
            for field, kind in TYPES.items():
                a, e = actual[key][field], expected[key][field]
                if a is None or e is None:
                    equal = a is None and e is None
                elif kind == "DOUBLE":
                    equal = (struct.pack(">d", float(a)) == struct.pack(">d", float(e)) if exact_double else
                             math.isclose(a, e, abs_tol=1e-8, rel_tol=1e-10))
                else:
                    equal = a == e
                result["field_comparisons"] += 1
                if not equal:
                    result["mismatches"].append({"trade_date": key[0], "field": field, "actual": a, "expected": e})
        result["passed"] = bool(expected) and not result["missing_dates"] and not result["additional_dates"] and not result["mismatches"]
    except (RuntimeError, ValueError) as exc:
        result["error"] = str(exc)
    return result


def source_contract(isolated, observed):
    columns = query(f"SELECT * FROM table_columns('{SOURCE}')", isolated)
    needed = {row["column"]: row["type"] for row in columns if row["column"] in parent.SOURCE_TYPES}
    physical = observed["tables"][SOURCE]
    if (needed != parent.SOURCE_TYPES or {row["column"] for row in columns if row["upsertKey"]} != {"ts", "symbol"} or
            [row["column"] for row in columns if row["designated"]] != ["ts"] or
            physical["partitionBy"] != "DAY" or not physical["walEnabled"] or not physical["dedup"]):
        raise RuntimeError("Parent real source DAY/WAL/complete-key or required field contract drifted")
    return {"required_types": needed, "physical_column_count": len(columns), "complete_key": ["ts", "symbol"]}


def audit(isolated, result):
    before = state(isolated)
    result["snapshot_before"] = before
    alias = before["alias"]
    if alias is None or alias["view_sql"].strip() != ALIAS_SELECT:
        raise RuntimeError("Ordinary alias is missing or differs from the exact registered parent binding")
    result["schemas"] = {table: schema(table, isolated) for table in (ALIAS, MV)}
    result["thirteen_field_schema_sha256"] = sha(json.dumps(result["schemas"], sort_keys=True))
    result["source_contract"] = source_contract(isolated, before)
    bound_source = f"WHERE ts >= '{START}' AND ts < '{STOP}'"
    count = one(f"SELECT count() AS n FROM {SOURCE} {bound_source}", isolated)["n"]
    if count < 1 or count > 50000:
        raise RuntimeError("Source window must contain 1..50000 actual rows")
    result["source_bounded_rows"] = count
    result["source_daily_counts"] = query(f"SELECT ts AS trade_date,count() AS n FROM {SOURCE} {bound_source} "
                                         "SAMPLE BY 1d ALIGN TO CALENDAR ORDER BY trade_date", isolated)
    rows = result["rows"] = {}
    for table in (ALIAS, MV):
        rows[table] = query(f"SELECT {','.join(FIELDS)} FROM {table} WHERE trade_date >= '{START}' "
                            f"AND trade_date < '{STOP}' ORDER BY trade_date", isolated)
    rows["direct_base"] = query(fixture.common.VIEW_SELECTS[ALIAS].format(where=bound_source) + " ORDER BY trade_date", isolated)
    result["comparisons"] = {"alias_vs_mv": comparison(rows[ALIAS], rows[MV], exact_double=True),
                             "alias_vs_direct_base": comparison(rows[ALIAS], rows["direct_base"]),
                             "mv_vs_direct_base": comparison(rows[MV], rows["direct_base"])}
    result["schemas_after"] = {table: schema(table, isolated) for table in (ALIAS, MV)}
    after = state(isolated)
    result["snapshot_after"] = after
    result["version_stable"] = before == after and result["schemas"] == result["schemas_after"]
    result["mv_ready"] = ready(after)
    result["actual_alias_view_sql"] = alias["view_sql"]
    result["actual_alias_view_sql_sha256"] = sha(alias["view_sql"])
    result["registered_alias_select_sha256"] = sha(ALIAS_SELECT)
    result["target_validated"] = (result["version_stable"] and result["mv_ready"] and alias["view_status"] == "valid" and
                                  not alias.get("invalidation_reason") and all(c["passed"] for c in result["comparisons"].values()))
    result["target"] = "private-127.0.0.1:19010/18822" if isolated else "formal-127.0.0.1:9000"
    result["operations"] = "SELECT only"
    result["range_start_inclusive"], result["range_stop_exclusive"] = START, STOP
    result["ordinary_view_storage"] = "partition/WAL/DEDUP/direct writes N/A; storage and native refresh belong to D098 MV"
    if not result["version_stable"]:
        raise RuntimeError("Source, parent MV, ordinary alias or schema changed during all-field SELECT parity")
    if isolated:
        dates = tuple(row["trade_date"][:10] for row in rows["direct_base"])
        daily = result["source_daily_counts"]
        if (not result["target_validated"] or count != EXPECTED_ROWS or dates != EXPECTED_DAYS or
                after["tables"][SOURCE]["table_row_count"] != EXPECTED_ROWS or
                tuple(row["trade_date"][:10] for row in daily) != EXPECTED_DAYS or sum(row["n"] for row in daily) != count):
            raise RuntimeError("Private alias acceptance requires valid/caught-up parent and all 23773 real rows in three daily buckets")


def ensure_private_alias(root, expected_pid, output, result):
    isolated = result.setdefault("isolated", {})
    if root.resolve(strict=True) != ROOT.resolve(strict=True):
        raise RuntimeError("D099 alias creation is pinned to workspace/var/d098-isolated-questdb")
    fixture.PRIVATE_TARGET = fixture.PrivateTarget(root, expected_pid)
    isolated["private_target_attestation"] = fixture.PRIVATE_TARGET.identity
    observed = state(True)
    isolated["protected_snapshot_before_ddl"] = observed
    if not ready(observed) or observed["tables"][SOURCE]["table_row_count"] != EXPECTED_ROWS:
        raise RuntimeError("D098 private parent must already be valid/caught-up with all 23773 real source rows")
    schema(MV, True)
    source_contract(True, observed)
    existing = observed["alias"]
    if existing is not None:
        if existing["view_sql"].strip() != ALIAS_SELECT or existing["view_status"] != "valid" or existing.get("invalidation_reason"):
            raise RuntimeError("Existing private alias differs or is invalid; it will not be replaced")
        isolated["alias_created"] = False
        return
    if state(True) != observed:
        raise RuntimeError("Protected source/parent state changed before missing alias creation")
    isolated["alias_submission"] = {"sql": ALIAS_DDL, "ack": "UNKNOWN", "automatic_retry": False,
                                   "operation": "one missing-only ordinary alias CREATE; no source/MV writes"}
    fixture.save(output, result)
    fixture.qwp(ALIAS_DDL)  # Reattests PID, root and both listeners immediately before the sole mutation.
    isolated["alias_submission"]["ack"] = "ACKNOWLEDGED"
    fixture.save(output, result)
    after = state(True)
    isolated["protected_snapshot_after_ddl"] = after
    if {key: value for key, value in observed.items() if key != "alias"} != {key: value for key, value in after.items() if key != "alias"}:
        raise RuntimeError("Protected source or parent MV changed while installing the ordinary alias")
    isolated["alias_created"] = True


def preserve_previous_output(output):
    if not output.exists():
        return None
    previous = json.loads(output.read_text(encoding="utf-8"))
    submission = previous.get("isolated", {}).get("alias_submission", {})
    if submission.get("ack") == "UNKNOWN":
        raise RuntimeError("Existing alias DDL UNKNOWN evidence is unresolved; preserve it and verify explicitly before another submission")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    archived = output.with_name(f"{output.stem}.previous-{stamp}{output.suffix}")
    shutil.copyfile(output, archived)
    return archived


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true")
    parser.add_argument("--private-root", type=Path, default=ROOT)
    parser.add_argument("--expected-pid", type=int, default=EXPECTED_PID)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    try:
        prior_output = preserve_previous_output(args.output)
    except (RuntimeError, OSError, ValueError) as exc:
        print(json.dumps({"task_id": "D099", "status": "FAILED", "formal_mutated": False,
                          "output": str(args.output), "error": str(exc), "existing_evidence_preserved": True}))
        return 1
    result = {"task_id": "D099", "checked_at": datetime.now(timezone.utc).isoformat(), "formal_mutated": False,
              "source_written_rows": 0, "mv_refresh_submitted": False, "execute_isolated": args.execute_isolated,
              "prior_output_preserved": str(prior_output) if prior_output else None,
              "python_owner_definition_sha256": sha(parent.OWNER_SQL),
              "d098_coordinator_evidence": str(PROVENANCE),
              "formal": {}}
    exit_code = 0
    try:
        result["d098_coordinator_evidence_sha256"] = hashlib.sha256(PROVENANCE.read_bytes()).hexdigest()
        gate = json.loads(PROVENANCE.read_text(encoding="utf-8"))
        if gate["task_id"] != "D098" or gate["decision"] != "accepted_for_serial_progress":
            raise RuntimeError("D098 coordinator prerequisite is not accepted")
        audit(False, result["formal"])
        result["status"] = "READ_ONLY_AUDIT"
        if args.execute_isolated:
            result["isolated"] = {}
            ensure_private_alias(args.private_root, args.expected_pid, args.output, result)
            audit(True, result["isolated"])
            result["isolated"]["private_target_attestation"] = fixture.PRIVATE_TARGET.verify()
            result["formal_snapshot_after_isolated"] = state(False)
            if result["formal_snapshot_after_isolated"] != result["formal"]["snapshot_after"]:
                raise RuntimeError("Formal source/MV/alias metadata changed during isolated acceptance")
            result["status"] = "VERIFIED_ISOLATED"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    fixture.save(args.output, result)
    print(json.dumps({"task_id": "D099", "status": result["status"], "formal_mutated": False,
                      "formal_validated": result["formal"].get("target_validated"),
                      "isolated_validated": result.get("isolated", {}).get("target_validated"),
                      "output": str(args.output), "error": result.get("error")}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
