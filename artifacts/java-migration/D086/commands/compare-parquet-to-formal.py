from __future__ import annotations

import json
import math
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import pyarrow.dataset as ds
import psycopg2

PROJECT = Path(r"D:/work/fund_2/back-monitor")
ROOT = PROJECT / "artifacts/level2_t0_dataset/l2_daily_features/year=2026/month=09"
SYMBOL = "000001.SZ"
DAYS = ["20260921", "20260922", "20260923", "20260924"]


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
        raise RuntimeError("Source QuestDB credentials are unavailable")
    return values["QUESTDB_USER"], values["QUESTDB_PASSWORD"]


def is_null(value: object) -> bool:
    return value is None or (isinstance(value, float) and math.isnan(value))


def equal(column: str, source: object, target: object) -> bool:
    if is_null(source) or is_null(target):
        return is_null(source) and is_null(target)
    if column == "ts":
        left = source.isoformat()[:10] if hasattr(source, "isoformat") else str(source)[:10]
        right = target.isoformat()[:10] if hasattr(target, "isoformat") else str(target)[:10]
        return left == right
    if isinstance(source, (int, float, Decimal)) and isinstance(target, (int, float, Decimal)):
        return math.isclose(float(source), float(target), rel_tol=1e-6, abs_tol=1e-9)
    return str(source) == str(target)


def main() -> None:
    user, password = credentials()
    connection = psycopg2.connect(host="127.0.0.1", port=8812, dbname="qdb", user=user,
                                  password=password, sslmode="disable", connect_timeout=10)
    mismatches: list[dict[str, str]] = []
    per_date = []
    total_fields = 0
    try:
        for basic in DAYS:
            partition = ROOT / f"trade_date={basic}"
            source_dataset = ds.dataset(str(partition), format="parquet")
            source_rows = source_dataset.to_table(
                filter=ds.field("symbol") == SYMBOL
            ).to_pylist()
            date_text = datetime.strptime(basic, "%Y%m%d").strftime("%Y-%m-%d")
            cursor = connection.cursor()
            cursor.execute(
                "SELECT * FROM l2_daily_features WHERE ts IN %s AND symbol IN ('%s')"
                % ("'" + date_text + "'", SYMBOL)
            )
            columns = [column.name for column in cursor.description]
            target_rows = [dict(zip(columns, row)) for row in cursor.fetchall()]
            cursor.close()
            if columns != source_dataset.schema.names:
                mismatches.append({"date": basic, "field": "schema_order"})
            if len(source_rows) != 1 or len(target_rows) != 1:
                mismatches.append({"date": basic, "field": "row_count_or_key"})
                per_date.append({"date": basic, "source_rows": len(source_rows), "target_rows": len(target_rows)})
                continue
            row_bad = 0
            for column in source_dataset.schema.names:
                total_fields += 1
                if not equal(column, source_rows[0].get(column), target_rows[0].get(column)):
                    mismatches.append({"date": basic, "field": column})
                    row_bad += 1
            per_date.append({"date": basic, "source_rows": 1, "target_rows": 1,
                             "columns": len(columns), "mismatches": row_bad})
    finally:
        connection.close()

    report = {
        "source_dataset": str(PROJECT / "artifacts/level2_t0_dataset/l2_daily_features"),
        "target_table": "l2_daily_features",
        "target_read_only": True,
        "symbol": SYMBOL,
        "dates": DAYS,
        "comparison": "all 110 fields; null and floating NaN are equivalent; doubles use rel_tol=1e-6 and abs_tol=1e-9",
        "source_rows": sum(row.get("source_rows", 0) for row in per_date),
        "target_rows": sum(row.get("target_rows", 0) for row in per_date),
        "fields_compared": total_fields,
        "mismatches": mismatches,
        "per_date": per_date,
        "passed": not mismatches and total_fields == 440,
    }
    output = Path(r"C:/Users/zouqiang/IdeaProjects/QuestDBWithData/artifacts/java-migration/D086/commands/parquet-vs-formal-readback.json")
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "fields_compared": total_fields,
                      "mismatches": len(mismatches)}, separators=(",", ":")))
    if not report["passed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
