# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2,<3", "pandas>=2,<3", "pyarrow>=18", "PyYAML>=6", "exchange_calendars==4.13.2"]
# ///
"""1029研究的有限只读取数与独立完整性检查；不写QuestDB或控制SQLite。"""

from __future__ import annotations
import argparse, hashlib, json, math
from pathlib import Path
from datetime import datetime, date, timedelta, timezone
from collections import Counter
import pyarrow as pa
import pyarrow.parquet as pq

try:
    from .btc_research_data import BtcReader, configured_endpoint, DATASETS
except ImportError:
    from btc_research_data import BtcReader, configured_endpoint, DATASETS

UTC = timezone.utc
CORE = [
    "AAPL",
    "AMZN",
    "AVGO",
    "GLD",
    "GOOGL",
    "IWM",
    "JPM",
    "META",
    "MSFT",
    "NVDA",
    "QQQ",
    "SPY",
    "TLT",
    "TSLA",
    "USO",
    "WMT",
]
ETFS = ["SPY", "QQQ", "IWM", "TLT", "GLD", "USO"]


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        while data := f.read(1024 * 1024):
            h.update(data)
    return h.hexdigest()


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2, allow_nan=False)


def query(reader, sql):
    columns, values = reader._query(sql)
    return columns, [dict(zip((x["name"] for x in columns), row)) for row in values]


def state(reader, table):
    return query(
        reader,
        "SELECT t.id,t.directoryName,t.table_min_timestamp,t.table_max_timestamp,t.table_row_count,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name='"
        + table
        + "'",
    )[1][0]


def clean_row(row, columns):
    row = dict(row)
    for column in columns:
        key = column["name"]
        value = row[key]
        if value is None:
            continue
        if column["type"].startswith("TIMESTAMP"):
            stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
            row[key] = stamp.astimezone(UTC)
        elif column["type"] == "DOUBLE":
            row[key] = float(value)
    return row


def frame(rows, columns):
    types = {
        "DOUBLE": pa.float64(),
        "INT": pa.int32(),
        "LONG": pa.int64(),
        "SYMBOL": pa.string(),
        "VARCHAR": pa.string(),
        "STRING": pa.string(),
        "BOOLEAN": pa.bool_(),
    }
    fields = []
    for column in columns:
        kind = column["type"]
        dtype = (
            pa.timestamp("ns" if kind == "TIMESTAMP_NS" else "us", tz="UTC")
            if kind.startswith("TIMESTAMP")
            else types[kind]
        )
        fields.append(pa.field(column["name"], dtype))
    return pa.Table.from_pylist(
        [clean_row(x, columns) for x in rows], schema=pa.schema(fields)
    )


def export_one(
    reader, folder, table, timekey, start, until, step, predicate="", order=None
):
    target = folder / (table + ".parquet")
    manifest = folder / (table + "-input.json")
    if target.exists() or manifest.exists():
        raise ValueError("固定输入已存在，不能覆盖或重复取数：" + table)
    before = state(reader, table)
    if (
        before["suspended"]
        or before["writerTxn"] != before["sequencerTxn"]
        or before["bufferedTxnSize"] != 0
    ):
        raise ValueError("WAL门槛未通过：" + table)
    total = 0
    writer = None
    parts = []
    lower = start
    try:
        while lower < until:
            upper = min(lower + timedelta(days=step), until)
            where = (
                f"{timekey}>='{lower.isoformat()}' AND {timekey}<'{upper.isoformat()}'"
                + (" AND " + predicate if predicate else "")
            )
            columns, rows = query(
                reader,
                f"SELECT * FROM {table} WHERE {where} ORDER BY {order or timekey} LIMIT 5000",
            )
            data = frame(rows, columns)
            if writer is None:
                writer = pq.ParquetWriter(target, data.schema, compression="zstd")
            writer.write_table(data)
            total += len(rows)
            parts.append(
                dict(
                    fromDate=lower.isoformat(),
                    beforeDate=upper.isoformat(),
                    rows=len(rows),
                )
            )
            lower = upper
    finally:
        if writer is not None:
            writer.close()
    after = state(reader, table)
    if after != before:
        raise ValueError("读数期间物理对象或WAL变化，保留输入但不发布清单：" + table)
    save(
        manifest,
        dict(
            checkedAt=datetime.now(UTC).isoformat(),
            table=table,
            rows=total,
            file=str(target),
            sha256=sha(target),
            snapshot=before,
            timeKey=timekey,
            fromDate=start.isoformat(),
            beforeDate=until.isoformat(),
            parts=parts,
            readOnly=True,
        ),
    )
    print(json.dumps(dict(table=table, rows=total, snapshotStable=True)), flush=True)


def capture(folder, group):
    folder.mkdir(parents=True, exist_ok=True)
    reader = BtcReader(*configured_endpoint())
    if group == "native":
        for dataset, (table, key, _, interval) in DATASETS.items():
            before = state(reader, table)
            last = date.fromisoformat(before["table_max_timestamp"][:10]) + timedelta(
                days=1
            )
            export_one(
                reader,
                folder,
                table,
                key,
                date(2020, 1, 1),
                last,
                120 if interval == 60 else 365,
            )
    else:
        names = ",".join("'" + x + "'" for x in CORE)
        export_one(
            reader,
            folder,
            "us_equity_daily",
            "trade_date",
            date(2006, 1, 1),
            date(2026, 10, 24),
            365,
            "provider='TIINGO' AND symbol IN (" + names + ")",
            "trade_date,symbol,provider",
        )
        export_one(
            reader,
            folder,
            "crypto_ohlcv",
            "ts",
            date(2021, 10, 1),
            date(2026, 10, 26),
            20,
            order="ts,exchange,symbol,instrument,interval_minutes",
        )


def gaps(times, start, until, step):
    actual = [int(x.timestamp() * 1000000) for x in times]
    counts = Counter(actual)
    expected = list(
        range(
            int(start.timestamp() * 1000000),
            int(until.timestamp() * 1000000),
            step * 1000000,
        )
    )
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    stamp = lambda x: datetime.fromtimestamp(x / 1000000, UTC).isoformat()
    return dict(
        duplicates=sum(n - 1 for n in counts.values()),
        missing=[stamp(x) for x in missing],
        extra=[stamp(x) for x in extra],
    )


def candle_issues(rows, timekey):
    issues = []
    for row in rows:
        for field in ["open", "high", "low", "close"]:
            value = row.get(field)
            if value is None or not math.isfinite(value) or value <= 0:
                issues.append(
                    dict(
                        time=row[timekey].isoformat(), kind="invalid_price", field=field
                    )
                )
        if all(
            row.get(x) is not None and math.isfinite(row[x])
            for x in ["open", "high", "low", "close"]
        ):
            if (
                not row["low"]
                <= min(row["open"], row["close"])
                <= max(row["open"], row["close"])
                <= row["high"]
            ):
                issues.append(dict(time=row[timekey].isoformat(), kind="ohlc_boundary"))
        for field in ["volume", "quote_volume"]:
            if field in row and (
                row[field] is None or not math.isfinite(row[field]) or row[field] < 0
            ):
                issues.append(
                    dict(
                        time=row[timekey].isoformat(),
                        kind="invalid_volume",
                        field=field,
                    )
                )
    return issues


def corporate_actions(rows):
    """复权收益包含除息日收盘再投资；量按供应商整股字段容差核对，不重复记现金分红。"""
    report = {}
    for symbol in ETFS:
        selected = sorted(
            (x for x in rows if x["symbol"] == symbol), key=lambda x: x["trade_date"]
        )
        price_errors = []
        volume_errors = []
        events = []
        future = 1.0
        maximum = 0.0
        for previous, current in zip(selected, selected[1:]):
            expected = current["split_factor"] * (
                1 + current["dividend"] / current["close"]
            )
            actual = (current["adj_close"] / current["close"]) / (
                previous["adj_close"] / previous["close"]
            )
            if not math.isclose(expected, actual, rel_tol=1e-9, abs_tol=1e-9):
                price_errors.append(
                    dict(
                        day=str(current["trade_date"].date()),
                        expected=expected,
                        actual=actual,
                    )
                )
            if current["split_factor"] != 1 or (
                symbol == "TLT"
                and current["dividend"]
                and current["trade_date"].year == 2026
            ):
                events.append(
                    dict(
                        day=str(current["trade_date"].date()),
                        previousDay=str(previous["trade_date"].date()),
                        dividend=current["dividend"],
                        splitFactor=current["split_factor"],
                        expected=expected,
                        actual=actual,
                    )
                )
        for row in reversed(selected):
            error = abs(row["adj_volume"] - row["volume"] * future)
            maximum = max(maximum, error)
            if error >= 1:
                volume_errors.append(str(row["trade_date"].date()))
            future *= row["split_factor"]
        report[symbol] = dict(
            priceErrors=price_errors,
            volumeErrors=volume_errors,
            maximumVolumeRoundingError=maximum,
            events=events,
            passed=not price_errors and not volume_errors,
        )
    return dict(
        formula="splitFactor[t] * (1 + divCash[t]/close[t])",
        adjustedVolumeAbsoluteToleranceExclusive=1,
        symbols=report,
    )


def native_contract(rows, dataset):
    """原生表按完整字段核对身份、空值和数值；资金费未知周期继续保留null。"""
    _, _, instrument, interval = DATASETS[dataset]
    nulls = {key: sum(row[key] is None for row in rows) for key in rows[0]}
    numeric_errors = []
    identity_errors = 0
    for row in rows:
        identity_errors += int(
            row["exchange"] != "BINANCE"
            or row["symbol"] != "BTCUSDT"
            or row["instrument"] != instrument
            or (interval is not None and row["interval_minutes"] != interval)
        )
        for key, value in row.items():
            if isinstance(value, (float, int)) and (
                not math.isfinite(value)
                or (
                    key in ("trade_count", "taker_buy_volume", "taker_buy_quote_volume")
                    and value < 0
                )
            ):
                numeric_errors.append(dict(field=key, value=str(value)))
    return dict(
        identityErrors=identity_errors,
        nullCounts=nulls,
        numericErrors=numeric_errors,
        passed=not identity_errors
        and not numeric_errors
        and not any(
            count for key, count in nulls.items() if key != "funding_interval_hours"
        ),
    )


def validate(folder):
    import exchange_calendars as xc

    calendar = xc.get_calendar("XNYS", start="2006-01-01", end="2026-12-31")
    sessions = [x.date() for x in calendar.sessions]
    approved = json.loads(
        Path(
            "markets/us/src/main/resources/market-us/calendars/us-equities-2026.json"
        ).read_text()
    )
    expected2026 = {
        date(2026, 1, 1) + timedelta(days=i)
        for i in range(365)
        if (date(2026, 1, 1) + timedelta(days=i)).weekday() < 5
        and (date(2026, 1, 1) + timedelta(days=i)).isoformat()
        not in approved["closedDates"]
    }
    if set(x for x in sessions if x.year == 2026) != expected2026:
        raise ValueError("研究日历与已核对2026年日历不一致")
    report = dict(
        checkedAt=datetime.now(UTC).isoformat(),
        calendar=dict(
            name="XNYS",
            version=xc.__version__,
            approved2026Exact=True,
            sessionCount=len(sessions),
        ),
        etfs=[],
        coreStocks=[],
        cryptoGroups=[],
        native=[],
        limitations=[],
    )
    hours = {
        x.date().isoformat(): dict(
            open=calendar.session_open(x).isoformat(),
            close=calendar.session_close(x).isoformat(),
        )
        for x in calendar.sessions
    }
    save(
        folder / "calendar.json",
        dict(
            source="exchange_calendars XNYS",
            version=xc.__version__,
            sessions=[x.isoformat() for x in sessions],
            hours=hours,
            approved2026Exact=True,
        ),
    )
    daily = pq.read_table(folder / "us_equity_daily.parquet").to_pylist()
    if len(daily) != 80054:
        raise ValueError("核心日线范围变化，先核对原范围")
    for symbol in CORE:
        rows = sorted(
            [x for x in daily if x["symbol"] == symbol], key=lambda x: x["trade_date"]
        )
        days = [x["trade_date"].date() for x in rows]
        wanted = {x for x in sessions if days[0] <= x <= date(2026, 10, 23)}
        issue = candle_issues(rows, "trade_date")
        dup = len(days) - len(set(days))
        missing = sorted(wanted - set(days))
        extra = sorted(set(days) - wanted)
        nulls = {k: sum(x[k] is None for x in rows) for k in rows[0]}
        finite = {
            k: sum(x[k] is None or not math.isfinite(x[k]) for x in rows)
            for k in [
                "adj_open",
                "adj_high",
                "adj_low",
                "adj_close",
                "adj_volume",
                "dividend",
                "split_factor",
            ]
        }
        adjbad = sum(
            not all(
                math.isclose(
                    x["adj_" + k] / x[k],
                    x["adj_close"] / x["close"],
                    rel_tol=1e-10,
                    abs_tol=1e-10,
                )
                for k in ["open", "high", "low"]
            )
            for x in rows
        )
        item = dict(
            symbol=symbol,
            provider="TIINGO",
            first=days[0].isoformat(),
            through=days[-1].isoformat(),
            rows=len(rows),
            duplicates=dup,
            missing=[x.isoformat() for x in missing],
            extra=[x.isoformat() for x in extra],
            ohlcIssues=issue,
            nullCounts=nulls,
            nonFiniteAdjusted=finite,
            adjustedOhlcScaleConflicts=adjbad,
            zeroVolumeRows=sum(x["volume"] == 0 for x in rows),
            dividendEvents=sum(x["dividend"] != 0 for x in rows),
            splitEvents=[
                dict(day=x["trade_date"].date().isoformat(), factor=x["split_factor"])
                for x in rows
                if x["split_factor"] != 1
            ],
            passed=not (
                issue
                or dup
                or missing
                or extra
                or any(nulls.values())
                or any(finite.values())
                or adjbad
            ),
        )
        report["etfs" if symbol in ETFS else "coreStocks"].append(item)
    crypto = pq.read_table(folder / "crypto_ohlcv.parquet").to_pylist()
    groups = {}
    for row in crypto:
        groups.setdefault(
            tuple(
                row[x] for x in ["exchange", "symbol", "instrument", "interval_minutes"]
            ),
            [],
        ).append(row)
    if len(groups) != 16 or len(crypto) != 370198:
        raise ValueError("八产品十六格范围变化")
    for key, rows in sorted(groups.items()):
        rows.sort(key=lambda x: x["ts"])
        g = gaps(
            [x["ts"] for x in rows],
            datetime(2021, 10, 1, tzinfo=UTC),
            datetime(2026, 10, 26, tzinfo=UTC),
            key[3] * 60,
        )
        issues = candle_issues(rows, "ts")
        allowed = (
            ["2023-03-24T13:00:00+00:00"]
            if key[0] == "BINANCE" and key[2] == "SPOT" and key[3] == 60
            else []
        )
        report["cryptoGroups"].append(
            dict(
                exchange=key[0],
                symbol=key[1],
                instrument=key[2],
                intervalMinutes=key[3],
                rows=len(rows),
                first=rows[0]["ts"].isoformat(),
                last=rows[-1]["ts"].isoformat(),
                **g,
                officialVenueClosureExceptions=allowed,
                ohlcIssues=issues,
                passed=not issues
                and not g["duplicates"]
                and not g["extra"]
                and g["missing"] == allowed,
            )
        )
    nativeRows = {}
    for dataset, (table, key, _, interval) in DATASETS.items():
        rows = pq.read_table(folder / (table + ".parquet")).to_pylist()
        rows.sort(key=lambda x: x[key])
        nativeRows[dataset] = rows
        first = rows[0][key]
        end = rows[-1][key] + (
            timedelta(hours=1) if interval == 60 else timedelta(days=1)
        )
        issue = candle_issues(rows, key) if interval else []
        g = (
            gaps([x[key] for x in rows], first, end, interval * 60)
            if interval
            else dict(
                duplicates=len(rows) - len({x[key] for x in rows}), missing=[], extra=[]
            )
        )
        if interval is None:
            issue = [
                dict(time=x[key].isoformat(), kind="invalid_funding")
                for x in rows
                if x["funding_rate"] is None or not math.isfinite(x["funding_rate"])
            ]
        shortened = []
        if interval is not None:
            for x in rows:
                expectedEnd = x[key] + timedelta(minutes=interval)
                if (
                    "close_time" in x
                    and not expectedEnd - timedelta(milliseconds=1)
                    <= x["close_time"]
                    < expectedEnd
                ):
                    shortened.append(
                        dict(
                            openTime=x[key].isoformat(),
                            closeTime=x["close_time"].isoformat(),
                            expectedExclusiveEnd=expectedEnd.isoformat(),
                        )
                    )
        unknown = (
            sum(x.get("funding_interval_hours") is None for x in rows)
            if interval is None
            else 0
        )
        report["native"].append(
            dict(
                dataset=dataset,
                table=table,
                rows=len(rows),
                first=first.isoformat(),
                last=rows[-1][key].isoformat(),
                **g,
                issues=issue,
                shortenedBars=shortened,
                unknownFundingIntervals=unknown,
                fullFieldContract=native_contract(rows, dataset),
                fundingObservedIntervalsHours=dict(
                    Counter(
                        (b[key] - a[key]).total_seconds() / 3600
                        for a, b in zip(rows, rows[1:])
                    )
                )
                if interval is None
                else {},
                passed=native_contract(rows, dataset)["passed"]
                and not issue
                and not shortened
                and not unknown
                and not g["duplicates"]
                and not g["missing"]
                and not g["extra"],
            )
        )
    spotDaily = {x["open_time"].date(): x for x in nativeRows["spot_1d"]}
    hourly = {}
    for row in nativeRows["spot_1h"]:
        hourly.setdefault(row["open_time"].date(), []).append(row)
    reconciliation = []
    for day, parts in sorted(hourly.items()):
        if day not in spotDaily:
            continue
        d = spotDaily[day]
        computed = dict(
            open=parts[0]["open"],
            high=max(x["high"] for x in parts),
            low=min(x["low"] for x in parts),
            close=parts[-1]["close"],
            volume=math.fsum(x["volume"] for x in parts),
            quote_volume=math.fsum(x["quote_volume"] for x in parts),
        )
        diff = {
            k: dict(hourly=v, daily=d[k])
            for k, v in computed.items()
            if not math.isclose(v, d[k], rel_tol=1e-12, abs_tol=1e-10)
        }
        if diff or len(parts) != 24:
            reconciliation.append(
                dict(day=day.isoformat(), hourlyRows=len(parts), differences=diff)
            )
    report["nativeSpotHourlyDailyDifferences"] = reconciliation
    cross = [
        x
        for x in crypto
        if x["exchange"] == "BINANCE"
        and x["symbol"] == "BTCUSDT"
        and x["instrument"] == "SPOT"
        and x["interval_minutes"] == 1440
    ]
    overlap = []
    for x in cross:
        old = spotDaily.get(x["ts"].date())
        if old:
            different = [
                k
                for k in ["open", "high", "low", "close", "volume", "quote_volume"]
                if not math.isclose(x[k], old[k], rel_tol=1e-12, abs_tol=1e-10)
            ]
            if different:
                overlap.append(dict(day=x["ts"].date().isoformat(), fields=different))
    report["nativeCrossAssetDailyDifferences"] = overlap
    report["corporateActions"] = corporate_actions(daily)
    # 复算使用同一份显式准入证明；已有冻结目录仍拒绝覆盖。
    save(folder / "company-actions-verified.json", report["corporateActions"])
    save(
        folder / "native-full-field-contract.json",
        {x["dataset"]: x["fullFieldContract"] for x in report["native"]},
    )
    report["ready"] = dict(
        etfBaseline=all(x["passed"] for x in report["etfs"])
        and all(x["passed"] for x in report["corporateActions"]["symbols"].values()),
        btcSpotBaseline=next(
            x["passed"] for x in report["native"] if x["dataset"] == "spot_1d"
        ),
        ethAndRotation=all(
            x["passed"]
            for x in report["cryptoGroups"]
            if x["exchange"] == "BINANCE"
            and x["instrument"] == "SPOT"
            and x["intervalMinutes"] == 1440
        ),
        allNativeTablesComplete=all(x["passed"] for x in report["native"]),
    )
    report["limitations"] = [
        "历史下载基准不证明历史PIT；本轮历史策略回放不称未见留出期。",
        "原生现货小时和mark/index缺口逐时保留，多表实验不能由日线基线通过代替。",
        "资金费未知间隔保留null，未建立ETH资金费或精确资金费现金账本。",
    ]
    save(folder / "audit.json", report)
    print(
        json.dumps(
            dict(
                ready=report["ready"],
                etfIssues=[x["symbol"] for x in report["etfs"] if not x["passed"]],
                cryptoGroupsPassed=sum(x["passed"] for x in report["cryptoGroups"]),
                nativeGaps={x["dataset"]: len(x["missing"]) for x in report["native"]},
            )
        ),
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command", choices=["capture-native", "capture-core", "validate"]
    )
    parser.add_argument("--folder", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "validate":
        validate(args.folder)
    else:
        capture(args.folder, "native" if args.command == "capture-native" else "core")
