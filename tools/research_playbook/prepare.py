"""把有来源证明的小数据准备为教学快照；缺源记为blocked，坏数据直接报错。"""
from __future__ import annotations

import json
import os
from pathlib import Path

import pandas as pd

from tools.btc_research_data import read_batch
from .data import (ROOT, settings, data_root, sha, data_lock, write_bounded,
                   raw_source, parse_fred, parse_stablecoins, parse_coinmetrics, parquet_bytes, annual_financials, size_bytes)


def prepare():
    config, root = settings(), data_root()
    limit = int(config["max.total.bytes"])
    report = {"schema": 1, "historicalPit": False, "through": config["through"],
              "capacityBytes": limit, "datasets": {}, "sources": {},
              "configSha256": sha(ROOT / "config/research-playbook.properties")}

    def blocked(name, reason):
        report["datasets"][name] = {"status": "blocked", "reason": reason}

    def save_frame(name, frame, scope):
        if frame.empty:
            raise ValueError(name + " 在固定窗口内为空")
        file = "prepared/" + name + ".parquet"
        write_bounded(root, file, parquet_bytes(frame), limit)
        report["datasets"][name] = {"status": "ready", "file": file, "rows": len(frame),
                                    "sha256": sha(root / file), "bytes": (root / file).stat().st_size,
                                    "scope": scope}

    with data_lock(root):
        lower = pd.Timestamp(config["from"], tz="UTC")
        upper = pd.Timestamp(config["through"], tz="UTC")
        fred = []
        for source_id, code in [("fred_walcl", "WALCL"), ("fred_tga", "WDTGAL"),
                                ("fred_rrp", "RRPONTSYD"), ("fred_dgs2", "DGS2"), ("fred_dgs10", "DGS10")]:
            try:
                path, proof = raw_source(source_id)
            except FileNotFoundError:
                blocked(code, "尚无带下载回执的FRED原件")
                continue
            frame = parse_fred(path, code)
            frame = frame.loc[frame.date.between(lower, upper)].copy()
            frame["retrieved_at"] = proof["retrievedAt"]
            fred.append(frame)
            report["sources"][source_id] = proof
        if len(fred) == 5:
            save_frame("macro", pd.concat(fred, ignore_index=True), "最新修订观测值；非历史发布时间/vintage；不可直接做时点回测")
        else:
            blocked("macro", "五条序列尚未全部取得，拒绝静默缩小范围")
        extra = []
        for sid, code in [("fred_cpi", "CPIAUCSL"), ("fred_unrate", "UNRATE"), ("fred_dff", "DFF"),
                          ("fred_m2", "M2SL"), ("fred_credit", "BAMLH0A0HYM2"), ("fred_dollar", "DTWEXBGS")]:
            try:
                path, proof = raw_source(sid)
            except FileNotFoundError:
                continue
            frame = parse_fred(path, code)
            extra.append(frame.loc[frame.date.between(lower, upper)])
            report["sources"][sid] = proof
        if len(extra) == 6:
            save_frame("macro_context", pd.concat(extra, ignore_index=True), "通胀、就业、利率、货币、信用和美元的修订后描述序列；不支持历史可见性回测")
        else:
            blocked("macro_context", "补充宏观六条序列尚未齐备")
        chain = []
        for asset in ["btc", "eth"]:
            try:
                path, proof = raw_source("coinmetrics_" + asset)
            except FileNotFoundError:
                continue
            frame = parse_coinmetrics(path, asset.upper())
            chain.append(frame.loc[frame.date.between(lower, upper)])
            report["sources"]["coinmetrics_" + asset] = proof
        if len(chain) == 2:
            save_frame("onchain", pd.concat(chain, ignore_index=True), "Coin Metrics社区回溯；费用为原生币、价格为USD参考价；空值保留。CC BY-NC 4.0")
        else:
            blocked("onchain", "BTC/ETH社区原件尚未齐备")
        try:
            path, proof = raw_source("ml4t_sp500")
        except FileNotFoundError:
            blocked("ml4t_sp500", "尚未取得固定commit中的AlgoSeek教学日线")
        else:
            frame = pd.read_parquet(path)
            save_frame("ml4t_sp500", frame, "ML4T固定commit的AlgoSeek 2017—2021教学日线；独立参考面板，非本项目正式行情。AlgoSeek保留数据权利")
            report["sources"]["ml4t_sp500"] = proof
        try:
            path, proof = raw_source("stablecoins")
        except FileNotFoundError:
            blocked("stablecoins", "未取得DefiLlama历史原件")
        else:
            frame = parse_stablecoins(path)
            save_frame("stablecoins", frame.loc[frame.date.between(lower, upper)],
                       "DefiLlama当前回溯的流通市值；不是支付额、桥接流量或历史可见快照")
            report["sources"]["stablecoins"] = proof

        financials = annual_financials()
        save_frame("ai_annual", financials, "四家公司2022—2024财政年度；官方年报人工核对；非季度、非完整AI收入")
        for path in [ROOT / "research/data/ai-annual.csv", ROOT / "research/data/ai-sources.json"]:
            report["sources"][path.name] = {"file": path.relative_to(ROOT).as_posix(), "sha256": sha(path)}
        # 未接通的自动财报路径保留状态；手工年度表不冒充API完整面板。
        blocked("ai_quarterly", "SEC companyfacts下载返回403；尚无四公司季度及修订可见性验收。年度种子表可独立使用")

        btc_root = ROOT / config["btc.root"]
        batch = config["btc.batch"]
        for dataset, fields, name in [
            ("spot_1d", ["open_time", "open", "high", "low", "close"], "btc_daily"),
            ("funding", ["funding_time", "funding_rate"], "btc_funding"),
        ]:
            try:
                result = read_batch(btc_root, dataset, batch_id=batch, columns=fields, mode="strict")
            except FileNotFoundError:
                blocked(name, "本机缺少配置固定的BTC批次；请导入原manifest、quality和引用的objects")
                continue
            save_frame(name, result["table"].to_pandas(),
                       "既有固定批次，严格按所选字段准入；日线仅OHLC，资金费仅实际结算事件；无历史PIT")
            report["sources"][name] = {"batchId": batch, "fields": fields, "qualityIssues": result["issues"]}
        if (btc_root / "batches" / batch).exists():
            for name in ["manifest.json", "quality.json"]:
                source = btc_root / "batches" / batch / name
                target = "evidence/btc-" + name
                write_bounded(root, target, source.read_bytes(), limit)
                report["sources"][target] = {"sha256": sha(source)}

        accepted_path = ROOT / "docs/snapshots/plan-1029-acceptance-20261030.json"
        accepted = json.loads(accepted_path.read_text())
        save_frame("historical_results", pd.DataFrame(accepted["实验"]["基础成本结果"]),
                   "2026-10-30已提交验收摘要；不是本次重新执行ETF/ETH回放")
        save_frame("historical_costs", pd.DataFrame(accepted["实验"]["三档成本"]), "同一历史开发回放的三档成本；非新成绩")
        report["sources"]["historical_results"] = {"file": accepted_path.relative_to(ROOT).as_posix(), "sha256": sha(accepted_path)}

        inputs = Path(os.environ.get("RESEARCH_EQUITY_INPUTS", str(ROOT / config["equity.inputs"])))
        # 只认原验收的快照摘要，不能随便放一个同名文件就获得正式就绪状态。
        for table, name in [("us_equity_daily", "etf_daily"), ("crypto_ohlcv", "eth_daily")]:
            source = inputs / (table + ".parquet")
            if not source.exists():
                blocked(name, "本机没有1029原价格快照；历史结果可读，逐日重算等待导入")
                continue
            if sha(source) != accepted["输入快照"][table + "-input"]["sha256"]:
                raise ValueError(table + " 与原验收快照摘要不符，不能替换")
            frame = pd.read_parquet(source)
            if table == "us_equity_daily":
                frame = frame.loc[(frame.provider == "TIINGO") & frame.symbol.isin(["SPY", "QQQ", "IWM", "TLT", "GLD"])]
            else:
                frame = frame.loc[(frame.exchange == "BINANCE") & (frame.instrument == "SPOT") &
                                  (frame.interval_minutes == 1440) & frame.symbol.isin(["BTCUSDT", "ETHUSDT"])]
            save_frame(name, frame, "1029原验收快照的日频子集；没有新增行情请求")
            report["sources"][name] = {"originalSha256": sha(source)}
        encoded = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False).encode()
        write_bounded(root, "manifest.json", encoded, limit)
    return {"bytes": size_bytes(root), "capacityBytes": limit,
            "ready": [k for k, v in report["datasets"].items() if v["status"] == "ready"],
            "blocked": [k for k, v in report["datasets"].items() if v["status"] == "blocked"]}


if __name__ == "__main__":
    print(json.dumps(prepare(), ensure_ascii=False, indent=2))
