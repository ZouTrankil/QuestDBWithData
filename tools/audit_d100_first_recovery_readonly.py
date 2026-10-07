"""D100 failed-first recovery proof. No DDL, publisher or database mutation."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json

import accept_d100_cache_isolated as owner

OUTPUT = owner.DIRECTORY / "cache-isolated-first-recovery-readonly-20261006.json"


def main():
    result = {"task_id": "D100", "status": "IN_PROGRESS", "database_mutations": 0,
              "checked_at": datetime.now(timezone.utc).isoformat(), "expected_pid": 31760}
    client = None
    exit_code = 0
    try:
        failed_bytes = owner.FAILED_FIRST.read_bytes()
        failed = json.loads(failed_bytes)
        result["failed_artifact"] = str(owner.FAILED_FIRST)
        result["failed_artifact_sha256"] = hashlib.sha256(failed_bytes).hexdigest()
        if (failed["status"] != "FAILED" or failed["owner_submissions"] != [] or
                len(failed["ddl_submissions"]) != 2 or
                {row["table"] for row in failed["ddl_submissions"]} != set(owner.MODELS) or
                any(row["ack"] != "ACKNOWLEDGED" or row["automatic_retry"] for row in failed["ddl_submissions"])):
            raise RuntimeError("Only the two acknowledged missing-table DDLs and zero publisher intents may be recovered")
        result["ddl_submissions"] = failed["ddl_submissions"]
        result["owner_submissions"] = failed["owner_submissions"]
        owner.fixture.PRIVATE_TARGET = owner.fixture.PrivateTarget(owner.fixture.ROOT, 31760)
        result["private_target_before"] = owner.fixture.PRIVATE_TARGET.identity
        protected = owner.native.state(True)
        formal = owner.native.state(False)
        if protected != failed["source_mv_alias_before"] or formal != failed["formal_source_mv_alias_before"]:
            raise RuntimeError("Protected private/formal source, MV or alias changed since failed first")
        result["source_mv_alias_before"] = protected
        result["formal_source_mv_alias_before"] = formal
        result["tables_before"] = owner.snapshots()
        if not all(snapshot["settled"] for snapshot in result["tables_before"].values()):
            raise RuntimeError("Original owner tables must be WAL settled")
        result["schema_checks"] = [owner.ensure_owner_table(model, {"ddl_submissions": []}, OUTPUT, False)
                                   for model in owner.MODELS.values()]
        client = owner.OwnerClient({"owner_submissions": [], "phases": []}, OUTPUT)
        result["selected_tables"] = {}
        for table, fields in ((owner.CACHE, owner.FIELDS), (owner.COVERAGE, owner.COVERAGE_FIELDS)):
            count = owner.one(f"SELECT count() AS n FROM {table}")["n"]
            sql = f"SELECT {','.join(fields)} FROM {table} ORDER BY trade_date,source_version LIMIT 201"
            frame = client.fetch_df(sql)
            rows = owner.canonical(frame.to_dict("records"))
            if count != 0 or len(rows) != 0 or tuple(frame.columns) != fields:
                raise RuntimeError("Both actual SELECT count and complete finite field reads must certify zero rows")
            result["selected_tables"][table] = {"fields": list(fields), "select_count": count,
                "full_field_selected_count": len(rows), "row_limit": 201, "records": rows,
                "raw_physical_row_count": result["tables_before"][table]["physical"]["table_row_count"]}
        result["tables_after"] = owner.snapshots()
        result["source_mv_alias_after"] = owner.native.state(True)
        result["formal_source_mv_alias_after"] = owner.native.state(False)
        if (result["tables_before"] != result["tables_after"] or protected != result["source_mv_alias_after"] or
                formal != result["formal_source_mv_alias_after"] or owner.FAILED_FIRST.read_bytes() != failed_bytes):
            raise RuntimeError("Recovery SELECT changed or raced fixed metadata or the retained failed artifact")
        result["private_target_after"] = owner.fixture.PRIVATE_TARGET.verify()
        result["failed_evidence_preserved"] = True
        result["source_mv_alias_formal_unchanged"] = True
        result["stable_physical_and_wal_versions"] = True
        result["status"] = "VERIFIED_EMPTY_TABLES_AFTER_ACKNOWLEDGED_DDL_ONLY"
    except Exception as exc:
        result["status"], result["error"] = "FAILED", str(exc)
        exit_code = 1
    finally:
        if client is not None:
            client.close()
        owner.fixture.save(OUTPUT, owner.canonical(result))
    print(json.dumps({"task_id": "D100", "status": result["status"], "output": str(OUTPUT), "error": result.get("error")}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
