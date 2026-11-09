"""教学中的少量共同口径；策略执行继续复用1029回放器。"""
from __future__ import annotations

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib import font_manager

from tools.plan_1029_research import simulate, metrics


def plot_style():
    available = {font.name for font in font_manager.fontManager.ttflist}
    for family in ["PingFang SC", "Arial Unicode MS", "Microsoft YaHei", "Noto Sans CJK SC"]:
        if family in available:
            plt.rcParams["font.sans-serif"] = [family]
            break
    plt.rcParams.update({"axes.unicode_minus": False, "figure.figsize": (10, 4.5),
                         "figure.dpi": 110, "axes.spines.top": False,
                         "axes.spines.right": False, "axes.grid": True, "grid.alpha": 0.2})


def btc_baselines(frame, start="2020-09-01", cost=0.0015):
    """只接收严格准入的日线OHLC；拒绝缺日，禁止前填伪造可交易价格。"""
    frame = frame.copy().sort_values("open_time")
    index = pd.DatetimeIndex(pd.to_datetime(frame.open_time, utc=True)).as_unit("ns")
    expected = pd.date_range(index.min(), index.max(), freq="D")
    if not index.equals(expected) or index.duplicated().any():
        raise ValueError("BTC日线非完整UTC日网格")
    if not np.isfinite(frame[["open", "high", "low", "close"]]).all().all():
        raise ValueError("OHLC非有限")
    if (frame.low <= 0).any() or (frame.high < frame[["open", "close", "low"]].max(axis=1)).any() or (frame.low > frame[["open", "close"]].min(axis=1)).any():
        raise ValueError("OHLC不自洽")
    first = pd.Timestamp(start, tz="UTC")
    if len(index[index < first]) < 200:
        raise ValueError("评分前不足200天预热")
    opens = pd.DataFrame({"BTCUSDT": frame.open.to_numpy()}, index=index)
    closes = pd.DataFrame({"BTCUSDT": frame.close.to_numpy()}, index=index)
    runs, scores = {}, []
    for rule in ["hold", "trend"]:
        daily, weights, trades = simulate(opens, closes, rule, first, index[-1], cost, "crypto")
        runs[rule] = {"daily": daily, "weights": weights, "trades": trades}
        scores.append({"rule": rule, **metrics(daily, trades, 100000, 365)})
    return runs, pd.DataFrame(scores).set_index("rule")


def liquidity_proxy(frame):
    """同一个星期三的余额相减；不以周均TGA替代周三余额，不向未来取值。"""
    required = {"WALCL", "WDTGAL", "RRPONTSYD"}
    if not required <= set(frame.series) or frame.duplicated(["date", "series"]).any():
        raise ValueError("宏观序列缺项或重复")
    values = frame.pivot(index="date", columns="series", values="value").sort_index()
    aligned = values.loc[values.index.dayofweek == 2, sorted(required)].dropna()
    if aligned.empty:
        raise ValueError("没有同日完整的周三观测")
    aligned["rrp_usd_m"] = aligned.RRPONTSYD * 1000
    aligned["proxy_usd_m"] = aligned.WALCL - aligned.WDTGAL - aligned.rrp_usd_m
    return aligned


def quarterly_cashflow(ytd):
    """显式同口径、同修订版本的累计值差分；缺前季拒绝，不按自然年猜财政年。"""
    required = {"symbol", "fiscal_start", "quarter", "value", "available_at", "vintage", "unit", "metric"}
    if not required <= set(ytd):
        raise ValueError("缺少累计现金流契约字段")
    keys = ["symbol", "fiscal_start", "vintage", "unit", "metric"]
    frame = ytd.copy().sort_values(keys + ["quarter"])
    frame["available_at"] = pd.to_datetime(frame.available_at, utc=True)
    if frame.duplicated(keys + ["quarter"]).any() or frame.available_at.isna().any() or not np.isfinite(frame.value).all():
        raise ValueError("季度累计记录重复、缺可见时刻或金额无效")
    pieces = []
    for _, group in frame.groupby(keys, dropna=False):
        if group[keys].isna().any().any() or group.quarter.tolist() != list(range(1, len(group) + 1)) or len(group) > 4:
            raise ValueError("累计季度不连续或缺少修订/口径身份")
        part = group.copy()
        part["quarter_value"] = part.value.diff()
        part.iloc[0, part.columns.get_loc("quarter_value")] = part.iloc[0].value
        # 当历史累计值后来修订，差分结果不能早于所用两笔输入的可见时间。
        part["quarter_available_at"] = [max(now, before) for now, before in
                                        zip(part.available_at, part.available_at.shift().fillna(part.available_at))]
        pieces.append(part)
    if not pieces:
        raise ValueError("季度输入为空")
    return pd.concat(pieces, ignore_index=True)


def block_bootstrap_mean(values, repetitions=1000, block=30, seed=42):
    """连续块重采样保留局部自相关；这里只描述均值不确定性，不声称校正了策略筛选。"""
    values = np.asarray(values, dtype=float)
    if values.ndim != 1 or not np.isfinite(values).all() or len(values) < block or block < 1:
        raise ValueError("块抽样输入无效")
    if not 1 <= repetitions <= 10000:
        raise ValueError("重复次数超出教学预算")
    rng = np.random.default_rng(seed)
    output = []
    for _ in range(repetitions):
        starts = rng.integers(0, len(values) - block + 1, size=int(np.ceil(len(values) / block)))
        sample = np.concatenate([values[i:i + block] for i in starts])[:len(values)]
        output.append(sample.mean())
    return np.asarray(output)
