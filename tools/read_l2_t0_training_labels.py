#!/usr/bin/env python3
"""Bounded, read-only D089 bridge from D085-certified T0 label Parquet."""

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

import pyarrow as pa
import pyarrow.dataset as ds
import pyarrow.parquet as pq

PARSER_VERSION = "l2-t0-training-labels-parquet-v1"
MAX_WINDOW_DAYS = 31
MAX_PAGE_ROWS = 200
MAX_FILES = 10_000
SYMBOL_RE = re.compile(r"^[0-9]{6}\.(SH|SZ|BJ)$")
PART_RE = re.compile(r"^part-([0-9]{5})\.parquet$")
DATASET = "l2_t0_training_labels"
METADATA = {"trade_date": "STRING", "symbol": "SYMBOL", "market": "STRING",
            "board": "STRING", "minute": "TIMESTAMP",
            "executability_reason": "STRING", "policy_label": "STRING",
            "policy_reason": "STRING", "primary_t0_side": "STRING"}
HORIZONS = (1, 3, 5, 10, 15, 30)
LONGS = {"executability_label"} | {
    f"{prefix}_{horizon}m"
    for horizon in HORIZONS
    for prefix in ("sell_first_opportunity_label", "buy_first_aux_opportunity_label")
}
DOUBLE_NAMES = (
    "roundtrip_cost_rate stamp_tax_rate commission_rate slippage_bps "
    + " ".join(
        f"{name}_{horizon}m"
        for horizon in HORIZONS
        for name in (
            "future_vwap_return", "future_mid_return",
            "sell_first_gross_alpha", "sell_first_net_alpha",
            "buy_first_aux_gross_alpha", "buy_first_aux_net_alpha",
        )
    )
).split()
EXPECTED_COLUMNS = (
    "trade_date symbol market board minute executability_label executability_reason policy_label policy_reason "
    "primary_t0_side roundtrip_cost_rate stamp_tax_rate commission_rate slippage_bps "
    + " ".join(
        name
        for horizon in HORIZONS
        for name in (
            f"future_vwap_return_{horizon}m", f"future_mid_return_{horizon}m",
            f"sell_first_gross_alpha_{horizon}m", f"sell_first_net_alpha_{horizon}m",
            f"sell_first_opportunity_label_{horizon}m", f"buy_first_aux_gross_alpha_{horizon}m",
            f"buy_first_aux_net_alpha_{horizon}m", f"buy_first_aux_opportunity_label_{horizon}m",
        )
    )
).split()
QDB_TYPES = {**METADATA, **{name: "LONG" for name in LONGS},
             **{name: "DOUBLE" for name in DOUBLE_NAMES}}
NULLABLE_DOUBLES = set(DOUBLE_NAMES) - {
    "roundtrip_cost_rate", "stamp_tax_rate", "commission_rate", "slippage_bps"
}
if len(EXPECTED_COLUMNS) != 62 or len(set(EXPECTED_COLUMNS)) != 62 or set(EXPECTED_COLUMNS) != set(QDB_TYPES):
    raise RuntimeError("D089 field contract is internally inconsistent")


def canonical(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, float):
        if math.isnan(value):
            return None
        if not math.isfinite(value):
            raise ValueError("D089 source contains a non-finite floating-point value")
        return value
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key): canonical(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [canonical(item) for item in value]
    return value


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def date_path(root: Path, dataset: str, day: date) -> Path:
    return root / dataset / f"year={day:%Y}" / f"month={day:%m}" / f"trade_date={day:%Y%m%d}"


def part_files(directory: Path, label: str) -> list[Path]:
    if not directory.is_dir() or directory.is_symlink():
        raise FileNotFoundError(f"D089 {label} partition is unavailable")
    parts = sorted(directory.glob("part-*.parquet"))
    if not parts or any(PART_RE.fullmatch(path.name) is None or path.is_symlink() for path in parts):
        raise ValueError(f"D089 {label} partition has missing or unsafe Parquet parts")
    return parts


def normalized_source_schema(schema: pa.Schema) -> dict[str, str]:
    if schema.names != EXPECTED_COLUMNS:
        raise ValueError("D089 Parquet field order or field set differs from the frozen 62-column contract")
    actual: dict[str, str] = {}
    for field in schema:
        name, kind = field.name, QDB_TYPES[field.name]
        arrow = field.type
        if name == "minute":
            if not pa.types.is_timestamp(arrow) or arrow.tz is not None or arrow.unit != "ns":
                raise ValueError("D089 minute must be timezone-naive timestamp[ns] exchange wall time")
            actual[name] = "timestamp[ns]"
        elif kind in {"STRING", "SYMBOL"}:
            if not (pa.types.is_string(arrow) or pa.types.is_large_string(arrow)):
                raise ValueError(f"D089 source text field has changed type: {name}")
            actual[name] = "string"
        elif kind == "LONG":
            if not pa.types.is_int64(arrow):
                raise ValueError(f"D089 source LONG field has changed type: {name}")
            actual[name] = "int64"
        elif kind == "DOUBLE":
            if not (pa.types.is_floating(arrow) or pa.types.is_integer(arrow)):
                raise ValueError(f"D089 source numeric field has changed type: {name}")
            actual[name] = str(arrow)
    return actual


def normalize_row(raw: dict[str, Any], day: date, selected_symbols: set[str]) -> dict[str, Any]:
    if set(raw) != set(EXPECTED_COLUMNS):
        raise ValueError("D089 row field set differs from the frozen source schema")
    if raw["trade_date"] != day.strftime("%Y%m%d"):
        raise ValueError("D089 row trade_date differs from its partition")
    symbol = raw["symbol"]
    if not isinstance(symbol, str) or not SYMBOL_RE.fullmatch(symbol) or symbol not in selected_symbols:
        raise ValueError("D089 row symbol differs from the selected canonical symbol filter")
    if raw["market"] != symbol[-2:]:
        raise ValueError("D089 source market does not agree with its stock code")
    if not isinstance(raw["board"], str) or not raw["board"]:
        raise ValueError("D089 source board is missing")
    minute = raw["minute"]
    if not isinstance(minute, datetime) or minute.tzinfo is not None:
        raise ValueError("D089 minute must be a naive local exchange timestamp")
    if minute.date() != day or minute.second != 0 or minute.microsecond != 0:
        raise ValueError("D089 minute must be a whole-minute timestamp in its exchange date")
    row: dict[str, Any] = {"trade_date": raw["trade_date"], "symbol": symbol,
                            "market": raw["market"], "board": raw["board"], "minute": minute.isoformat()}
    for name in ("executability_reason", "policy_label", "policy_reason", "primary_t0_side"):
        value = raw[name]
        if not isinstance(value, str) or not value:
            raise ValueError(f"D089 required source text is missing: {name}")
        row[name] = value
    for name in LONGS:
        value = raw[name]
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"D089 required integral field is not LONG: {name}")
        if value < -(2**63) or value >= 2**63:
            raise ValueError(f"D089 LONG is outside QuestDB range: {name}")
        if name == "executability_label" or "_opportunity_label_" in name:
            if value not in (0, 1):
                raise ValueError(f"D089 binary label must be 0 or 1: {name}")
        row[name] = value
    for name in DOUBLE_NAMES:
        value = raw[name]
        if value is None:
            if name not in NULLABLE_DOUBLES:
                raise ValueError(f"D089 required DOUBLE is null: {name}")
            row[name] = None
        elif isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"D089 DOUBLE field is not numeric: {name}")
        else:
            converted = float(value)
            if not math.isfinite(converted):
                if math.isnan(converted):
                    row[name] = None
                    continue
                raise ValueError(f"D089 DOUBLE field is non-finite: {name}")
            if isinstance(value, int) and int(converted) != value:
                raise ValueError(f"D089 integer source value cannot be represented exactly as DOUBLE: {name}")
            row[name] = converted
    if row["executability_reason"] not in {"quote_depth_ok", "wide_spread_or_thin_depth"}:
        raise ValueError("D089 executability_reason differs from the Python source contract")
    if row["policy_label"] != "rule_data_missing" or row["policy_reason"] != "needs_inventory_limit_st_price_cage_inputs":
        raise ValueError("D089 policy placeholders differ from the Python source contract")
    if row["primary_t0_side"] != "sell_first_inventory_required":
        raise ValueError("D089 primary_t0_side differs from the Python source contract")
    expected_cost = (2.0 * row["commission_rate"]) + row["stamp_tax_rate"] + (row["slippage_bps"] / 10000.0)
    if abs(row["roundtrip_cost_rate"] - expected_cost) > 1e-12:
        raise ValueError("D089 roundtrip_cost_rate differs from the Python T0CostConfig formula")
    for horizon in HORIZONS:
        for side in ("sell_first", "buy_first_aux"):
            gross = row[f"{side}_gross_alpha_{horizon}m"]
            net = row[f"{side}_net_alpha_{horizon}m"]
            label_prefix = "sell_first" if side == "sell_first" else "buy_first_aux"
            label = row[f"{label_prefix}_opportunity_label_{horizon}m"]
            if gross is None:
                if label != 0:
                    raise ValueError("D089 source labels a missing future alpha as positive")
            else:
                if net is None or abs(net - (gross - row["roundtrip_cost_rate"])) > 1e-12:
                    raise ValueError(f"D089 net alpha differs from gross minus round-trip cost at {horizon}m")
                if label != int(gross > row["roundtrip_cost_rate"]):
                    raise ValueError(f"D089 opportunity label differs from the source threshold rule at {horizon}m")
    return row


def receipt_part(root: Path, day: date, batch_id: int, raw_path: str) -> Path:
    expected = date_path(root, DATASET, day) / f"part-{batch_id:05d}.parquet"
    candidate = Path(raw_path)
    if not candidate.is_absolute():
        candidate = root / candidate
    if candidate.resolve(strict=True) != expected.resolve(strict=True):
        raise ValueError("D089 D085 manifest output receipt points outside the expected feature part")
    if expected.is_symlink() or PART_RE.fullmatch(expected.name) is None:
        raise ValueError("D089 feature part is unsafe")
    return expected


def rows_for_day(root: Path, day: date, symbols: list[str]) -> dict[str, Any]:
    manifest_dir = date_path(root, "l2_dataset_manifest", day)
    manifest_parts = part_files(manifest_dir, "manifest")
    feature_dir = date_path(root, DATASET, day)
    manifest = ds.dataset([str(path) for path in manifest_parts], format="parquet")
    required = {"trade_date", "symbol", "batch_id", "t0_ok", "output_paths"}
    if not required.issubset(manifest.schema.names):
        raise ValueError("D089 D085 source manifest lacks a required receipt field")
    source_rows = manifest.count_rows()
    receipts = manifest.to_table(columns=["trade_date", "symbol", "batch_id", "t0_ok", "output_paths"],
                                 filter=ds.field("symbol").isin(symbols)).to_pylist()
    by_symbol: dict[str, list[dict[str, Any]]] = {symbol: [] for symbol in symbols}
    for receipt in receipts:
        symbol = str(receipt["symbol"])
        if symbol not in by_symbol:
            continue
        if str(receipt["trade_date"]).replace("-", "") != day.strftime("%Y%m%d"):
            raise ValueError("D089 D085 receipt trade_date differs from the manifest partition")
        by_symbol[symbol].append(receipt)
    if any(not values for values in by_symbol.values()):
        missing = sorted(symbol for symbol, values in by_symbol.items() if not values)
        raise ValueError(f"D089 D085 manifest does not cover selected symbols: {','.join(missing[:8])}")

    selected_by_batch: dict[int, set[str]] = {}
    paths_by_batch: dict[int, Path] = {}
    receipt_fingerprint_by_batch: dict[int, list[dict[str, Any]]] = {}
    for symbol, rows in by_symbol.items():
        rows.sort(key=lambda item: int(item["batch_id"]))
        ids = [int(row["batch_id"]) for row in rows]
        if len(ids) != len(set(ids)):
            raise ValueError(f"D089 D085 manifest repeats a symbol/batch receipt for {symbol}")
        receipt = rows[-1]
        if receipt["t0_ok"] is not True:
            raise ValueError(f"D089 latest D085 batch is not t0_ok for {symbol} on {day:%Y-%m-%d}")
        batch_id = int(receipt["batch_id"])
        paths = receipt["output_paths"]
        if isinstance(paths, str):
            paths = json.loads(paths or "{}")
        if not isinstance(paths, dict) or not paths.get("training_labels"):
            raise ValueError(f"D089 D085 output_paths has no training_labels receipt for {symbol}")
        path = receipt_part(root, day, batch_id, str(paths["training_labels"]))
        selected_by_batch.setdefault(batch_id, set()).add(symbol)
        paths_by_batch[batch_id] = path
        receipt_fingerprint_by_batch.setdefault(batch_id, []).append({
            "date": day.strftime("%Y%m%d"), "symbol": symbol, "batch_id": batch_id,
            "t0_ok": True, "training_labels": path.relative_to(root).as_posix(),
        })

    rows: list[dict[str, Any]] = []
    selected_parts: list[Path] = []
    actual_types: dict[str, set[str]] = {name: set() for name in EXPECTED_COLUMNS}
    selected_symbols_by_batch: dict[int, set[str]] = {}
    for batch_id, expected_symbols in sorted(selected_by_batch.items()):
        path = paths_by_batch[batch_id]
        parquet = pq.ParquetFile(path)
        schema_types = normalized_source_schema(parquet.schema_arrow)
        for name, source_type in schema_types.items():
            actual_types[name].add(source_type)
        table = ds.dataset(str(path), format="parquet").to_table(
            filter=ds.field("symbol").isin(sorted(expected_symbols)))
        part_rows = table.to_pylist()
        observed = {str(item.get("symbol")) for item in part_rows}
        if observed != expected_symbols or not part_rows:
            raise ValueError(f"D089 manifest receipt and source feature symbols disagree for batch {batch_id}")
        selected_symbols_by_batch[batch_id] = observed
        selected_parts.append(path)
        for raw in part_rows:
            rows.append(normalize_row(raw, day, expected_symbols))

    rows.sort(key=lambda row: (row["minute"], row["symbol"]))
    keys = [(row["symbol"], row["minute"]) for row in rows]
    if len(keys) != len(set(keys)):
        raise ValueError(f"D089 source contains duplicate (symbol,minute) keys on {day:%Y-%m-%d}")
    return {
        "day": day, "manifest_parts": manifest_parts, "feature_parts": sorted(set(selected_parts)),
        "source_rows": int(source_rows), "rows": rows, "receipt_fingerprint_by_batch": receipt_fingerprint_by_batch,
        "selected_symbols_by_batch": selected_symbols_by_batch,
        "actual_types": {name: sorted(values) for name, values in actual_types.items()},
    }


def inspect(root: Path, start: date, end: date, symbols: list[str], max_rows: int,
            max_files: int, max_bytes: int, page_rows: int) -> dict[str, Any]:
    if end < start or (end - start).days >= MAX_WINDOW_DAYS:
        raise ValueError("D089 date range must be positive and shorter than 31 calendar days")
    if page_rows < 1 or page_rows > MAX_PAGE_ROWS:
        raise ValueError("D089 page size must be between 1 and 200")
    if not symbols or len(symbols) > 100 or len(set(symbols)) != len(symbols) or any(not SYMBOL_RE.fullmatch(s) for s in symbols):
        raise ValueError("D089 requires 1..100 unique canonical stock symbols")
    root = root.resolve(strict=True)
    if not root.is_dir() or root.is_symlink():
        raise ValueError("D089 dataset root must be an existing regular directory")
    date_results = []
    day = start
    while day <= end:
        if date_path(root, "l2_dataset_manifest", day).exists():
            date_results.append(rows_for_day(root, day, symbols))
        day += timedelta(days=1)
    if not date_results:
        raise FileNotFoundError("D089 requested interval contains no D085 manifest source partitions")
    selected_rows = [row for result in date_results for row in result["rows"]]
    keys = [(row["symbol"], row["minute"]) for row in selected_rows]
    if not selected_rows:
        raise ValueError("D089 certified source window contains no feature rows")
    if len(keys) != len(set(keys)):
        raise ValueError("D089 source repeats complete (symbol,minute) business keys across pages")
    if len(selected_rows) > max_rows:
        raise ValueError("D089 selected source rows exceed their bounded budget")
    manifest_parts = sorted({path for item in date_results for path in item["manifest_parts"]})
    feature_parts = sorted({path for item in date_results for path in item["feature_parts"]})
    source_files = sorted(set(manifest_parts + feature_parts))
    source_bytes = sum(path.stat().st_size for path in source_files)
    if len(source_files) > max_files or len(source_files) > MAX_FILES or source_bytes > max_bytes:
        raise ValueError("D089 source files or bytes exceed their bounded budget")
    file_manifest = [{"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size,
                      "sha256": digest_file(path)} for path in source_files]
    source_fingerprint = hashlib.sha256(json.dumps(file_manifest, sort_keys=True, separators=(",", ":"),
                                                 ensure_ascii=False).encode("utf-8")).hexdigest()
    schema_contract = [(name, QDB_TYPES[name]) for name in EXPECTED_COLUMNS]
    schema_fingerprint = hashlib.sha256(json.dumps(schema_contract, separators=(",", ":"),
                                                ensure_ascii=False).encode("utf-8")).hexdigest()
    pages = sum((len(result["rows"]) + page_rows - 1) // page_rows for result in date_results)
    if pages < 1:
        raise ValueError("D089 selected interval has no pages")
    outcome_coverage = {}
    for horizon in HORIZONS:
        sell_gross = f"sell_first_gross_alpha_{horizon}m"
        buy_gross = f"buy_first_aux_gross_alpha_{horizon}m"
        sell_label = f"sell_first_opportunity_label_{horizon}m"
        buy_label = f"buy_first_aux_opportunity_label_{horizon}m"
        outcome_coverage[f"{horizon}m"] = {
            "rows": len(selected_rows),
            "future_vwap_return_non_null": sum(row[f"future_vwap_return_{horizon}m"] is not None for row in selected_rows),
            "future_mid_return_non_null": sum(row[f"future_mid_return_{horizon}m"] is not None for row in selected_rows),
            "sell_alpha_non_null": sum(row[sell_gross] is not None for row in selected_rows),
            "buy_alpha_non_null": sum(row[buy_gross] is not None for row in selected_rows),
            "sell_binary_zero_with_missing_alpha": sum(row[sell_gross] is None and row[sell_label] == 0 for row in selected_rows),
            "buy_binary_zero_with_missing_alpha": sum(row[buy_gross] is None and row[buy_label] == 0 for row in selected_rows),
        }
    return {
        "from": start.strftime("%Y%m%d"), "to": end.strftime("%Y%m%d"),
        "dates": [result["day"].strftime("%Y%m%d") for result in date_results],
        "sourceRows": sum(result["source_rows"] for result in date_results),
        "selectedRows": len(selected_rows), "files": len(source_files), "pages": pages,
        "sourceBytes": source_bytes, "sourceFingerprint": source_fingerprint,
        "schemaFingerprint": schema_fingerprint, "parserVersion": PARSER_VERSION,
        "completeForSelectedSymbols": True,
        "outcomeCoverageByHorizon": outcome_coverage,
        "rootIdentity": hashlib.sha256(str(root).encode("utf-8")).hexdigest(),
        "actualSourceTypes": {name: sorted({kind for result in date_results for kind in result["actual_types"][name]})
                              for name in EXPECTED_COLUMNS},
        "_dateResults": date_results, "_fileManifest": file_manifest,
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
    if min(args.max_files, args.max_rows, args.max_bytes, args.max_output_bytes) < 1:
        raise ValueError("D089 source budgets must be positive")
    output_bytes = 0

    def emit(record: dict[str, Any]) -> None:
        nonlocal output_bytes
        encoded = (json.dumps(record, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
        output_bytes += len(encoded)
        if output_bytes > args.max_output_bytes:
            raise ValueError("D089 JSONL output exceeds its byte budget")
        sys.stdout.buffer.write(encoded)

    start = datetime.strptime(args.from_date, "%Y%m%d").date()
    end = datetime.strptime(args.to_date, "%Y%m%d").date()
    result = inspect(args.dataset_root, start, end, args.symbol, args.max_rows,
                     args.max_files, args.max_bytes, args.page_rows)
    public = {key: value for key, value in result.items() if not key.startswith("_")}
    if args.inspect:
        emit({"kind": "inspection", **public})
        return 0
    if args.expected_fingerprint != result["sourceFingerprint"]:
        raise ValueError("D089 Parquet source changed after frozen plan")
    emit({"kind": "header", **public})
    page_number = 0
    total_offset = 0
    for date_result in result["_dateResults"]:
        part_evidence = [{"path": path.relative_to(args.dataset_root.resolve()).as_posix(),
                          "sha256": digest_file(path)} for path in date_result["feature_parts"]]
        for batch_id, receipt_rows in date_result["receipt_fingerprint_by_batch"].items():
            canonical_receipts = json.dumps(receipt_rows, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            receipt_hash = hashlib.sha256(canonical_receipts.encode("utf-8")).hexdigest()
            batch_symbols = date_result["selected_symbols_by_batch"].get(int(batch_id), set())
            batch_rows = [row for row in date_result["rows"] if row["symbol"] in batch_symbols]
            for offset in range(0, len(batch_rows), args.page_rows):
                page = batch_rows[offset: offset + args.page_rows]
                page_number += 1
                emit({"kind": "page", "sourceFingerprint": result["sourceFingerprint"],
                      "cursor": f"{date_result['day']:%Y%m%d}:{batch_id}:{offset}",
                      "responseEvidence": {"date": date_result["day"].strftime("%Y%m%d"),
                                           "batchId": int(batch_id), "sourceRowOffset": offset,
                                           "page": page_number, "manifestReceiptFingerprint": receipt_hash,
                                           "featureParts": part_evidence},
                      "rows": page})
                total_offset += len(page)
    emit({"kind": "completion", "sourceFingerprint": result["sourceFingerprint"],
          "files": result["files"], "sourceRows": result["sourceRows"],
          "returnedRows": total_offset, "pages": page_number, "complete": True,
          "completeForSelectedSymbols": True})
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"{type(exc).__name__}: D089 source read failed", file=sys.stderr)
        raise SystemExit(2)
