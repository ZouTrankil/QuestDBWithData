"""用合成行情验证连续账本，不连接外部服务。"""
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from tools.paper_etf.e0 import ASSETS, run


class PaperE0Test(unittest.TestCase):
    def test_continuity_cost_idempotence_and_input_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            path, db = Path(tmp) / 'input.json', Path(tmp) / 'paper.sqlite'
            sessions = []
            for day, price in [('2026-01-30', 10), ('2026-02-02', 10), ('2026-02-03', 11), ('2026-03-02', 12)]:
                sessions.append({'date': day, 'open_at': day + 'T14:30:00Z', 'close_at': day + 'T21:00:00Z',
                                 'prices': {s: {'open': price, 'close': price} for s in ASSETS}})
            path.write_text(json.dumps({'data_version': 'synthetic', 'sessions': sessions}))
            first = run(db, path, '2026-02-02')
            self.assertAlmostEqual(first['nav'], 100000 / 1.001)
            self.assertAlmostEqual(sum(t['cost'] for t in first['trades']), 100000 - first['nav'])
            self.assertEqual(first['signal_at'], sessions[0]['close_at'])
            self.assertEqual(first, run(db, path, '2026-02-02'))
            with self.assertRaises(ValueError):
                run(db, path, '2026-03-02')
            second = run(db, path, '2026-02-03')
            self.assertEqual(second['trades'], [])
            self.assertAlmostEqual(second['nav'], first['nav'] * 1.1)
            third = run(db, path, '2026-03-02')
            self.assertEqual(len(third['trades']), 5)
            path.write_text(path.read_text() + ' ')
            with self.assertRaises(ValueError):
                run(db, path, '2026-02-02')
            with sqlite3.connect(db) as conn:
                self.assertEqual(conn.execute('select count(*) from daily').fetchone()[0], 3)

    def test_rejects_existing_unrelated_sqlite(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / 'other.sqlite'
            with sqlite3.connect(db) as conn:
                conn.execute('create table important (value text)')
                conn.execute("insert into important values ('keep')")
            path = Path(tmp) / 'input.json'
            dates = ['2026-01-30', '2026-02-02']
            sessions = [{'date': d, 'open_at': d+'T14:30:00Z', 'close_at': d+'T21:00:00Z',
                         'prices': {s: {'open': 10, 'close': 10} for s in ASSETS}} for d in dates]
            path.write_text(json.dumps({'data_version': 'synthetic', 'sessions': sessions}))
            with self.assertRaises(ValueError):
                run(db, path, dates[-1])
            with sqlite3.connect(db) as conn:
                self.assertEqual(conn.execute('select value from important').fetchone()[0], 'keep')
                self.assertEqual(conn.execute("select count(*) from sqlite_master where name='daily'").fetchone()[0], 0)

    def test_bad_price_rolls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'input.json'
            dates = ['2026-01-30', '2026-02-02']
            sessions = [{'date': d, 'open_at': d+'T14:30:00Z', 'close_at': d+'T21:00:00Z',
                         'prices': {s: {'open': 0, 'close': 10} for s in ASSETS}} for d in dates]
            path.write_text(json.dumps({'data_version': 'synthetic', 'sessions': sessions}))
            with self.assertRaises(ValueError):
                run(Path(tmp) / 'paper.sqlite', path, dates[-1])

if __name__ == '__main__':
    unittest.main()
