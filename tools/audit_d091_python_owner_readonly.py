"""Exercise the Python D091 source-version/cache read helpers with writes disabled."""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
from pathlib import Path


class ReadOnlyQuestDBClient:
    """Allow SELECT through the project's client and reject every write surface."""

    def __init__(self, delegate):
        self._delegate = delegate

    def fetch_df(self, query, *args, **kwargs):
        normalized = query.lstrip().upper()
        if not normalized.startswith(("SELECT", "WITH")) or ";" in query:
            raise RuntimeError("D091 audit rejected a non-read-only SQL statement")
        if normalized == "SELECT * FROM TABLES()":
            # QuestDB may expose out-of-range sentinel timestamps for unrelated tables.
            # The owner's _source_state needs only these six metadata columns.
            query = (
                "SELECT table_name, id, table_suspended, wal_pending_row_count, table_txn, wal_txn "
                "FROM tables()"
            )
        return self._delegate.fetch_df(query, *args, **kwargs)

    def write_model(self, *args, **kwargs):
        raise RuntimeError("D091 read-only audit rejected write_model")

    def write_df(self, *args, **kwargs):
        raise RuntimeError("D091 read-only audit rejected write_df")

    def execute_ddl(self, *args, **kwargs):
        raise RuntimeError("D091 read-only audit rejected DDL")

    def execute_uncounted(self, *args, **kwargs):
        raise RuntimeError("D091 read-only audit rejected DML")

    def execute_counted(self, *args, **kwargs):
        raise RuntimeError("D091 read-only audit rejected DML")

    def __getattr__(self, name):
        return getattr(self._delegate, name)


def read_input(path: Path):
    raw = path.read_bytes()
    payload = gzip.decompress(raw) if path.suffix.lower() == ".gz" else raw
    return json.loads(payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--python-project", default="D:/work/fund_2/back-monitor")
    args = parser.parse_args()

    project = Path(args.python_project).resolve()
    sys.path.insert(0, str(project / "src"))
    import pandas as pd
    from quant_platform.common.persistence.questdb_client import QuestDBClient
    from quant_platform.data.adapters.questdb.backtest_cache import BacktestReadThroughCache, content_digest

    evidence = read_input(Path(args.input))
    receipt = evidence["coverage"]
    if not re.fullmatch(r"[0-9a-f]{64}", receipt["source_version"]):
        raise ValueError("source_version is not a lowercase SHA-256 fingerprint")

    host = os.environ.get("APP_QUESTDB_HOST", "127.0.0.1")
    username = os.environ.get("APP_QUESTDB_USERNAME", "admin")
    password = os.environ.get("APP_QUESTDB_PASSWORD")
    if not password:
        raise ValueError("QuestDB password must be supplied through the local environment")
    port = int(os.environ.get("APP_QUESTDB_PGPORT", "8812"))
    database = os.environ.get("APP_QUESTDB_DATABASE", "qdb")
    delegate = QuestDBClient(config={
        "host": host,
        "port": port,
        "user": username,
        "password": password,
        "database": database,
        "qwp_enabled": False,
    }, pool_min_conn=1, pool_max_conn=1)
    client = ReadOnlyQuestDBClient(delegate)
    cache = BacktestReadThroughCache(client)
    # The project's PostgreSQL reader exposes calendar TIMESTAMP values as naive UTC carriers.
    day = pd.Timestamp(receipt["trade_date"])
    try:
        before = client.fetch_df(
            "SELECT table_name, table_row_count, table_txn FROM tables() "
            "WHERE table_name IN ('backtest_daily_cache_coverage','backtest_daily_cache')"
        ).set_index("table_name")
        source_state = cache._source_state()
        current_version = cache._version(source_state, day)
        verified_frame = cache._cached(day, receipt["source_version"])
        if verified_frame is None:
            raise RuntimeError("Python owner rejected the persisted coverage/cache pair")
        current_frame = (
            verified_frame if current_version == receipt["source_version"]
            else cache._cached(day, current_version)
        )
        from_input = content_digest(pd.DataFrame(evidence["cache_rows"]))
        verified_digest = content_digest(verified_frame)
        expected_count = int(receipt["row_count"])
        if len(verified_frame) != expected_count or verified_digest != receipt["content_digest"]:
            raise RuntimeError("Python owner readback differs from the published coverage receipt")
        after = client.fetch_df(
            "SELECT table_name, table_row_count, table_txn FROM tables() "
            "WHERE table_name IN ('backtest_daily_cache_coverage','backtest_daily_cache')"
        ).set_index("table_name")
        before_ids = before.loc[["backtest_daily_cache_coverage", "backtest_daily_cache"],
                                ["table_row_count", "table_txn"]].astype("int64")
        after_ids = after.loc[["backtest_daily_cache_coverage", "backtest_daily_cache"],
                              ["table_row_count", "table_txn"]].astype("int64")
        unchanged = before_ids.equals(after_ids)
        if not unchanged:
            raise RuntimeError("A D091/related cache table changed during the read-only audit")

        result = {
            "task_id": "D091",
            "status": "VERIFIED",
            "source_owner": "Python BacktestReadThroughCache",
            "checked_at": pd.Timestamp.now(tz="UTC").isoformat(),
            "database_operations": "SELECT only; install/read/write entry points were not called",
            "receipt_trade_date": receipt["trade_date"],
            "receipt_source_version": receipt["source_version"],
            "current_source_version": current_version,
            "receipt_matches_current_source_version": current_version == receipt["source_version"],
            "owner_cached_read_accepted": True,
            "owner_read_row_count": len(verified_frame),
            "current_source_version_cache_hit": current_frame is not None,
            "current_source_version_cache_rows": None if current_frame is None else len(current_frame),
            "input_digest": from_input,
            "owner_read_digest": verified_digest,
            "receipt_content_digest": receipt["content_digest"],
            "digest_values_match": from_input == verified_digest == receipt["content_digest"],
            "source_state_tables": sorted(source_state),
            "table_metadata_before": before_ids.reset_index().to_dict("records"),
            "table_metadata_after": after_ids.reset_index().to_dict("records"),
            "table_counts_and_txns_unchanged": unchanged,
            "writes": "none",
        }
        if not result["digest_values_match"]:
            raise RuntimeError("Java-captured and Python-owner cache digests differ")
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: result[key] for key in (
            "task_id", "status", "receipt_matches_current_source_version",
            "owner_read_row_count", "digest_values_match", "table_counts_and_txns_unchanged"
        )}, ensure_ascii=False))
        return 0
    finally:
        delegate.close()


if __name__ == "__main__":
    raise SystemExit(main())
