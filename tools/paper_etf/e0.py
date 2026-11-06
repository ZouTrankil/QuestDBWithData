"""E0 连续纸面账本；只消费明确冻结的输入，不连接交易账户。"""
import argparse
import hashlib
import json
import math
import sqlite3
from datetime import date, datetime
from pathlib import Path

ASSETS = ('SPY', 'QQQ', 'IWM', 'TLT', 'GLD')
COST = 0.001  # 单边 5bps 费用 + 5bps 滑点，按交易额收取。
CAPITAL = 100000.0


def instant(value):
    dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if dt.tzinfo is None or dt.utcoffset() is None:
        raise ValueError('时间必须包含时区')
    return dt.timestamp()


def run(db_path, input_path, day):
    raw = Path(input_path).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    data = json.loads(raw)
    if not isinstance(data.get('data_version'), str) or not data['data_version'].strip():
        raise ValueError('必须提供数据版本')
    sessions = data['sessions']
    dates = [s['date'] for s in sessions]
    if dates != sorted(set(dates)):
        raise ValueError('交易日必须唯一且递增')
    for s in sessions:
        date.fromisoformat(s['date'])
        if instant(s['open_at']) >= instant(s['close_at']):
            raise ValueError('开收盘时间错误')
    index = dates.index(day)
    if index == 0:
        raise ValueError('缺少前一交易日信号时间')
    prev, current = sessions[index - 1:index + 1]
    if instant(prev['close_at']) >= instant(current['open_at']):
        raise ValueError('信号必须早于成交')
    prices = current['prices']
    if set(prices) != set(ASSETS):
        raise ValueError('必须提供五只 ETF 的开收盘价')
    for p in prices.values():
        for field in ('open', 'close'):
            value = p[field]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                raise ValueError('价格必须为有限正数')
    with sqlite3.connect(db_path, timeout=10, isolation_level=None) as db:
        db.execute('BEGIN IMMEDIATE')
        # 只接受本工具建立的独立账本，避免向已有 SQLite 状态库写入。
        app_id = db.execute('PRAGMA application_id').fetchone()[0]
        tables = db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        if app_id != 0x45305052 and (app_id != 0 or tables):
            raise ValueError('目标不是独立的 E0 纸面账本')
        db.execute('PRAGMA application_id=1160794194')
        db.execute('CREATE TABLE IF NOT EXISTS daily (day TEXT PRIMARY KEY, input_sha256 TEXT NOT NULL, record TEXT NOT NULL)')
        existing = db.execute('SELECT input_sha256, record FROM daily WHERE day=?', (day,)).fetchone()
        if existing:
            if existing[0] != digest:
                raise ValueError('同日输入变更；保留原账本，需人工核对')
            db.commit()
            return json.loads(existing[1])
        last = db.execute('SELECT day, record FROM daily ORDER BY day DESC LIMIT 1').fetchone()
        if last and last[0] != prev['date']:
            raise ValueError('必须按输入日历逐交易日连续运行')
        state = json.loads(last[1]) if last else None
        cash = state['cash'] if state else CAPITAL
        units = state['holdings'] if state else {symbol: 0.0 for symbol in ASSETS}
        scheduled = not state or prev['date'][:7] != day[:7]
        target = {symbol: 0.2 for symbol in ASSETS} if scheduled else None
        trades = []
        if scheduled:
            # 求解交易后的可投资资金，使费用不会导致现金透支。
            values = {s: units[s] * prices[s]['open'] for s in ASSETS}
            before = cash + sum(values.values())
            after = before
            for _ in range(100):
                updated = before - COST * sum(abs(after * 0.2 - values[s]) for s in ASSETS)
                if abs(updated - after) < 1e-12 * before:
                    after = updated
                    break
                after = updated
            for s in ASSETS:
                delta = after * 0.2 - values[s]
                trades.append({'symbol': s, 'price': prices[s]['open'], 'units_change': delta / prices[s]['open'], 'notional': delta, 'cost': abs(delta) * COST})
                units[s] = after * 0.2 / prices[s]['open']
            cash = before - sum(t['notional'] + t['cost'] for t in trades)
            if abs(cash) < 1e-8:
                cash = 0.0
            if cash < 0:
                raise ValueError('现金不足')
        nav = cash + sum(units[s] * prices[s]['close'] for s in ASSETS)
        record = {'day': day, 'data_version': data['data_version'], 'input_sha256': digest,
                  'signal_at': prev['close_at'] if scheduled else None,
                  'execution_at': current['open_at'] if scheduled else None,
                  'target_weights': target, 'trades': trades, 'cash': cash,
                  'holdings': units, 'close_prices': {s: prices[s]['close'] for s in ASSETS}, 'nav': nav}
        db.execute('INSERT INTO daily VALUES (?, ?, ?)', (day, digest, json.dumps(record, sort_keys=True, allow_nan=False)))
        db.commit()
        return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--db', required=True)
    parser.add_argument('--input', required=True)
    parser.add_argument('--day', required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.db, args.input, args.day), ensure_ascii=False, indent=2))
