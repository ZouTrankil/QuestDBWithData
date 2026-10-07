"""Read-only D095 audit of the configured local QuestDB and Python MV owner."""
import base64
import hashlib
import json
import math
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path("D:/work/fund_2/back-monitor/src")
sys.path.insert(0, str(ROOT))
from quant_platform.data.adapters.questdb.market_barometer_views import VIEW_SELECTS

OUTPUT = Path("artifacts/java-migration/D095/commands/formal-readonly-audit-20260930.json")
MV = "mv_market_breadth_daily_v1"
START, STOP = "2026-09-17", "2026-09-19"


def query(sql):
    auth = base64.b64encode(
        (os.environ["APP_QUESTDB_USERNAME"] + ":" + os.environ["APP_QUESTDB_PASSWORD"]).encode()
    ).decode()
    url = f"http://{os.environ.get('APP_QUESTDB_HOST', '127.0.0.1')}:9000/exec?" + urlencode({"query": sql})
    with urlopen(Request(url, headers={"Authorization": "Basic " + auth}), timeout=90) as response:
        payload = json.load(response)
    if "error" in payload:
        raise RuntimeError(payload["error"])
    names = [column["name"] for column in payload.get("columns", [])]
    return [dict(zip(names, row)) for row in payload.get("dataset", [])]


def main():
    sql = VIEW_SELECTS["v_market_breadth_daily"].format(where="")
    state_before = query(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
    if len(state_before) != 1:
        raise RuntimeError("Exactly one materialized view required")
    alias = query("SELECT view_name,view_sql,view_status FROM views() "
                  "WHERE view_name='v_market_breadth_daily'")
    if len(alias) != 1:
        raise RuntimeError("Exactly one public alias required")
    target = query("SELECT table_name,table_row_count,table_txn,partitionBy,walEnabled,dedup,"
                   "table_min_timestamp,table_max_timestamp,table_suspended "
                   f"FROM tables() WHERE table_name IN ('{MV}','stk_factor')")
    columns = query(f"SELECT \"column\",\"type\",designated,upsertKey "
                    f"FROM table_columns('{MV}')")
    source_columns = query("SELECT \"column\",\"type\",designated,upsertKey "
                           "FROM table_columns('stk_factor')")
    where = f"WHERE trade_date >= '{START}' AND trade_date < '{STOP}'"
    direct = query(VIEW_SELECTS["v_market_breadth_daily"].format(where=where)
                   + " ORDER BY trade_date")
    materialized = query("SELECT trade_date,stock_count,up_count,down_count,flat_count,"
                         f"avg_pct_change,total_amount_yi FROM {MV} {where} ORDER BY trade_date")
    gap_where = "WHERE trade_date >= '2026-09-19' AND trade_date < '2026-09-25'"
    source_gap = query(VIEW_SELECTS["v_market_breadth_daily"].format(where=gap_where)
                       + " ORDER BY trade_date")
    mv_gap = query("SELECT trade_date,stock_count,up_count,down_count,flat_count,"
                   f"avg_pct_change,total_amount_yi FROM {MV} {gap_where} ORDER BY trade_date")
    by_day = {row["trade_date"][:10]: row for row in direct}
    materialized_by_day = {row["trade_date"][:10]: row for row in materialized}
    values_match = by_day.keys() == materialized_by_day.keys()
    comparisons = 0
    for day in by_day.keys() & materialized_by_day.keys():
        for field in ("stock_count", "up_count", "down_count", "flat_count",
                      "avg_pct_change", "total_amount_yi"):
            a, b = by_day[day][field], materialized_by_day[day][field]
            values_match &= (a is None and b is None) if a is None or b is None else (
                math.isclose(a, b, rel_tol=1e-10, abs_tol=1e-8)
                if isinstance(a, float) or isinstance(b, float) else a == b)
            comparisons += 1
    state_after = query(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
    assert state_after == state_before, "MV state changed during read-only audit"
    result = {
        "task_id": "D095", "checked_at": datetime.now(timezone.utc).isoformat(),
        "mode": "formal_instance_read_only", "formal_mutated": False,
        "mv_state": state_before[0], "public_alias": alias[0],
        "python_sql_sha256": hashlib.sha256(sql.strip().encode()).hexdigest(),
        "catalog_sql_sha256": hashlib.sha256(state_before[0]["view_sql"].strip().encode()).hexdigest(),
        "catalog_sql_matches_python": state_before[0]["view_sql"].strip() == sql.strip(),
        "tables": target, "mv_columns": columns,
        "source_key_columns": [column for column in source_columns
                               if column["designated"] or column["upsertKey"]],
        "bounded_range": [START, STOP], "source_aggregate_rows": direct,
        "materialized_rows": materialized, "compared_values": comparisons,
        "bounded_values_match": values_match,
        "gap_range": ["2026-09-19", "2026-09-25"],
        "gap_source_aggregate_rows": source_gap, "gap_materialized_rows": mv_gap,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"mv_status": state_before[0]["view_status"],
                      "invalidation_reason": state_before[0]["invalidation_reason"],
                      "refresh_base_table_txn": state_before[0]["refresh_base_table_txn"],
                      "base_table_txn": state_before[0]["base_table_txn"],
                      "catalog_sql_matches_python": result["catalog_sql_matches_python"],
                      "bounded_values_match": values_match,
                      "gap_source_days": len(source_gap), "gap_mv_days": len(mv_gap),
                      "compared_values": comparisons}, ensure_ascii=False))


if __name__ == "__main__":
    main()
