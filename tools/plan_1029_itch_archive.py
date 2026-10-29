"""退出ITCH前只读归档；逐文件压缩、回验，并保留全部十六列和整数纳秒。"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from plan_1029_audit import query, save, sha, state
from btc_research_data import BtcReader, configured_endpoint


def archive(folder: Path, database_root: Path):
    table = "us_nasdaq_itch_messages"
    reader = BtcReader(*configured_endpoint())
    before = state(reader, table)
    if before["id"] != 3208 or before["table_row_count"] != 450836:
        raise ValueError("原ITCH物理身份或范围变化")
    if (
        before["writerTxn"] != before["sequencerTxn"]
        or before["suspended"]
        or before["bufferedTxnSize"]
    ):
        raise ValueError("ITCH未结清")
    folder.mkdir(exist_ok=False)
    source = database_root.resolve() / before["directoryName"]
    if source.parent != database_root.resolve() or source.is_symlink():
        raise ValueError("归档目标越界")
    files = []
    target = folder / "itch-physical.zip"
    with ZipFile(target, "x", ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(source.rglob("*")):
            if not path.is_file() or path.name in ("_txn_scoreboard", ".lock"):
                continue
            digest = sha(path)
            name = path.relative_to(source).as_posix()
            z.write(path, name)
            if sha(path) != digest:
                raise ValueError("归档期间原文件变化")
            files.append(dict(relative=name, sha256=digest, bytes=path.stat().st_size))
    with ZipFile(target) as z:
        if z.testzip() is not None:
            raise ValueError("压缩归档CRC错误")
        for item in files:
            with z.open(item["relative"]) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != item["sha256"]:
                    raise ValueError("压缩归档摘要不符")
    columns, _ = query(reader, "SELECT * FROM table_columns('" + table + "')")
    definitions = query(reader, "SELECT * FROM table_columns('" + table + "')")[1]
    fields = [
        "cast(ts as long) AS ts_ns"
        if x["column"] == "ts"
        else "cast(observed_at as long) AS observed_at_us"
        if x["column"] == "observed_at"
        else x["column"]
        for x in definitions
    ]
    rows = 0
    raw = folder / "itch-sixteen-columns.jsonl.gz"
    with gzip.open(raw, "xt", encoding="utf8", newline="\n") as output:
        for offset in range(0, before["table_row_count"], 5000):
            _, batch = query(
                reader,
                "SELECT "
                + ",".join(fields)
                + " FROM "
                + table
                + " ORDER BY ts,source_sha256,message_index LIMIT "
                + str(offset)
                + ","
                + str(min(offset + 5000, before["table_row_count"])),
            )
            if len(batch) != min(5000, before["table_row_count"] - offset):
                raise ValueError("完整字段导出不足")
            for row in batch:
                output.write(
                    json.dumps(
                        row, ensure_ascii=False, separators=(",", ":"), allow_nan=False
                    )
                    + "\n"
                )
            rows += len(batch)
    if state(reader, table) != before:
        raise ValueError("ITCH归档期间快照变化")
    save(
        folder / "archive-verified.json",
        dict(
            table=table,
            snapshot=before,
            rows=rows,
            columns=definitions,
            physicalZip=str(target.resolve()),
            physicalZipSha256=sha(target),
            logicalExport=str(raw.resolve()),
            logicalExportSha256=sha(raw),
            files=files,
            sourceReadOnly=True,
            complete=True,
            pgWireFullReadbackStillRequired=True,
        ),
    )
    print(
        json.dumps(dict(rows=rows, files=len(files), archiveSha256=sha(target))),
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--folder", type=Path, required=True)
    parser.add_argument("--database-root", type=Path, required=True)
    args = parser.parse_args()
    archive(args.folder, args.database_root)
