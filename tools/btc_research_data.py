# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2,<3", "pyarrow>=18", "PyYAML>=6", "duckdb>=1.2", "pytz>=2024.1"]
# ///
"""只读远端 BTC 数据；导出 UTC Parquet，并核对现货小时线与原生日线。"""
from __future__ import annotations

import argparse
from collections import defaultdict
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
import os
import sqlite3
from pathlib import Path
import urllib.parse
import urllib.request
import uuid

DATASETS = {
    "spot_1d": ("btc_spot_1d", "open_time", "SPOT", 1440),
    "spot_1h": ("btc_spot_1h", "open_time", "SPOT", 60),
    "perp_1h": ("btc_perp_1h", "open_time", "USDT_PERPETUAL", 60),
    "funding": ("btc_funding", "funding_time", "USDT_PERPETUAL", None),
    "mark_1h": ("btc_mark_1h", "open_time", "USDT_PERPETUAL", 60),
    "index_1h": ("btc_index_1h", "open_time", "USDT_PERPETUAL", 60),
}
UTC = timezone.utc
ROOT = Path(__file__).resolve().parents[1]


def configured_endpoint():
    """公共值仍读根配置；支持本地覆盖及标准环境覆盖，不打印凭据。"""
    import yaml
    settings = {}
    for path in (ROOT / "config/application.yml", ROOT / "config/application-local.yml"):
        if path.is_file():
            for data in yaml.safe_load_all(path.read_text()):
                data = data or {}
                spring = data.get("spring", {})
                activation = spring.get("config", {}).get("activate", {}).get("on-profile")
                if activation and activation not in ("btc-data", "market-btc", "local"):
                    continue
                settings.update(data.get("app", {}).get("questdb", {}))
    host = os.environ.get("APP_QUESTDB_HOST", str(settings.get("host", "")))
    port = os.environ.get("APP_QUESTDB_QWP_PORT", str(settings.get("qwp-port", "")))
    endpoint = os.environ.get("APP_QUESTDB_HTTP_URL", str(settings.get("http-url", "")))
    endpoint = endpoint.replace("${app.questdb.host}", host).replace("${app.questdb.qwp-port}", port)
    if not endpoint or "${" in endpoint:
        raise ValueError("请在根配置或 APP_QUESTDB_HTTP_URL 中提供实际 QuestDB 地址")
    return endpoint, os.environ.get("APP_QUESTDB_HTTP_AUTHORIZATION", settings.get("http-authorization", ""))


def configured_writer_root():
    import yaml
    values = {}
    for path in (ROOT / "config/apps/btc-data.yml", ROOT / "config/application-local.yml"):
        if path.is_file():
            for doc in yaml.safe_load_all(path.read_text()):
                doc = doc or {}
                values.update(doc.get("app.crypto-data", {}))
                values.update(doc.get("app", {}).get("crypto-data", {}))
    value = os.environ.get("APP_CRYPTO_DATA_ROOT", values.get("root"))
    return Path(value).absolute() if value else None


class BtcReader:
    def __init__(self, endpoint: str, authorization: str = "", writer_root: Path | None = None):
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query:
            raise ValueError("QuestDB 地址必须是无明文账号的 HTTP(S) 地址")
        self.endpoint = endpoint.rstrip("/")
        self.authorization = authorization
        self.snapshots = {}
        self.writer_root = writer_root if writer_root is not None else configured_writer_root()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _query(self, sql: str):
        # 调用方只传本文件生成的 SELECT；没有执行任意 SQL 或写入的接口。
        if not sql.startswith("SELECT "):
            raise ValueError("研究读取只接受 SELECT")
        headers = {"Authorization": self.authorization} if self.authorization else {}
        request = urllib.request.Request(self.endpoint + "/exec?" + urllib.parse.urlencode({"query": sql, "limit": "0,5001"}), headers=headers)
        with self.opener.open(request, timeout=60) as response:
            data = json.load(response)
        if "error" in data:
            raise ValueError("QuestDB 只读查询失败")
        if len(data.get("dataset", [])) > 5000:
            raise ValueError("查询超过明确行数上限")
        return data["columns"], data["dataset"]

    def _publication_fence(self, table: str):
        # 原账本在本机时，也拒绝正式运行中或未决范围；不只看已经结清的 WAL。
        ledger = self.writer_root / "sync-ledger.sqlite3" if self.writer_root else None
        if ledger is None or not ledger.is_file():
            return
        with closing(sqlite3.connect(ledger.as_uri() + "?mode=ro", uri=True, timeout=5)) as db:
            count = db.execute("SELECT count(*) FROM sync_interval_locks WHERE dataset_id=?", (table,)).fetchone()[0]
        if count:
            raise ValueError("BTC 原发布账本仍有运行或未决锁，不发布研究快照")

    def _snapshot(self, dataset: str):
        table = DATASETS[dataset][0]
        self._publication_fence(table)
        columns, values = self._query("SELECT t.id,t.directoryName,t.table_suspended,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "'")
        if len(values) != 1:
            raise ValueError("BTC 原物理表或 WAL 状态缺失")
        state = dict(zip((c["name"] for c in columns), values[0]))
        if state["table_suspended"] or state["suspended"] or state["writerTxn"] != state["sequencerTxn"] or state["bufferedTxnSize"] != 0:
            raise ValueError("BTC 表的 WAL 尚未结清，不发布研究快照")
        return tuple(state[k] for k in ("id", "directoryName", "writerTxn", "sequencerTxn"))

    def batches(self, dataset: str, from_date: date, through: date):
        import pyarrow as pa
        table, time_key, instrument, interval = DATASETS[dataset]
        if through < from_date:
            raise ValueError("截止日期早于开始日期")
        lower = datetime.combine(from_date, datetime.min.time(), UTC).isoformat()
        upper = datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC).isoformat()
        last = None
        snapshot = self._snapshot(dataset)
        while True:
            predicate = f'"{time_key}">=\'{lower}\' AND "{time_key}"<\'{upper}\' AND exchange=\'BINANCE\' AND symbol=\'BTCUSDT\' AND instrument=\'{instrument}\''
            if interval is not None:
                predicate += f" AND interval_minutes={interval}"
            if last is not None:
                predicate += f' AND "{time_key}">\'{last.isoformat()}\''
            columns, values = self._query(f'SELECT * FROM "{table}" WHERE {predicate} ORDER BY "{time_key}" LIMIT 5000')
            if not values:
                break
            arrays = []
            schema = []
            for i, column in enumerate(columns):
                name, kind = column["name"], column["type"]
                source = [row[i] for row in values]
                nullable = dataset == "funding" and name == "funding_interval_hours"
                if not nullable and any(v is None for v in source):
                    raise ValueError("BTC 必需字段为空：" + name)
                if kind == "TIMESTAMP":
                    dtype = pa.timestamp("us", tz="UTC")
                    source = [datetime.fromisoformat(v.replace("Z", "+00:00")) if v is not None else None for v in source]
                elif kind == "INT": dtype = pa.int32()
                elif kind == "LONG": dtype = pa.int64()
                elif kind == "DOUBLE": dtype = pa.float64()
                elif kind in ("SYMBOL", "VARCHAR", "STRING"): dtype = pa.string()
                else: raise ValueError(f"未约定的 BTC 字段类型：{name}/{kind}")
                arrays.append(pa.array(source, type=dtype))
                schema.append(pa.field(name, dtype, nullable=nullable))
            batch = pa.Table.from_arrays(arrays, schema=pa.schema(schema))
            times = batch[time_key].to_pylist()
            for current in times:
                if current is None or last is not None and current <= last:
                    raise ValueError("BTC 时间键为空、重复或未升序")
                last = current
            yield batch
            if len(values) < 5000:
                break
        if self._snapshot(dataset) != snapshot:
            raise ValueError("BTC 表或 WAL 版本在取数期间变化，保留旧导出并重取")
        self.snapshots[dataset] = dict(zip(("tableId", "directoryName", "writerTxn", "sequencerTxn"), snapshot))
        self.snapshots[dataset]["originalLedgerAvailable"] = bool(self.writer_root and (self.writer_root / "sync-ledger.sqlite3").is_file())

    def read(self, dataset: str, from_date: date, through: date):
        import pyarrow as pa
        batches = list(self.batches(dataset, from_date, through))
        return pa.concat_tables(batches) if batches else pa.table({})


def export(reader: BtcReader, dataset: str, start: date, through: date, output: Path):
    import pyarrow.parquet as pq
    output.mkdir(parents=True, exist_ok=True)
    target = output / (dataset + ".parquet")
    pending = output / (dataset + "." + uuid.uuid4().hex + ".pending")
    writer = None
    rows = 0
    try:
        for batch in reader.batches(dataset, start, through):
            if writer is None:
                writer = pq.ParquetWriter(pending, batch.schema, compression="zstd")
            writer.write_table(batch)
            rows += batch.num_rows
        if writer is None:
            raise ValueError(f"{dataset} 范围内没有数据，保留原文件")
        writer.close()
        writer = None
        with pending.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        metadata = {"dataset": dataset, "from": str(start), "through": str(through), "rows": rows,
                    "parquetSha256": digest, "file": dataset + "-" + digest + ".parquet",
                    "exportedAt": datetime.now(UTC).isoformat(), "readSnapshot": getattr(reader, "snapshots", {}).get(dataset), "dataVerified": False,
                    "说明": "只读快照；具体缺项见采集队列，不能用行数证明来源完整。"}
        target = output / metadata["file"]
        if target.exists():
            with target.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                    raise ValueError("已有版本文件摘要不符")
            pending.unlink()
        else:
            pending.replace(target)
        manifest = output / (dataset + ".json")
        manifest_pending = output / (dataset + "." + uuid.uuid4().hex + ".json.pending")
        manifest_pending.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
        manifest_pending.replace(manifest)
        return metadata
    finally:
        if writer is not None:
            writer.close()
        pending.unlink(missing_ok=True)


def load_export(output: Path, dataset: str):
    """先核对原子发布的清单，再读不可变 Parquet；不会读取半更新的一对文件。"""
    import pyarrow.parquet as pq
    if dataset not in DATASETS:
        raise ValueError("未知 BTC 数据集")
    metadata = json.loads((output / (dataset + ".json")).read_text())
    filename = metadata["file"]
    if Path(filename).name != filename:
        raise ValueError("研究清单路径越界")
    target = output / filename
    with target.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != metadata["parquetSha256"]:
            raise ValueError("Parquet 与发布清单不一致")
    table = pq.ParquetFile(target).read()
    if table.num_rows != metadata["rows"]:
        raise ValueError("Parquet 行数与发布清单不一致")
    return table


def reconcile(reader: BtcReader, start: date, through: date):
    daily = reader.read("spot_1d", start, through).to_pylist()
    hourly = reader.read("spot_1h", start, through).to_pylist()
    hours = defaultdict(list)
    for row in hourly:
        hours[row["open_time"].date()].append(row)
    day_rows = {row["open_time"].date(): row for row in daily}
    mismatches = []
    incomplete = []
    matched = 0
    for offset in range((through - start).days + 1):
        day = start + timedelta(days=offset)
        items = hours.get(day, [])
        row = day_rows.get(day)
        if row is None or len(items) != 24:
            incomplete.append({"day": str(day), "dailyPresent": row is not None, "hours": len(items)})
            continue
        actual = {"open": items[0]["open"], "high": max(r["high"] for r in items), "low": min(r["low"] for r in items), "close": items[-1]["close"],
                  "volume": math.fsum(r["volume"] for r in items), "quote_volume": math.fsum(r["quote_volume"] for r in items),
                  "trade_count": sum(r["trade_count"] for r in items), "taker_buy_volume": math.fsum(r["taker_buy_volume"] for r in items), "taker_buy_quote_volume": math.fsum(r["taker_buy_quote_volume"] for r in items)}
        differences = {key: {"native": row[key], "hours": value} for key, value in actual.items() if (row[key] != value if key == "trade_count" else not math.isclose(row[key], value, rel_tol=1e-12, abs_tol=1e-8))}
        if differences: mismatches.append({"day": str(day), "differences": differences})
        else: matched += 1
    return {"from": str(start), "through": str(through), "matchedDays": matched, "incompleteDays": incomplete, "mismatches": mismatches, "relativeTolerance": 1e-12, "absoluteTolerance": 1e-8, "tradeCountExact": True,
            "dataVerified": False, "说明": "只核对现货日小时关系；期现、标记与指数价差不要求为零。"}


def funding_with_prices(reader: BtcReader, start: date, through: date):
    """按事件时刻关联已经结束的 K 线；缺价保持空，不把当前小时的收盘提前使用。"""
    from bisect import bisect_right
    funding = reader.read("funding", start, through).to_pylist()
    prices = {}
    for dataset in ("spot_1h", "perp_1h", "mark_1h", "index_1h"):
        rows = reader.read(dataset, start - timedelta(days=1), through).to_pylist()
        prices[dataset] = ([r["close_time"] for r in rows], rows)
    for event in funding:
        event_time = event["funding_time"]
        for dataset, (times, rows) in prices.items():
            index = bisect_right(times, event_time) - 1
            row = rows[index] if index >= 0 and event_time - times[index] <= timedelta(hours=2) else None
            prefix = dataset.removesuffix("_1h")
            event[prefix + "_close"] = row["close"] if row else None
            event[prefix + "_close_time"] = row["close_time"] if row else None
    return funding


def audit(reader: BtcReader, start: date, through: date):
    coverage = {}
    hourly_prices = {}
    for dataset, (_, time_key, _, interval) in DATASETS.items():
        rows = reader.read(dataset, start, through).to_pylist()
        item = {"rows": len(rows), "first": rows[0][time_key].isoformat() if rows else None, "last": rows[-1][time_key].isoformat() if rows else None}
        if interval is not None:
            present = {r[time_key] for r in rows}
            lower = datetime.combine(start, datetime.min.time(), UTC)
            until = datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC)
            missing = []
            current = lower
            while current < until:
                if current not in present: missing.append(current.isoformat())
                current += timedelta(minutes=interval)
            item["missingOpenTimes"] = missing
        else:
            item["说明"] = "事件级来源，不按固定八小时凑预期行数。"
        if interval is not None:
            shortened = []
            for row in rows:
                micros = 1 if dataset.startswith("spot_") and row[time_key].date() >= date(2025,1,1) else 1000
                expected = row[time_key] + timedelta(minutes=interval) - timedelta(microseconds=micros)
                if row["close_time"] != expected:
                    shortened.append({"openTime": row[time_key].isoformat(), "closeTime": row["close_time"].isoformat(), "nominalCloseTime": expected.isoformat()})
            item["shortenedBars"] = shortened
        if interval == 60:
            hourly_prices[dataset] = {r[time_key]: r["close"] for r in rows}
        coverage[dataset] = item
    events = funding_with_prices(reader, start, through)
    unmatched = [r["funding_time"].isoformat() for r in events if any(r[k + "_close"] is None for k in ("spot", "perp", "mark", "index"))]
    common = set.intersection(*(set(rows) for rows in hourly_prices.values()))
    premiums = {}
    for label, left, right in (("perpVsSpot", "perp_1h", "spot_1h"), ("markVsIndex", "mark_1h", "index_1h")):
        values = [(hourly_prices[left][t] / hourly_prices[right][t] - 1) for t in common]
        premiums[label] = {"alignedHours": len(values), "minRatio": min(values) if values else None, "maxRatio": max(values) if values else None, "meanRatio": math.fsum(values)/len(values) if values else None}
    return {"observedAt": datetime.now(UTC).isoformat(), "from": str(start), "through": str(through), "coverage": coverage, "priceDifferences": premiums,
            "spotReconciliation": reconcile(reader, start, through), "fundingEvents": len(events), "fundingWithoutAllPrices": unmatched,
            "dataVerified": False, "说明": "范围、缺口与关联诊断；来源完整性和正式发布证据另看 Java 账本。历史修订价格不证明当时已知。"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("export", "reconcile", "audit"))
    parser.add_argument("--dataset", choices=("all", *DATASETS), default="all")
    parser.add_argument("--from", dest="start", required=True, type=date.fromisoformat)
    parser.add_argument("--through", required=True, type=date.fromisoformat)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    endpoint, authorization = configured_endpoint()
    reader = BtcReader(endpoint, authorization)
    if args.command == "export":
        result = [export(reader, dataset, args.start, args.through, args.output) for dataset in (DATASETS if args.dataset == "all" else [args.dataset])]
    else:
        result = audit(reader, args.start, args.through) if args.command == "audit" else reconcile(reader, args.start, args.through)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result if args.command == "export" else {"output": str(args.output), "dataVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
