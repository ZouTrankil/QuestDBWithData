"""D096 SELECT-only formal audit and explicitly enabled private alias acceptance.

The only mutation is CREATE VIEW on the attested D095 private process. Neither
base data nor MV lifecycle is changed. Formal requests reuse D095's SELECT gate.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

import accept_d095_mv_isolated as fixture

ALIAS = "v_market_breadth_daily"
MV = fixture.MV
FIELDS = ("trade_date", "stock_count", "up_count", "down_count", "flat_count",
          "avg_pct_change", "total_amount_yi")
TYPES = dict(zip(FIELDS, ("TIMESTAMP", "LONG", "LONG", "LONG", "LONG", "DOUBLE", "DOUBLE")))
START, STOP = "2026-09-17", "2026-09-22"
EXPECTED_DAYS = ("2026-09-17", "2026-09-18", "2026-09-21")
EXPECTED_ROWS = 16658
PRIVATE_ROOT = fixture.REPO_ROOT / "var/d095-isolated-questdb"
PROVENANCE = fixture.REPO_ROOT / "artifacts/java-migration/D095/commands/java-materialize-acceptance-20261006.json"
ALIAS_SELECT = f"SELECT * FROM {MV}"
BOUND = f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}'"


def sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalized(value):
    return re.sub(r"\s+", "", value).lower().strip(";()")


def one(sql, isolated):
    rows = fixture.qwp(sql, isolated=isolated)
    if len(rows) != 1:
        raise RuntimeError(f"Exactly one metadata/result row required: {sql}")
    return rows[0]


def select_fields(row, fields):
    return {field: row.get(field) for field in fields}


def state(isolated):
    tables, wal = {}, {}
    for name in ("stk_factor", MV):
        tables[name] = select_fields(fixture.table_snapshot(name, isolated=isolated), (
            "id", "directoryName", "table_txn", "table_row_count", "table_min_timestamp",
            "table_max_timestamp", "table_suspended", "wal_pending_row_count"))
        wal[name] = select_fields(one(f"SELECT * FROM wal_tables() WHERE name='{name}'", isolated), (
            "sequencerTxn", "writerTxn", "bufferedTxnSize", "suspended"))
    mv = one(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'", isolated)
    mv_state = select_fields(mv, ("view_name", "view_status", "invalidation_reason", "base_table_name",
        "view_directory_name", "refresh_base_table_txn", "base_table_txn", "refresh_type",
        "timer_interval", "timer_interval_unit"))
    mv_state["view_sql_sha256"] = sha(mv["view_sql"])
    mv_state["definition_matches_python"] = normalized(mv["view_sql"]) == normalized(
        fixture.VIEW_SELECTS[ALIAS].format(where=""))
    aliases = fixture.qwp(f"SELECT * FROM views() WHERE view_name='{ALIAS}'", isolated=isolated)
    if len(aliases) > 1:
        raise RuntimeError("Ambiguous ordinary alias identity")
    return {"tables": tables, "wal": wal, "mv": mv_state,
            "alias": aliases[0] if aliases else None}


def ready(observed):
    mv = observed["mv"]
    if (mv["view_status"] != "valid" or mv["invalidation_reason"] or
            mv["base_table_name"] != "stk_factor" or not mv["definition_matches_python"] or
            mv["refresh_type"] != "timer" or mv["timer_interval"] != 1 or
            mv["timer_interval_unit"] != "MINUTE"):
        return False
    for name in ("stk_factor", MV):
        table, wal = observed["tables"][name], observed["wal"][name]
        if (table["table_suspended"] or wal["suspended"] or
                table["wal_pending_row_count"] != 0 or wal["bufferedTxnSize"] != 0 or
                wal["writerTxn"] != wal["sequencerTxn"]):
            return False
    return (mv["refresh_base_table_txn"] == observed["wal"]["stk_factor"]["sequencerTxn"] and
            mv["base_table_txn"] == observed["wal"]["stk_factor"]["sequencerTxn"])


def schema(name, isolated):
    rows = fixture.qwp(f"SELECT * FROM table_columns('{name}')", isolated=isolated)
    actual = {row["column"]: row["type"] for row in rows}
    if tuple(row["column"] for row in rows) != FIELDS or actual != TYPES:
        raise RuntimeError(f"{name}: seven-field schema drift: {actual}")
    return actual


def comparison(left, right):
    try:
        count = fixture.compare_fields(left, right, ("trade_date",), FIELDS)
        return {"passed": True, "rows": len(right), "fields": list(FIELDS), "field_comparisons": count}
    except RuntimeError as exc:
        return {"passed": False, "error": str(exc), "left_rows": len(left), "right_rows": len(right)}


def audit(isolated):
    before = state(isolated)
    if before["alias"] is None:
        raise RuntimeError("Public ordinary alias is missing")
    alias = before["alias"]
    if normalized(alias["view_sql"]) != normalized(ALIAS_SELECT):
        raise RuntimeError("Public alias differs from registered MV binding")
    schemas = {name: schema(name, isolated) for name in (ALIAS, MV)}
    count = one(f"SELECT count() AS n FROM stk_factor {BOUND}", isolated)["n"]
    rows = {}
    for name in (ALIAS, MV):
        rows[name] = fixture.qwp(f"SELECT {','.join(FIELDS)} FROM {name} {BOUND} ORDER BY trade_date",
                                 isolated=isolated)
    rows["direct_base"] = fixture.qwp(fixture.VIEW_SELECTS[ALIAS].format(where=BOUND) +
                                      " ORDER BY trade_date", isolated=isolated)
    after = state(isolated)
    if before != after:
        raise RuntimeError("Source, MV or public alias changed during SELECT parity; rerun read audit")
    comparisons = {"alias_vs_mv": comparison(rows[ALIAS], rows[MV]),
                   "alias_vs_direct_base": comparison(rows[ALIAS], rows["direct_base"]),
                   "mv_vs_direct_base": comparison(rows[MV], rows["direct_base"])}
    validated = (ready(after) and alias.get("view_status") == "valid" and
                 not alias.get("invalidation_reason") and all(c["passed"] for c in comparisons.values()))
    result = {"target": "private-127.0.0.1:19000" if isolated else "formal-127.0.0.1:9000",
        "operations": "SELECT only", "range_start_inclusive": START, "range_stop_exclusive": STOP,
        "source_bounded_rows": count, "schemas": schemas,
        "seven_field_schema_sha256": sha(json.dumps(schemas, sort_keys=True)), "snapshot": after,
        "version_stable": True, "actual_alias_view_sql": alias["view_sql"],
        "actual_alias_view_sql_sha256": sha(alias["view_sql"]),
        "registered_alias_select_sha256": sha(ALIAS_SELECT), "comparisons": comparisons,
        "rows": rows, "mv_ready": ready(after), "target_validated": validated,
        "ordinary_view_wal_dedup": "not applicable; projection is owned by D095 MV"}
    if isolated:
        days = tuple(str(row["trade_date"])[:10] for row in rows["direct_base"])
        if (not validated or count != EXPECTED_ROWS or days != EXPECTED_DAYS or
                after["tables"]["stk_factor"]["table_row_count"] != EXPECTED_ROWS or
                sum(row["stock_count"] for row in rows["direct_base"]) != EXPECTED_ROWS):
            raise RuntimeError("Private acceptance requires valid/caught-up MV and all 16658 real rows in three buckets")
    return result


def ensure_private_alias(root):
    root = root.resolve(strict=True)
    if root != PRIVATE_ROOT.resolve(strict=True):
        raise RuntimeError("D096 alias creation is pinned to workspace/var/d095-isolated-questdb")
    fixture.PRIVATE_TARGET = fixture.PrivateTarget(root)
    marker = json.loads((root / "d095-fixture.json").read_text(encoding="utf-8"))
    expected_marker = {"task_id": "D095", "data_root": str(root), "fixture_tables": ["stk_factor", MV]}
    if marker != expected_marker:
        raise RuntimeError("D095 private fixture ownership marker differs")
    observed = state(True)
    if not ready(observed) or observed["tables"]["stk_factor"]["table_row_count"] != EXPECTED_ROWS:
        raise RuntimeError("Private fixture must already contain the D095 validated 16658 rows and ready MV")
    if observed["alias"] is not None:
        if normalized(observed["alias"]["view_sql"]) != normalized(ALIAS_SELECT):
            raise RuntimeError("Existing private alias differs; it will not be replaced")
        return False
    fixture.qwp(f"CREATE VIEW {ALIAS} AS ({ALIAS_SELECT})", isolated=True)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-isolated", action="store_true",
                        help="create the missing alias only on the attested D095 private target and compare all seven fields")
    parser.add_argument("--private-root", type=Path, default=PRIVATE_ROOT)
    parser.add_argument("--output", type=Path, default=fixture.REPO_ROOT /
                        "artifacts/java-migration/D096/commands/alias-audit-20261006.json")
    args = parser.parse_args()
    result = {"task_id": "D096", "checked_at": datetime.now(timezone.utc).isoformat(),
              "formal_mutated": False, "execute_isolated": args.execute_isolated,
              "python_owner_definition_sha256": sha(fixture.VIEW_SELECTS[ALIAS].format(where="")),
              "d095_real_source_evidence": str(PROVENANCE),
              "d095_real_source_evidence_sha256": hashlib.sha256(PROVENANCE.read_bytes()).hexdigest()}
    exit_code = 0
    try:
        result["formal"] = audit(False)
        result["status"] = "READ_ONLY_AUDIT"
        if args.execute_isolated:
            created = ensure_private_alias(args.private_root)
            result["isolated"] = audit(True)
            result["isolated"]["alias_created"] = created
            result["isolated"]["operations"] = "CREATE VIEW when missing; all acceptance queries SELECT only"
            result["isolated"]["private_target_attestation"] = fixture.PRIVATE_TARGET.verify()
            result["status"] = "VERIFIED_ISOLATED"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        exit_code = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"task_id": "D096", "status": result["status"], "formal_mutated": False,
                      "output": str(args.output), "error": result.get("error")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
