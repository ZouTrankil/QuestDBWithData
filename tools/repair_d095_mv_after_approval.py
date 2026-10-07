"""Guarded formal D095 MV repair. Execute only after explicit approval.

Requires the same APP_QUESTDB_* environment as read-only audits and --execute.
No retry is issued after a submitted FULL refresh; timeout is reported as in_doubt.
"""
import argparse
import base64
import hashlib
import json
import math
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

SOURCE_ROOT = Path("D:/work/fund_2/back-monitor/src")
sys.path.insert(0, str(SOURCE_ROOT))
from quant_platform.data.adapters.questdb.market_barometer_views import VIEW_SELECTS

MV = "mv_market_breadth_daily_v1"
AUDIT = Path("artifacts/java-migration/D095/commands/formal-readonly-audit-20260930.json")
RESULT = Path("artifacts/java-migration/D095/commands/formal-repair-result.json")


def query(sql, *, timeout=120):
    token = base64.b64encode((os.environ["APP_QUESTDB_USERNAME"] + ":"
                              + os.environ["APP_QUESTDB_PASSWORD"]).encode()).decode()
    url = f"http://{os.environ.get('APP_QUESTDB_HOST', '127.0.0.1')}:9000/exec?" + urlencode(
        {"query": sql, "limit": "0,10000"})
    with urlopen(Request(url, headers={"Authorization": "Basic " + token}), timeout=timeout) as response:
        payload = json.load(response)
    if "error" in payload:
        raise RuntimeError(payload["error"])
    names = [column["name"] for column in payload.get("columns", [])]
    rows = [dict(zip(names, row)) for row in payload.get("dataset", [])]
    if len(rows) >= 10000:
        raise RuntimeError("QuestDB result reached explicit 10000-row cap; do not treat it as complete")
    return rows


def state():
    rows = query(f"SELECT * FROM materialized_views() WHERE view_name='{MV}'")
    if len(rows) != 1:
        raise RuntimeError("D095 MV is missing or duplicate")
    return rows[0]


def table_identity(table):
    rows = query(f"SELECT * FROM tables() WHERE table_name='{table}'")
    if len(rows) != 1:
        raise RuntimeError(f"Missing table: {table}")
    return rows[0]


def version(snapshot):
    """Include the MV's own commit: RANGE does not advance the base checkpoint."""
    tables = tuple(tuple((field, snapshot[name].get(field)) for field in (
        "id", "table_name", "table_txn", "table_row_count", "table_min_timestamp",
        "table_max_timestamp", "table_suspended", "partitionBy", "walEnabled", "dedup",
    )) for name in ("source", "mv"))
    mv_state = tuple((field, snapshot["state"].get(field)) for field in (
        "view_table_dir_name", "base_table_name", "view_sql", "view_status",
        "invalidation_reason", "base_table_txn", "refresh_base_table_txn",
        "last_refresh_start_timestamp", "last_refresh_finish_timestamp",
    ))
    return tables, mv_state


def snapshot():
    before = state()
    source = table_identity("stk_factor")
    target = table_identity(MV)
    after = state()
    first = {"source": source, "mv": target, "state": before}
    last = {"source": source, "mv": target, "state": after}
    if version(first) != version(last):
        raise RuntimeError("MV changed while capturing the verification boundary")
    return last


def synchronized(snapshot):
    mv_state = snapshot["state"]
    return (mv_state["view_status"] == "valid" and
            mv_state["refresh_base_table_txn"] == mv_state["base_table_txn"] and
            snapshot["source"]["table_txn"] == mv_state["base_table_txn"])


def verify_stable_parity(source_sql, mv_sql, attempts=3):
    """Retry SELECTs only; never resubmit the FULL operation after instability."""
    observed = []
    for attempt in range(1, attempts + 1):
        try:
            before = snapshot()
            if not synchronized(before):
                raise RuntimeError("MV is not synchronized at verification start")
            source_rows = query(source_sql, timeout=300)
            middle = snapshot()
            mv_rows = query(mv_sql, timeout=300)
            after = snapshot()
            stable = (version(before) == version(middle) == version(after) and
                      synchronized(middle) and synchronized(after))
            entry = {"attempt": attempt, "before": before, "between_reads": middle,
                     "after": after, "versions_stable": stable}
            observed.append(entry)
            if stable:
                return {"versions_stable": True, "attempts": observed,
                        "full_parity": compare_all(source_rows, mv_rows),
                        "snapshot": after}
        except RuntimeError as exc:
            observed.append({"attempt": attempt, "versions_stable": False,
                             "reason": str(exc)})
        if attempt < attempts:
            time.sleep(0.5)
    return {"versions_stable": False, "attempts": observed}


def compare_all(source, target):
    a = {row["trade_date"]: row for row in source}
    b = {row["trade_date"]: row for row in target}
    if len(a) != len(source) or len(b) != len(target):
        raise RuntimeError("Duplicate calendar-day bucket")
    missing = sorted(a.keys() - b.keys())
    extra = sorted(b.keys() - a.keys())
    mismatch = []
    checked = 0
    for day in sorted(a.keys() & b.keys()):
        for field in ("stock_count", "up_count", "down_count", "flat_count",
                      "avg_pct_change", "total_amount_yi"):
            x, y = a[day][field], b[day][field]
            equal = (x is None and y is None) if x is None or y is None else (
                math.isclose(x, y, rel_tol=1e-10, abs_tol=1e-8)
                if isinstance(x, float) or isinstance(y, float) else x == y)
            checked += 1
            if not equal and len(mismatch) < 20:
                mismatch.append({"date": day, "field": field, "source": x, "mv": y})
    return {"source_days": len(source), "mv_days": len(target),
            "compared_values": checked, "missing_days": missing[:20],
            "extra_days": extra[:20], "mismatches": mismatch,
            "passed": not (missing or extra or mismatch)}


def save(result):
    RESULT.parent.mkdir(parents=True, exist_ok=True)
    RESULT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true", help="Submit one formal FULL refresh")
    args = parser.parse_args()
    expected = json.loads(AUDIT.read_text(encoding="utf-8"))
    if os.environ.get("APP_QUESTDB_HOST") != "127.0.0.1":
        raise RuntimeError("D095 repair is pinned to the audited local QuestDB host")
    current = state()
    alias = query("SELECT view_sql FROM views() WHERE view_name='v_market_breadth_daily'")
    source = table_identity("stk_factor")
    target = table_identity(MV)
    if len(alias) != 1 or alias[0]["view_sql"].strip() != f"SELECT * FROM {MV}":
        raise RuntimeError("Public alias changed; re-audit before repair")
    canonical = VIEW_SELECTS["v_market_breadth_daily"].format(where="").strip()
    sha = hashlib.sha256(canonical.encode()).hexdigest()
    if sha != expected["python_sql_sha256"] or current["view_sql"].strip() != canonical:
        raise RuntimeError("Owner SQL or formal MV definition changed; re-audit before repair")
    if current["base_table_name"] != "stk_factor" or current["view_status"] != "invalid":
        raise RuntimeError("Expected invalid stk_factor MV; re-audit before repair")
    audited_tables = {item["table_name"]: item for item in expected["tables"]}
    for name, observed in (("stk_factor", source), (MV, target)):
        audited = audited_tables[name]
        for field in ("table_row_count", "table_txn", "table_max_timestamp"):
            if observed[field] != audited[field]:
                raise RuntimeError(f"{name} changed since formal audit; re-audit before repair")
    if (current["base_table_txn"] != expected["mv_state"]["base_table_txn"] or
            current["refresh_base_table_txn"] != expected["mv_state"]["refresh_base_table_txn"] or
            current["invalidation_reason"] != expected["mv_state"]["invalidation_reason"]):
        raise RuntimeError("MV state changed since formal audit; re-audit before repair")
    if not args.execute:
        print(json.dumps({"ready": True, "action": f"REFRESH MATERIALIZED VIEW {MV} FULL",
                          "source_rows": source["table_row_count"], "target_rows": target["table_row_count"],
                          "state": current["view_status"], "base_txn": current["base_table_txn"]}))
        return
    if RESULT.exists():
        previous = json.loads(RESULT.read_text(encoding="utf-8"))
        if previous.get("submitted") is not False:
            raise RuntimeError("Prior FULL submission may exist; inspect it before any retry")
    result = {"task_id": "D095", "started_at": datetime.now(timezone.utc).isoformat(),
              "operation": f"REFRESH MATERIALIZED VIEW {MV} FULL", "submitted": False,
              "status": "preflight_passed", "source_before": source, "mv_before": target,
              "state_before": current}
    save(result)
    # Mark uncertainty before the network call: a timeout can occur after server acceptance.
    result["submitted"] = "unknown"
    result["status"] = "in_doubt"
    save(result)
    # FULL is intentionally issued once. QuestDB runs it asynchronously.
    query(f"REFRESH MATERIALIZED VIEW {MV} FULL")
    result["submitted"] = True
    result["status"] = "in_doubt"
    save(result)
    deadline = time.monotonic() + 15 * 60
    while time.monotonic() < deadline:
        now = state()
        if now["view_status"] == "valid" and now["refresh_base_table_txn"] == now["base_table_txn"]:
            break
        if now["view_status"] == "invalid" and now["invalidation_reason"] != current["invalidation_reason"]:
            raise RuntimeError("FULL refresh failed with a new invalidation reason")
        time.sleep(2)
    else:
        result["state_after_timeout"] = state()
        save(result)
        raise TimeoutError("FULL refresh still pending; inspect state, do not resubmit")
    source_sql = VIEW_SELECTS["v_market_breadth_daily"].format(where="") + " ORDER BY trade_date"
    fields = "trade_date,stock_count,up_count,down_count,flat_count,avg_pct_change,total_amount_yi"
    verification = verify_stable_parity(source_sql, f"SELECT {fields} FROM {MV} ORDER BY trade_date")
    result["verification"] = verification
    if verification["versions_stable"]:
        checked_snapshot = verification["snapshot"]
        result["full_parity"] = verification["full_parity"]
        result["state_after"] = checked_snapshot["state"]
        result["source_after"] = checked_snapshot["source"]
        result["mv_after"] = checked_snapshot["mv"]
    result["finished_at"] = datetime.now(timezone.utc).isoformat()
    result["status"] = ("verification_inconclusive" if not verification["versions_stable"] else
                        "verified" if verification["full_parity"]["passed"] else "failed")
    save(result)
    print(json.dumps({"status": result["status"], "full_parity": result.get("full_parity"),
                      "versions_stable": verification["versions_stable"]}, ensure_ascii=False))
    if result["status"] != "verified":
        raise RuntimeError("MV full parity failed or verification versions changed; do not resubmit FULL")


if __name__ == "__main__":
    main()
