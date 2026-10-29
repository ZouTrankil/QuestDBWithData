"""1029维护的物理备份：只读原数据目录，全部副本写入本轮证据目录。"""

from __future__ import annotations
import argparse, hashlib, json, os, shutil, time
from pathlib import Path
from datetime import datetime, timezone


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(8 * 1024 * 1024):
            value.update(block)
    return value.hexdigest()


def backup(folder, database):
    folder = folder.resolve()
    database = database.resolve()
    if (folder / "backup-verified.json").exists():
        raise ValueError("备份已提交，先核对原维护进程")
    request = folder / "backup-request.json"
    deadline = time.monotonic() + 120
    while not request.exists():
        if time.monotonic() > deadline:
            raise ValueError("原维护进程尚未请求备份，不能另开恢复")
        time.sleep(1)
    spec = json.loads(request.read_text())
    files = []
    excluded = []
    originals = spec["originals"]
    if set(originals) != {"us_equity_daily", "crypto_ohlcv"}:
        raise ValueError("只允许本轮两个明确目标")
    for table, entry in originals.items():
        name = entry["metadata"]["directoryName"]
        source = (database / name).resolve()
        if source.parent != database or not source.is_dir():
            raise ValueError("原物理目录越界或缺失")
        candidates = sorted(x for x in source.rglob("*") if x.is_file())
        for index, path in enumerate(candidates):
            relative = path.relative_to(source)
            # 读者记分板和进程锁不是业务内容，恢复时由引擎重新创建。
            if (
                path.name == "_txn_scoreboard"
                or path.name.endswith(".lock")
                or path.name == ".lock"
            ):
                excluded.append(
                    dict(table=table, path=str(relative), reason="临时读者状态或进程锁")
                )
                continue
            target = folder / "physical-backup" / name / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            before = digest(path)
            if target.exists():
                if digest(target) != before:
                    raise ValueError("已有副本与原文件不同，保留现场")
            else:
                pending = target.with_name(target.name + ".copying")
                with path.open("rb") as src, pending.open("xb") as dst:
                    shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
                    dst.flush()
                    os.fsync(dst.fileno())
                if digest(pending) != before or digest(path) != before:
                    raise ValueError("复制期间原文件变化，不能批准重建")
                pending.replace(target)
                shutil.copystat(path, target)
            files.append(
                dict(
                    table=table,
                    relative=str(relative).replace("\\", "/"),
                    source=str(path),
                    backup=str(target),
                    bytes=path.stat().st_size,
                    sha256=before,
                )
            )
            if (index + 1) % 100 == 0:
                print(
                    json.dumps(
                        dict(
                            table=table,
                            files=index + 1,
                            bytesCopied=sum(x["bytes"] for x in files),
                        )
                    ),
                    flush=True,
                )
        print(
            json.dumps(
                dict(
                    table=table,
                    completeFiles=len([x for x in files if x["table"] == table]),
                )
            ),
            flush=True,
        )
    value = dict(
        checkedAt=datetime.now(timezone.utc).isoformat(),
        complete=True,
        requestSha256=digest(request),
        databaseRoot=str(database),
        files=files,
        excludedTransientFiles=excluded,
        totalBytes=sum(x["bytes"] for x in files),
        note="原表处于拒写暂停状态；原来源、Native和控制库另外保留。",
    )
    with (folder / "backup-verified.json").open("x", encoding="utf8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
    print(
        json.dumps(
            dict(backupVerified=True, files=len(files), bytes=value["totalBytes"])
        ),
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--folder", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    args = parser.parse_args()
    backup(args.folder, args.database)
