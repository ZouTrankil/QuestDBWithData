"""Read-only comparison of the legacy backtest_daily table with its active view."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen


FIELDS = ["trade_date", "ts_code", "open", "high", "low", "close", "vol", "amount",
          "adj_factor", "up_limit", "down_limit", "is_suspended", "is_st"]


def canonical(row):
    result = []
    for field in FIELDS:
        value = row.get(field)
        if isinstance(value, (float, int)) and not isinstance(value, bool):
            value = float(value)
        result.append(value)
    return result


def digest(rows):
    payload = json.dumps([canonical(row) for row in rows], sort_keys=False,
                         ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(payload.encode()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    host = os.environ.get("APP_QUESTDB_HOST", "127.0.0.1")
    port = os.environ.get("APP_QUESTDB_QWPPORT", "9000")
    username = os.environ.get("APP_QUESTDB_USERNAME", "admin")
    password = os.environ.get("APP_QUESTDB_PASSWORD")
    if not password:
        raise ValueError("QuestDB password must be supplied through the local environment")
    auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    endpoint = f"http://{host}:{port}/exec"

    def query(sql):
        if not sql.startswith("SELECT ") or ";" in sql:
            raise ValueError("Only single read-only SELECT queries are allowed")
        request = Request(endpoint + "?" + urlencode({"query": sql}), headers={"Authorization": auth})
        with urlopen(request, timeout=45) as response:
            payload = json.load(response)
        if "error" in payload:
            raise RuntimeError("QuestDB rejected a D090 read-only query")
        names = [column["name"] for column in payload["columns"]]
        return [dict(zip(names, row)) for row in payload["dataset"]]

    formal = "backtest_daily"
    view = "v_backtest_daily"
    cache = "backtest_daily_cache"
    formal_meta = query(f"SELECT * FROM tables() WHERE table_name='{formal}'")
    cache_meta = query(f"SELECT * FROM tables() WHERE table_name='{cache}'")
    view_meta = query(f"SELECT * FROM views() WHERE view_name='{view}'")
    formal_columns = query(f"SELECT * FROM table_columns('{formal}')")
    view_columns = query(f"SELECT * FROM table_columns('{view}')")
    formal_aggregate = query(
        f'SELECT count() AS row_count, min(trade_date) AS min_date, max(trade_date) AS max_date FROM "{formal}"'
    )[0]
    latest_day = str(formal_aggregate["max_date"])[:10]
    compact_day = latest_day.replace("-", "")
    projection = ", ".join('"' + field + '"' for field in FIELDS)
    day_predicate = f"trade_date = to_timestamp('{compact_day}', 'yyyyMMdd')"
    formal_sample = query(
        f'SELECT {projection} FROM "{formal}" WHERE {day_predicate} ORDER BY ts_code LIMIT 200'
    )
    view_sample = query(
        f'SELECT {projection} FROM "{view}" WHERE {day_predicate} ORDER BY ts_code LIMIT 200'
    )
    formal_by_key = {(str(row["trade_date"]), str(row["ts_code"])): canonical(row) for row in formal_sample}
    view_by_key = {(str(row["trade_date"]), str(row["ts_code"])): canonical(row) for row in view_sample}
    formal_keys = set(formal_by_key)
    view_keys = set(view_by_key)
    common = formal_keys & view_keys
    matched = sum(formal_by_key[key] == view_by_key[key] for key in common)
    duplicate_formal = len(formal_sample) != len(formal_keys)
    duplicate_view = len(view_sample) != len(view_keys)
    assertions = {
        "formalTableExists": len(formal_meta) == 1,
        "activeViewExists": len(view_meta) == 1,
        "versionedCacheExists": len(cache_meta) == 1,
        "formalSchemaHas13Columns": len(formal_columns) == len(FIELDS),
        "viewSchemaHas13Columns": len(view_columns) == len(FIELDS),
        "sampleKeysUnique": not duplicate_formal and not duplicate_view,
        "formalSampleNonempty": len(formal_sample) > 0,
        "viewContainsFormalSampleKeys": formal_keys.issubset(view_keys),
        "overlapValuesEqual": matched == len(common),
        "overlapRows": len(common),
        "overlapMatchedRows": matched,
    }
    passed = all(value for key, value in assertions.items()
                 if key not in {"overlapRows", "overlapMatchedRows"}) and len(common) > 0
    report = {
        "task_id": "D090",
        "status": "VERIFIED" if passed else "FAILED",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "access": "read_only_http_select",
        "formalTable": formal,
        "formalTableMetadata": formal_meta[0] if formal_meta else None,
        "formalData": formal_aggregate,
        "formalColumns": formal_columns,
        "activeView": view,
        "activeViewMetadata": view_meta[0] if view_meta else None,
        "viewColumns": view_columns,
        "versionedReadthroughCache": cache,
        "cacheMetadata": cache_meta[0] if cache_meta else None,
        "sampleDay": latest_day,
        "sampleLimitPerSource": 200,
        "formalSampleRows": len(formal_sample),
        "viewSampleRows": len(view_sample),
        "formalSampleSha256": digest(formal_sample),
        "viewSampleSha256": digest(view_sample),
        "overlapRows": len(common),
        "overlapMatchedRows": matched,
        "formalKeysMissingFromView": len(formal_keys - view_keys),
        "viewKeysMissingFromFormalSample": len(view_keys - formal_keys),
        "duplicateFormalSampleKeys": duplicate_formal,
        "duplicateViewSampleKeys": duplicate_view,
        "assertions": assertions,
        "samples": {"formal": formal_sample[:3], "view": view_sample[:3]},
    }
    from pathlib import Path
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"task_id": report["task_id"], "formalRows": formal_aggregate["row_count"],
                      "formalRange": [formal_aggregate["min_date"], formal_aggregate["max_date"]],
                      "sampleDay": latest_day, "formalSampleRows": len(formal_sample),
                      "viewSampleRows": len(view_sample), "overlapRows": len(common),
                      "matchedRows": matched, "assertions": assertions}, ensure_ascii=False))
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
