from __future__ import annotations

import json
from pathlib import Path
from datetime import timezone
from zoneinfo import ZoneInfo

import pyarrow.dataset as ds
import psycopg2

PROJECT = Path(r"D:/work/fund_2/back-monitor")
ROOT = PROJECT / "artifacts/level2_t0_dataset"
JAVA = Path(r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData")
DATE = "20260921"
SYMBOL = "000001.SZ"


def credentials() -> tuple[str, str]:
    values: dict[str, str] = {}
    for line in (PROJECT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key not in {"QUESTDB_USER", "QUESTDB_PASSWORD"}:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    if set(values) != {"QUESTDB_USER", "QUESTDB_PASSWORD"}:
        raise RuntimeError("QuestDB credentials are unavailable")
    return values["QUESTDB_USER"], values["QUESTDB_PASSWORD"]


def main() -> None:
    manifest_dir = ROOT / "l2_dataset_manifest" / f"year={DATE[:4]}" / f"month={DATE[4:6]}" / f"trade_date={DATE}"
    manifest = ds.dataset(str(manifest_dir), format="parquet")
    manifest_table = manifest.to_table(
        columns=["trade_date", "symbol", "batch_id", "t0_ok", "output_paths", "errors"],
        filter=ds.field("symbol") == SYMBOL,
    )
    receipts = manifest_table.to_pylist()
    intraday_dir = ROOT / "l2_intraday_bar_features" / f"year={DATE[:4]}" / f"month={DATE[4:6]}" / f"trade_date={DATE}"
    selected = []
    for receipt in receipts:
        paths = receipt.get("output_paths")
        if isinstance(paths, str):
            paths = json.loads(paths)
        raw_path = (paths or {}).get("intraday_bars")
        if receipt.get("t0_ok") and raw_path:
            part = Path(raw_path)
            if not part.is_absolute():
                part = ROOT / part
            if part.is_file() and part.parent == intraday_dir:
                selected.append({"batch_id": receipt["batch_id"], "part": part, "bytes": part.stat().st_size})
    if not selected:
        raise RuntimeError("No complete D085 receipt-backed D087 source part found for selected date/symbol")
    selected.sort(key=lambda item: item["batch_id"])
    newest = selected[-1]
    parquet = ds.dataset(str(newest["part"]), format="parquet")
    table = parquet.to_table(filter=ds.field("symbol") == SYMBOL)
    rows = table.to_pylist()
    if not rows:
        raise RuntimeError("Receipt-backed source part contains no rows for selected symbol")
    minute_values = [row["minute"] for row in rows]
    keys = [(row["symbol"], row["minute"]) for row in rows]
    if len(keys) != len(set(keys)):
        raise RuntimeError("Duplicate symbol/minute keys in selected source part")

    user, password = credentials()
    connection = psycopg2.connect(
        host="127.0.0.1", port=8812, dbname="qdb", user=user, password=password,
        sslmode="disable", connect_timeout=10,
    )
    try:
        cursor = connection.cursor()
        cursor.execute("SHOW COLUMNS FROM l2_intraday_bar_features")
        live_columns = [{"name": row[0], "type": row[1]} for row in cursor.fetchall()]
        cursor.execute("SELECT count(), min(minute), max(minute) FROM l2_intraday_bar_features")
        census = cursor.fetchone()
        cursor.execute(
            "SELECT * FROM l2_intraday_bar_features WHERE symbol = %s AND trade_date = %s ORDER BY minute",
            (SYMBOL, DATE),
        )
        sample = cursor.fetchall()
        sample_columns = [item[0] for item in cursor.description]
    finally:
        connection.close()

    formal_by_key = {row[sample_columns.index("minute")]: dict(zip(sample_columns, row)) for row in sample}
    source_by_key = {}
    for row in rows:
        local_minute = row["minute"]
        utc_minute = local_minute.replace(tzinfo=ZoneInfo("Asia/Shanghai")).astimezone(timezone.utc).replace(tzinfo=None)
        normalized = dict(row)
        normalized["minute"] = utc_minute
        for column in table.column_names:
            value = normalized[column]
            if value is None:
                continue
            if column in {"tick_count", "has_trade_1m"}:
                normalized[column] = int(value)
            elif column not in {"trade_date", "symbol", "market", "board", "minute"}:
                normalized[column] = float(value)
        source_by_key[utc_minute] = normalized
    compared_rows = 0
    mismatches = []
    if len(source_by_key) == len(rows) and len(formal_by_key) == len(sample):
        for minute, source_row in source_by_key.items():
            formal_row = formal_by_key.get(minute)
            if formal_row is None:
                mismatches.append({"minute": str(minute), "field": "<missing-key>"})
                continue
            compared_rows += 1
            for column in table.column_names:
                expected, actual = source_row[column], formal_row[column]
                if expected is None and actual is None:
                    continue
                if expected is None or actual is None:
                    equal = False
                elif isinstance(expected, float) and isinstance(actual, (float, int)):
                    equal = abs(expected - float(actual)) <= max(1e-12, abs(expected) * 1e-12)
                else:
                    equal = expected == actual
                if not equal:
                    mismatches.append({"minute": str(minute), "field": column,
                                       "source": str(expected), "questdb": str(actual)})
                    if len(mismatches) >= 100:
                        break
            if len(mismatches) >= 100:
                break
    else:
        mismatches.append({"field": "<key-coverage>", "source_keys": len(source_by_key), "questdb_keys": len(formal_by_key)})

    report = {
        "dataset": "l2_intraday_bar_features",
        "source_root": str(ROOT),
        "selected_date": DATE,
        "selected_symbol": SYMBOL,
        "manifest_rows": len(receipts),
        "complete_receipt_parts": len(selected),
        "selected_part": str(newest["part"]),
        "selected_part_bytes": newest["bytes"],
        "selected_row_count": len(rows),
        "selected_minute_range": [min(minute_values).isoformat(), max(minute_values).isoformat()],
        "selected_source_columns": [{"name": field.name, "type": str(field.type)} for field in table.schema],
        "unique_symbol_minute_keys": len(set(keys)),
        "live_questdb_columns": live_columns,
        "live_questdb_census": {"rows": int(census[0]), "min_minute": str(census[1]), "max_minute": str(census[2])},
        "formal_sample_rows_for_selected_symbol_date": len(sample),
        "formal_full_field_comparison": {
            "compared_rows": compared_rows,
            "compared_fields": len(table.column_names),
            "matched_values": compared_rows * len(table.column_names) - len(mismatches),
            "mismatches": mismatches,
            "passed": compared_rows == len(rows) == len(sample) and not mismatches,
            "minute_conversion": "Asia/Shanghai local Parquet wall-time -> UTC QuestDB TIMESTAMP",
        },
        "live_formal_sample": [dict(zip(sample_columns, row)) for row in sample[:3]],
        "formal_sample_columns": sample_columns,
    }
    out = JAVA / "artifacts/java-migration/D087/commands/preflight-source-20260930.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, default=str, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "selected_rows": len(rows), "selected_part_bytes": newest["bytes"],
        "live_columns": len(live_columns), "live_sample_rows": len(sample),
        "output": str(out),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
