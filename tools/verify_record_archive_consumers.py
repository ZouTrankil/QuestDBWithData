"""Cold-process verification of deployed record stores and real application read routers."""
import argparse
import json
from pathlib import Path
import sys

from migrate_questdb_record_archives import check_integrity, digest, fingerprint, load_snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-root", type=Path, required=True)
    parser.add_argument("--python-root", type=Path, required=True)
    parser.add_argument("--receipt-name", default="consumer-verification.json")
    args = parser.parse_args()
    sys.path[:0] = [str(args.python_root), str(args.python_root / "src")]
    import pandas as pd
    from quant_platform.best_trader.adapters.questdb.storage import QuestDbTradingStorage
    from quant_platform.best_trader.adapters.trading_review_data import QuestDbTradingReviewDataGateway
    from quant_platform.best_trader.application.main_strategy_data import _fetch_df as main_read
    from quant_platform.best_trader.application.etf_only_top2_execution_dashboard import _fetch_df as dashboard_read
    from quant_platform.research.adapters.strategy_identity_files import StrategyIdentityFileStore
    from quant_platform.research.adapters.strategy_production_files import _DATE_COLUMNS

    class ForbiddenRemoteRecords:
        def fetch_df(self, *args, **kwargs):
            raise AssertionError("Local record query unexpectedly reached QuestDB")

    root = args.audit_root
    manifest = json.loads((root / "migration-verification.json").read_text())
    if manifest["status"] != "DEPLOYED_AND_VERIFIED":
        raise ValueError("Verified deployment is required")
    result = {"status": "PASS", "tables": {}, "targets": {}, "process": "cold-start", "remote_record_reads": 0}
    for group, meta in manifest["groups"].items():
        target = Path(meta["target"])
        target.chmod(0o600)
        Path(meta["staged"]).chmod(0o600)
        Path(meta["backup"]).chmod(0o600)
        check_integrity(target)
        if digest(target) != meta["sha256"]:
            raise ValueError(f"Deployed ledger differs from its verified snapshot: {target}")
        result["targets"][group] = {"path": str(target), "sha256": digest(target)}
    identities = StrategyIdentityFileStore()
    identities.verify_schema()
    for table, meta in manifest["tables"].items():
        raw, schema = load_snapshot(root, table)
        sql = f'SELECT * FROM "{table}"'
        date_columns = ()
        if table in {"strategy_templates", "strategy_instances"}:
            kind = "templates" if table == "strategy_templates" else "instances"
            from migrate_questdb_record_archives import readonly_rows
            saved = [r for r in readonly_rows(identities.path, "records") if r["kind"] == kind]
            rows = [json.loads(r["payload"]) for r in saved]
            for row in saved:
                assert identities.latest(kind, row["identity"]) is not None
        elif table.startswith("strategy_"):
            primary = main_read(sql)
            secondary = dashboard_read(sql)
            assert len(primary) == len(secondary)
            rows = primary.to_dict("records")
            date_columns = _DATE_COLUMNS
        elif table in {"trade_signals", "order_records", "trade_records", "paired_execution_plans"}:
            rows = QuestDbTradingStorage(ForbiddenRemoteRecords()).fetch_df(sql).to_dict("records")
        else:
            rows = QuestDbTradingReviewDataGateway(ForbiddenRemoteRecords()).fetch_df(sql).to_dict("records")
            date_columns = _DATE_COLUMNS
        rows = [{k: None if pd.isna(v) else v for k, v in row.items()} for row in rows]
        actual = fingerprint(rows, schema, date_columns)
        if actual != meta["source"]:
            raise ValueError(f"Application read differs from original QuestDB data: {table}")
        result["tables"][table] = actual
        print(table, actual["rows"], "application rows verified", flush=True)
    (root / args.receipt_name).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print("PASS: application consumers read all 26 tables from local SQLite", flush=True)


if __name__ == "__main__":
    main()
