"""只读取冻结Parquet的1029日频基线；保存逐日资金、权重、交易与可复算成本。"""

from __future__ import annotations
import argparse, hashlib, json, math, os
from pathlib import Path
from datetime import datetime, timezone
import numpy as np
import pandas as pd
import pyarrow.parquet as pq

try:
    from .plan_1029_audit import sha, save
except ImportError:
    from plan_1029_audit import sha, save


def target_weights(history, rule):
    """只接收执行日前的收盘；过滤后份额留现金，不重新补选。"""
    names = list(history.columns)
    out = pd.Series(0.0, index=names)
    if rule == "cash":
        return out
    if rule == "hold":
        out.iloc[0] = 1
        return out
    if rule in ["equal", "weekly_equal"]:
        out[:] = 1 / len(names)
        return out
    if rule == "trend":
        if len(history) < 200:
            return out
        out.iloc[0] = float(history.iloc[-1, 0] > history.iloc[-200:, 0].mean())
        return out
    window = 90 if rule == "rotation" else 126
    if len(history) < max(window + 1, 200 if rule == "momentum_trend" else 0):
        return out
    scores = history.iloc[-1] / history.iloc[-window - 1] - 1
    chosen = sorted(names, key=lambda x: (-scores[x], x))[
        : 1 if rule == "rotation" else 2
    ]
    for name in chosen:
        if (
            rule != "momentum_trend"
            or history.iloc[-1][name] >= history.iloc[-200:][name].mean()
        ):
            out[name] = 1 / len(chosen)
    return out


def rebalance(cash, units, prices, weights, cost):
    """费用从实际买卖额扣除；解交易后资金，避免买入费用透支现金。"""
    current = units * prices
    before = float(cash + current.sum())
    after = before
    if cost < 0 or cost >= 0.05 or min(weights) < 0 or weights.sum() > 1 + 1e-12:
        raise ValueError("研究成本或目标权重非法")
    for _ in range(100):
        value = before - cost * float(np.abs(weights * after - current).sum())
        if abs(value - after) < 1e-10 * max(1, before):
            after = value
            break
        after = value
    desired = weights * after
    delta = desired - current
    fees = cost * np.abs(delta)
    newcash = float(after - desired.sum())
    if (
        abs(before - after - float(fees.sum())) > 1e-7 * max(1, before)
        or newcash < -1e-7
    ):
        raise ValueError("研究交易不自融资")
    return newcash, desired / prices, delta, fees


def event_time(day, kind, market, hours):
    name = day.strftime("%Y-%m-%d")
    if market == "etf":
        return hours[name]["open" if kind == "open" else "close"]
    value = day if kind == "open" else day + pd.Timedelta(days=1)
    return value.isoformat()


def simulate(
    opens, closes, rule, start, through, cost, market, hours=None, capital=100000
):
    if not opens.index.equals(closes.index) or not opens.columns.equals(closes.columns):
        raise ValueError("开收盘范围不一致")
    if (
        opens.isna().any().any()
        or closes.isna().any().any()
        or (opens <= 0).any().any()
        or (closes <= 0).any().any()
    ):
        raise ValueError("研究输入含缺失或非法价格")
    score = opens.loc[start:through]
    cash = float(capital)
    units = np.zeros(len(opens.columns))
    daily = []
    weights = []
    trades = []
    previous = capital
    for i, day in enumerate(score.index):
        hist = closes.loc[closes.index < day]
        previous_day = (
            opens.index[opens.index.get_loc(day) - 1]
            if opens.index.get_loc(day) > 0
            else None
        )
        monthly = market == "etf" and (
            previous_day is None or previous_day.month != day.month
        )
        scheduled = (
            i == 0
            or rule == "trend"
            or (rule in ["equal", "momentum", "momentum_trend"] and monthly)
            or (rule in ["rotation", "weekly_equal"] and day.weekday() == 0)
        )
        if rule in ["hold", "cash"] and i > 0:
            scheduled = False
        turnover = fees_today = 0.0
        if scheduled:
            target = target_weights(hist, rule).to_numpy()
            price = opens.loc[day].to_numpy()
            cash, units, delta, fees = rebalance(cash, units, price, target, cost)
            turnover = float(np.abs(delta).sum())
            fees_today = float(fees.sum())
            signal = (
                None
                if hist.empty
                else event_time(hist.index[-1], "close", market, hours)
            )
            for j, name in enumerate(opens.columns):
                if abs(delta[j]) > 1e-8:
                    trades.append(
                        dict(
                            day=day.date().isoformat(),
                            symbol=name,
                            executionAt=event_time(day, "open", market, hours),
                            signalLastBarClosedAt=signal,
                            price=float(price[j]),
                            notionalChange=float(delta[j]),
                            unitsChange=float(delta[j] / price[j]),
                            cost=float(fees[j]),
                            event="rebalance",
                        )
                    )
        close = closes.loc[day].to_numpy()
        nav = float(cash + (units * close).sum())
        if i == len(score) - 1:
            cash, units, delta, fees = rebalance(
                cash, units, close, np.zeros(len(units)), cost
            )
            fees_today += float(fees.sum())
            turnover += float(np.abs(delta).sum())
            nav = cash
            for j, name in enumerate(opens.columns):
                if abs(delta[j]) > 1e-8:
                    trades.append(
                        dict(
                            day=day.date().isoformat(),
                            symbol=name,
                            executionAt=event_time(day, "close", market, hours),
                            signalLastBarClosedAt=None,
                            price=float(close[j]),
                            notionalChange=float(delta[j]),
                            unitsChange=float(delta[j] / close[j]),
                            cost=float(fees[j]),
                            event="fixed_terminal_liquidation",
                        )
                    )
        daily.append(
            dict(
                day=day.date().isoformat(),
                nav=nav,
                return_net=nav / previous - 1,
                cost=fees_today,
                turnoverNotional=turnover,
                cash=cash,
                investedFraction=float((units * close).sum() / nav),
            )
        )
        previous = nav
        weights.append(
            dict(
                day=day.date().isoformat(),
                **{
                    name: float(units[j] * close[j] / nav)
                    for j, name in enumerate(opens.columns)
                },
                CASH=cash / nav,
            )
        )
    if not daily:
        raise ValueError("评分窗口为空")
    return pd.DataFrame(daily), pd.DataFrame(weights), pd.DataFrame(trades)


def metrics(daily, trades, capital, annual):
    nav = daily.nav.to_numpy()
    returns = daily.return_net.to_numpy()
    days = len(daily)
    vol = float(np.std(returns, ddof=1) * math.sqrt(annual))
    peak = np.maximum.accumulate(np.r_[capital, nav])
    draw = np.r_[capital, nav] / peak - 1
    return dict(
        totalReturn=float(nav[-1] / capital - 1),
        annualReturn=float((nav[-1] / capital) ** (annual / days) - 1),
        volatility=vol,
        sharpe=float(np.mean(returns) * annual / vol) if vol else 0.0,
        maxDrawdown=float(draw.min()),
        turnover=float(daily.turnoverNotional.sum() / capital),
        tradeCount=len(trades),
        cost=float(daily.cost.sum()),
        days=days,
        investedFraction=float(daily.investedFraction.mean()),
        finalNav=float(nav[-1]),
        riskFreeRate=0,
        annualizationDays=annual,
    )


def price_frames(table, key, assets, open_key, close_key, predicate=None):
    frame = table.to_pandas()
    if predicate is not None:
        frame = frame.loc[predicate(frame)]
    frame = frame.loc[frame.symbol.isin(assets)].copy()
    frame["date"] = pd.to_datetime(frame[key], utc=True)
    if frame.duplicated(["date", "symbol"]).any():
        raise ValueError("研究业务键重复")
    return (
        frame.pivot(index="date", columns="symbol", values=open_key)
        .reindex(columns=assets)
        .sort_index(),
        frame.pivot(index="date", columns="symbol", values=close_key)
        .reindex(columns=assets)
        .sort_index(),
    )


def run(folder, config_path, output):
    config = json.loads(config_path.read_text(encoding="utf8"))
    # 此入口只实现已冻结基线；修改配置不能悄悄继续运行代码内的旧窗口或市场。
    if (
        config["etf"]["momentumSessions"] != 126
        or config["etf"]["trendSessions"] != 200
        or config["etf"]["positions"] != 2
        or config["btc"]["trendDays"] != 200
        or config["commonCrypto"]["momentumDays"] != 90
        or config["commonCrypto"]["trendDays"] != 200
        or config["commonCrypto"]["exchange"] != "BINANCE"
        or config["commonCrypto"]["instrument"] != "SPOT"
        or config["etf"]["assets"] != ["SPY", "QQQ", "IWM", "TLT", "GLD"]
        or config["cashReturn"] != 0
        or config["leverage"] != 1
        or config["fractionalUnits"] is not True
    ):
        raise ValueError("本入口只支持1029固定基线；新规则须另存配置并适配验证")
    audit = json.loads((folder / "audit.json").read_text(encoding="utf8"))
    if not all(
        audit["ready"][k] for k in ["etfBaseline", "btcSpotBaseline", "ethAndRotation"]
    ):
        raise ValueError("所需日线验收未通过")
    actions = json.loads(
        (folder / "company-actions-verified.json").read_text(encoding="utf8")
    )
    if not all(item["passed"] for item in actions["symbols"].values()):
        raise ValueError("公司行动核对未通过")
    required = ["us_equity_daily", "btc_spot_1d", "crypto_ohlcv"]
    inputs = {}
    for table in required:
        meta = json.loads((folder / (table + "-input.json")).read_text(encoding="utf8"))
        path = folder / (table + ".parquet")
        if sha(path) != meta["sha256"]:
            raise ValueError("冻结输入摘要改变")
        inputs[table] = dict(sha256=meta["sha256"], snapshot=meta["snapshot"])
    identity = dict(
        configSha256=sha(config_path),
        auditSha256=sha(folder / "audit.json"),
        corporateActionsSha256=sha(folder / "company-actions-verified.json"),
        calendarSha256=sha(folder / "calendar.json"),
        inputs=inputs,
        codeSha256=sha(Path(__file__)),
    )
    run_id = hashlib.sha256(
        json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    target = output / "runs" / run_id
    if (target / "manifest.json").exists():
        old = json.loads((target / "manifest.json").read_text(encoding="utf8"))
        if not all(sha(target / x) == v for x, v in old["artifacts"].items()):
            raise ValueError("已有结果摘要不一致")
        return dict(runId=run_id, reused=True)
    target.mkdir(parents=True, exist_ok=False)
    calendar = json.loads((folder / "calendar.json").read_text(encoding="utf8"))
    hours = calendar["hours"]
    etf = price_frames(
        pq.read_table(folder / "us_equity_daily.parquet"),
        "trade_date",
        config["etf"]["assets"],
        "adj_open",
        "adj_close",
    )
    btc_table = pq.read_table(folder / "btc_spot_1d.parquet")
    btc = price_frames(btc_table, "open_time", ["BTCUSDT"], "open", "close")
    crypto_table = pq.read_table(folder / "crypto_ohlcv.parquet")
    predicate = (
        lambda f: (f.exchange == "BINANCE")
        & (f.instrument == "SPOT")
        & (f.interval_minutes == 1440)
    )
    crypto = price_frames(
        crypto_table, "ts", ["BTCUSDT", "ETHUSDT"], "open", "close", predicate
    )
    definitions = [
        ("E0", etf, "equal", "etf", config["etf"]),
        ("E1", etf, "momentum", "etf", config["etf"]),
        ("E2", etf, "momentum_trend", "etf", config["etf"]),
        ("SPY_BH", tuple(x[["SPY"]] for x in etf), "hold", "etf", config["etf"]),
        ("ETF_CASH", etf, "cash", "etf", config["etf"]),
        ("B0", btc, "hold", "crypto", config["btc"]),
        ("B1", btc, "trend", "crypto", config["btc"]),
        ("BTC_CASH", btc, "cash", "crypto", config["btc"]),
    ]
    common = dict(config["btc"], **config["commonCrypto"])
    definitions += [
        ("H0_ETH_BH", tuple(x[["ETHUSDT"]] for x in crypto), "hold", "crypto", common),
        ("H0", tuple(x[["ETHUSDT"]] for x in crypto), "trend", "crypto", common),
        ("H1", crypto, "rotation", "crypto", common),
        ("H1_EQUAL", crypto, "weekly_equal", "crypto", common),
        (
            "COMMON_BTC_BH",
            tuple(x[["BTCUSDT"]] for x in crypto),
            "hold",
            "crypto",
            common,
        ),
        (
            "COMMON_BTC_TREND",
            tuple(x[["BTCUSDT"]] for x in crypto),
            "trend",
            "crypto",
            common,
        ),
    ]
    results = []
    artifacts = {}
    for name, (op, cl), rule, market, spec in definitions:
        for multiplier in config["costMultipliers"]:
            cost = (
                multiplier * (spec["feeBpsOneWay"] + spec["slippageBpsOneWay"]) / 10000
            )
            daily, weights, trades = simulate(
                op,
                cl,
                rule,
                spec["from"],
                spec["through"],
                cost,
                market,
                hours,
                config["initialCapital"],
            )
            prefix = f"{name}-cost{multiplier}"
            for suffix, data in [
                ("daily", daily),
                ("weights", weights),
                ("trades", trades),
            ]:
                file = prefix + "-" + suffix + ".csv"
                data.to_csv(target / file, index=False, float_format="%.12g")
                artifacts[file] = sha(target / file)
            result = dict(
                experiment=name,
                rule=rule,
                costMultiplier=multiplier,
                quoteCurrency=spec.get("quoteCurrency", "USDT"),
                fromDate=spec["from"],
                throughDate=spec["through"],
                **metrics(
                    daily,
                    trades,
                    config["initialCapital"],
                    252 if market == "etf" else 365,
                ),
            )
            results.append(result)
            phases = []
            for label, part in [
                ("developmentThrough2023", daily.loc[daily.day < "2024-01-01"]),
                ("descriptiveSince2024", daily.loc[daily.day >= "2024-01-01"]),
            ]:
                if not part.empty:
                    initial = float(part.iloc[0].nav / (1 + part.iloc[0].return_net))
                    phaseTrades = (
                        trades.loc[trades.day.isin(part.day)]
                        if not trades.empty
                        else trades
                    )
                    phases.append(
                        dict(
                            label=label,
                            notAnUnseenHoldout=True,
                            **metrics(
                                part,
                                phaseTrades,
                                initial,
                                252 if market == "etf" else 365,
                            ),
                        )
                    )
            result["phases"] = phases
            result["years"] = []
            for year, part in daily.groupby(daily.day.str[:4]):
                initial = float(part.iloc[0].nav / (1 + part.iloc[0].return_net))
                year_trades = (
                    trades.loc[trades.day.isin(part.day)]
                    if not trades.empty
                    else trades
                )
                result["years"].append(
                    dict(
                        year=int(year),
                        fromDate=part.day.iloc[0],
                        throughDate=part.day.iloc[-1],
                        partialYear=part.day.iloc[0][5:] > "01-07"
                        or part.day.iloc[-1][5:] < "12-24",
                        **metrics(
                            part, year_trades, initial, 252 if market == "etf" else 365
                        ),
                    )
                )
    save(target / "summary.json", results)
    artifacts["summary.json"] = sha(target / "summary.json")
    save(
        target / "manifest.json",
        dict(
            runId=run_id,
            createdAt=datetime.now(timezone.utc).isoformat(),
            identity=identity,
            artifacts=artifacts,
            config=config,
            independentHistoricalBaseline=True,
            unseenHoldoutVerified=False,
            liveTradingVerified=False,
            limitations=[
                "未使用杠杆或资金费；现金零收益，USD与USDT分开报告。",
                "供应商当前调整基准不等于历史PIT；原始与复权字段不混算，不重复加分红。",
                "边界开盘价是研究成交近似，费用与滑点为冻结假设，不是实盘报价。",
            ],
        ),
    )
    output.mkdir(parents=True, exist_ok=True)
    with (output / "run_log.jsonl").open("a", encoding="utf8") as log:
        log.write(
            json.dumps(
                dict(
                    at=datetime.now(timezone.utc).isoformat(),
                    runId=run_id,
                    manifestSha256=sha(target / "manifest.json"),
                ),
                ensure_ascii=False,
            )
            + "\n"
        )
        log.flush()
        os.fsync(log.fileno())
    return dict(
        runId=run_id,
        experiments=len(definitions),
        costVariants=len(results),
        manifestSha256=sha(target / "manifest.json"),
        reused=False,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument(
        "--config", type=Path, default=Path("docs/plan/1029/experiment-config.json")
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.inputs, args.config, args.output)), flush=True)
