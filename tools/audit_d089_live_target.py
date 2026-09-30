"""Read-only D089 QuestDB metadata and row-count audit for the isolated live target."""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
TARGET_PREFIX = "java_d089_l2_t0_training_labels_"


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True)
    parser.add_argument("--preflight", type=Path, required=True)
    parser.add_argument("--acceptance", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not IDENTIFIER.fullmatch(args.target) or not args.target.startswith(TARGET_PREFIX):
        raise ValueError("D089 audit requires the exact isolated java_d089 target name")

    host = os.environ.get("APP_QUESTDB_HOST", "127.0.0.1")
    port = os.environ.get("APP_QUESTDB_QWPPORT", "9000")
    username = os.environ.get("APP_QUESTDB_USERNAME", "admin")
    password = os.environ.get("APP_QUESTDB_PASSWORD")
    if not password:
        raise ValueError("QuestDB password must be supplied through the local environment")
    authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    endpoint = f"http://{host}:{port}/exec"

    def query(sql: str):
        if not sql.startswith("SELECT ") or ";" in sql:
            raise ValueError("Only one read-only SELECT is allowed")
        request = Request(endpoint + "?" + urlencode({"query": sql}),
                          headers={"Authorization": authorization})
        with urlopen(request, timeout=30) as response:
            payload = json.load(response)
        if "error" in payload:
            raise RuntimeError("QuestDB rejected a bounded D089 audit query")
        names = [column["name"] for column in payload["columns"]]
        return [dict(zip(names, row)) for row in payload["dataset"]]

    before = read_json(args.preflight)
    acceptance = read_json(args.acceptance)
    formal_table = "l2_t0_training_labels"
    formal_before = before["data"]["dataset"][0][0]
    expected_rows = acceptance["targetReadbackRows"]

    def table_info(name: str):
        rows = query(f"SELECT * FROM tables() WHERE table_name='{name}'")
        if len(rows) != 1:
            raise RuntimeError(f"Expected exactly one QuestDB table metadata row for {name}")
        metadata = query(f"SELECT * FROM table_columns('{name}')")
        return rows[0], metadata

    formal_meta, formal_columns = table_info(formal_table)
    target_meta, target_columns = table_info(args.target)
    quoted_target = '"' + args.target + '"'
    target_data = query(f"SELECT count() AS row_count, min(minute) AS min_minute, max(minute) AS max_minute FROM {quoted_target}")
    formal_data = query(f'SELECT count() AS row_count, min(minute) AS min_minute, max(minute) AS max_minute FROM "{formal_table}"')
    if len(target_data) != 1 or len(formal_data) != 1:
        raise RuntimeError("QuestDB count query did not return exactly one aggregate row")

    formal_schema = [(row["column"], row["type"], row["designated"], row["upsertKey"])
                     for row in formal_columns]
    target_schema = [(row["column"], row["type"], row["designated"], row["upsertKey"])
                     for row in target_columns]
    live_rows = target_data[0]["row_count"]
    formal_after = formal_data[0]["row_count"]
    assertions = {
        "targetRowsMatchAcceptance": live_rows == expected_rows,
        "targetColumnsMatchFormal": target_schema == formal_schema,
        "targetSchemaHas62Columns": len(target_schema) == 62,
        "minuteIsDesignated": sum(bool(row[2]) for row in target_schema) == 1
            and next(row[0] for row in target_schema if row[2]) == "minute",
        "dedupKeysAreSymbolMinute": sorted(row[0] for row in target_schema if row[3]) == ["minute", "symbol"],
        "targetPartitionDay": str(target_meta.get("partitionBy", "")).upper() == "DAY",
        "targetWalEnabled": bool(target_meta.get("walEnabled", False)),
        "targetDedupEnabled": bool(target_meta.get("dedup", False)),
        "formalRowCountUnchanged": formal_after == formal_before,
    }
    report = {
        "status": "VERIFIED" if all(assertions.values()) else "FAILED",
        "access": "read_only_http_select",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "target": args.target,
        "targetTableMetadata": target_meta,
        "targetColumns": target_columns,
        "targetData": target_data[0],
        "formalTable": formal_table,
        "formalTableMetadata": formal_meta,
        "formalColumnCount": len(formal_columns),
        "formalRowCountBefore": formal_before,
        "formalDataAfter": formal_data[0],
        "acceptanceSourceFingerprint": acceptance["sourceFingerprint"],
        "acceptanceSchemaFingerprint": acceptance["schemaFingerprint"],
        "assertions": assertions,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "target": args.target,
                      "targetRows": live_rows, "formalRowsBefore": formal_before,
                      "formalRowsAfter": formal_after, "assertions": assertions}, ensure_ascii=False))
    return 0 if all(assertions.values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
