#!/usr/bin/env python3
"""Bounded, read-only D086 reader for Python-materialized L2 feature Parquet."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

import pyarrow.dataset as ds
import pyarrow.parquet as pq

PROJECT_ROOT = Path(r"D:/work/fund_2/back-monitor")
PARSER_VERSION = "l2-daily-features-parquet-v1"
MAX_WINDOW_DAYS = 31
MAX_PAGE_ROWS = 200
DATE_RE = re.compile(r"^[0-9]{8}$")
SYMBOL_RE = re.compile(r"^[0-9]{6}\.(SH|SZ|BJ)$")
PART_RE = re.compile(r"^part-([0-9]{5})\.parquet$")


def source_schema() -> tuple[dict[str, str], str]:
    sys.path.insert(0, str(PROJECT_ROOT / "src"))
    from quant_platform.data.adapters.questdb.models.stock.l2_features import L2DailyFeatures

    schema = L2DailyFeatures.get_questdb_schema()["schema"]
    arrow_type = {
        "TIMESTAMP": "timestamp[ns]",
        "SYMBOL": "string",
        "STRING": "string",
        "BOOLEAN": "bool",
        "LONG": "int64",
        "DOUBLE": "double",
    }
    expected = {name: arrow_type[value] for name, value in schema.items()}
    canonical = json.dumps(list(expected.items()), separators=(",", ":"), ensure_ascii=False)
    return expected, hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def clean(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, float):
        if math.isnan(value):
            return None
        if not math.isfinite(value):
            raise ValueError("D086 source contains an infinite floating-point value")
        return value
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(k): clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [clean(v) for v in value]
    return value


def date_path(root: Path, dataset: str, day: date) -> Path:
    basic = day.strftime("%Y%m%d")
    return root / dataset / f"year={day:%Y}" / f"month={day:%m}" / f"trade_date={basic}"


def parquet_parts(directory: Path, label: str) -> list[Path]:
    if not directory.exists() or directory.is_symlink() or not directory.is_dir():
        raise FileNotFoundError(f"D086 {label} partition is unavailable")
    parts = sorted(directory.glob("part-*.parquet"))
    if not parts or any(PART_RE.fullmatch(path.name) is None or path.is_symlink() for path in parts):
        raise ValueError(f"D086 {label} partition has missing or unsafe Parquet parts")
    return parts


def table_for_day(root: Path, day: date, symbols: list[str], expected_schema: dict[str, str]) -> dict:
    manifest_parts = parquet_parts(date_path(root, "l2_dataset_manifest", day), "manifest")
    feature_parts = parquet_parts(date_path(root, "l2_daily_features", day), "feature")
    manifest_dir = date_path(root, "l2_dataset_manifest", day)
    feature_dir = date_path(root, "l2_daily_features", day)
    manifest = ds.dataset([str(path) for path in manifest_parts], format="parquet")
    if not {"trade_date", "symbol", "daily_feature_ok", "batch_id", "output_paths"}.issubset(manifest.schema.names):
        raise ValueError("D086 source manifest lacks the required per-symbol output receipt fields")
    source_rows = manifest.count_rows()
    selected = manifest.to_table(
        columns=["trade_date", "symbol", "daily_feature_ok", "batch_id", "output_paths"],
        filter=None if not symbols else ds.field("symbol").isin(symbols),
    ).to_pylist()
    seen_symbols: set[str] = set()
    expected_by_batch: dict[int, set[str]] = {}
    part_by_batch: dict[int, Path] = {}
    for item in selected:
        symbol = str(item["symbol"])
        batch_id = int(item["batch_id"])
        source_date = str(item["trade_date"]).replace("-", "")
        if source_date != day.strftime("%Y%m%d"):
            raise ValueError(f"D086 manifest trade date differs from its partition for {symbol}")
        if symbol in seen_symbols:
            raise ValueError(f"D086 manifest repeats a symbol for {day:%Y-%m-%d}")
        seen_symbols.add(symbol)
        if item["daily_feature_ok"] is not True:
            raise ValueError(f"D086 manifest does not certify the daily feature for {symbol} on {day:%Y-%m-%d}")
        output_paths = json.loads(item["output_paths"] or "{}")
        output_path = output_paths.get("daily_features")
        expected_path = feature_dir / f"part-{batch_id:05d}.parquet"
        if not output_path or Path(output_path).resolve() != expected_path.resolve():
            raise ValueError(f"D086 output receipt does not point to the requested source partition for {symbol}")
        if not expected_path.is_file() or expected_path.is_symlink():
            raise FileNotFoundError(f"D086 feature part is missing for {symbol} on {day:%Y-%m-%d}")
        match = PART_RE.fullmatch(expected_path.name)
        if match is None:
            raise ValueError("D086 feature batch part name is malformed")
        expected_by_batch.setdefault(batch_id, set()).add(symbol)
        part_by_batch[batch_id] = expected_path
    if symbols and seen_symbols != set(symbols):
        missing = sorted(set(symbols) - seen_symbols)
        raise ValueError(f"D086 manifest does not cover selected symbols: {','.join(missing[:8])}")
    if not selected:
        raise ValueError(f"D086 manifest selected no certified rows for {day:%Y-%m-%d}")

    rows: list[dict] = []
    for batch_id, expected_symbols in sorted(expected_by_batch.items()):
        path = part_by_batch[batch_id]
        part = ds.dataset(str(path), format="parquet")
        actual_schema = {field.name: str(field.type) for field in part.schema}
        if actual_schema != expected_schema:
            raise ValueError("D086 daily-feature Parquet schema differs from the frozen Python model")
        table = part.to_table(filter=ds.field("symbol").isin(sorted(expected_symbols)))
        batch_rows = table.to_pylist()
        actual_symbols = [str(row.get("symbol")) for row in batch_rows]
        if len(actual_symbols) != len(expected_symbols) or set(actual_symbols) != expected_symbols:
            raise ValueError(f"D086 manifest receipt and daily-feature rows disagree for batch {batch_id}")
        for row in batch_rows:
            if row.get("ts") is None or row["ts"].date() != day:
                raise ValueError(f"D086 Parquet ts differs from the partition date for {row.get('symbol')}")
            rows.append(clean(row))

    return {
        "day": day,
        "manifest_parts": manifest_parts,
        "feature_parts": sorted(set(part_by_batch.values())),
        "all_feature_parts": feature_parts,
        "source_rows": int(source_rows),
        "selected_rows": rows,
    }


def inspect(root: Path, start: date, end: date, symbols: list[str],
            max_rows: int, max_files: int, max_bytes: int, page_rows: int) -> dict:
    if end < start or (end - start).days >= MAX_WINDOW_DAYS:
        raise ValueError("D086 date range must be positive and shorter than 31 calendar days")
    if page_rows < 1 or page_rows > MAX_PAGE_ROWS:
        raise ValueError("D086 page size must be between 1 and 200")
    if len(symbols) > 1000 or len(set(symbols)) != len(symbols) or any(not SYMBOL_RE.fullmatch(s) for s in symbols):
        raise ValueError("D086 symbols must be unique canonical stock codes")
    root = root.resolve(strict=True)
    if not root.is_dir() or root.is_symlink():
        raise ValueError("D086 dataset root must be an existing regular directory")

    expected_schema, schema_fingerprint = source_schema()
    date_results = []
    day = start
    while day <= end:
        manifest_path = date_path(root, "l2_dataset_manifest", day)
        if manifest_path.exists():
            date_results.append(table_for_day(root, day, symbols, expected_schema))
        day += timedelta(days=1)

    if not date_results:
        raise FileNotFoundError("D086 requested range contains no source manifest partitions")
    selected_rows = [row for result in date_results for row in result["selected_rows"]]
    selected_rows.sort(key=lambda row: (row["ts"], row["symbol"]))
    keys = [(row["ts"], row["symbol"]) for row in selected_rows]
    if len(keys) != len(set(keys)):
        raise ValueError("D086 source has duplicate (ts,symbol) business keys")
    if len(selected_rows) > max_rows:
        raise ValueError("D086 selected rows exceed their bounded source budget")

    all_manifest_parts = sorted({p for result in date_results for p in result["manifest_parts"]})
    selected_feature_parts = sorted({p for result in date_results for p in result["feature_parts"]})
    source_files = sorted(set(all_manifest_parts + selected_feature_parts))
    source_bytes = sum(path.stat().st_size for path in source_files)
    if len(source_files) > max_files or source_bytes > max_bytes:
        raise ValueError("D086 source files or bytes exceed their bounded source budget")
    root_identity = str(root)
    file_manifest = []
    for path in source_files:
        file_manifest.append({
            "path": path.relative_to(root).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": digest_file(path),
        })
    fingerprint_payload = json.dumps(file_manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    source_fingerprint = hashlib.sha256(fingerprint_payload.encode("utf-8")).hexdigest()
    source_rows = sum(result["source_rows"] for result in date_results)
    pages = sum((len(result["selected_rows"]) + page_rows - 1) // page_rows for result in date_results)
    if pages < 1:
        raise ValueError("D086 selected source interval is empty")
    return {
        "from": start.strftime("%Y%m%d"),
        "to": end.strftime("%Y%m%d"),
        "dates": [result["day"].strftime("%Y%m%d") for result in date_results],
        "sourceRows": source_rows,
        "selectedRows": len(selected_rows),
        "files": len(source_files),
        "pages": pages,
        "sourceBytes": source_bytes,
        "sourceFingerprint": source_fingerprint,
        "schemaFingerprint": schema_fingerprint,
        "parserVersion": PARSER_VERSION,
        "completeForSelectedSymbols": True,
        "rootIdentity": hashlib.sha256(root_identity.encode("utf-8")).hexdigest(),
        "_dateResults": date_results,
        "_rows": selected_rows,
        "_fileManifest": file_manifest,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--from-date", required=True)
    parser.add_argument("--to-date", required=True)
    parser.add_argument("--page-rows", type=int, default=MAX_PAGE_ROWS)
    parser.add_argument("--max-files", type=int, required=True)
    parser.add_argument("--max-rows", type=int, required=True)
    parser.add_argument("--max-bytes", type=int, required=True)
    parser.add_argument("--max-output-bytes", type=int, required=True)
    parser.add_argument("--symbol", action="append", default=[])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect", action="store_true")
    mode.add_argument("--stream", action="store_true")
    parser.add_argument("--expected-fingerprint")
    args = parser.parse_args()
    if args.max_output_bytes < 1 or args.max_files < 1 or args.max_rows < 1 or args.max_bytes < 1:
        raise ValueError("D086 source budgets must be positive")
    output_bytes = 0

    def emit(record: dict[str, Any]) -> None:
        nonlocal output_bytes
        encoded = (json.dumps(record, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
        output_bytes += len(encoded)
        if output_bytes > args.max_output_bytes:
            raise ValueError("D086 JSONL output exceeds its byte budget")
        sys.stdout.buffer.write(encoded)

    start = datetime.strptime(args.from_date, "%Y%m%d").date()
    end = datetime.strptime(args.to_date, "%Y%m%d").date()
    result = inspect(args.dataset_root, start, end, args.symbol, args.max_rows,
                     args.max_files, args.max_bytes, args.page_rows)
    public_result = {key: value for key, value in result.items() if not key.startswith("_")}
    if args.inspect:
        emit({"kind": "inspection", **public_result})
        return 0
    if args.expected_fingerprint != result["sourceFingerprint"]:
        raise ValueError("D086 Parquet source changed after the frozen plan")
    emit({"kind": "header", **public_result})
    cursor_number = 0
    page_rows = args.page_rows
    row_offset = 0
    for date_result in result["_dateResults"]:
        rows = date_result["selected_rows"]
        part_hashes = [
            {"path": path.relative_to(args.dataset_root.resolve()).as_posix(), "sha256": digest_file(path)}
            for path in date_result["feature_parts"]
        ]
        evidence = {
            "date": date_result["day"].strftime("%Y%m%d"),
            "rowOffset": row_offset,
            "featureParts": part_hashes,
            "manifestPartCount": len(date_result["manifest_parts"]),
        }
        for offset in range(0, len(rows), page_rows):
            batch = rows[offset: offset + page_rows]
            cursor_number += 1
            page_evidence = {**evidence, "page": cursor_number, "sourceRowOffset": offset}
            emit({
                "kind": "page",
                "sourceFingerprint": result["sourceFingerprint"],
                "cursor": f"{date_result['day']:%Y%m%d}:{offset}",
                "responseEvidence": page_evidence,
                "rows": batch,
            })
            row_offset += len(batch)
    emit({
        "kind": "completion",
        "sourceFingerprint": result["sourceFingerprint"],
        "files": result["files"],
        "sourceRows": result["sourceRows"],
        "returnedRows": result["selectedRows"],
        "pages": result["pages"],
        "complete": True,
        "completeForSelectedSymbols": True,
    })
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"{type(exc).__name__}: D086 source read failed", file=sys.stderr)
        raise SystemExit(2)
