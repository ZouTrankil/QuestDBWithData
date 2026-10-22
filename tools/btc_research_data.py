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


def _arrow_table(dataset, columns, values):
    """空结果也沿用实际表的 Schema；字段类型和空值规则与非空页一致。"""
    import pyarrow as pa
    if not columns:
        raise ValueError("空查询缺少实际 Schema，不能猜测字段")
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
    return pa.Table.from_arrays(arrays, schema=pa.schema(schema))


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
                if last is None:
                    yield _arrow_table(dataset, columns, [])
                break
            batch = _arrow_table(dataset, columns, values)
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
        if not batches:
            raise ValueError("读取没有返回实际 Schema")
        return pa.concat_tables(batches)


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
            raise ValueError(f"{dataset} 未取得实际 Schema，保留原文件")
        writer.close()
        writer = None
        with pending.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        metadata = {"dataset": dataset, "from": str(start), "through": str(through), "rows": rows,
                    "parquetSha256": digest, "file": dataset + "-" + digest + ".parquet",
                    "resultState": "EMPTY_OBSERVED" if rows == 0 else "OBSERVED",
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


def configured_crypto_value(name, default):
    """研究参数沿用根配置，环境覆盖与 Java 配置名对应。"""
    import yaml
    value = default
    for path in (ROOT / "config/apps/btc-data.yml", ROOT / "config/application-local.yml"):
        if path.is_file():
            for doc in yaml.safe_load_all(path.read_text()):
                doc = doc or {}
                activation = doc.get("spring", {}).get("config", {}).get("activate", {}).get("on-profile")
                if activation and activation not in ("btc-data", "market-btc", "local"):
                    continue
                values = {**doc.get("app.crypto-data", {}), **doc.get("app", {}).get("crypto-data", {})}
                value = values.get(name, value)
    return os.environ.get("APP_CRYPTO_DATA_" + name.upper().replace("-", "_"), value)


def funding_with_prices(reader: BtcReader, start: date, through: date, *, mode="carry", max_age_seconds=None):
    """兼容沿用价与完整同步小时分开；最终结算费率不当作结算前已知信号。"""
    from bisect import bisect_right
    if mode not in ("carry", "synchronous"):
        raise ValueError("未知关联模式")
    age_limit = float(configured_crypto_value("research-price-max-age-seconds", 7200) if max_age_seconds is None else max_age_seconds)
    if not math.isfinite(age_limit) or age_limit <= 0:
        raise ValueError("价格最大年龄必须为有限正数")
    funding = reader.read("funding", start, through).to_pylist()
    prices = {}
    for dataset in ("spot_1h", "perp_1h", "mark_1h", "index_1h"):
        rows = sorted(reader.read(dataset, start - timedelta(days=math.ceil(age_limit / 86400)), through).to_pylist(), key=lambda r: r["close_time"])
        prices[dataset] = ([r["close_time"] for r in rows], rows)
    for event in funding:
        event_time = event["funding_time"]
        # 包含毫秒偏移的结算仍选择事件所在整点之前的完整小时。
        previous_open = event_time.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
        chosen = {}
        for dataset, (times, rows) in prices.items():
            index = bisect_right(times, event_time) - 1
            row = rows[index] if index >= 0 and event_time - times[index] <= timedelta(seconds=age_limit) else None
            if row and mode == "synchronous":
                quantum = 1 if dataset.startswith("spot_") and previous_open.date() >= date(2025, 1, 1) else 1000
                if row.get("open_time") != previous_open or row["close_time"] != previous_open + timedelta(hours=1) - timedelta(microseconds=quantum):
                    row = None
            chosen[dataset] = row
        complete = all(row is not None for row in chosen.values())
        for dataset, row in chosen.items():
            prefix = dataset.removesuffix("_1h")
            event[prefix + "_close"] = row["close"] if row else None
            event[prefix + "_open_time"] = row.get("open_time") if row else None
            event[prefix + "_close_time"] = row["close_time"] if row else None
            event[prefix + "_age_seconds"] = (event_time - row["close_time"]).total_seconds() if row else None
            event[prefix + "_carried"] = bool(row and row.get("open_time") != previous_open)
        event["prices_complete"] = complete
        event["association_mode"] = mode
        event["historical_pit"] = False
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


# 批次协议与质量规则独立分版，旧单表清单不自动升级。
BATCH_VERSION = 1
QUALITY_VERSION = 3


def _json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def _digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _safe_path(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("研究文件路径越界")
    return path


def _sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_name(path.name + "." + uuid.uuid4().hex + ".pending")
    try:
        with pending.open("xb") as stream:
            stream.write(_json_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        pending.replace(path)
        _sync_directory(path.parent)
    finally:
        pending.unlink(missing_ok=True)


class FixedReader:
    """所有联表诊断只读同一批候选文件，不回到实时库取另一版数据。"""
    def __init__(self, tables):
        self.tables = tables

    def read(self, dataset, start, through):
        import pyarrow.compute as pc
        table = self.tables[dataset]
        key = DATASETS[dataset][1]
        lower = datetime.combine(start, datetime.min.time(), UTC)
        upper = datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC)
        return table.filter(pc.and_(pc.greater_equal(table[key], lower), pc.less(table[key], upper)))


def _issue(dataset, kind, lower, upper, fields, evidence=None):
    item = {"dataset": dataset, "kind": kind, "from": lower.isoformat(), "until": upper.isoformat(),
            "fields": sorted(fields), "evidence": evidence or {}, "action": "reject_strict",
            "reason": "来源原因未核实" if kind == "missing_bar" else "来源未提供间隔" if kind == "unknown_interval" else kind}
    item["id"] = hashlib.sha256(_json_bytes(item)).hexdigest()
    return item


def quality_report(tables, start, through, inputs):
    """问题按字段及区间约束；冲突双方均保留，不猜哪一侧正确。"""
    issues, coverage = [], {}
    lower = datetime.combine(start, datetime.min.time(), UTC)
    upper = datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC)
    for dataset, table in tables.items():
        key, interval = DATASETS[dataset][1], DATASETS[dataset][3]
        rows = table.to_pylist()
        present = set()
        for row in rows:
            time = row[key]
            if time in present or time < lower or time >= upper:
                raise ValueError("候选文件业务键重复或越界：" + dataset)
            present.add(time)
            until = time + timedelta(minutes=interval) if interval else time + timedelta(microseconds=1)
            source = {"sourceSha256": row.get("source_sha256")}
            bad = [name for name, value in row.items() if value is None and name != "funding_interval_hours"
                   or isinstance(value, float) and not math.isfinite(value)]
            if bad:
                issues.append(_issue(dataset, "invalid_value", time, until, bad, source))
            if interval:
                if (time - lower).total_seconds() % (interval * 60):
                    issues.append(_issue(dataset, "off_grid", time, until, ["*"], source))
                quantum = 1 if dataset.startswith("spot_") and time.date() >= date(2025, 1, 1) else 1000
                if row["close_time"] != until - timedelta(microseconds=quantum):
                    issues.append(_issue(dataset, "shortened_bar", time, until, ["*"], source))
                if not (0 < row["low"] <= min(row["open"], row["close"]) <= max(row["open"], row["close"]) <= row["high"]):
                    issues.append(_issue(dataset, "invalid_ohlc", time, until, ["open", "high", "low", "close"], source))
                for field in ("volume", "quote_volume", "trade_count", "taker_buy_volume", "taker_buy_quote_volume"):
                    if field in row and (row[field] is None or row[field] < 0):
                        issues.append(_issue(dataset, "negative_or_null", time, until, [field], source))
            elif row.get("funding_interval_hours") is None:
                issues.append(_issue(dataset, "unknown_interval", time, until, ["funding_interval_hours"], source))
        missing = []
        if interval:
            current = lower
            while current < upper:
                if current not in present:
                    missing.append(current.isoformat())
                    issues.append(_issue(dataset, "missing_bar", current, current + timedelta(minutes=interval), ["*"]))
                current += timedelta(minutes=interval)
        coverage[dataset] = {"rows": len(rows), "missingOpenTimes": missing,
                             "first": min(present).isoformat() if present else None, "last": max(present).isoformat() if present else None,
                             "sourceCompleteness": "unknown" if interval is None else "gaps" if missing else "grid_present"}
    reconciliation = None
    if {"spot_1d", "spot_1h"} <= tables.keys():
        reconciliation = reconcile(FixedReader(tables), start, through)
        for mismatch in reconciliation["mismatches"]:
            day = datetime.combine(date.fromisoformat(mismatch["day"]), datetime.min.time(), UTC)
            for dataset in ("spot_1d", "spot_1h"):
                issues.append(_issue(dataset, "daily_hourly_conflict", day, day + timedelta(days=1),
                                     mismatch["differences"].keys(), mismatch["differences"]))
    # 共同价格范围排除缺小时、非完整小时和 OHLC 问题；成交字段冲突不牵连价格。
    hourly = [d for d in tables if DATASETS[d][3] == 60]
    common = None
    for dataset in hourly:
        valid = set(tables[dataset]["open_time"].to_pylist())
        for issue in issues:
            if issue["dataset"] != dataset or not ({"*", "open", "high", "low", "close"} & set(issue["fields"])):
                continue
            current = datetime.fromisoformat(issue["from"])
            end = datetime.fromisoformat(issue["until"])
            while current < end:
                valid.discard(current)
                current += timedelta(hours=1)
        common = valid if common is None else common & valid
    ranges = []
    for current in sorted(common or []):
        if ranges and ranges[-1]["until"] == current.isoformat():
            ranges[-1]["until"] = (current + timedelta(hours=1)).isoformat()
        else:
            ranges.append({"from": current.isoformat(), "until": (current + timedelta(hours=1)).isoformat()})
    funding_associations, association_issues = funding_association_report(tables, lower, upper)
    issues.extend(association_issues)
    issues.sort(key=lambda i: (i["dataset"], i["from"], i["kind"], i["id"]))
    return {"schemaVersion": QUALITY_VERSION, "from": lower.isoformat(), "until": upper.isoformat(), "inputs": inputs, "issues": issues, "coverage": coverage,
            "spotReconciliation": reconciliation, "historicalPit": False,
            "commonCompleteHourlyOhlc": {"datasets": hourly, "hours": len(common or []), "ranges": ranges},
            "fundingPriceAssociations": funding_associations,
            "relativeTolerance": 1e-12, "absoluteTolerance": 1e-8, "tradeCountExact": True}


def funding_association_report(tables, lower, until):
    """事件只关联前一完整小时；价格缺失与费率字段的可用性分别约束。"""
    prices = ("spot_1h", "perp_1h", "mark_1h", "index_1h")
    required = ("funding", *prices)
    missing = sorted(set(required) - tables.keys())
    if missing:
        return {"status": "NOT_EVALUATED", "missingDatasets": missing, "events": []}, []
    indices = {d: {r["open_time"]: r for r in tables[d].to_pylist()} for d in prices}
    events, issues = [], []
    for event in tables["funding"].to_pylist():
        time = event["funding_time"]
        expected = time.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
        selected = {}
        for dataset in prices:
            row = indices[dataset].get(expected)
            status = "COMPLETE"
            quantum = 1 if dataset.startswith("spot_") and expected.date() >= date(2025, 1, 1) else 1000
            if expected < lower or expected >= until:
                status = "OUTSIDE_BATCH"
            elif row is None:
                status = "MISSING_HOUR"
            elif row["close_time"] != expected + timedelta(hours=1) - timedelta(microseconds=quantum):
                status = "INCOMPLETE_HOUR"
            elif row["close_time"] > time:
                status = "AFTER_EVENT"
            elif not all(isinstance(row[k], (int, float)) and math.isfinite(row[k]) for k in ("open", "high", "low", "close")) or not (0 < row["low"] <= min(row["open"], row["close"]) <= max(row["open"], row["close"]) <= row["high"]):
                status = "INVALID_OHLC"
            selected[dataset] = {"status": status, "openTime": row["open_time"].isoformat() if row else None,
                                 "closeTime": row["close_time"].isoformat() if row else None,
                                 "close": row["close"] if row else None,
                                 "sourceSha256": row["source_sha256"] if row else None,
                                 "ageSeconds": (time - row["close_time"]).total_seconds() if row else None}
        complete = all(v["status"] == "COMPLETE" for v in selected.values())
        item = {"fundingTime": time.isoformat(), "expectedOpenTime": expected.isoformat(),
                "complete": complete, "prices": selected}
        events.append(item)
        if not complete:
            issue = _issue("funding", "funding_price_missing", time, time + timedelta(microseconds=1), [], item)
            issue["purposes"] = ["funding_synchronous"]
            issue["id"] = hashlib.sha256(_json_bytes({k: v for k, v in issue.items() if k != "id"})).hexdigest()
            issues.append(issue)
    return {"status": "EVALUATED", "datasets": list(required), "events": events,
            "completeEvents": sum(e["complete"] for e in events), "incompleteEvents": len(issues),
            "historicalPit": False}, issues


def _normalized_row(row):
    # Java prepared 使用 ISO 文本，Parquet 使用 UTC datetime；按相同微秒值核对。
    result = dict(row)
    for key in ("open_time", "close_time", "funding_time", "observed_at"):
        value = result.get(key)
        if isinstance(value, str):
            result[key] = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return result



def _successful_receipt(base, run, card, target):
    """恢复回执不能覆盖原未决回执；两者共同绑定原 prepared 和物理目标。"""
    if card["result"].get("state") == "VERIFIED":
        return []
    recoveries = []
    for recovered in (base / "reconciliations").glob(run + "-*/receipt.json"):
        intent_path = recovered.parent / "intent.json"
        if not intent_path.is_file():
            continue
        intent, final = json.loads(intent_path.read_text()), json.loads(recovered.read_text())
        if final.get("state") == "VERIFIED" and final.get("runId") == run and intent.get("runId") == run and intent.get("targetId") == target and intent.get("preparedSha256") == card["preparedSha256"]:
            recoveries.append({"receiptSha256": _digest(recovered), "intentSha256": _digest(intent_path)})
    if not recoveries:
        raise ValueError("原运行缺少核验完成或恢复证明：" + run)
    return recoveries


def _revision_resolution(base, finding):
    """只接受原 Java 修订及恢复完成，不用新一轮 UNCHANGED 或人工删报告解除阻断。"""
    if finding.get("kind") == "overlap":
        # 跨来源冲突不是原 ZIP 同键修订，不能拿另一种成功记录冒认已解决。
        return None
    queue, ledger = base / "binance-vision/archive-queue.sqlite3", base / "sync-ledger.sqlite3"
    if not queue.is_file() or not ledger.is_file():
        return None
    with closing(sqlite3.connect(queue.as_uri() + "?mode=ro", uri=True)) as db:
        if not db.execute("SELECT name FROM sqlite_master WHERE name='archive_revision_runs'").fetchone():
            return None
        rows = db.execute("SELECT id,new_run,bundle FROM archive_revision_runs WHERE task=? AND previous_run=? AND source_sha=? AND phase='VERIFIED'", (finding["task"], finding["originalRun"], finding["newSha256"])).fetchall()
        current = db.execute("SELECT phase,run_id FROM archive_tasks WHERE id=?", (finding["task"],)).fetchone()
        if not rows or not current or current[0] not in ("VERIFIED", "PARTIAL_VERIFIED"):
            return None
        edges = db.execute("SELECT previous_run,new_run FROM archive_revision_runs WHERE task=? AND phase='VERIFIED'", (finding["task"],)).fetchall()
    if len(rows) != 1:
        raise ValueError("同一修订存在冲突成功记录")
    revision, run, bundle = rows[0]
    if not run or Path(run).name != run or not run.startswith("market-"):
        raise ValueError("原修订运行号非法")
    if revision != hashlib.sha256((finding["originalRun"] + "|" + finding["newSha256"]).encode()).hexdigest():
        raise ValueError("原修订身份不符")
    reachable = {run}
    while True:
        more = {new for old, new in edges if old in reachable}
        if more <= reachable:
            break
        reachable |= more
    if current[1] not in reachable:
        return None
    with closing(sqlite3.connect(ledger.as_uri() + "?mode=ro", uri=True)) as db:
        row = db.execute("SELECT r.target_id,r.frozen_json FROM sync_runs r JOIN sync_entries e ON e.run_id=r.id WHERE r.id=? AND r.job_id=? AND e.kind='RUN' AND e.state='VERIFIED'", (run, "market." + DATASETS[finding["dataset"]][0])).fetchone()
    if not row:
        return None
    prepared = base / "prepared" / (run + ".json")
    receipt = base / "receipts" / (run + ".json")
    if not prepared.is_file() or not receipt.is_file():
        raise ValueError("原修订完成但缺少 prepared 或回执")
    doc, card = json.loads(prepared.read_text()), json.loads(receipt.read_text())
    if card.get("preparedSha256") != _digest(prepared) or card.get("targetId") != row[0] or card.get("dataset") != DATASETS[finding["dataset"]][0] or card.get("result", {}).get("runId") != run:
        raise ValueError("原修订正式回执不符")
    frozen = json.loads(row[1])
    if frozen.get("parameters", {}).get("prepared_sha256") != card["preparedSha256"] or frozen.get("parameters", {}).get("source_sha256") != doc["sourceSha256"] or doc["definition"]["datasetId"] != DATASETS[finding["dataset"]][0]:
        raise ValueError("原修订冻结请求与回执不符")
    recoveries = _successful_receipt(base, run, card, row[0])
    evidence = doc.get("evidence", {})
    raw = Path(doc["raw"]).resolve()
    if evidence.get("revision") != revision or evidence.get("previousRun") != finding["originalRun"] or raw != Path(bundle).resolve() or not raw.is_relative_to(base.resolve()) or _digest(raw) != doc["sourceSha256"] or card.get("sourceSha256") != doc["sourceSha256"]:
        raise ValueError("原修订来源与意图不符")
    source = json.loads(raw.read_text())
    members = source.get("members", [])
    if len(members) != 1 or members[0].get("task") != finding["task"] or members[0].get("sha256") != finding["newSha256"]:
        raise ValueError("原修订未绑定待办的官方版本")
    for field, hash_field in (("raw", "sha256"), ("checksum", "checksumSha256")):
        original = Path(members[0][field]).resolve()
        if not original.is_relative_to(base.resolve()) or _digest(original) != members[0][hash_field]:
            raise ValueError("原修订官方原件已变化")
    if not doc.get("rows") or any(r.get("source_sha256") != finding["newSha256"] for r in doc["rows"]):
        raise ValueError("原修订行版本不符")
    return {"runId": run, "preparedSha256": _digest(prepared), "receiptSha256": _digest(receipt), "reconciliations": recoveries}


def pending_revisions(base, lower=None, until=None, datasets=None):
    """持久登记全部探测发现；报告丢失或改写拒绝放行，完成证据每轮重核。"""
    if base is None:
        return []
    base = Path(base).absolute()
    root = base / "binance-vision"
    if not root.is_dir():
        return []
    reports = sorted([*(root / "revision-checks").glob("*.json"), *(root / "overlap-conflicts").glob("*.json")])
    registry = root / "revision-review.sqlite3"
    if not reports and not registry.is_file():
        return []
    with closing(sqlite3.connect(registry, timeout=30)) as db:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("CREATE TABLE IF NOT EXISTS review_reports(path TEXT PRIMARY KEY,sha256 TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS review_findings(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        with db:
            recorded = dict(db.execute("SELECT path,sha256 FROM review_reports"))
            for name, digest in recorded.items():
                path = _safe_path(root, name)
                if not path.is_file() or _digest(path) != digest:
                    raise ValueError("修订探测原报告缺失或变化：" + name)
            for report in reports:
                relative = str(report.relative_to(root))
                report = _safe_path(root, relative)
                data = report.read_bytes()
                digest = hashlib.sha256(data).hexdigest()
                if relative in recorded:
                    continue
                doc = json.loads(data)
                if relative.startswith("overlap-conflicts/"):
                    dataset = next((d for d, specification in DATASETS.items() if specification[0] == doc.get("dataset")), None)
                    if dataset is None or not doc.get("conflicts") or not doc.get("targetId"):
                        raise ValueError("来源重叠冲突报告缺少身份或差异")
                    # 旧报告的编码键没有独立时间契约，不能猜日期缩小受影响范围。
                    finding = {"id": digest, "kind": "overlap", "dataset": dataset, "targetId": doc["targetId"],
                               "from": "0001-01-01", "through": "9999-12-30", "report": relative,
                               "reportSha256": digest, "resolution": "EXPLICIT_SOURCE_REVIEW_REQUIRED"}
                    db.execute("INSERT OR IGNORE INTO review_findings VALUES(?,?)", (digest, _json_bytes(finding).decode()))
                    db.execute("INSERT INTO review_reports VALUES(?,?)", (relative, digest))
                    continue
                if not isinstance(doc.get("results"), list):
                    raise ValueError("修订探测报告缺少 results")
                for candidate in doc["results"]:
                    if candidate.get("status") != "REVISION_REQUIRES_REVIEW":
                        continue
                    finding = {k: candidate[k] for k in ("task", "dataset", "from", "through", "originalRun", "oldSha256", "newSha256")}
                    if finding["dataset"] not in DATASETS or date.fromisoformat(finding["through"]) < date.fromisoformat(finding["from"]):
                        raise ValueError("修订待办数据集或范围不符")
                    for key in ("task", "oldSha256", "newSha256"):
                        if len(finding[key]) != 64 or any(c not in "0123456789abcdef" for c in finding[key]):
                            raise ValueError("修订待办身份或摘要非法")
                    if finding["oldSha256"] == finding["newSha256"]:
                        raise ValueError("修订待办新旧版本相同")
                    finding_id = hashlib.sha256(_json_bytes(finding)).hexdigest()
                    finding.update(id=finding_id, report=relative, reportSha256=digest)
                    db.execute("INSERT OR IGNORE INTO review_findings VALUES(?,?)", (finding_id, _json_bytes(finding).decode()))
                db.execute("INSERT INTO review_reports VALUES(?,?)", (relative, digest))
        findings = [json.loads(r[0]) for r in db.execute("SELECT payload FROM review_findings ORDER BY id")]
    pending = []
    for finding in findings:
        begin = datetime.combine(date.fromisoformat(finding["from"]), datetime.min.time(), UTC)
        end = datetime.combine(date.fromisoformat(finding["through"]) + timedelta(days=1), datetime.min.time(), UTC)
        if datasets is not None and finding["dataset"] not in datasets or lower is not None and end <= lower or until is not None and begin >= until:
            continue
        if _revision_resolution(base, finding) is None:
            pending.append(finding)
    return pending


def require_no_pending_revisions(base, lower=None, until=None, datasets=None):
    findings = pending_revisions(base, lower, until, datasets)
    if findings:
        raise ValueError("存在跨期待核对修订，保留旧研究批次：" + ",".join(f["id"] for f in findings))


def formal_evidence(reader, dataset, table, snapshot, lower=None, until=None):
    """逐行匹配已核验 prepared；账本可读或 WAL 稳定本身不构成正式证明。"""
    base = reader.writer_root
    if not base or not (base / "sync-ledger.sqlite3").is_file():
        return {"verified": False, "reason": "原发布账本不可用", "runs": []}
    name, key, _, _ = DATASETS[dataset]
    target = "market-" + hashlib.sha256(json.dumps({"id": snapshot[0], "directoryName": snapshot[1]}, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    actual = {r[key]: r for r in table.to_pylist()}
    expected, proofs = {}, []
    if not actual:
        return {"verified": False, "reason": "没有可认证记录", "runs": []}
    lower = lower or min(actual)
    until = until or max(actual) + timedelta(microseconds=1)
    with closing(sqlite3.connect((base / "sync-ledger.sqlite3").absolute().as_uri() + "?mode=ro", uri=True)) as db:
        runs = db.execute("SELECT r.id,e.updated_at,r.frozen_json FROM sync_runs r JOIN sync_entries e ON e.run_id=r.id AND e.kind='RUN' AND e.state='VERIFIED' WHERE r.job_id=? AND r.target_id=?", ("market." + name, target)).fetchall()
        # 同键按正式完成次序选最后版本，不能拿旧成功运行认证回退值。
        runs.sort(key=lambda item: (datetime.fromisoformat(item[1].replace("Z", "+00:00")), item[0]))
        for run, completed_at, frozen_json in runs:
            # 先按原请求排除不相交运行；影响当前范围的任何缺证都必须拒绝认证。
            try:
                frozen = json.loads(frozen_json)
                run_lower = datetime.combine(date.fromisoformat(frozen["from"]), datetime.min.time(), UTC)
                run_upper = datetime.combine(date.fromisoformat(frozen["to"]) + timedelta(days=1), datetime.min.time(), UTC)
                if run_upper <= run_lower or frozen["definition"]["datasetId"] != name:
                    raise ValueError("原请求数据集或范围不符")
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError("原成功请求缺少有效冻结范围：" + run) from error
            if run_upper <= lower or run_lower >= until:
                continue
            if not run.startswith("market-") or Path(run).name != run:
                raise ValueError("原运行号非法")
            prepared, receipt = base / "prepared" / (run + ".json"), base / "receipts" / (run + ".json")
            if not prepared.is_file() or not receipt.is_file():
                raise ValueError("原成功运行缺少 prepared 或回执：" + run)
            card = json.loads(receipt.read_text())
            if card.get("dataset") != name or card.get("targetId") != target or card.get("preparedSha256") != _digest(prepared) or card.get("result", {}).get("runId") != run:
                raise ValueError("原正式回执不一致：" + run)
            recovery = _successful_receipt(base, run, card, target)
            doc = json.loads(prepared.read_text())
            if frozen.get("parameters", {}).get("prepared_sha256") != card["preparedSha256"]:
                raise ValueError("原请求与 prepared 摘要不符：" + run)
            if doc["definition"]["datasetId"] != name or frozen.get("parameters", {}).get("source_sha256") != doc["sourceSha256"]:
                raise ValueError("原 prepared 数据集或冻结来源摘要不符")
            raw = Path(doc["raw"]).resolve()
            if not raw.is_relative_to(base.resolve()) or not raw.is_file() or _digest(raw) != doc["sourceSha256"] or card.get("sourceSha256") != doc["sourceSha256"]:
                raise ValueError("原正式来源摘要或归属不符：" + run)
            checksum_name = doc.get("evidence", {}).get("checksumPath")
            if checksum_name:
                checksum = Path(checksum_name).resolve()
                if not checksum.is_relative_to(base.resolve()) or _digest(checksum) != doc["evidence"]["checksumSha256"]:
                    raise ValueError("原官方 CHECKSUM 摘要不符：" + run)
            if raw.suffix == ".json":
                source = json.loads(raw.read_text())
                if isinstance(source, dict) and str(source.get("format", "")).startswith("binance.archive.bundle."):
                    for member in source["members"]:
                        for field, hash_field in (("raw", "sha256"), ("checksum", "checksumSha256")):
                            original = Path(member[field]).resolve()
                            if not original.is_relative_to(base.resolve()) or _digest(original) != member[hash_field]:
                                raise ValueError("原归档成员摘要或归属不符：" + run)

            hits = 0
            for row in doc["rows"]:
                row = _normalized_row(row)
                time = row[key]
                if lower <= time < until:
                    expected[time] = row
                    hits += 1
            if hits:
                proofs.append({"runId": run, "preparedSha256": _digest(prepared), "receiptSha256": _digest(receipt), "targetId": target, "reconciliations": recovery})
    matched = sum(actual.get(time) == row for time, row in expected.items())
    return {"verified": expected == actual and table.num_rows > 0, "matchedRows": matched,
            "expectedRows": len(expected), "actualRows": table.num_rows, "runs": proofs,
            "reason": "原成功来源键集合及最新正式值匹配；不证明历史 PIT 或来源自身无缺口"}


def publish_batch(reader, start, through, output, datasets=None, *, max_lag_days=None, require_verified=False):
    """先固定所有表版本和候选文件，再核验质量，最后只切换一个批次指针。"""
    import fcntl
    import pyarrow.parquet as pq
    datasets = list(DATASETS if datasets is None else datasets)
    if not datasets or len(set(datasets)) != len(datasets) or any(d not in DATASETS for d in datasets) or through < start:
        raise ValueError("批次数据集或范围非法")
    output.mkdir(parents=True, exist_ok=True)
    with (output / ".publish.lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        begin = datetime.combine(start, datetime.min.time(), UTC)
        end = datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC)
        require_no_pending_revisions(reader.writer_root, begin, end, datasets)
        before = {d: reader._snapshot(d) for d in datasets}
        members, tables = {}, {}
        for dataset in datasets:
            # export 保留旧接口；候选目录隔离，避免更新旧单表指针。
            candidate = output / (".candidate-" + uuid.uuid4().hex)
            try:
                meta = export(reader, dataset, start, through, candidate)
                source = candidate / meta["file"]
                table = pq.ParquetFile(source).read()
                # 再从固定版本读取并逐字段比较，不能仅凭行数证明文件回读正确。
                expected = reader.read(dataset, start, through)
                if not table.equals(expected, check_metadata=True):
                    raise ValueError("Parquet 全字段回读不一致：" + dataset)
                target = _safe_path(output, "objects/" + meta["file"])
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists():
                    if _digest(target) != meta["parquetSha256"]:
                        raise ValueError("已有不可变文件损坏")
                else:
                    with source.open("rb") as stream:
                        os.fsync(stream.fileno())
                    source.replace(target)
                _sync_directory(target.parent)
                meta["file"] = str(target.relative_to(output.resolve()))
                meta["schema"] = str(table.schema)
                meta["sourceSha256"] = sorted(set(table["source_sha256"].to_pylist()))
                meta["formalEvidence"] = formal_evidence(reader, dataset, table, before[dataset], datetime.combine(start, datetime.min.time(), UTC), datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC))
                members[dataset], tables[dataset] = meta, table
            finally:
                import shutil
                shutil.rmtree(candidate, ignore_errors=True)
        if {d: reader._snapshot(d) for d in datasets} != before:
            raise ValueError("批次成员版本变化，保留旧批次")
        inputs = {d: m["parquetSha256"] for d, m in members.items()}
        quality = quality_report(tables, start, through, inputs)
        if require_verified and not all(m["formalEvidence"]["verified"] for m in members.values()):
            raise ValueError("候选批次缺少完整原正式发布证明，保留旧指针")
        if max_lag_days is not None:
            if max_lag_days <= 0:
                raise ValueError("新鲜度阈值必须大于零")
            stale = [d for d, c in quality["coverage"].items() if c["last"] is None or through - date.fromisoformat(c["last"][:10]) >= timedelta(days=max_lag_days)]
            if stale:
                raise ValueError("来源延迟，保留旧批次：" + ",".join(stale))
        quality_sha = hashlib.sha256(_json_bytes(quality)).hexdigest()
        manifest = {"schemaVersion": BATCH_VERSION, "ruleVersion": QUALITY_VERSION,
                    "from": datetime.combine(start, datetime.min.time(), UTC).isoformat(),
                    "until": datetime.combine(through + timedelta(days=1), datetime.min.time(), UTC).isoformat(),
                    "members": members, "qualitySha256": quality_sha, "historicalPit": False, "verificationScope": "present_rows",
                    "dataVerified": all(m["formalEvidence"]["verified"] for m in members.values())}
        batch_id = hashlib.sha256(_json_bytes(manifest)).hexdigest()
        folder = _safe_path(output, "batches/" + batch_id)
        folder.mkdir(parents=True, exist_ok=True)
        for name, value in (("quality.json", quality), ("manifest.json", manifest)):
            path = folder / name
            if path.exists():
                if path.read_bytes() != _json_bytes(value):
                    raise ValueError("不可变批次文件冲突")
            else:
                _atomic_json(path, value)
        # 先持久化成员和批次目录，再提交唯一指针；不能只 fsync 根目录。
        _sync_directory(folder)
        _sync_directory(folder.parent)
        _sync_directory(output)
        # 质量核验后再次检查所有成员，拒绝核验期间发生的正式更新。
        if {d: reader._snapshot(d) for d in datasets} != before:
            raise ValueError("批次发布前版本变化，保留旧指针")
        require_no_pending_revisions(reader.writer_root, begin, end, datasets)
        _atomic_json(output / "current.json", {"schemaVersion": BATCH_VERSION, "batchId": batch_id})
        return {"batchId": batch_id, "dataVerified": manifest["dataVerified"], "issues": len(quality["issues"])}


def read_batch(output, dataset, *, batch_id=None, lower=None, until=None, columns=None, mode="strict", require_verified=True, purpose="raw", writer_root=None):
    """严格读取执行字段级质量规则；探索读取保留问题，不静默丢行。"""
    import pyarrow.compute as pc
    import pyarrow.parquet as pq
    current_requested = batch_id is None
    if dataset not in DATASETS or mode not in ("strict", "explore") or purpose not in ("raw", "funding_synchronous"):
        raise ValueError("未知数据集、读取模式或用途")
    if purpose == "funding_synchronous" and dataset != "funding":
        raise ValueError("同步资金费率用途只接受 funding 数据集")
    if batch_id is None:
        pointer = json.loads(_safe_path(output, "current.json").read_text())
        if pointer.get("schemaVersion") != BATCH_VERSION:
            raise ValueError("未知批次指针版本")
        batch_id = pointer["batchId"]
    if len(batch_id) != 64 or any(c not in "0123456789abcdef" for c in batch_id):
        raise ValueError("批次 ID 非法")
    folder = _safe_path(output, "batches/" + batch_id)
    manifest = json.loads(_safe_path(output, "batches/" + batch_id + "/manifest.json").read_text())
    if hashlib.sha256(_json_bytes(manifest)).hexdigest() != batch_id or manifest.get("schemaVersion") != BATCH_VERSION or manifest.get("ruleVersion") not in (1, 2, QUALITY_VERSION):
        raise ValueError("批次清单摘要或版本不符")
    quality_path = _safe_path(output, "batches/" + batch_id + "/quality.json")
    if _digest(quality_path) != manifest["qualitySha256"]:
        raise ValueError("质量报告摘要不符")
    quality = json.loads(quality_path.read_text())
    if quality.get("schemaVersion") != manifest["ruleVersion"] or quality["inputs"] != {d: m["parquetSha256"] for d, m in manifest["members"].items()}:
        raise ValueError("质量报告未绑定本批次")
    if manifest["ruleVersion"] >= 2 and (quality.get("from") != manifest["from"] or quality.get("until") != manifest["until"]):
        raise ValueError("质量报告范围与批次不符")
    if dataset not in manifest["members"]:
        raise ValueError("批次不含所需数据集")
    meta = manifest["members"][dataset]
    path = _safe_path(output, meta["file"])
    if _digest(path) != meta["parquetSha256"]:
        raise ValueError("批次成员摘要不符")
    table = pq.ParquetFile(path).read()
    if table.num_rows != meta["rows"] or str(table.schema) != meta["schema"]:
        raise ValueError("批次成员 Schema 或行数不符")
    lower = lower or datetime.fromisoformat(manifest["from"])
    until = until or datetime.fromisoformat(manifest["until"])
    if lower.tzinfo is None or until.tzinfo is None or until < lower or lower < datetime.fromisoformat(manifest["from"]) or until > datetime.fromisoformat(manifest["until"]):
        raise ValueError("读取范围必须是批次内 UTC 左闭右开区间")
    review_base = Path(writer_root).absolute() if writer_root is not None else output.absolute().parent
    review_available = (review_base / "binance-vision").is_dir()
    review_datasets = [dataset] if purpose == "raw" else ["funding", "spot_1h", "perp_1h", "mark_1h", "index_1h"]
    review_pending = pending_revisions(review_base, lower, until, review_datasets) if review_available else []
    if current_requested and mode == "strict" and review_pending:
        raise ValueError("当前版本存在待核对修订；固定历史批次需显式选择：" + ",".join(f["id"] for f in review_pending))
    requested = list(table.column_names if columns is None else columns)
    if not requested or any(c not in table.column_names for c in requested):
        raise ValueError("读取列为空或未知")
    issues = [i for i in quality["issues"] if lower < until and i["dataset"] == dataset and datetime.fromisoformat(i["from"]) < until and datetime.fromisoformat(i["until"]) > lower and ("*" in i["fields"] or set(requested).intersection(i["fields"]) or purpose in i.get("purposes", []))]
    associations = []
    if purpose == "funding_synchronous":
        association_report = quality.get("fundingPriceAssociations", {})
        if association_report.get("status") != "EVALUATED":
            raise ValueError("本批次缺少固定文件的资金费率同步关联证据")
        for dependency in association_report["datasets"]:
            if dependency == dataset:
                continue
            member = manifest["members"][dependency]
            original = _safe_path(output, member["file"])
            if _digest(original) != member["parquetSha256"]:
                raise ValueError("关联价格摘要不符：" + dependency)
            file = pq.ParquetFile(original)
            if file.metadata.num_rows != member["rows"] or str(file.schema_arrow) != member["schema"]:
                raise ValueError("关联价格 Schema 或行数不符：" + dependency)
        associations = [event for event in association_report["events"] if lower <= datetime.fromisoformat(event["fundingTime"]) < until]
        if mode == "strict" and require_verified and any(not manifest["members"][d]["formalEvidence"]["verified"] for d in association_report["datasets"]):
            raise ValueError("关联价格缺少本批次正式发布证明")
    if mode == "strict" and require_verified and not meta["formalEvidence"]["verified"]:
        raise ValueError("缺少本批次原正式发布证明")
    if mode == "strict" and issues:
        raise ValueError("质量规则拒绝读取：" + ",".join(i["id"] for i in issues))
    key = DATASETS[dataset][1]
    table = table.filter(pc.and_(pc.greater_equal(table[key], lower), pc.less(table[key], until)))
    return {"table": table.select(requested), "batchId": batch_id, "issues": issues, "excludedRows": 0,
            "actualRows": table.num_rows, "resultState": "EMPTY_RESULT" if table.num_rows == 0 else "REVISION_REVIEW" if review_pending else "PARTIAL" if issues else "AVAILABLE",
            "purpose": purpose, "associations": associations,
            "pendingRevisions": review_pending, "revisionReviewAvailable": review_available,
            "historicalPit": False, "formalVerified": meta["formalEvidence"]["verified"], "file": str(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("export", "publish-batch", "reconcile", "audit"))
    parser.add_argument("--dataset", choices=("all", *DATASETS), default="all")
    parser.add_argument("--from", dest="start", required=True, type=date.fromisoformat)
    parser.add_argument("--through", required=True, type=date.fromisoformat)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    endpoint, authorization = configured_endpoint()
    reader = BtcReader(endpoint, authorization)
    if args.command == "publish-batch":
        result = publish_batch(reader, args.start, args.through, args.output, None if args.dataset == "all" else [args.dataset])
    elif args.command == "export":
        result = [export(reader, dataset, args.start, args.through, args.output) for dataset in (DATASETS if args.dataset == "all" else [args.dataset])]
    else:
        result = audit(reader, args.start, args.through) if args.command == "audit" else reconcile(reader, args.start, args.through)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result if args.command in ("export", "publish-batch") else {"output": str(args.output), "dataVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
