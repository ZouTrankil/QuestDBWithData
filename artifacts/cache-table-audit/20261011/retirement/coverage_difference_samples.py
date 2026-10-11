"""Retain the small historical-value differences without changing formal values."""

import json
import urllib.parse
import urllib.request
from pathlib import Path

path = Path(__file__).resolve().parent / "coverage-pre-drop.json"
report = json.loads(path.read_text(encoding="utf-8"))


def query(sql):
    if not sql.upper().startswith("SELECT") or ";" in sql:
        raise ValueError("Only SELECT is allowed")
    with urllib.request.urlopen("http://127.0.0.1:9000/exec?" + urllib.parse.urlencode({"query": sql, "limit": 20000}), timeout=60) as handle:
        raw = json.load(handle)
    names = [column["name"] for column in raw["columns"]]
    rows = [dict(zip(names, row)) for row in raw["dataset"]]
    report["queries"].append({"sql": sql, "rows": rows, "returned_count": len(rows)})
    return rows


samples = {}
for cache, native, by_stock in (("backtest_daily_cache", "backtest_daily", True),
                                ("market_breadth_daily_cache", "mv_market_breadth_daily_v1", False)):
    fields = [r["column"] for r in query(f'SELECT "column" FROM table_columns(\'{cache}\')')
              if r["column"] not in {"trade_date", "ts_code", "source_version"}]
    diff = " OR ".join(f"(c.{f}!=b.{f} OR (c.{f} IS NULL AND b.{f} IS NOT NULL) OR (c.{f} IS NOT NULL AND b.{f} IS NULL))" for f in fields)
    projection = "c.trade_date,c.source_version" + (",c.ts_code" if by_stock else "")
    projection += "," + ",".join(f"c.{f} AS cached_{f},b.{f} AS formal_{f}" for f in fields)
    keys = "c.trade_date=b.trade_date" + (" AND c.ts_code=b.ts_code" if by_stock else "")
    source = f"(SELECT * FROM {cache} WHERE trade_date>='2026-09-01' AND trade_date<'2026-10-01')" if by_stock else cache
    formal = f"(SELECT * FROM {native} WHERE trade_date>='2026-09-01' AND trade_date<'2026-10-01')" if by_stock else native
    rows = query(f"SELECT {projection} FROM {source} c JOIN {formal} b ON ({keys}) WHERE {diff} ORDER BY c.trade_date LIMIT 100")
    samples[cache] = [dict(trade_date=row["trade_date"], source_version=row["source_version"],
                           **({"ts_code": row["ts_code"]} if by_stock else {}),
                           differences={field: {"cached": row[f"cached_{field}"], "formal": row[f"formal_{field}"]}
                                        for field in fields if row[f"cached_{field}"] != row[f"formal_{field}"]}) for row in rows]

report["old_version_difference_samples"] = samples
report["historical_value_policy"] = "Differences retained as audit evidence only; keep current formal base/MV values unchanged."
path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps(samples, ensure_ascii=False), flush=True)
