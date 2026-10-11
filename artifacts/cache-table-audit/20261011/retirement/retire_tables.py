"""Retire only the four explicitly selected derived caches after recorded validation."""

import json
import subprocess
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent
TABLES = (
    "backtest_daily_cache",
    "backtest_daily_cache_coverage",
    "market_breadth_daily_cache",
    "retail_sentiment_daily_cache",
)
PRESERVED = (
    "backtest_daily",
    "v_backtest_daily",
    "mv_market_breadth_daily_v1",
    "mv_retail_sentiment_daily_v1",
    "etf_market_overview_daily_cache",
    "market_barometer_cache_coverage",
)
BASES = {
    "v_backtest_daily": "backtest_daily",
    "mv_market_breadth_daily_v1": "stk_factor",
    "mv_retail_sentiment_daily_v1": "l2_daily_features",
}
HEALTH_SQL = (
    "SELECT t.table_name,t.id,t.directoryName,t.table_suspended,t.wal_pending_row_count,"
    "coalesce(t.table_txn,w.writerTxn) AS table_txn,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize "
    "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name IN "
    "('backtest_daily_cache','backtest_daily_cache_coverage','market_breadth_daily_cache','retail_sentiment_daily_cache',"
    "'backtest_daily','v_backtest_daily','mv_market_breadth_daily_v1','mv_retail_sentiment_daily_v1') ORDER BY t.table_name"
)


def query(sql):
    drops = {"DROP TABLE " + table for table in TABLES}
    if not (sql.lower().startswith("select ") or sql in drops) or ";" in sql:
        raise ValueError("Only SELECT and the four authorized DROP statements are allowed")
    url = "http://127.0.0.1:9000/exec?" + urllib.parse.urlencode({"query": sql})
    proc = subprocess.run(
        ["curl.exe", "--noproxy", "*", "--silent", "--show-error", "--max-time", "20", url],
        capture_output=True, timeout=26,
    )
    record = {"at": datetime.now(timezone.utc).isoformat(), "sql": sql,
              "exit_code": proc.returncode}
    if proc.returncode:
        record["error"] = proc.stderr.decode(errors="replace")
        result = None
    else:
        result = json.loads(proc.stdout)
        record["result"] = result
    with (ROOT / "retirement-queries.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record) + "\n")
    if proc.returncode:
        raise RuntimeError(record["error"])
    if "error" in result:
        raise RuntimeError(result["error"])
    names = [column["name"] for column in result.get("columns", [])]
    return [dict(zip(names, row)) for row in result.get("dataset", [])]


def validate_views():
    metadata = {row["view_name"]: row for row in query("select * from materialized_views()")}
    for view, base in BASES.items():
        row = metadata[view]
        if (row["base_table_name"] != base or row["view_status"] != "valid"
                or row["refresh_base_table_txn"] != row["base_table_txn"]):
            raise RuntimeError("Native MV is not ready: " + view)
    aliases = {row["view_name"]: row for row in query("select * from views()")}
    for alias, target in (("v_market_breadth_daily", "mv_market_breadth_daily_v1"),
                          ("v_retail_sentiment_daily", "mv_retail_sentiment_daily_v1")):
        row = aliases[alias]
        if row["view_status"] != "valid" or row["view_sql"].strip() != "SELECT * FROM " + target:
            raise RuntimeError("Public alias is not bound to the required native MV: " + alias)
    return metadata


def main():
    validation = json.loads((ROOT / "validation.json").read_text(encoding="utf-8"))
    if validation.get("passed") is not True:
        raise RuntimeError("Source checks and live direct-reader acceptance must pass before DROP")
    coverage = json.loads((ROOT / "coverage-pre-drop.json").read_text(encoding="utf-8"))
    if (coverage.get("complete") is not True
            or coverage.get("state_unchanged") is not True
            or coverage.get("all_cache_business_keys_covered") is not True):
        raise RuntimeError("Full cache business-key coverage must pass before DROP")
    if (coverage.get("current_source_validation", {}).get("current_source_agrees") is not True
            or coverage.get("current_source_validation", {}).get("source_state_unchanged") is not True):
        raise RuntimeError("Current formal values must agree with their sources before DROP")
    current_health = query(HEALTH_SQL)
    if current_health != coverage["after"]:
        raise RuntimeError("Target or native source changed since full coverage verification; recheck before DROP")
    for row in current_health:
        if (row["table_suspended"] or row["suspended"] or row["wal_pending_row_count"]
                or row["bufferedTxnSize"]
                or row["table_txn"] != row["writerTxn"]
                or row["writerTxn"] != row["sequencerTxn"]):
            raise RuntimeError("Target or native source WAL is not settled: " + row["table_name"])
    initial = json.loads((ROOT.parent / "metadata.json").read_text(encoding="utf-8"))
    expected = {row["table_name"]: row for row in initial["tables"]}
    before = {row["table_name"]: row for row in query("select * from tables()")}
    for table in TABLES:
        row = before[table]
        if row["id"] != expected[table]["id"] or row["table_type"] != "T" or row["matView"]:
            raise RuntimeError("Target identity or object kind changed: " + table)
    for table in PRESERVED:
        if table not in before:
            raise RuntimeError("Required retained object is absent: " + table)
    pre_views = validate_views()
    result = {"started_at": datetime.now(timezone.utc).isoformat(),
              "before_targets": {name: before[name] for name in TABLES},
              "before_preserved": {name: before[name] for name in PRESERVED},
              "pre_views": pre_views, "coverage_health_pin": current_health, "dropped": []}
    for table in TABLES:
        query("DROP TABLE " + table)
        result["dropped"].append(table)
        (ROOT / "retirement-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    after = {row["table_name"]: row for row in query("select * from tables()")}
    if any(name in after for name in TABLES):
        raise RuntimeError("Retired cache still exists")
    for name in PRESERVED:
        if name not in after or before[name]["id"] != after[name]["id"]:
            raise RuntimeError("Retained object identity changed: " + name)
    result.update({"finished_at": datetime.now(timezone.utc).isoformat(), "passed": True,
                   "after_preserved": {name: after[name] for name in PRESERVED},
                   "post_views": validate_views()})
    (ROOT / "retirement-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"passed": True, "dropped": TABLES, "preserved": PRESERVED}))


if __name__ == "__main__":
    main()
