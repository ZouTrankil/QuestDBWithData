"""小型研究数据的容量、摘要与解析。下载由Java负责，这里只读原件和派生文件。"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]


def properties(path):
    """读取本项目简单properties与Java回执；配置不使用续行或Unicode转义。"""
    result = {}
    for line in Path(path).read_text(encoding="utf8").splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = re.sub(r"\\([:=\\ ])", r"\1", value.strip())
    return result


def settings():
    return properties(ROOT / "config/research-playbook.properties")


def data_root():
    return ROOT / settings()["data.root"]


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def size_bytes(folder):
    return sum(p.stat().st_size for p in Path(folder).rglob("*") if p.is_file())


@contextmanager
def data_lock(folder):
    """与Java使用POSIX记录锁；当前教学环境是macOS/Linux。"""
    import fcntl
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / ".lock").open("a") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            fcntl.lockf(lock, fcntl.LOCK_UN)


def write_bounded(root, relative, body, limit=300_000_000):
    """调用者持锁；计入临时副本峰值，超限时保留旧文件。"""
    root = Path(root).resolve()
    target = (root / relative).resolve()
    if root not in target.parents or limit > 300_000_000 or limit <= 0:
        raise ValueError("输出路径或容量不合法")
    if target.exists() and target.read_bytes() == body:
        return
    if size_bytes(root) + len(body) > limit:
        raise ValueError("研究数据超过容量预算")
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix=".prepare-", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(body)
        os.replace(temp, target)
    finally:
        Path(temp).unlink(missing_ok=True)


def raw_source(source_id):
    config = settings()
    if source_id not in config["sources"].split(","):
        raise ValueError("来源不在清单")
    raw = data_root() / "raw" / (source_id + ".source")
    receipt = properties(raw.with_suffix(".properties"))
    if sha(raw) != receipt["sha256"] or receipt["url"] != config[source_id + ".url"]:
        raise ValueError("原件摘要或来源地址与冻结回执不符")
    if raw.stat().st_size != int(receipt["bytes"]):
        raise ValueError("原件大小与回执不符")
    return raw, receipt


def parse_fred(path, series):
    frame = pd.read_csv(path, na_values=["."])
    if list(frame) != ["observation_date", series] or frame.empty:
        raise ValueError("FRED字段改变或为空")
    frame = frame.rename(columns={"observation_date": "date", series: "value"})
    frame["date"] = pd.to_datetime(frame.date, utc=True, errors="raise")
    frame["value"] = pd.to_numeric(frame.value, errors="raise")
    if frame.date.duplicated().any() or not np.isfinite(frame.value.dropna()).all():
        raise ValueError("FRED有重复日期或非有限值")
    frame["series"] = series
    # 不前填空值、不把观测日写成发布时间；保留缺值供notebook展示。
    return frame.sort_values("date")


def parse_stablecoins(path):
    records = json.loads(Path(path).read_text())
    if not isinstance(records, list) or not records:
        raise ValueError("稳定币响应不是非空列表")
    rows = []
    for record in records:
        values = record.get("totalCirculatingUSD")
        if not isinstance(values, dict) or "peggedUSD" not in values:
            raise ValueError("缺少美元计价流通量；不能把缺项补零")
        numeric = np.array(list(values.values()), dtype=float)
        if not np.isfinite(numeric).all() or (numeric < 0).any():
            raise ValueError("稳定币流通量异常")
        rows.append({"date": pd.to_datetime(int(record["date"]), unit="s", utc=True),
                     "usd_pegged_value_usd": float(values["peggedUSD"]),
                     "all_pegs_value_usd": float(numeric.sum())})
    frame = pd.DataFrame(rows).sort_values("date")
    if frame.date.duplicated().any():
        raise ValueError("稳定币日期重复")
    return frame


def parse_coinmetrics(path, asset):
    frame = pd.read_csv(path)
    fields = ["time", "AdrActCnt", "TxCnt", "FeeTotNtv", "PriceUSD"]
    if not set(fields) <= set(frame):
        raise ValueError("Coin Metrics社区字段改变")
    frame = frame[fields].rename(columns={"time": "date"}).copy()
    frame["date"] = pd.to_datetime(frame.date, utc=True)
    for column in fields[1:]:
        frame[column] = pd.to_numeric(frame[column], errors="raise")
        if not np.isfinite(frame[column].dropna()).all() or (frame[column].dropna() < 0).any():
            raise ValueError("链上计数、费用或价格异常")
    if frame.date.duplicated().any():
        raise ValueError("链上日期重复")
    frame["asset"] = asset
    # 留住原空值。原生币费用不自动乘日末价格冒充供应商的美元费用。
    return frame.sort_values("date")


def load_frame(name):
    manifest = json.loads((data_root() / "manifest.json").read_text())
    meta = manifest["datasets"][name]
    if meta["status"] != "ready":
        raise ValueError(name + " 尚未就绪：" + meta["reason"])
    path = data_root() / meta["file"]
    if sha(path) != meta["sha256"]:
        raise ValueError(name + " 派生数据摘要改变，请重新准备并检查差异")
    return pd.read_parquet(path)


def inventory():
    manifest = json.loads((data_root() / "manifest.json").read_text())
    return pd.DataFrame([{"dataset": name, **meta} for name, meta in manifest["datasets"].items()])


def annual_financials():
    frame = pd.read_csv(ROOT / "research/data/ai-annual.csv")
    sources = json.loads((ROOT / "research/data/ai-sources.json").read_text())["sources"]
    numeric = ["revenue_usd_m", "cfo_usd_m", "cash_ppe_usd_m", "da_reported_usd_m"]
    if frame.duplicated(["symbol", "fiscal_year"]).any() or not np.isfinite(frame[numeric]).all().all():
        raise ValueError("财务表有重复年度、缺值或非有限值")
    if not set(frame.source_id) <= sources.keys() or (frame.revenue_usd_m <= 0).any():
        raise ValueError("财务表缺少来源或收入无效")
    # 这是统一计算口径，不自动冒充公司披露的FCF，也不把所有资本开支归给AI。
    frame["cash_surplus_usd_m"] = frame.cfo_usd_m - frame.cash_ppe_usd_m
    frame["cash_capex_to_revenue"] = frame.cash_ppe_usd_m / frame.revenue_usd_m
    frame["cash_surplus_margin"] = frame.cash_surplus_usd_m / frame.revenue_usd_m
    frame["source_url"] = frame.source_id.map(lambda x: sources[x]["url"])
    frame["da_scope"] = frame.source_id.map(lambda x: sources[x]["da_scope"])
    return frame.sort_values(["symbol", "fiscal_year"])


def parquet_bytes(frame):
    buffer = io.BytesIO()
    frame.to_parquet(buffer, index=False)
    return buffer.getvalue()
