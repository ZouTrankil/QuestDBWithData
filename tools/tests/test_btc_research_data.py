"""研究导出只读、UTC 及发布清单的边界检查。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from datetime import date

MODULE = Path(__file__).resolve().parents[1] / 'btc_research_data.py'
spec = importlib.util.spec_from_file_location('btc_research_data', MODULE)
btc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(btc)


class ResearchReaderTest(unittest.TestCase):
    def test_only_select_is_allowed(self):
        reader = btc.BtcReader('http://localhost:9000')
        with self.assertRaises(ValueError):
            reader._query('DROP TABLE btc_spot_1d')

    def test_pagination_keeps_microseconds_and_utc(self):
        reader = btc.BtcReader('http://localhost:9000')
        reader._snapshot = lambda dataset: (1,0)
        reader._query = lambda sql: ([{'name': 'open_time', 'type': 'TIMESTAMP'}, {'name': 'close_time', 'type': 'TIMESTAMP'}], [['2025-01-01T00:00:00.000000Z', '2025-01-01T23:59:59.999999Z']])
        table = reader.read('spot_1d', date(2025,1,1), date(2025,1,1))
        self.assertEqual('UTC', table.schema.field('close_time').type.tz)
        self.assertEqual(999999, table['close_time'][0].as_py().microsecond)

    def test_duplicate_time_is_rejected(self):
        reader = btc.BtcReader('http://localhost:9000')
        reader._snapshot = lambda dataset: (1,0)
        reader._query = lambda sql: ([{'name': 'open_time', 'type': 'TIMESTAMP'}], [['2025-01-01T00:00:00Z'], ['2025-01-01T00:00:00Z']])
        with self.assertRaises(ValueError):
            reader.read('spot_1d', date(2025,1,1), date(2025,1,1))

    def test_export_uses_immutable_file_and_refuses_corruption(self):
        import pyarrow as pa
        class Fake:
            def batches(self, *args):
                yield pa.table({'value': [1, 2]})
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            metadata = btc.export(Fake(), 'spot_1d', date(2025,1,1), date(2025,1,1), root)
            self.assertEqual(2, btc.load_export(root,'spot_1d').num_rows)
            (root / metadata['file']).write_bytes(b'changed')
            with self.assertRaises(ValueError):
                btc.load_export(root,'spot_1d')

    def test_funding_uses_previous_finished_hour_and_keeps_missing_prices_empty(self):
        import pyarrow as pa
        from datetime import datetime, timezone
        event = datetime(2026,10,1,12,0,0,2000,tzinfo=timezone.utc)
        prior = datetime(2026,10,1,11,59,59,999000,tzinfo=timezone.utc)
        future = datetime(2026,10,1,12,59,59,999000,tzinfo=timezone.utc)
        class Fake:
            def read(self, dataset, *args):
                if dataset == 'funding': return pa.Table.from_pylist([{'funding_time': event}])
                if dataset == 'index_1h': return pa.table({})
                return pa.Table.from_pylist([{'close_time':prior,'close':100.0},{'close_time':future,'close':999.0}])
        rows = btc.funding_with_prices(Fake(), date(2026,10,1), date(2026,10,1))
        self.assertEqual(100,rows[0]['spot_close'])
        self.assertEqual(prior,rows[0]['spot_close_time'])
        self.assertIsNone(rows[0]['index_close'])


    def test_export_rejects_target_or_wal_change_before_manifest_publish(self):
        reader = btc.BtcReader('http://localhost:9000')
        tokens = iter([(1, 'old', 1, 1), (1, 'old', 2, 2)])
        reader._snapshot = lambda dataset: next(tokens)
        reader._query = lambda sql: ([{'name':'open_time','type':'TIMESTAMP'}], [['2025-01-01T00:00:00Z']])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                btc.export(reader,'spot_1d',date(2025,1,1),date(2025,1,1),root)
            self.assertFalse((root/'spot_1d.json').exists())
            self.assertFalse(list(root.glob('*.pending')))


    def test_unresolved_formal_run_blocks_snapshot_even_when_no_new_wal_is_visible(self):
        import sqlite3
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with sqlite3.connect(root/'sync-ledger.sqlite3') as db:
                db.execute('CREATE TABLE sync_interval_locks(dataset_id TEXT)')
                db.execute("INSERT INTO sync_interval_locks VALUES('btc_spot_1d')")
            reader = btc.BtcReader('http://localhost:9000',writer_root=root)
            with self.assertRaises(ValueError): reader._publication_fence('btc_spot_1d')
            reader._publication_fence('btc_funding')


if __name__ == '__main__':
    unittest.main()
