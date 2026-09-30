"""Read-only integrity audit of one published receipt per market barometer product."""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pandas as pd


TABLES = ("market_barometer_cache_coverage", "market_breadth_daily_cache",
          "etf_market_overview_daily_cache", "retail_sentiment_daily_cache")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--python-project", default="D:/work/fund_2/back-monitor")
    args = parser.parse_args()
    sys.path.insert(0, str(Path(args.python_project) / "src"))
    from quant_platform.data.adapters.questdb.market_barometer_cache import (
        SPECS, _digest, _record_digest,
    )

    host = os.environ.get("APP_QUESTDB_HOST", "127.0.0.1")
    port = os.environ.get("APP_QUESTDB_QWPPORT", "9000")
    username = os.environ.get("APP_QUESTDB_USERNAME", "admin")
    password = os.environ.get("APP_QUESTDB_PASSWORD")
    if not password:
        raise ValueError("QuestDB password must be supplied through the local environment")
    auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    endpoint = f"http://{host}:{port}/exec"

    def query(sql: str) -> list[dict]:
        if not sql.startswith("SELECT ") or ";" in sql:
            raise ValueError("Only single read-only SELECT queries are allowed")
        request = Request(endpoint + "?" + urlencode({"query": sql}), headers={"Authorization": auth})
        with urlopen(request, timeout=45) as response:
            payload = json.load(response)
        if "error" in payload:
            raise RuntimeError(f"QuestDB rejected D094 read-only query: {payload['error']}")
        names = [column["name"] for column in payload["columns"]]
        return [dict(zip(names, row)) for row in payload["dataset"]]

    def identities() -> dict[str, dict]:
        result = {}
        for name in TABLES:
            rows = query("SELECT table_name,table_row_count,table_txn,partitionBy,walEnabled "
                         f"FROM tables() WHERE table_name='{name}'")
            if len(rows) != 1:
                raise RuntimeError(f"Missing or duplicate D094 table: {name}")
            result[name] = rows[0]
        return result

    before = identities()
    columns = query("SELECT * FROM table_columns('market_barometer_cache_coverage')")
    types = {row["column"]: row["type"] for row in columns}
    expected_types = {"trade_date": "TIMESTAMP", "dataset_id": "SYMBOL",
                      "source_version": "SYMBOL", "row_count": "LONG", "content_digest": "STRING"}
    keys = {row["column"] for row in columns if row["upsertKey"]}
    designated = [row["column"] for row in columns if row["designated"]]
    if types != expected_types or keys != {"trade_date", "dataset_id", "source_version"}:
        raise RuntimeError("D094 physical columns or complete upsert key drifted")
    if designated != ["trade_date"] or before[TABLES[0]]["partitionBy"] != "MONTH" \
            or not before[TABLES[0]]["walEnabled"]:
        raise RuntimeError("D094 coverage physical storage contract drifted")

    by_dataset = {spec.dataset_id: spec for spec in SPECS.values()}
    recent = query("SELECT trade_date,dataset_id,source_version,row_count,content_digest "
                   "FROM market_barometer_cache_coverage "
                   "ORDER BY trade_date DESC, source_version DESC LIMIT 100")
    selected = {}
    for row in recent:
        dataset = row["dataset_id"]
        if dataset in by_dataset and dataset not in selected:
            selected[dataset] = row
    if set(selected) != set(by_dataset):
        raise RuntimeError("Latest bounded receipts do not cover all Python market barometer products")

    verified = []
    for dataset, receipt in selected.items():
        spec = by_dataset[dataset]
        day = str(receipt["trade_date"])[:10]
        day_compact = day.replace("-", "")
        version = str(receipt["source_version"])
        if len(version) != 64:
            raise RuntimeError("Invalid published source version")
        fields = ", ".join(spec.fields)
        rows = query(f"SELECT {fields} FROM {spec.table} WHERE "
                     f"trade_date = to_timestamp('{day_compact}', 'yyyyMMdd') "
                     f"AND source_version = '{version}' LIMIT 2")
        count = int(receipt["row_count"])
        if count not in (0, 1) or len(rows) != count:
            raise RuntimeError(f"{dataset}: coverage row_count does not match cache")
        digest = _record_digest(rows[0], spec.fields) if rows else _digest(pd.DataFrame(), spec.fields)
        if digest != receipt["content_digest"]:
            raise RuntimeError(f"{dataset}: Python owner digest does not match stored receipt")
        verified.append({
            "dataset_id": dataset,
            "cache_table": spec.table,
            "trade_date": day,
            "source_version": version,
            "receipt_row_count": count,
            "matching_cache_rows": len(rows),
            "expected_content_digest": receipt["content_digest"],
            "actual_python_content_digest": digest,
            "cache_rows": rows,
        })

    after = identities()
    if before != after:
        raise RuntimeError("Coverage or cache table metadata changed during D094 read-only audit")
    result = {
        "task_id": "D094",
        "status": "VERIFIED",
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "database_operations": "SELECT only",
        "python_owner": "MarketBarometerReadThroughCache",
        "physical_types": types,
        "physical_upsert_keys": sorted(keys),
        "designated_timestamp": designated[0],
        "selected_receipts": verified,
        "tables_before": before,
        "tables_after": after,
        "writes": "none",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"task_id": "D094", "status": "VERIFIED",
                      "datasets": [item["dataset_id"] for item in verified],
                      "matching_receipts": len(verified)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
