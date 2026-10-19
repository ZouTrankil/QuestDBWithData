#!/usr/bin/env python3
"""只读核对跨市场实库范围、UTC 连续性和来源队列；不以行数替代字段验收。"""

import argparse
import datetime as dt
import hashlib
import itertools
import json
import math
import pathlib
import sqlite3
import urllib.parse
import urllib.request

import yaml


class Quest:
    def __init__(self, url):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("需要明确且不含账号的 QuestDB HTTP 地址")
        self.url = url.rstrip("/")
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def query(self, sql):
        url = self.url + "/exec?" + urllib.parse.urlencode({"query": sql, "limit": "0,100001"})
        with self.client.open(url, timeout=60) as response:
            raw = response.read(64 * 1024 * 1024 + 1)
        if len(raw) > 64 * 1024 * 1024:
            raise ValueError("覆盖查询响应超过 64 MiB")
        body = json.loads(raw)
        if "error" in body:
            raise ValueError("QuestDB 查询失败：" + body["error"])
        if len(body["dataset"]) >= 100001:
            raise ValueError("覆盖查询达到行数边界，不能当作完整结果")
        names = [column["name"] for column in body["columns"]]
        return [dict(zip(names, values)) for values in body["dataset"]]

    def exists(self, table):
        return bool(self.query("select table_name from tables() where table_name=" + literal(table)))


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def queue(path, table):
    if not path.is_file():
        return {"exists": False}
    with sqlite3.connect("file:" + path.resolve().as_posix() + "?mode=ro", uri=True) as connection:
        connection.row_factory = sqlite3.Row
        phases = {row[0]: row[1] for row in connection.execute("select phase,count(*) from " + table + " group by phase")}
        errors = [dict(row) for row in connection.execute("select id,symbol,phase from " + table + " where phase='FAILED_SOURCE' limit 30")]
    return {"exists": True, "phases": phases, "sourceGapSamples": errors, "allPlannedTasksVerified": bool(phases) and set(phases) == {"VERIFIED"}}


def ledger(path):
    if not path.is_file():
        return {"exists": False}
    with sqlite3.connect("file:" + path.resolve().as_posix() + "?mode=ro", uri=True) as connection:
        rows = connection.execute("select kind,state,count(*) from sync_entries group by kind,state").fetchall()
        locks = connection.execute("select dataset_id,in_doubt,count(*) from sync_interval_locks group by dataset_id,in_doubt").fetchall()
    return {"exists": True, "entryStates": [{"kind": kind, "state": state, "count": count} for kind, state, count in rows],
            "intervalLocks": [{"dataset": dataset, "inDoubt": bool(uncertain), "count": count} for dataset, uncertain, count in locks],
            "inDoubtEntries": sum(count for _, state, count in rows if state == "IN_DOUBT")}


def instant(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def gap_proofs(root):
    result = {}
    for path in (root / "var/crypto-market-data/gap-preparations").glob("*.json"):
        raw = path.read_bytes()
        result[hashlib.sha256(raw).hexdigest()] = json.loads(raw)
    return result


def checked_artifact(reference):
    raw = pathlib.Path(reference["rawPath"]).read_bytes()
    if hashlib.sha256(raw).hexdigest() != reference["sha256"]:
        raise ValueError("停市核对依赖的冻结文件摘要变化")
    return raw


def validate_gap(row, proofs):
    """不能仅按一条缺口声明减计预期数量；核对原归档、日线校验和、REST 空记录和官方事实。"""
    start = instant(row["ts"])
    if row["reason"] != "OFFICIAL_VENUE_CLOSURE" or row["interval_minutes"] != 60 or instant(row["gap_until"]) != start + dt.timedelta(hours=1) or start < instant(row["closure_from"]) or start + dt.timedelta(hours=1) > instant(row["closure_until"]):
        return False
    proof = proofs[row["source_sha256"]]
    checked_artifact(proof["hourArchive"])
    checked_artifact(proof["nativeDaily"])
    checksum = checked_artifact(proof["nativeDailyChecksum"]).decode().split()[0]
    if checksum != row["native_daily_sha256"] or proof["nativeDaily"]["sha256"] != row["native_daily_sha256"] or proof["hourArchive"]["sha256"] != row["hour_archive_sha256"] or not proof["nativeDailyReconciled"]:
        return False
    events = [event for event in proof["missingHours"] if instant(event["hour"]) == start]
    if len(events) != 1:
        return False
    event = events[0]
    empty = checked_artifact(event["restEmpty"])
    expected_start = int(start.timestamp() * 1000)
    rest_url = urllib.parse.urlsplit(event["restEmpty"]["sourceUrl"])
    query = urllib.parse.parse_qs(rest_url.query)
    if row["exchange"] != "BINANCE" or rest_url.hostname != "data-api.binance.vision" or rest_url.path != "/api/v3/klines" or json.loads(empty) != [] or query.get("symbol") != [row["symbol"]] or query.get("interval") != ["1h"] or query.get("startTime") != [str(expected_start)] or query.get("endTime") != [str(expected_start + 3599999)] or event["restEmpty"]["sha256"] != row["rest_empty_sha256"]:
        return False
    official = pathlib.Path(event["officialEvidencePath"]).read_bytes()
    fact = json.loads(official)
    return (hashlib.sha256(official).hexdigest() == row["official_evidence_sha256"] == event["officialEvidenceSha256"]
            and fact["sourceUrl"] == row["official_url"] and fact["exchange"] == row["exchange"] and fact["instrument"] == row["instrument"]
            and instant(fact["from"]) == instant(row["closure_from"]) and instant(fact["until"]) == instant(row["closure_until"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path("."))
    parser.add_argument("--url", default="http://127.0.0.1:9000")
    parser.add_argument("--through", type=dt.date.fromisoformat, required=True)
    parser.add_argument("--crypto-through", type=dt.date.fromisoformat, required=True)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("var/market-data/coverage-audit.json"))
    args = parser.parse_args()
    root = args.root.resolve()
    us_config = yaml.safe_load((root / "config/apps/us-data.yml").read_text(encoding="utf-8"))["app.market-data"]
    crypto_config = yaml.safe_load((root / "config/apps/btc-data.yml").read_text(encoding="utf-8"))["app.crypto-data"]
    history_from = dt.date.fromisoformat(str(us_config["history-start"]))
    crypto_from = dt.date.fromisoformat(str(crypto_config["history-start"]))
    qdb = Quest(args.url)
    report = {"observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "endpoint": qdb.url,
              "equityThrough": args.through.isoformat(), "cryptoThrough": args.crypto_through.isoformat(),
              "auditScope": "实库范围、UTC 网格、目录映射、队列和账本。范围聚合本身不证明全部价格字段；字段验收证据保存在 Java 原始响应、prepared 和回执中。"}
    report["queues"] = {"core": queue(root / "var/market-data/collection-queue.sqlite3", "us_collection_tasks"),
                         "broad": queue(root / "var/market-data/broad-queue.sqlite3", "broad_tasks"),
                         "crypto": queue(root / "var/crypto-market-data/queue.sqlite3", "crypto_tasks")}
    report["ledgers"] = {"us": ledger(root / "var/market-data/sync-ledger.sqlite3"), "crypto": ledger(root / "var/crypto-market-data/sync-ledger.sqlite3")}
    report["wal"] = qdb.query("select name,writerTxn,sequencerTxn,bufferedTxnSize,suspended from wal_tables() where name in ('us_equity_daily','us_equity_intraday','us_security_universe','us_security_history_catalog','us_etf_equity_holdings','us_nasdaq_itch_messages','us_market_daily','crypto_ohlcv','crypto_data_gap_events')")
    for row in report["wal"]:
        row["settled"] = not row["suspended"] and row["writerTxn"] == row["sequencerTxn"] and row["bufferedTxnSize"] == 0
    prices = qdb.query("select symbol,provider,count() rows,min(trade_date) first_date,max(trade_date) last_date from us_equity_daily where trade_date >= " + literal(history_from) + " and trade_date <= " + literal(args.through) + " group by symbol,provider order by symbol,provider") if qdb.exists("us_equity_daily") else []
    present = {row["symbol"] for row in prices}
    prices_by_symbol = {}
    for row in prices:
        prices_by_symbol.setdefault(row["symbol"], []).append(row)
    report["equityDailyRanges"] = prices
    core = set(us_config["benchmark-symbols"] + us_config["core-stock-symbols"])
    report["coreSymbols"] = {"expected": sorted(core), "withoutAnyDailyPrices": sorted(core - present)}
    if qdb.exists("us_market_daily"):
        report["macroIndexRanges"] = qdb.query("select ts_code,count() rows,count_distinct(trade_date) distinct_dates,min(trade_date) first_date,max(trade_date) last_date from us_market_daily group by ts_code order by ts_code")
    report["securityScope"] = []
    for table in ("us_security_universe", "us_security_history_catalog"):
        if not qdb.exists(table):
            report["securityScope"].append({"table": table, "exists": False})
            continue
        if table == "us_security_universe":
            # 两份官方文件取得时间不同，分别选每份目录的最后快照。
            scope = []
            for snapshot in qdb.query("select directory,max(observed_at) observed_at from us_security_universe group by directory"):
                scope.extend(qdb.query("select symbol,scope_reason,daily_candidate from us_security_universe where directory=" + literal(snapshot["directory"]) + " and observed_at=" + literal(snapshot["observed_at"]) + " limit 100001"))
        else:
            latest = qdb.query("select max(observed_at) observed_at from " + table)[0]["observed_at"]
            scope = qdb.query("select symbol,scope_reason,daily_candidate,source_start_date,source_end_date from " + table + " where observed_at=" + literal(latest) + " limit 100001")
        expected = {row["symbol"] for row in scope if row["daily_candidate"]}
        reasons = {}
        for row in scope:
            reasons[row["scope_reason"]] = reasons.get(row["scope_reason"], 0) + 1
        missing = sorted(expected - present)
        item = {"table": table, "exists": True, "snapshotRows": len(scope), "scopeReasons": reasons,
                                         "priceCandidateSymbols": len(expected), "candidatesWithoutAnyPrices": len(missing), "missingPriceSymbols": missing,
                                         "scopeLimit": "有价格只证明存在；来源目录不是完整历史指数成分，也不证明来源授权或整个窗口已取得。"}
        if table == "us_security_history_catalog":
            shortfalls = []
            for row in scope:
                if not row["daily_candidate"] or row["symbol"] not in present:
                    continue
                collected = prices_by_symbol[row["symbol"]]
                first = min(dt.date.fromisoformat(p["first_date"][:10]) for p in collected)
                last = max(dt.date.fromisoformat(p["last_date"][:10]) for p in collected)
                wanted_first = max(history_from, dt.date.fromisoformat(row["source_start_date"][:10]))
                wanted_last = min(args.through, dt.date.fromisoformat(row["source_end_date"][:10]))
                if first > wanted_first + dt.timedelta(days=10) or last < wanted_last - dt.timedelta(days=10):
                    shortfalls.append({"symbol": row["symbol"], "sourceFrom": str(wanted_first), "sourceThrough": str(wanted_last), "collectedFrom": str(first), "collectedThrough": str(last)})
            item["providerWindowRangeShortfalls"] = shortfalls
            item["rangeShortfallMeaning"] = "仅诊断供应商窗口与实库首尾相差超过十天的情况；不能用首尾吻合证明中间交易日齐备。"
        report["securityScope"].append(item)
    if qdb.exists("us_etf_equity_holdings"):
        holdings = []
        for snapshot in qdb.query("select fund,max(asof_date) asof_date from us_etf_equity_holdings group by fund"):
            holdings.extend(qdb.query("select fund,symbol,asof_date,membership_basis from us_etf_equity_holdings where fund=" + literal(snapshot["fund"]) + " and asof_date=" + literal(snapshot["asof_date"]) + " limit 100001"))
        report["indexProxyCoverage"] = [{"fund": fund, "basis": "ETF_HOLDINGS_PROXY", "stockRows": len(group), "withoutAnyPrices": sorted({r["symbol"] for r in group} - present)} for fund, it in itertools.groupby(sorted(holdings, key=lambda x: x["fund"]), key=lambda x: x["fund"]) for group in [list(it)]]
    if qdb.exists("us_equity_intraday"):
        report["intradayRanges"] = qdb.query("select symbol,provider,interval_minutes,count() rows,count_distinct(trade_date) trade_days,min(ts) first_ts,max(ts) last_ts from us_equity_intraday group by symbol,provider,interval_minutes order by symbol,provider,interval_minutes")
    if qdb.exists("us_nasdaq_itch_messages"):
        report["itchPartial"] = qdb.query("select source_sha256,source_complete,count() rows,count_distinct(message_index) distinct_indexes,min(message_index) first_index,max(message_index) last_index,min(ts) first_ts,max(ts) last_ts from us_nasdaq_itch_messages group by source_sha256,source_complete")
    if qdb.exists("crypto_ohlcv"):
        end = args.crypto_through + dt.timedelta(days=1)
        rows = qdb.query("select exchange,symbol,instrument,interval_minutes,count() rows,count_distinct(ts) distinct_ts,min(ts) first_ts,max(ts) last_ts,sum(case when (cast(ts as long) % (cast(interval_minutes as long)*60000000))=0 then 0 else 1 end) off_grid from crypto_ohlcv where ts >= " + literal(crypto_from) + " and ts < " + literal(end) + " group by exchange,symbol,instrument,interval_minutes")
        by_key = {(r["exchange"], r["symbol"], r["instrument"], r["interval_minutes"]): r for r in rows}
        allowances = {}
        closure_events = []
        if qdb.exists("crypto_data_gap_events"):
            proofs = gap_proofs(root)
            for event in qdb.query("select * from crypto_data_gap_events where ts >= " + literal(crypto_from) + " and ts < " + literal(end) + " limit 100001"):
                try:
                    valid = validate_gap(event, proofs)
                except (ValueError, KeyError, OSError):
                    valid = False
                count = qdb.query("select count() rows from crypto_ohlcv where exchange=" + literal(event["exchange"]) + " and symbol=" + literal(event["symbol"]) + " and instrument=" + literal(event["instrument"]) + " and interval_minutes=60 and ts=" + literal(event["ts"]))[0]["rows"]
                event["evidenceVerified"] = valid
                event["currentlyMissingBars"] = count == 0
                closure_events.append(event)
                if valid and count == 0:
                    event_key = (event["exchange"], event["symbol"], event["instrument"], 60)
                    allowances.setdefault(event_key, set()).add(instant(event["ts"]))
        report["cryptoVenueClosureEvents"] = closure_events
        grids = []
        start_time = dt.datetime.combine(crypto_from, dt.time(), dt.timezone.utc)
        end_time = dt.datetime.combine(end, dt.time(), dt.timezone.utc)
        for key in itertools.product(("BINANCE", "BYBIT"), ("BTCUSDT", "ETHUSDT"), ("SPOT", "USDT_PERPETUAL"), (60, 1440)):
            row = by_key.get(key, {})
            expected = int((end_time - start_time).total_seconds()) // (key[3] * 60)
            passed = bool(row) and row["rows"] == row["distinct_ts"] == expected and row["off_grid"] == 0 and instant(row["first_ts"]) == start_time and instant(row["last_ts"]) == end_time - dt.timedelta(minutes=key[3])
            allowed = allowances.get(key, set())
            active_expected = expected - len(allowed)
            first_active, last_active = start_time, end_time - dt.timedelta(minutes=key[3])
            while first_active in allowed:
                first_active += dt.timedelta(minutes=key[3])
            while last_active in allowed:
                last_active -= dt.timedelta(minutes=key[3])
            active_passed = bool(row) and row["rows"] == row["distinct_ts"] == active_expected and row["off_grid"] == 0 and instant(row["first_ts"]) == first_active and instant(row["last_ts"]) == last_active
            grids.append({"exchange": key[0], "symbol": key[1], "instrument": key[2], "intervalMinutes": key[3], "expectedRows": expected, "actual": row, "fullUtcGrid": passed, "verifiedClosureHours": sorted(t.isoformat() for t in allowed), "expectedRowsOutsideVerifiedClosures": active_expected, "activeVenueGridComplete": active_passed})
        report["cryptoGrids"] = grids
        report["allCryptoUtcGridsComplete"] = all(r["fullUtcGrid"] for r in grids)
        report["allCryptoActiveVenueGridsComplete"] = all(r["activeVenueGridComplete"] for r in grids)
        aggregates = qdb.query("select exchange,symbol,instrument,date_trunc('day',ts) day_ts,count() hourly_rows,first(open) open,max(high) high,min(low) low,last(close) close,sum(volume) volume,sum(quote_volume) quote_volume from crypto_ohlcv where interval_minutes=60 and ts >= " + literal(crypto_from) + " and ts < " + literal(end) + " group by exchange,symbol,instrument,day_ts")
        daily = qdb.query("select exchange,symbol,instrument,ts day_ts,open,high,low,close,volume,quote_volume from crypto_ohlcv where interval_minutes=1440 and ts >= " + literal(crypto_from) + " and ts < " + literal(end) + " limit 100001")
        key = lambda row: (row["exchange"], row["symbol"], row["instrument"], row["day_ts"][:10])
        actual_days = {key(row): row for row in daily}
        bad = []
        for row in aggregates:
            actual = actual_days.pop(key(row), None)
            if actual is None or any(not math.isclose(row[field], actual[field], rel_tol=1e-12, abs_tol=1e-8) for field in ("open", "high", "low", "close", "volume", "quote_volume")):
                bad.append({"key": key(row), "hourAggregate": row, "daily": actual})
        report["cryptoDailyValueReconciliation"] = {"collectedDaysChecked": len(aggregates), "mismatchedDays": len(bad), "dailyWithoutAnyHours": len(actual_days), "mismatchSamples": bad[:30], "allCollectedDailyValuesMatchHours": not bad and not actual_days and bool(aggregates), "tolerance": {"relative": 1e-12, "absolute": 1e-8}}
    output = args.out if args.out.is_absolute() else root / args.out
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(output), "queues": report["queues"], "allCryptoUtcGridsComplete": report.get("allCryptoUtcGridsComplete", False)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
