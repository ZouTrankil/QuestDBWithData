#!/usr/bin/env python
"""Bounded, read-only Parquet reader for the D085 Java ingestion adapter."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq


PARSER_VERSION = "l2-dataset-manifest-parquet-v1"
FIELDS = (
    "trade_date",
    "symbol",
    "market",
    "board",
    "source_root",
    "output_root",
    "feature_version",
    "daily_feature_ok",
    "t0_ok",
    "raw_row_counts",
    "output_paths",
    "cost_config",
    "horizons_min",
    "errors",
    "batch_id",
)
JSON_FIELDS = ("raw_row_counts", "output_paths", "cost_config", "horizons_min", "errors")
DATE_DIR = re.compile(r"^trade_date=(\d{8})$")
PART_FILE = re.compile(r"^part-(\d+)\.parquet$")
SYMBOL = re.compile(r"^[0-9]{6}\.(SH|SZ|BJ)$")
EXPECTED_TYPES = {
    "trade_date": "string",
    "symbol": "string",
    "market": "string",
    "board": "string",
    "source_root": "string",
    "output_root": "string",
    "feature_version": "string",
    "daily_feature_ok": "bool",
    "t0_ok": "bool",
    "raw_row_counts": "string",
    "output_paths": "string",
    "cost_config": "string",
    "horizons_min": "string",
    "errors": "string",
    "batch_id": "int64",
}


class SourceError(RuntimeError):
    pass


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _digest(parts: list[str]) -> str:
    digest = hashlib.sha256()
    for part in parts:
        digest.update(part.encode("utf-8"))
        digest.update(b"\0")
    return digest.hexdigest()


def _date_partition(path: Path) -> str:
    match = DATE_DIR.fullmatch(path.name)
    if match is None:
        raise SourceError("Unexpected trade-date partition directory")
    day = match.group(1)
    datetime.strptime(day, "%Y%m%d")
    if path.parent.name != f"month={day[4:6]}" or path.parent.parent.name != f"year={day[:4]}":
        raise SourceError("Trade-date partition does not match its year/month directories")
    return day


def _files(root: Path, start: str, end: str, max_files: int) -> tuple[list[tuple[str, int, Path]], list[str]]:
    product_root = root / "l2_dataset_manifest"
    if not product_root.is_dir() or product_root.is_symlink():
        raise SourceError("D085 Parquet product directory is missing or linked")
    files: list[tuple[str, int, Path]] = []
    dates: list[str] = []
    for year_dir in sorted(product_root.glob("year=*")):
        if year_dir.is_symlink() or not year_dir.is_dir() or not re.fullmatch(r"year=\d{4}", year_dir.name):
            raise SourceError("Unexpected or linked year directory in D085 source")
        for month_dir in sorted(year_dir.glob("month=*")):
            if month_dir.is_symlink() or not month_dir.is_dir() or not re.fullmatch(r"month=\d{2}", month_dir.name):
                raise SourceError("Unexpected or linked month directory in D085 source")
            for day_dir in sorted(month_dir.glob("trade_date=*")):
                if day_dir.is_symlink() or not day_dir.is_dir():
                    raise SourceError("Unexpected or linked date directory in D085 source")
                day = _date_partition(day_dir)
                if day < start or day > end:
                    continue
                part_rows: list[tuple[int, Path]] = []
                for path in sorted(day_dir.iterdir()):
                    if path.is_symlink() or not path.is_file():
                        raise SourceError("Unexpected or linked item in a D085 date partition")
                    match = PART_FILE.fullmatch(path.name)
                    if match is None:
                        raise SourceError("Unexpected Parquet part filename")
                    part_rows.append((int(match.group(1)), path))
                if not part_rows:
                    raise SourceError("Trade-date partition contains no Parquet parts")
                part_ids = [item[0] for item in part_rows]
                if part_ids != list(range(part_ids[0], part_ids[-1] + 1)) or part_ids[0] != 0:
                    raise SourceError("Parquet part IDs are missing or non-contiguous")
                dates.append(day)
                files.extend((day, batch, path) for batch, path in part_rows)
                if len(files) > max_files:
                    raise SourceError("D085 source exceeds the frozen Parquet-file budget")
    return files, dates


def _schema_fingerprint(schema: pa.Schema) -> str:
    actual = {field.name: str(field.type) for field in schema}
    if tuple(schema.names) != FIELDS or actual != EXPECTED_TYPES:
        raise SourceError("Parquet schema differs from the frozen D085 source columns/types")
    return _digest([f"{name}:{actual[name]}" for name in FIELDS])


def _validate_row(row: dict[str, Any], day: str, batch_id: int) -> None:
    if set(row) != set(FIELDS):
        raise SourceError("Parquet row columns differ from the frozen D085 schema")
    if row["trade_date"] != day:
        raise SourceError("Parquet row trade_date differs from its partition")
    if not isinstance(row["symbol"], str) or SYMBOL.fullmatch(row["symbol"]) is None:
        raise SourceError("Parquet row has an invalid stock symbol")
    if isinstance(row["batch_id"], bool) or not isinstance(row["batch_id"], int) or row["batch_id"] != batch_id:
        raise SourceError("Parquet batch_id differs from its part filename")
    for name in ("daily_feature_ok", "t0_ok"):
        if row[name] is not None and not isinstance(row[name], bool):
            raise SourceError(f"Parquet {name} must be a nullable boolean")
    for name in FIELDS:
        if name in {"trade_date", "symbol", "batch_id", "daily_feature_ok", "t0_ok"}:
            continue
        if row[name] is not None and not isinstance(row[name], str):
            raise SourceError(f"Parquet {name} must be nullable text")
    for name in JSON_FIELDS:
        if row[name] is not None:
            try:
                json.loads(row[name])
            except (json.JSONDecodeError, TypeError) as exc:
                raise SourceError(f"Parquet {name} is not valid JSON text") from exc


def inspect(args: argparse.Namespace) -> dict[str, Any]:
    root = args.dataset_root
    if root.is_symlink() or not root.is_dir():
        raise SourceError("Configured D085 dataset root is missing or linked")
    root = root.resolve(strict=True)
    start, end = args.from_date, args.to_date
    if start > end:
        raise SourceError("D085 date range must increase")
    symbols = tuple(sorted(set(args.symbol)))
    if len(symbols) > 1000 or any(SYMBOL.fullmatch(item) is None for item in symbols):
        raise SourceError("D085 symbol filter must contain at most 1000 canonical stock codes")
    selected_symbols = set(symbols)
    files, dates = _files(root, start, end, args.max_files)
    schema_fingerprint: str | None = None
    source_rows = selected_rows = source_bytes = page_count = 0
    file_hashes: list[tuple[str, str, int]] = []
    seen_keys: set[tuple[str, str, int]] = set()
    root_token = hashlib.sha256(str(root).encode("utf-8")).hexdigest()
    page_rows = args.page_rows
    for day, batch_id, path in files:
        before = path.stat()
        size = before.st_size
        if size < 1:
            raise SourceError("Empty Parquet source file")
        source_bytes += size
        if source_bytes > args.max_bytes:
            raise SourceError("D085 source exceeds the frozen input-byte budget")
        file_hash = _sha256_file(path)
        parquet = pq.ParquetFile(path)
        signature = _schema_fingerprint(parquet.schema_arrow)
        if schema_fingerprint is None:
            schema_fingerprint = signature
        elif schema_fingerprint != signature:
            raise SourceError("D085 source schema drift across Parquet parts")
        file_selected = 0
        file_pages = 0
        for batch in parquet.iter_batches(columns=list(FIELDS), batch_size=page_rows, use_threads=False):
            values = batch.to_pylist()
            selected_in_page = 0
            for row in values:
                _validate_row(row, day, batch_id)
                key = (day, row["symbol"], batch_id)
                if key in seen_keys:
                    raise SourceError("Duplicate D085 source business key across Parquet parts")
                seen_keys.add(key)
                source_rows += 1
                if source_rows > args.max_rows:
                    raise SourceError("D085 source exceeds the frozen row budget")
                if not selected_symbols or row["symbol"] in selected_symbols:
                    selected_rows += 1
                    file_selected += 1
                    selected_in_page += 1
            if selected_in_page:
                page_count += 1
                file_pages += 1
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise SourceError("D085 Parquet file changed during inspection")
        file_hashes.append((path.relative_to(root).as_posix(), file_hash, size))
    source_fingerprint = _digest([
        PARSER_VERSION,
        root_token,
        start,
        end,
        schema_fingerprint or _digest([f"{n}:{EXPECTED_TYPES[n]}" for n in FIELDS]),
        json.dumps(symbols, separators=(",", ":")),
        *[f"{name}\0{size}\0{digest}" for name, digest, size in file_hashes],
    ])
    return {
        "kind": "inspection",
        "parserVersion": PARSER_VERSION,
        "schemaFingerprint": schema_fingerprint or _digest([f"{n}:{EXPECTED_TYPES[n]}" for n in FIELDS]),
        "sourceFingerprint": source_fingerprint,
        "from": start,
        "to": end,
        "dates": dates,
        "sourceRows": source_rows,
        "selectedRows": selected_rows,
        "files": len(files),
        "pages": page_count,
        "sourceBytes": source_bytes,
        "quality": {
            "schemaStable": True,
            "businessKeysUnique": True,
            "partIdsContiguous": True,
            "dateAndBatchMatchPartition": True,
            "jsonFieldsValid": True,
            "completeForDiscoveredFiles": True,
            "externalCompletionManifest": False,
        },
    }


def stream(args: argparse.Namespace) -> None:
    inspection = inspect(args)
    if inspection["sourceFingerprint"] != args.expected_fingerprint:
        raise SourceError("D085 source changed after planning; no rows were emitted")
    header = json.dumps({"kind": "header", **{k: v for k, v in inspection.items() if k != "kind"}},
                        ensure_ascii=False, separators=(",", ":"))
    output_bytes = len(header.encode("utf-8")) + 1
    if output_bytes > args.max_output_bytes:
        raise SourceError("D085 stream header exceeds the output-byte budget")
    print(header, flush=True)
    root = args.dataset_root.resolve(strict=True)
    symbols = set(args.symbol)
    files, _ = _files(root, args.from_date, args.to_date, args.max_files)
    emitted_rows = emitted_pages = 0
    for day, batch_id, path in files:
        before = path.stat()
        file_hash = _sha256_file(path)
        parquet = pq.ParquetFile(path)
        relative = path.relative_to(root).as_posix()
        page_index = 0
        for batch in parquet.iter_batches(columns=list(FIELDS), batch_size=args.page_rows, use_threads=False):
            rows = [row for row in batch.to_pylist() if not symbols or row["symbol"] in symbols]
            if not rows:
                continue
            fingerprint = _digest([
                PARSER_VERSION,
                inspection["schemaFingerprint"],
                inspection["sourceFingerprint"],
                relative,
                file_hash,
                str(page_index),
            ])
            evidence = {
                "date": day,
                "sourcePath": relative,
                "sourceSha256": file_hash,
                "sourceBytes": before.st_size,
                "batchId": batch_id,
                "page": page_index,
                "sourceRowsInPage": batch.num_rows,
                "returnedRows": len(rows),
                "parserVersion": PARSER_VERSION,
                "schemaFingerprint": inspection["schemaFingerprint"],
                "sourceFingerprint": inspection["sourceFingerprint"],
                "completeForDiscoveredFiles": True,
            }
            payload = {
                "kind": "page",
                "sourceFingerprint": fingerprint,
                "cursor": f"{day}/{relative}#{page_index}",
                "responseEvidence": evidence,
                "rows": rows,
            }
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            line_bytes = len(encoded.encode("utf-8")) + 1
            if line_bytes > args.max_output_bytes or output_bytes + line_bytes > args.max_output_bytes:
                raise SourceError("D085 JSONL pages exceed the output-byte budget")
            print(encoded, flush=True)
            output_bytes += line_bytes
            emitted_rows += len(rows)
            emitted_pages += 1
            page_index += 1
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise SourceError("D085 Parquet file changed while the source pages were read")
        if file_hash != _sha256_file(path):
            raise SourceError("D085 Parquet content changed while the source pages were read")
    final_inspection = inspect(args)
    if final_inspection["sourceFingerprint"] != inspection["sourceFingerprint"]:
        raise SourceError("D085 source inventory changed while the source pages were read")
    if emitted_rows != inspection["selectedRows"] or emitted_pages != inspection["pages"]:
        raise SourceError("D085 emitted page coverage differs from source inspection")
    footer = json.dumps({
        "kind": "completion",
        "sourceFingerprint": inspection["sourceFingerprint"],
        "sourceRows": inspection["sourceRows"],
        "returnedRows": emitted_rows,
        "files": inspection["files"],
        "pages": emitted_pages,
        "complete": True,
        "quality": inspection["quality"],
    }, ensure_ascii=False, separators=(",", ":"))
    output_bytes += len(footer.encode("utf-8")) + 1
    if output_bytes > args.max_output_bytes:
        raise SourceError("D085 completion evidence exceeds the output-byte budget")
    print(footer, flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--from-date", required=True, help="Inclusive YYYYMMDD")
    parser.add_argument("--to-date", required=True, help="Inclusive YYYYMMDD")
    parser.add_argument("--symbol", action="append", default=[])
    parser.add_argument("--page-rows", type=int, default=200)
    parser.add_argument("--max-files", type=int, required=True)
    parser.add_argument("--max-rows", type=int, required=True)
    parser.add_argument("--max-bytes", type=int, required=True)
    parser.add_argument("--max-output-bytes", type=int, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect", action="store_true")
    mode.add_argument("--stream", action="store_true")
    parser.add_argument("--expected-fingerprint")
    args = parser.parse_args()
    if args.page_rows < 1 or args.page_rows > 1000:
        parser.error("--page-rows must be 1..1000")
    for name in ("max_files", "max_rows", "max_bytes", "max_output_bytes"):
        if getattr(args, name) < 1:
            parser.error(f"--{name.replace('_', '-')} must be positive")
    for value in (args.from_date, args.to_date):
        try:
            datetime.strptime(value, "%Y%m%d")
        except ValueError:
            parser.error("date bounds must be valid YYYYMMDD values")
    if args.stream and (not args.expected_fingerprint or len(args.expected_fingerprint) != 64):
        parser.error("--stream requires the inspected source fingerprint")
    return args


def main() -> int:
    args = parse_args()
    try:
        if args.inspect:
            print(json.dumps(inspect(args), ensure_ascii=False, separators=(",", ":")), flush=True)
        else:
            stream(args)
        return 0
    except Exception as error:
        # Only a bounded error code/type leaves the source process; credentials are never loaded here.
        print(f"{type(error).__name__}: {error}", file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
