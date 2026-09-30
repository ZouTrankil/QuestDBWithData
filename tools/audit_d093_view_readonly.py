"""Compare live QuestDB v_backtest_daily with Python's authoritative VIEW_SELECT using SELECT only."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen


FIELDS = ("trade_date", "ts_code", "open", "high", "low", "close", "vol", "amount",
          "adj_factor", "up_limit", "down_limit", "is_suspended", "is_st")
SOURCE_KEYS = {
    "stk_factor": {"trade_date", "ts_code"},
    "stk_limit": {"trade_date", "ts_code"},
    "stk_st_daily": {"timestamp", "ts_code"},
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--python-project", default="D:/work/fund_2/back-monitor")
    parser.add_argument("--date", default="2026-09-17")
    args = parser.parse_args()
    sys.path.insert(0, str(Path(args.python_project) / "src"))
    from quant_platform.data.adapters.questdb.backtest_view import VIEW_SELECT

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
            raise RuntimeError(f"QuestDB rejected D093 read-only query: {payload['error']}")
        names = [column["name"] for column in payload["columns"]]
        return [dict(zip(names, row)) for row in payload["dataset"]]

    view = query("SELECT * FROM views() WHERE view_name='v_backtest_daily'")
    if len(view) != 1 or view[0]["view_sql"].strip() != VIEW_SELECT:
        raise RuntimeError("Live view SQL differs from Python's authoritative VIEW_SELECT")
    columns = query("SELECT * FROM table_columns('v_backtest_daily')")
    types = {row["column"]: row["type"] for row in columns}
    if set(types) != set(FIELDS) or len(columns) != len(FIELDS):
        raise RuntimeError("Live view field set differs from Python BacktestDaily fields")
    expected_types = {"trade_date": "TIMESTAMP", "ts_code": "SYMBOL",
                      "is_suspended": "LONG", "is_st": "INT"}
    expected_types.update({name: "DOUBLE" for name in FIELDS[2:11]})
    if types != expected_types:
        raise RuntimeError(f"Live view type drift: {types}")
    if any(row["upsertKey"] or row["designated"] for row in columns):
        raise RuntimeError("Ordinary view unexpectedly declares physical key/timestamp")

    source_keys = {}
    for table, expected in SOURCE_KEYS.items():
        actual = {row["column"] for row in query(f"SELECT * FROM table_columns('{table}')")
                  if row["upsertKey"]}
        source_keys[table] = sorted(actual)
        if actual != expected:
            raise RuntimeError(f"{table} no longer satisfies Python installer key guard")

    day = args.date.replace("-", "")
    projection = ", ".join(FIELDS)
    sample = query(f"SELECT {projection} FROM v_backtest_daily "
                   f"WHERE trade_date = to_timestamp('{day}', 'yyyyMMdd') "
                   "ORDER BY trade_date, ts_code LIMIT 200")
    if len(sample) != 200:
        raise RuntimeError("D093 bounded real view sample must contain 200 rows")
    keys = [(str(row["trade_date"])[:10], row["ts_code"]) for row in sample]
    if len(set(keys)) != len(keys):
        raise RuntimeError("View returned duplicate complete keys in bounded sample")
    result = {
        "task_id": "D093",
        "status": "VERIFIED",
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "database_operations": "SELECT only",
        "view_name": "v_backtest_daily",
        "view_status": view[0].get("view_status"),
        "view_sql_matches_python": True,
        "view_sql_sha256": hashlib.sha256(VIEW_SELECT.encode()).hexdigest(),
        "python_view_sql_sha256": hashlib.sha256(VIEW_SELECT.encode()).hexdigest(),
        "physical_types": types,
        "view_physical_upsert_keys": [],
        "view_designated_timestamp": None,
        "source_upsert_keys": source_keys,
        "sample_date": args.date,
        "sample_row_count": len(sample),
        "sample_unique_complete_keys": len(set(keys)),
        "sample_rows": sample,
        "writes": "none",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in (
        "task_id", "status", "view_sql_matches_python", "sample_row_count",
        "sample_unique_complete_keys")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
