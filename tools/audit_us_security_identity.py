#!/usr/bin/env python3
"""只读列出历史代码窗口及当前类别冲突，不凭同代码的价格宣称历史已覆盖。"""
import argparse
import collections
import datetime as dt
import itertools
import json
import pathlib
import sqlite3


def review(history, current, tasks, start, through):
    historical, official = collections.defaultdict(list), collections.defaultdict(list)
    for row in history:
        historical[row['symbol']].append(row)
    for row in current:
        official[row['symbol']].append(row)
    result, candidate_rows, candidate_symbols = [], 0, 0
    for symbol, rows in sorted(historical.items()):
        stock = [row for row in rows if row['daily_candidate']]
        if not stock:
            continue
        candidate_rows += len(stock)
        candidate_symbols += 1
        flags = []
        if len(rows) > 1:
            flags.append('MULTIPLE_VENDOR_ROWS')
        if any(row['is_etf'] for row in official[symbol]):
            flags.append('CURRENT_ETF_REQUIRES_HISTORY_REVIEW')
        if len({row['asset_type'] for row in rows}) > 1:
            flags.append('VENDOR_ASSET_TYPE_CONFLICT')
        overlaps = []
        for left, right in itertools.combinations(rows, 2):
            if not (left['daily_candidate'] or right['daily_candidate']):
                continue
            dates = [row[field] for row in (left, right) for field in ('source_start_date', 'source_end_date')]
            if any(date is None for date in dates):
                continue
            lower = max(start, *(dt.date.fromisoformat(row['source_start_date'][:10]) for row in (left, right)))
            upper = min(through, *(dt.date.fromisoformat(row['source_end_date'][:10]) for row in (left, right)))
            if lower <= upper:
                overlaps.append({'leftSourceRow': left['source_row'], 'rightSourceRow': right['source_row'], 'from': str(lower), 'through': str(upper)})
        if overlaps:
            flags.append('OVERLAPPING_VENDOR_WINDOWS')
        if flags:
            result.append({'symbol': symbol, 'flags': flags, 'task': tasks.get(symbol), 'currentOfficialRows': official[symbol], 'historicalRows': rows, 'overlapsInRequestedRange': overlaps,
                           'coverageConclusion': 'IDENTITY_UNPROVEN', 'meaning': '多个窗口可能来自转板、类别变化或代码复用；没有身份依据时不推断为同一证券，不删除旧窗口。'})
    etfs = [row for row in result if 'CURRENT_ETF_REQUIRES_HISTORY_REVIEW' in row['flags']]
    return {'requestedFrom': str(start), 'through': str(through), 'historicalRows': len(history), 'candidateStockRows': candidate_rows, 'candidateStockSymbols': candidate_symbols,
            'reviewSymbols': len(result), 'currentEtfHistoricalStockSymbols': len(etfs),
            'currentEtfTaskPhases': dict(collections.Counter((row['task'] or {}).get('phase', 'MISSING') for row in etfs)),
            'groups': result, 'wholeGoalComplete': False,
            'scope': '只诊断目录声明和当前官方类别。当前代码有价格、没有冲突标记或目录日期吻合，都不能单独证明历史证券身份和完整覆盖。'}


def main():
    if __package__:
        from .audit_cross_market_coverage import Quest, literal
    else:
        from audit_cross_market_coverage import Quest, literal
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path.cwd())
    parser.add_argument('--url', default='http://localhost:9000')
    parser.add_argument('--from', dest='start', type=dt.date.fromisoformat, required=True)
    parser.add_argument('--through', type=dt.date.fromisoformat, required=True)
    parser.add_argument('--out', type=pathlib.Path, default=pathlib.Path('var/market-data/security-identity-review.json'))
    args = parser.parse_args()
    if args.start > args.through:
        raise ValueError('请求日期倒置')
    qdb = Quest(args.url)
    latest = qdb.query('select max(observed_at) observed_at from us_security_history_catalog')[0]['observed_at']
    history = qdb.query('select source_row,observed_at,symbol,exchange,asset_type,currency,source_start_date,source_end_date,daily_candidate,scope_reason,source_sha256 from us_security_history_catalog where observed_at=' + literal(latest) + ' limit 100001')
    current = []
    snapshots = qdb.query('select directory,max(observed_at) observed_at from us_security_universe group by directory')
    for snapshot in snapshots:
        current.extend(qdb.query('select symbol,directory,observed_at,security_name,exchange,is_etf,daily_candidate,scope_reason,source_sha256 from us_security_universe where directory=' + literal(snapshot['directory']) + ' and observed_at=' + literal(snapshot['observed_at']) + ' limit 100001'))
    queue = args.root / 'var/market-data/broad-queue.sqlite3'
    with sqlite3.connect('file:' + queue.resolve().as_posix() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        tasks = {row['symbol']: dict(row) for row in db.execute("select id,symbol,phase,priority from broad_tasks where kind='DAILY'")}
    report = review(history, current, tasks, args.start, args.through)
    report.update({'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'historySnapshot': latest, 'officialSnapshots': snapshots})
    output = args.out if args.out.is_absolute() else args.root / args.out
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'groups'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
