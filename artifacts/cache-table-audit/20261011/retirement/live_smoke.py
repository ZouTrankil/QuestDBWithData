"""Read-only acceptance against localhost through the current Python readers."""

import hashlib
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from quant_platform.data.adapters.questdb.backtest_view import BacktestMaterializedViewReader, FIELDS
from quant_platform.data.adapters.questdb.dataset_snapshots import QuestDbDatasetSnapshotRepository
from quant_platform.data.adapters.questdb.dataset_catalog import (
    MARKET_STOCK_BACKTEST_DAILY, MARKET_STOCK_MONTH_END_BACKTEST_DAILY,
)
from quant_platform.data.adapters.questdb.market_barometer import QuestDbMarketBarometerRepository
from quant_platform.data.contracts.dataset_snapshots import DatasetSnapshotRequest


ROOT = Path(__file__).resolve().parent
RETIRED = ("backtest_daily_cache", "backtest_daily_cache_coverage",
           "market_breadth_daily_cache", "retail_sentiment_daily_cache")


class HttpReadOnlyClient:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.queries = []

    def fetch_df(self, sql, params=None):
        if params:
            for value in params:
                literal = "'" + str(value).replace("'", "''") + "'"
                sql = sql.replace("%s", literal, 1)
        if not sql.lstrip().lower().startswith("select ") or ";" in sql:
            raise RuntimeError("Smoke client permits SELECT only")
        if any(table in sql for table in RETIRED):
            raise RuntimeError("Reader attempted to access a retired cache: " + sql)
        self.queries.append(sql)
        url = "http://127.0.0.1:9000/exec?" + urllib.parse.urlencode(
            {"query": sql, "limit": "0,30000"}
        )
        with self.opener.open(url, timeout=30) as response:
            payload = json.load(response)
        if "error" in payload:
            raise RuntimeError(payload["error"] + " SQL: " + sql)
        columns = payload["columns"]
        frame = pd.DataFrame(payload["dataset"], columns=[column["name"] for column in columns])
        for column in columns:
            name, kind = column["name"], column["type"]
            if kind in {"TIMESTAMP", "TIMESTAMP_NS", "DATE"}:
                frame[name] = pd.to_datetime(frame[name], utc=True).dt.tz_localize(None)
        return frame

    def execute_ddl(self, *args, **kwargs):
        raise RuntimeError("Smoke client cannot execute DDL")

    def create_table_if_not_exists(self, *args, **kwargs):
        raise RuntimeError("Smoke client cannot install tables")

    def write_model(self, *args, **kwargs):
        raise RuntimeError("Smoke client cannot publish rows")


def digest(frame):
    records = frame.sort_values(["trade_date", "ts_code"])[list(FIELDS)].to_dict("records")
    return hashlib.sha256(json.dumps(records, sort_keys=True, default=str).encode()).hexdigest()


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "pre-drop"
    client = HttpReadOnlyClient()
    result = {"at": datetime.now(timezone.utc).isoformat(), "mode": mode,
              "endpoint": "http://127.0.0.1:9000", "operations": "SELECT only"}
    reader = BacktestMaterializedViewReader(client)
    read = reader.read("2026-09-30", "2026-09-30", max_rows=10000)
    base = client.fetch_df(
        "SELECT " + ",".join(FIELDS) + " FROM backtest_daily "
        "WHERE trade_date='2026-09-30' ORDER BY trade_date,ts_code"
    )
    assert len(read.frame) > 0 and digest(read.frame) == digest(base)
    reader.require_version(read.version)
    result["backtest_full_day"] = {"date": "2026-09-30", "rows": len(read.frame),
                                   "base_mv_digest_equal": True, "version": read.version}
    repository = QuestDbDatasetSnapshotRepository(client)
    result["datasets"] = []
    for dataset in (MARKET_STOCK_BACKTEST_DAILY, MARKET_STOCK_MONTH_END_BACKTEST_DAILY):
        extract = repository.extract(DatasetSnapshotRequest(
            dataset_id=dataset, start_date="20260930", end_date="20260930",
            filters={"symbols": "000001.SZ"}, max_rows=100,
        ))
        assert len(extract.frame) == 1
        assert extract.metadata["read_path"] == "materialized_view"
        assert extract.metadata["source_tables"] == ["backtest_daily", "v_backtest_daily"]
        result["datasets"].append({"id": dataset, "rows": len(extract.frame),
                                   "read_path": extract.metadata["read_path"],
                                   "source_version": extract.metadata["source_version"]})
    market = QuestDbMarketBarometerRepository(client)
    result["market"] = []
    for view in ("v_market_breadth_daily", "v_retail_sentiment_daily"):
        frame = market.fetch_view(view, start_date="2026-09-30", end_date="2026-09-30")
        assert len(frame) == 1
        latest = market.fetch_latest(view)
        assert latest and str(latest["trade_date"])[:10] == "2026-09-30"
        result["market"].append({"view": view, "rows": len(frame), "latest": str(latest["trade_date"])})
    result.update(passed=True, query_count=len(client.queries), queries=client.queries)
    (ROOT / ("live-smoke-" + mode + ".json")).write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("passed", "mode", "backtest_full_day", "datasets", "market", "query_count")}))


if __name__ == "__main__":
    main()
