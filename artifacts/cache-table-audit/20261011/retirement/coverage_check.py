"""Bounded SELECT-only business-key containment before cache retirement."""

import datetime as dt
import json
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "coverage-pre-drop.json"
ENDPOINT = "http://127.0.0.1:9000"
report = {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "endpoint": ENDPOINT,
          "operations": "SELECT only", "queries": [], "backtest": {}, "market": {}, "complete": False}


def query(sql):
    if not sql.lstrip().upper().startswith("SELECT") or ";" in sql:
        raise ValueError("This audit only permits a single SELECT")
    with urllib.request.urlopen(ENDPOINT + "/exec?" + urllib.parse.urlencode({"query": sql, "limit": 20000}), timeout=120) as handle:
        raw = json.load(handle)
    if "error" in raw:
        raise RuntimeError(raw["error"])
    names = [column["name"] for column in raw["columns"]]
    rows = [dict(zip(names, row)) for row in raw["dataset"]]
    report["queries"].append({"sql": sql, "rows": rows, "returned_count": len(rows)})
    OUTPUT.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return rows


def date_window(lo, hi):
    return f"trade_date>=cast('{lo}T00:00:00.000000Z' AS TIMESTAMP) AND trade_date<cast('{hi}T00:00:00.000000Z' AS TIMESTAMP)"


def month_bounds(dates):
    months = sorted({value[:7] for value in dates})
    for month in months:
        lo = dt.date.fromisoformat(month + "-01")
        hi = dt.date(lo.year + (lo.month == 12), 1 if lo.month == 12 else lo.month + 1, 1)
        yield month, lo.isoformat(), hi.isoformat()


HEALTH_SQL = (
    "SELECT t.table_name,t.id,t.directoryName,t.table_suspended,t.wal_pending_row_count,"
    "coalesce(t.table_txn,w.writerTxn) AS table_txn,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize "
    "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name IN "
    "('backtest_daily_cache','backtest_daily_cache_coverage','market_breadth_daily_cache','retail_sentiment_daily_cache',"
    "'backtest_daily','v_backtest_daily','mv_market_breadth_daily_v1','mv_retail_sentiment_daily_v1') ORDER BY t.table_name"
)
report["before"] = query(HEALTH_SQL)

try:
    receipt_dates = [r["trade_date"] for r in query("SELECT DISTINCT trade_date FROM backtest_daily_cache_coverage ORDER BY trade_date")]
    cache_dates = [r["trade_date"] for r in query("SELECT DISTINCT trade_date FROM backtest_daily_cache ORDER BY trade_date")]
    report["backtest"].update(receipt_date_count=len(receipt_dates), actual_cache_date_count=len(cache_dates),
                               cache_dates_without_receipt=sorted(set(cache_dates)-set(receipt_dates)),
                               receipt_dates_without_cache=sorted(set(receipt_dates)-set(cache_dates)), windows=[])
    fields = [r["column"] for r in query('SELECT "column" FROM table_columns(\'backtest_daily_cache\')')
              if r["column"] not in {"trade_date", "ts_code", "source_version"}]
    diff = " OR ".join(f"(c.{f}!=b.{f} OR (c.{f} IS NULL AND b.{f} IS NOT NULL) OR (c.{f} IS NOT NULL AND b.{f} IS NULL))" for f in fields)
    for month, lo, hi in month_bounds(cache_dates):
        window = date_window(lo, hi)
        cache_keys = f"(SELECT trade_date,ts_code FROM backtest_daily_cache WHERE {window} GROUP BY trade_date,ts_code)"
        base = f"(SELECT trade_date,ts_code,{','.join(fields)} FROM backtest_daily WHERE {window})"
        total = query(f"SELECT count() AS distinct_business_keys FROM {cache_keys}")[0]["distinct_business_keys"]
        missing_from = f"{cache_keys} c LEFT JOIN {base} b ON (c.trade_date=b.trade_date AND c.ts_code=b.ts_code)"
        missing = query(f"SELECT count() AS missing_keys FROM {missing_from} WHERE b.ts_code IS NULL")[0]["missing_keys"]
        old_rows = f"(SELECT * FROM backtest_daily_cache WHERE {window}) c JOIN {base} b ON (c.trade_date=b.trade_date AND c.ts_code=b.ts_code)"
        differences = query(f"SELECT count() AS versioned_rows_differing_from_current_base FROM {old_rows} WHERE {diff}")[0]["versioned_rows_differing_from_current_base"]
        item = {"month": month, "distinct_business_keys": total, "missing_keys": missing,
                "versioned_rows_differing_from_current_base": differences}
        if missing:
            item["missing_sample"] = query(f"SELECT c.trade_date,c.ts_code FROM {missing_from} WHERE b.ts_code IS NULL ORDER BY c.trade_date,c.ts_code LIMIT 100")
            print(json.dumps({"MISSING": item}, ensure_ascii=False), flush=True)
        report["backtest"]["windows"].append(item)
        print(json.dumps({"month": month, "keys": total, "missing": missing, "old_value_differences": differences}), flush=True)
        OUTPUT.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    for cache, native in (("market_breadth_daily_cache", "mv_market_breadth_daily_v1"),
                          ("retail_sentiment_daily_cache", "mv_retail_sentiment_daily_v1")):
        fields = [r["column"] for r in query(f'SELECT "column" FROM table_columns(\'{cache}\')')
                  if r["column"] not in {"trade_date", "source_version"}]
        keys = f"(SELECT DISTINCT trade_date FROM {cache})"
        total = query(f"SELECT count() AS distinct_date_keys FROM {keys}")[0]["distinct_date_keys"]
        missing_from = f"{keys} c LEFT JOIN {native} b ON c.trade_date=b.trade_date"
        missing = query(f"SELECT count() AS missing_date_keys FROM {missing_from} WHERE b.trade_date IS NULL")[0]["missing_date_keys"]
        missing_sample = query(f"SELECT c.trade_date FROM {missing_from} WHERE b.trade_date IS NULL ORDER BY c.trade_date LIMIT 100") if missing else []
        diff = " OR ".join(f"(c.{f}!=b.{f} OR (c.{f} IS NULL AND b.{f} IS NOT NULL) OR (c.{f} IS NOT NULL AND b.{f} IS NULL))" for f in fields)
        differences = query(f"SELECT count() AS versioned_rows_differing_from_current_native FROM {cache} c JOIN {native} b ON c.trade_date=b.trade_date WHERE {diff}")[0]["versioned_rows_differing_from_current_native"]
        item = {"native": native, "distinct_date_keys": total, "missing_date_keys": missing,
                "missing_sample": missing_sample, "versioned_rows_differing_from_current_native": differences}
        report["market"][cache] = item
        print(json.dumps({"market": cache, **item}), flush=True)

    report["after"] = query(HEALTH_SQL)
    report["state_unchanged"] = report["before"] == report["after"]
    report["backtest"]["distinct_business_keys"] = sum(r["distinct_business_keys"] for r in report["backtest"]["windows"])
    report["backtest"]["missing_keys"] = sum(r["missing_keys"] for r in report["backtest"]["windows"])
    report["backtest"]["versioned_rows_differing_from_current_base"] = sum(r["versioned_rows_differing_from_current_base"] for r in report["backtest"]["windows"])
    report["all_cache_business_keys_covered"] = report["backtest"]["missing_keys"] == 0 and all(r["missing_date_keys"] == 0 for r in report["market"].values())
    report["complete"] = True
except Exception as exc:
    report["error"] = repr(exc)
    raise
finally:
    OUTPUT.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

print(json.dumps({"complete": report["complete"], "unchanged": report["state_unchanged"],
                  "covered": report["all_cache_business_keys_covered"], "backtest": {k:v for k,v in report["backtest"].items() if k != "windows"},
                  "market": report["market"]}, ensure_ascii=False), flush=True)
