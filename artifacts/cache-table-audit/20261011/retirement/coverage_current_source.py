"""Verify differing cache values against current authoritative sources, read-only."""

import json
import math
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, "F:/repo/engine/back-monitor/src")
from quant_platform.data.adapters.questdb.market_barometer_views import select_sql

path = Path(__file__).resolve().parent / "coverage-pre-drop.json"
report = json.loads(path.read_text(encoding="utf-8"))


def query(sql):
    if not sql.lstrip().upper().startswith("SELECT") or ";" in sql:
        raise ValueError("Only SELECT is allowed")
    with urllib.request.urlopen("http://127.0.0.1:9000/exec?" + urllib.parse.urlencode({"query": sql, "limit": 20000}), timeout=60) as handle:
        raw = json.load(handle)
    names = [column["name"] for column in raw["columns"]]
    rows = [dict(zip(names, row)) for row in raw["dataset"]]
    report["queries"].append({"sql": sql, "rows": rows, "returned_count": len(rows)})
    return rows


health_sql = (
    "SELECT t.table_name,t.id,t.directoryName,t.table_suspended,t.wal_pending_row_count,"
    "coalesce(t.table_txn,w.writerTxn) AS table_txn,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize "
    "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name IN "
    "('stk_factor','stk_limit','stk_suspend','stk_st_daily','backtest_daily','v_backtest_daily',"
    "'mv_market_breadth_daily_v1','mv_retail_sentiment_daily_v1') ORDER BY t.table_name"
)
before = query(health_sql)
checks = {"backtest": [], "market_breadth": [], "float_tolerance": {"rtol": 1e-9, "atol": 1e-8}}
for sample in report["old_version_difference_samples"]["backtest_daily_cache"]:
    day, code = sample["trade_date"][:10], sample["ts_code"]
    differences = sample["differences"]
    if set(differences) != {"adj_factor"}:
        raise ValueError("Source validation requires reviewing newly observed difference fields")
    rows = query(f"SELECT trade_date,ts_code,adj_factor FROM stk_factor WHERE trade_date='{day}' AND ts_code='{code}'")
    expected = sample["differences"]["adj_factor"]["formal"]
    agrees = len(rows) == 1 and rows[0]["adj_factor"] == expected
    checks["backtest"].append({"trade_date": day, "ts_code": code, "cached": differences["adj_factor"]["cached"],
                                 "current_formal": expected, "current_source": rows,
                                 "current_source_agrees": agrees, "basis": "Direct current stk_factor adj_factor for the same business key"})

for day in sorted({s["trade_date"][:10] for s in report["old_version_difference_samples"]["market_breadth_daily_cache"]}):
    sql = select_sql("v_market_breadth_daily", bounded=True).replace("%s", f"'{day}'")
    sources = query(sql)
    formal = query(f"SELECT * FROM mv_market_breadth_daily_v1 WHERE trade_date='{day}'")
    metrics = ("stock_count", "up_count", "down_count", "flat_count", "avg_pct_change", "total_amount_yi")
    comparisons = {}
    for field in metrics:
        left = sources[0][field] if len(sources) == 1 else None
        right = formal[0][field] if len(formal) == 1 else None
        if field in {"stock_count", "up_count", "down_count", "flat_count"}:
            same = left is not None and left == right
        else:
            same = left is not None and right is not None and math.isclose(left, right, rel_tol=1e-9, abs_tol=1e-8)
        comparisons[field] = {"current_source": left, "current_native": right, "agrees": same}
    checks["market_breadth"].append({"trade_date": day, "comparison": comparisons,
                                      "current_source_agrees": all(item["agrees"] for item in comparisons.values()),
                                      "basis": "Current authoritative select_sql(v_market_breadth_daily) bounded to the date"})

after = query(health_sql)
checks["source_state_before"] = before
checks["source_state_after"] = after
checks["source_state_unchanged"] = before == after
checks["current_source_agrees"] = all(item["current_source_agrees"] for family in ("backtest", "market_breadth") for item in checks[family]) and before == after
report["current_source_validation"] = checks
path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps({"current_source_agrees": checks["current_source_agrees"], "source_state_unchanged": checks["source_state_unchanged"],
                  "backtest": checks["backtest"], "market_breadth": checks["market_breadth"]}, ensure_ascii=False), flush=True)
