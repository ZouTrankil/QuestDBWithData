#!/usr/bin/env python3
"""只读核对跨市场实库范围、UTC 连续性和来源队列；不以行数替代字段验收。"""

import argparse
import bisect
from contextlib import closing
import datetime as dt
import hashlib
import html
import itertools
import json
import math
import pathlib
import re
import sqlite3
import urllib.parse
import urllib.request
import zipfile
from zoneinfo import ZoneInfo

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
        review = [dict(row) for row in connection.execute("select id,symbol,phase from " + table + " where phase='SCOPE_REVIEW_REQUIRED' limit 30")]
    return {"exists": True, "phases": phases, "sourceGapSamples": errors, "scopeReviewSamples": review, "allPlannedTasksVerified": bool(phases) and set(phases) == {"VERIFIED"}}


def ledger(path):
    if not path.is_file():
        return {"exists": False}
    with sqlite3.connect("file:" + path.resolve().as_posix() + "?mode=ro", uri=True) as connection:
        rows = connection.execute("select kind,state,count(*) from sync_entries group by kind,state").fetchall()
        locks = connection.execute("select dataset_id,in_doubt,count(*) from sync_interval_locks group by dataset_id,in_doubt").fetchall()
    return {"exists": True, "entryStates": [{"kind": kind, "state": state, "count": count} for kind, state, count in rows],
            "intervalLocks": [{"dataset": dataset, "inDoubt": bool(uncertain), "count": count} for dataset, uncertain, count in locks],
            "inDoubtEntries": sum(count for _, state, count in rows if state == "IN_DOUBT")}


def publication_groups(path):
    if not path.is_file():
        return {"exists": False}
    with sqlite3.connect("file:" + path.resolve().as_posix() + "?mode=ro", uri=True) as connection:
        if not connection.execute("select 1 from sqlite_master where name='broad_bundles'").fetchone():
            return {"exists": False}
        phases = dict(connection.execute("select phase,count(*) from broad_bundles group by phase"))
        failures = []
        for group_id, phase, proof, count, wrong in connection.execute("select b.id,b.phase,b.proof,count(t.id),sum(case when t.phase=b.phase then 0 else 1 end) from broad_bundles b left join broad_tasks t on t.bundle_id=b.id group by b.id,b.phase,b.proof"):
            expected = json.loads(proof)["members"] if proof is not None else None
            if not count or wrong or expected is not None and expected != count:
                failures.append({"bundle": group_id, "phase": phase, "memberCount": count, "expectedMembers": expected, "membersInWrongPhase": wrong})
    return {"exists": True, "phases": phases, "memberStateConsistencyFailures": failures,
            "allGroupsVerified": bool(phases) and set(phases) == {"VERIFIED"} and not failures}


def verified_core_day_members(folder, run_id, request):
    """单日原运行可能冻结整个 ZIP；只有原文件、原 prepared 与叶子价格都一致才追溯叶子摘要。"""
    if not request.get("from") or request.get("from") != request.get("to"):
        return set()
    path = folder / "prepared" / (run_id + ".json")
    if not path.is_file():
        return set()
    prepared_bytes = path.read_bytes()
    prepared = json.loads(prepared_bytes)
    evidence = prepared.get("evidence", {})
    child = evidence.get("child", {})
    if child.get("id") != "DAILY" or child.get("dataset") != "us_equity_daily":
        return set()
    params = request["parameters"]
    source_sha = params["source_sha256"]
    if (hashlib.sha256(prepared_bytes).hexdigest() != params.get("prepared_sha256")
            or prepared.get("definition", {}).get("datasetId") != "us_equity_daily"
            or prepared.get("sourceSha256") != source_sha or evidence.get("packageSha256") != source_sha):
        raise ValueError("核心单日原 prepared 或 ZIP 声明摘要不符")
    raw = pathlib.Path(prepared["raw"])
    if not raw.is_absolute():
        raw = folder.parent.parent / raw
    if hashlib.sha256(raw.read_bytes()).hexdigest() != source_sha:
        raise ValueError("核心单日 ZIP 摘要不符")
    day = str(request["from"])[:10]
    symbols = child.get("symbols", [])
    if (not symbols or len(symbols) != len(set(symbols)) or child.get("rows") != len(symbols)
            or evidence.get("day") != day):
        raise ValueError("核心单日子运行证券范围不符")
    expected = []
    daily_sources = set()
    normalize = lambda row: {k: instant(v).isoformat() if k in ("trade_date", "observed_at") else v for k, v in row.items()}
    with zipfile.ZipFile(raw) as archive:
        names = archive.namelist()
        manifest = json.loads(archive.read("manifest.json"))
        members = manifest.get("members", [])
        if (manifest.get("version") != 1 or manifest.get("kind") != "TIINGO_CORE_DAY"
                or manifest.get("from") != day or manifest.get("through") != day
                or len(names) != len(set(names)) or len(members) != 2 * len(symbols)
                or {(m.get("symbol"), m.get("kind")) for m in members} != {(s, k) for s in symbols for k in ("DAILY", "MINUTE")}
                or len({m.get("entry") for m in members}) != len(members)
                or set(names) != {"manifest.json"} | {m.get("entry") for m in members}):
            raise ValueError("核心单日 ZIP 成员范围或布局不符")
        for member in members:
            body = archive.read(member["entry"])
            if hashlib.sha256(body).hexdigest() != member["sha256"]:
                raise ValueError("核心单日叶子原文摘要不符")
            if member["kind"] != "DAILY":
                continue
            points = json.loads(body)
            if (not isinstance(points, list) or len(points) != 1 or not isinstance(points[0], dict)
                    or not isinstance(points[0].get("date"), str)
                    or instant(points[0]["date"]) != dt.datetime.combine(dt.date.fromisoformat(day), dt.time(), dt.timezone.utc)):
                raise ValueError("核心单日日线原文日期或数量不符")
            point = points[0]
            row = {"trade_date": day + "T00:00:00Z", "symbol": member["symbol"], "provider": "TIINGO", "venue": "US_COMPOSITE"}
            for field in ("open", "high", "low", "close", "volume"):
                row[field] = float(point[field])
                row["adj_" + field] = float(point["adj" + field.title()])
            row.update(dividend=float(point["divCash"]), split_factor=float(point["splitFactor"]), currency="USD",
                       raw_price_basis="VENDOR_UNADJUSTED", source_sha256=member["sha256"], observed_at=member["observedAt"])
            expected.append(normalize(row))
            daily_sources.add(member["sha256"])
    key = lambda row: (row["trade_date"], row["symbol"], row["provider"])
    if sorted(map(normalize, prepared.get("rows", [])), key=key) != sorted(expected, key=key):
        raise ValueError("核心单日叶子原文与原 prepared 全部二十列不一致")
    return daily_sources


def verified_daily_sources(root):
    """来源组的行摘要与运行 ZIP 摘要不同，必须沿成功的原组回执核对成员。"""
    folder = root / "var/market-data"
    with closing(sqlite3.connect("file:" + (folder / "sync-ledger.sqlite3").resolve().as_posix() + "?mode=ro", uri=True)) as connection:
        verified = set()
        leaves = set()
        for run_id, frozen in connection.execute("select r.id,r.frozen_json from sync_runs r join sync_entries e on r.id=e.id where e.kind='RUN' and e.state='VERIFIED'"):
            request = json.loads(frozen)
            if request["definition"]["datasetId"] == "us_equity_daily":
                verified.add(request["parameters"]["source_sha256"])
                leaves.update(verified_core_day_members(folder, run_id, request))
    native = verified | leaves
    broad = folder / "broad-queue.sqlite3"
    if not broad.is_file():
        return native
    with closing(sqlite3.connect("file:" + broad.resolve().as_posix() + "?mode=ro", uri=True)) as connection:
        if connection.execute("select 1 from sqlite_master where name='broad_bundles'").fetchone():
            for source_sha, bundle_sha in connection.execute("select t.sha,b.sha from broad_tasks t join broad_bundles b on t.bundle_id=b.id where t.kind='DAILY' and t.phase='VERIFIED' and b.phase='VERIFIED'"):
                if bundle_sha in verified:
                    native.add(source_sha)
    return native


def equity_daily_dates(qdb, root, start, through):
    """以已验收 SPY 实有日期作诊断参照；不冒充官方二十年日历或上市退市证明。"""
    identity_sql = "select id,directoryName from tables() where table_name='us_equity_daily'"
    identity = qdb.query(identity_sql)
    bounds = "trade_date>=" + literal(start) + " and trade_date<=" + literal(through)
    source_sql = "select symbol,provider,source_sha256,count() rows,min(trade_date) first_date,max(trade_date) last_date from us_equity_daily where " + bounds + " group by symbol,provider,source_sha256 order by symbol,provider,source_sha256"
    inventory_sql = "select symbol,provider,count() rows,count_distinct(trade_date) distinct_dates,min(trade_date) first_date,max(trade_date) last_date from us_equity_daily where " + bounds + " group by symbol,provider order by symbol,provider"
    reference_sql = "select trade_date ref_date,source_sha256 from us_equity_daily where symbol='SPY' and provider='TIINGO' and " + bounds + " order by trade_date"
    sources = qdb.query(source_sql)
    inventory = qdb.query(inventory_sql)
    successful = verified_daily_sources(root)
    known = {}
    key = lambda row: (row["symbol"], row["provider"])
    for row in sources:
        known.setdefault(key(row), []).append(row)
    eligible = {key(row): row for row in inventory if all(r["source_sha256"] in successful for r in known.get(key(row), [])) and known.get(key(row))}
    excluded = [{"symbol": row["symbol"], "provider": row["provider"], "reason": "当前行的原始来源尚无对应成功运行；采集中或未决来源不参与完整性结论。"} for row in inventory if key(row) not in eligible]
    reference = qdb.query(reference_sql)
    dates = [dt.date.fromisoformat(row["ref_date"][:10]) for row in reference]
    if not dates or len(dates) != len(set(dates)) or any(row["source_sha256"] not in successful for row in reference):
        raise ValueError("SPY 参照日期为空、重复或原来源未验收")
    extra = {}
    for year in range(start.year, through.year + 1):
        lower, upper = max(start, dt.date(year, 1, 1)), min(through + dt.timedelta(days=1), dt.date(year + 1, 1, 1))
        year_bounds = "trade_date>=" + literal(lower) + " and trade_date<" + literal(upper)
        # 逐年先证明所有实有日期都属于参照集合，再用唯一键数量证明集合相等。
        # 仅对数量或成员不一致的证券读日期明细，避免全证券与全日期的笛卡尔积。
        year_dates = dates[bisect.bisect_left(dates, lower):bisect.bisect_left(dates, upper)]
        membership = "trade_date NOT IN (" + ",".join(literal(day) for day in year_dates) + ")" if year_dates else "true"
        outside = qdb.query("SELECT symbol,provider,trade_date ref_date FROM us_equity_daily WHERE " + year_bounds + " AND " + membership + " LIMIT 100001")
        for row in outside:
            if key(row) in eligible:
                extra.setdefault(key(row), set()).add(row["ref_date"][:10])
    series = []
    for k, row in sorted(eligible.items()):
        first, last = (dt.date.fromisoformat(row[name][:10]) for name in ("first_date", "last_date"))
        expected = bisect.bisect_right(dates, last) - bisect.bisect_left(dates, first)
        absent, unexpected = [], sorted(extra.get(k, set()))
        if row["rows"] != expected or row["distinct_dates"] != expected or unexpected:
            actual_rows = qdb.query("SELECT trade_date FROM us_equity_daily WHERE symbol=" + literal(k[0]) + " AND provider=" + literal(k[1]) + " AND " + bounds + " ORDER BY trade_date LIMIT 100001")
            actual_dates = {instant(point["trade_date"]) for point in actual_rows}
            expected_dates = {dt.datetime.combine(day, dt.time(), dt.timezone.utc) for day in dates[bisect.bisect_left(dates, first):bisect.bisect_right(dates, last)]}
            absent = sorted(point.date().isoformat() for point in expected_dates - actual_dates)
            unexpected = sorted({point.date().isoformat() for point in actual_dates - expected_dates})
        passed = row["rows"] == row["distinct_dates"] == expected and not absent and not unexpected
        series.append({**row, "expectedReferenceDatesWithinCollectedRange": expected, "missingReferenceDates": absent,
                       "datesOutsideReference": unexpected, "collectedDateSetMatchesReference": passed})
    # 明细核对也必须落在同一稳定性检查内；采集中新增的其他未验收证券不影响原集合。
    after_sources = qdb.query(source_sql)
    after_inventory = {key(row): row for row in qdb.query(inventory_sql)}
    stable = (identity == qdb.query(identity_sql) and reference == qdb.query(reference_sql)
              and all(after_inventory.get(k) == row for k, row in eligible.items())
              and [r for r in sources if key(r) in eligible] == [r for r in after_sources if key(r) in eligible])
    if not stable:
        raise ValueError("日线日期核对期间，已验收来源范围、SPY 参照或原物理表发生变化")
    return {"reference": {"symbol": "SPY", "provider": "TIINGO", "rows": len(dates), "firstDate": str(dates[0]), "lastDate": str(dates[-1]),
                           "sourceSha256": sorted({row["source_sha256"] for row in reference})},
            "stableSourceInventory": stable, "checkedSeries": len(series), "excludedUnverifiedSources": excluded,
            "missingDateCount": sum(len(row["missingReferenceDates"]) for row in series),
            "datesOutsideReferenceCount": sum(len(row["datesOutsideReference"]) for row in series), "series": series,
            "allCheckedCollectedDateSetsMatchReference": bool(series) and all(row["collectedDateSetMatchesReference"] for row in series),
            "scope": "只诊断已成功来源在实有首尾之间的中间缺日、重复日和参照外日期。SPY 实有日期不是官方完整历史日历；停牌、上市退市边界及尚未取得的证券另行核对，不能据此判全目标完成。"}


def us_session_calendar(root, year):
    """从保留的官方页面读取当年休市及半日市；不以工作日单独充当交易日历。"""
    sources = []
    bodies = []
    for name, url in ((f"nasdaq-calendar-{year}", "https://nasdaqtrader.com/Trader.aspx?id=Calendar"),
                      ("nyse-hours", "https://www.nyse.com/trade/hours-calendars")):
        raw = root / "var/market-data/probes" / (name + ".response")
        card = json.loads(raw.with_suffix(".json").read_text(encoding="utf-8"))
        body = raw.read_bytes()
        if len(body) > 2 * 1024 * 1024 or hashlib.sha256(body).hexdigest() != card["sha256"] or card["sourceUrl"] != url:
            raise ValueError("官方日历或交易时间页面摘要、来源不符")
        sources.append({"sourceUrl": url, "sha256": card["sha256"], "observedAt": card["observedAt"], "rawPath": str(raw)})
        bodies.append(body.decode("utf-8"))
    clean = lambda s: re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]*>", " ", s))).strip()
    if not re.search(r"Core Trading Session:\s*9:30 a\.m\. to 4:00 p\.m\. ET", clean(bodies[1])):
        raise ValueError("官方正常交易时段未得到核对")
    heading = f"U.S. Equity and Options Markets Holiday Schedule {year}"
    if heading not in bodies[0]:
        raise ValueError("官方日历年份不符")
    table = re.search(r"<table\b[^>]*>(.*?)</table>", bodies[0].split(heading, 1)[1], re.S | re.I)
    if table is None:
        raise ValueError("官方日历表格缺失")
    months = {m: i for i, m in enumerate("January February March April May June July August September October November December".split(), 1)}
    closed, early = {}, {}
    for row in re.findall(r"<tr\b[^>]*>(.*?)</tr>", table.group(1), re.S | re.I):
        cells = [clean(c) for c in re.findall(r"<td\b[^>]*>(.*?)</td>", row, re.S | re.I)]
        if not cells:
            continue
        if len(cells) != 3:
            raise ValueError("官方日历字段数变化")
        match = re.fullmatch(r"([A-Za-z]+) ([0-9]{1,2}), ([0-9]{4})", cells[0])
        if not match or int(match[3]) != year:
            raise ValueError("官方日历日期非法或跨年")
        day = dt.date(year, months[match[1]], int(match[2])).isoformat()
        if day in closed or day in early:
            raise ValueError("官方日历日期重复")
        if cells[2] == "Closed":
            closed[day] = cells[1]
        elif cells[2] == "1:00 p.m.":
            early[day] = "13:00"
        else:
            raise ValueError("官方日历出现未核对的状态")
    if year != 2026 or len(closed) != 10 or len(early) != 2:
        raise ValueError("当年日历需核对完整年度范围，不能沿用旧年规则")
    return {"verified": True, "year": year, "zone": "America/New_York", "regularOpen": "09:30", "regularClose": "16:00",
            "closedDates": closed, "earlyCloseDates": early, "sources": sources}


def intraday_sessions(qdb, config, calendar, through, table="us_equity_intraday"):
    if table not in {"us_equity_intraday", "us_equity_intraday_regular"}:
        raise ValueError("未知分钟产物")
    start = dt.date.fromisoformat(str(config["minute-start"]))
    if start.year != through.year or start.year != calendar["year"]:
        raise ValueError("分钟覆盖窗口须由同一年度的已核对日历覆盖")
    symbols = set(config["benchmark-symbols"] + config["core-stock-symbols"])
    source = qdb.query("select symbol,interval_minutes,trade_date,count() rows,count_distinct(ts) distinct_ts,min(ts) first_ts,max(ts) last_ts,sum(case when volume is null then 1 else 0 end) null_volume,sum(case when volume is null or volume<>0 or open<>high or high<>low or low<>close then 1 else 0 end) non_flat_zero,sum(case when (cast(ts as long) % (cast(interval_minutes as long)*60000000))=0 then 0 else 1 end) off_grid from " + table + " where provider='TIINGO_IEX' and trade_date >= " + literal(start) + " and trade_date <= " + literal(through) + " and symbol in (" + ",".join(literal(s) for s in sorted(symbols)) + ") group by symbol,interval_minutes,trade_date")
    actual = {(r["symbol"], r["interval_minutes"], r["trade_date"][:10]): r for r in source}
    dates = [start + dt.timedelta(days=i) for i in range((through - start).days + 1)]
    opened = [d for d in dates if d.weekday() < 5 and d.isoformat() not in calendar["closedDates"]]
    opened_set = {d.isoformat() for d in opened}
    unexpected = [r for r in source if r["trade_date"][:10] not in opened_set or r["interval_minutes"] not in (1, 5)]
    result = []
    zone = ZoneInfo(calendar["zone"])
    for symbol, minutes in itertools.product(sorted(symbols), (1, 5)):
        missing, sparse = [], []
        for day in opened:
            opening = dt.datetime.combine(day, dt.time.fromisoformat(calendar["regularOpen"]), zone).astimezone(dt.timezone.utc)
            closing = dt.datetime.combine(day, dt.time.fromisoformat(calendar["earlyCloseDates"].get(day.isoformat(), calendar["regularClose"])), zone).astimezone(dt.timezone.utc)
            expected = int((closing - opening).total_seconds()) // (60 * minutes)
            row = actual.get((symbol, minutes, day.isoformat()))
            if row is None:
                missing.append(day.isoformat())
            elif not (row["rows"] == row["distinct_ts"] == expected and row["off_grid"] == 0 and instant(row["first_ts"]) == opening and instant(row["last_ts"]) == closing - dt.timedelta(minutes=minutes)):
                sparse.append({"date": day.isoformat(), "expectedRegularSessionBars": expected, "actual": row})
        result.append({"symbol": symbol, "intervalMinutes": minutes, "expectedTradingDays": len(opened), "missingTradingDays": missing,
                       "nonFullRegularSessionDays": sparse, "allCalendarSessionsPresent": not missing,
                       "fullRegularSessionGrid": not missing and not sparse, "scope": "只核对实际来源场所；稀疏成交不能补成价格或零量 K 线。"})
    return {"table": table, "from": str(start), "through": str(through), "expectedTradingDays": len(opened), "series": result, "unexpectedSessionRows": unexpected,
            "allCoreCalendarSessionsPresent": all(r["allCalendarSessionsPresent"] for r in result) and not unexpected,
            "allCoreRegularSessionGridsComplete": all(r["fullRegularSessionGrid"] for r in result) and not unexpected,
            "collectedBarsWithUnknownVolume": sum(r["null_volume"] for r in source),
            "zeroVolumeFlatBarsOnOfficialClosedDates": sum(r["rows"] for r in unexpected if r["non_flat_zero"] == 0),
            "nonFlatOrNonZeroBarsOnOfficialClosedDates": sum(r["non_flat_zero"] for r in unexpected)}


def five_minute_values(qdb, config, through):
    """用数据库独立聚合实际 1 分钟，核对正常时段视图中的 5 分钟 OHLCV；按月限制返回量。"""
    start = dt.date.fromisoformat(str(config["minute-start"]))
    month = start.replace(day=1)
    checked, partial, extra, bad = 0, 0, 0, []
    symbols = sorted(set(config["benchmark-symbols"] + config["core-stock-symbols"]))
    while month <= through:
        next_month = (month.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        lower, upper = max(start, month), min(through + dt.timedelta(days=1), next_month)
        scope = " from us_equity_intraday_regular where provider='TIINGO_IEX' and trade_date >= " + literal(lower) + " and trade_date < " + literal(upper) + " and symbol in (" + ",".join(literal(s) for s in symbols) + ")"
        aggregate = qdb.query("select symbol,provider,timestamp_floor('5m',ts) bucket_ts,count() minute_rows,first(open) open,max(high) high,min(low) low,last(close) close,sum(volume) volume,sum(case when volume is null then 1 else 0 end) null_volume" + scope + " and interval_minutes=1 group by symbol,provider,bucket_ts")
        actual = qdb.query("select symbol,provider,ts bucket_ts,open,high,low,close,volume" + scope + " and interval_minutes=5 limit 100001")
        key = lambda r: (r["symbol"], r["provider"], r["bucket_ts"])
        by_key = {key(r): r for r in actual}
        if len(by_key) != len(actual):
            raise ValueError("五分钟视图中发现重复完整业务键")
        for row in aggregate:
            found = by_key.pop(key(row), None)
            expected_volume = None if row["null_volume"] else row["volume"]
            matched_volume = found is not None and ((expected_volume is None and found["volume"] is None) or expected_volume is not None and found["volume"] is not None and math.isclose(expected_volume, found["volume"], rel_tol=1e-12, abs_tol=1e-8))
            if found is None or not matched_volume or any(row[f] != found[f] for f in ("open", "high", "low", "close")):
                bad.append({"key": key(row), "minuteAggregate": row, "fiveMinute": found})
            partial += row["minute_rows"] != 5
        checked += len(aggregate)
        extra += len(by_key)
        month = next_month
    return {"collectedBucketsChecked": checked, "mismatchedBuckets": len(bad), "fiveMinuteWithoutConstituents": extra,
            "bucketsWithFewerThanFiveSourceMinutes": partial, "mismatchSamples": bad[:30],
            "allCollectedFiveMinuteValuesMatchOneMinute": bool(checked) and not bad and not extra,
            "scope": "OHLC 精确比较；成交量允许 1e-12 相对及 1e-8 绝对浮点累计误差。缺交易日仍单独报告，采集中未完成的月份可能暂时只有一种频率。"}


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
    report["broadPublicationGroups"] = publication_groups(root / "var/market-data/broad-queue.sqlite3")
    report["wal"] = qdb.query("select name,writerTxn,sequencerTxn,bufferedTxnSize,suspended from wal_tables() where name in ('us_equity_daily','us_equity_intraday','us_security_universe','us_security_history_catalog','us_etf_equity_holdings','us_nasdaq_itch_messages','us_market_daily','crypto_ohlcv','crypto_data_gap_events')")
    for row in report["wal"]:
        row["settled"] = not row["suspended"] and row["writerTxn"] == row["sequencerTxn"] and row["bufferedTxnSize"] == 0
    prices = qdb.query("select symbol,provider,count() rows,min(trade_date) first_date,max(trade_date) last_date from us_equity_daily where trade_date >= " + literal(history_from) + " and trade_date <= " + literal(args.through) + " group by symbol,provider order by symbol,provider") if qdb.exists("us_equity_daily") else []
    present = {row["symbol"] for row in prices}
    prices_by_symbol = {}
    for row in prices:
        prices_by_symbol.setdefault(row["symbol"], []).append(row)
    report["equityDailyRanges"] = prices
    if prices:
        report["equityDailyDateReconciliation"] = equity_daily_dates(qdb, root, history_from, args.through)
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
        try:
            calendar = us_session_calendar(root, args.through.year)
            report["usSessionCalendar"] = calendar
            report["coreIntradaySessionCoverage"] = intraday_sessions(qdb, us_config, calendar, args.through)
            if qdb.exists("us_equity_intraday_regular"):
                report["coreRegularViewSessionCoverage"] = intraday_sessions(qdb, us_config, calendar, args.through, "us_equity_intraday_regular")
                report["regularViewCalendarVersions"] = qdb.query("select calendar_version,count() rows from us_equity_intraday_regular group by calendar_version")
                report["coreFiveMinuteValueReconciliation"] = five_minute_values(qdb, us_config, args.through)
        except (OSError, ValueError, KeyError) as error:
            report["usSessionCalendar"] = {"verified": False, "error": str(error), "scope": "缺少或未核对官方当年日历时，不能用工作日补推完整性。"}
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
