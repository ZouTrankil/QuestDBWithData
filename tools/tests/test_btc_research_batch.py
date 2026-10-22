"""固定研究批次、字段准入和失败时保留旧指针。"""
import importlib.util
from pathlib import Path
from datetime import date, datetime, timedelta, timezone
import tempfile
import unittest
from unittest.mock import patch
import json
import pyarrow as pa

spec = importlib.util.spec_from_file_location('btc_batch_test_subject', Path(__file__).resolve().parents[1] / 'btc_research_data.py')
btc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(btc)
UTC = timezone.utc
DAY = date(2025, 1, 1)
START = datetime(2025, 1, 1, tzinfo=UTC)


def rows(dataset):
    interval = btc.DATASETS[dataset][3]
    if interval is None:
        return [{'funding_time': START + timedelta(hours=8), 'exchange':'BINANCE', 'symbol':'BTCUSDT', 'instrument':'USDT_PERPETUAL',
                 'funding_rate':0.001, 'funding_interval_hours':None, 'source_sha256':'a'*64, 'observed_at':START}]
    return [dict(open_time=START + timedelta(minutes=interval*i), close_time=START + timedelta(minutes=interval*(i+1)) - timedelta(microseconds=1 if dataset.startswith('spot') else 1000),
                 open=100.0, high=102.0, low=99.0, close=101.0, exchange='BINANCE', symbol='BTCUSDT',
                 instrument=btc.DATASETS[dataset][2], interval_minutes=interval, source_sha256='a'*64, observed_at=START,
                 **({'volume':float(interval//60), 'quote_volume':float(interval//60*100), 'trade_count':interval//60,
                     'taker_buy_volume':float(interval//60), 'taker_buy_quote_volume':float(interval//60*100)} if dataset.startswith('spot') or dataset=='perp_1h' else {}))
            for i in range(1440//interval)]


class Fake(btc.FixedReader):
    writer_root = None
    snapshots = {}
    def __init__(self):
        super().__init__({d:pa.Table.from_pylist(rows(d)) for d in btc.DATASETS})
    def _snapshot(self, dataset):
        return (1, dataset, 1, 1)
    def batches(self, dataset, start, through):
        yield self.read(dataset, start, through)


class BatchTest(unittest.TestCase):
    def test_quality_conflict_only_restricts_conflicting_fields(self):
        fake = Fake()
        daily = rows('spot_1d'); daily[0]['volume'] = 999.0
        fake.tables['spot_1d'] = pa.Table.from_pylist(daily)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = btc.publish_batch(fake, DAY, DAY, root)
            self.assertFalse(result['dataVerified'])
            self.assertEqual(1, btc.read_batch(root, 'spot_1d', columns=['close'], require_verified=False)['actualRows'])
            with self.assertRaisesRegex(ValueError, '质量规则'):
                btc.read_batch(root, 'spot_1d', columns=['volume'], require_verified=False)
            explored = btc.read_batch(root, 'spot_1d', columns=['volume'], mode='explore')
            self.assertEqual(['volume'], explored['issues'][0]['fields'])
            with self.assertRaisesRegex(ValueError, '正式发布'):
                btc.read_batch(root, 'spot_1d', columns=['close'])

    def test_partial_export_keeps_old_batch_and_fixed_version_remains_readable(self):
        fake = Fake()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = btc.publish_batch(fake, DAY, DAY, root)
            pointer = (root/'current.json').read_bytes()
            original = fake.batches
            def fail(dataset, *args):
                if dataset == 'perp_1h': raise RuntimeError('第三个文件失败')
                return original(dataset, *args)
            fake.batches = fail
            with self.assertRaises(RuntimeError): btc.publish_batch(fake, DAY, DAY, root)
            self.assertEqual(pointer, (root/'current.json').read_bytes())
            self.assertEqual(1, btc.read_batch(root, 'spot_1d', batch_id=first['batchId'], columns=['close'], require_verified=False)['actualRows'])
            self.assertFalse(list(root.glob('.candidate-*')))

    def test_member_change_before_commit_keeps_pointer(self):
        fake = Fake()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            btc.publish_batch(fake, DAY, DAY, root)
            pointer = (root/'current.json').read_bytes()
            calls = 0
            def snapshot(dataset):
                nonlocal calls
                calls += 1
                return (1, dataset, 2 if calls > 6 else 1, 1)
            fake._snapshot = snapshot
            with self.assertRaisesRegex(ValueError, '版本变化'):
                btc.publish_batch(fake, DAY, DAY, root)
            self.assertEqual(pointer, (root/'current.json').read_bytes())

    def test_corruption_report_mismatch_and_path_escape_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = btc.publish_batch(Fake(), DAY, DAY, root)
            folder = root/'batches'/result['batchId']
            report = folder/'quality.json'
            report.write_text('{}')
            with self.assertRaisesRegex(ValueError, '质量报告摘要'):
                btc.read_batch(root, 'spot_1d', mode='explore')
            with self.assertRaises(ValueError): btc.read_batch(root, 'spot_1d', batch_id='../bad')
            with self.assertRaises(ValueError): btc._safe_path(root, '../escape')
            outside = root.parent/'escape'
            (root/'symlink').symlink_to(outside)
            with self.assertRaises(ValueError): btc._safe_path(root, 'symlink/file')

    def test_half_open_range_and_unknown_column(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            btc.publish_batch(Fake(), DAY, DAY, root)
            table = btc.read_batch(root, 'spot_1h', lower=START, until=START+timedelta(hours=1), columns=['close'], require_verified=False)['table']
            self.assertEqual(1, table.num_rows)
            self.assertEqual(0, btc.read_batch(root, 'spot_1h', lower=START, until=START, mode='explore')['actualRows'])
            with self.assertRaises(ValueError): btc.read_batch(root, 'spot_1h', columns=['missing'], mode='explore')
            with self.assertRaises(ValueError): btc.read_batch(root, 'spot_1h', lower=START-timedelta(hours=1), mode='explore')

    def test_unknown_funding_interval_and_missing_hour_have_separate_rules(self):
        fake = Fake(); fake.tables['spot_1h'] = fake.tables['spot_1h'].slice(1)
        report = btc.quality_report(fake.tables, DAY, DAY, {})
        self.assertTrue(any(i['kind']=='missing_bar' for i in report['issues']))
        self.assertEqual(['funding_interval_hours'], next(i for i in report['issues'] if i['kind']=='unknown_interval')['fields'])

    def test_synchronous_prices_refuse_shortened_and_older_hour(self):
        fake = Fake()
        short = rows('mark_1h'); short[7]['close_time'] -= timedelta(minutes=30)
        fake.tables['mark_1h'] = pa.Table.from_pylist(short)
        event = btc.funding_with_prices(fake, DAY, DAY, mode='synchronous')[0]
        self.assertIsNone(event['mark_close'])
        self.assertFalse(event['prices_complete'])
        carried = btc.funding_with_prices(fake, DAY, DAY, mode='carry')[0]
        self.assertIsNotNone(carried['mark_close'])
        self.assertIsNotNone(carried['mark_age_seconds'])
        self.assertFalse(carried['historical_pit'])

    def test_formal_proof_requires_ledger_target_prepared_and_recovery(self):
        import sqlite3
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)
            fake=Fake(); fake.writer_root=base
            run='market-00000000-0000-0000-0000-000000000001'
            target='market-'+hashlib.sha256(json.dumps({'id':1,'directoryName':'spot_1d'},separators=(',',':')).encode()).hexdigest()
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                db.execute('CREATE TABLE sync_runs(id TEXT, job_id TEXT, target_id TEXT, frozen_json TEXT)')
                db.execute('CREATE TABLE sync_entries(run_id TEXT,kind TEXT,state TEXT,updated_at TEXT)')
                db.execute('INSERT INTO sync_runs VALUES(?,?,?,?)',(run,'market.btc_spot_1d',target,json.dumps({'from':'2025-01-01','to':'2025-01-02','definition':{'datasetId':'btc_spot_1d'}})))
                db.execute("INSERT INTO sync_entries VALUES(?,'RUN','VERIFIED','2026-10-21T00:00:00Z')",(run,))
            (base/'prepared').mkdir(); (base/'receipts').mkdir()
            prepared=base/'prepared'/(run+'.json')
            row={k:v.isoformat() if isinstance(v,datetime) else v for k,v in rows('spot_1d')[0].items()}
            raw=base/'raw.response';raw.write_bytes(b'original')
            prepared.write_text(json.dumps({'definition':{'datasetId':'btc_spot_1d'},'rows':[row],'raw':str(raw),'sourceSha256':btc._digest(raw)}))
            receipt={'dataset':'btc_spot_1d','targetId':target,'sourceSha256':btc._digest(raw),'preparedSha256':btc._digest(prepared),'result':{'state':'IN_DOUBT','runId':run}}
            (base/'receipts'/(run+'.json')).write_text(json.dumps(receipt))
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                frozen_scope={'from':'2025-01-01','to':'2025-01-02','definition':{'datasetId':'btc_spot_1d'},
                              'parameters':{'prepared_sha256':btc._digest(prepared),'source_sha256':btc._digest(raw)}}
                db.execute('UPDATE sync_runs SET frozen_json=? WHERE id=?',(json.dumps(frozen_scope),run))
            with self.assertRaisesRegex(ValueError,'恢复证明'):
                btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'))
            recovered=base/'reconciliations'/(run+'-recovered');recovered.mkdir(parents=True)
            (recovered/'intent.json').write_text(json.dumps({'runId':run,'targetId':target,'preparedSha256':btc._digest(prepared)}))
            (recovered/'receipt.json').write_text(json.dumps({'state':'VERIFIED','runId':run}))
            self.assertTrue(btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'))['verified'])
            frozen=json.loads(prepared.read_text())
            extra=dict(frozen['rows'][0]);extra['open_time']=(START+timedelta(days=1)).isoformat();extra['close_time']=(START+timedelta(days=2)-timedelta(microseconds=1)).isoformat()
            frozen['rows'].append(extra)
            prepared.write_text(json.dumps(frozen))
            receipt['preparedSha256']=btc._digest(prepared);receipt['result']['state']='VERIFIED'
            (base/'receipts'/(run+'.json')).write_text(json.dumps(receipt))
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                frozen_scope={'from':'2025-01-01','to':'2025-01-02','definition':{'datasetId':'btc_spot_1d'},
                              'parameters':{'prepared_sha256':btc._digest(prepared),'source_sha256':btc._digest(raw)}}
                db.execute('UPDATE sync_runs SET frozen_json=? WHERE id=?',(json.dumps(frozen_scope),run))
            missing=btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'),START,START+timedelta(days=2))
            self.assertFalse(missing['verified']);self.assertEqual(2,missing['expectedRows'])
            frozen['rows'].pop();prepared.write_text(json.dumps(frozen));receipt['preparedSha256']=btc._digest(prepared)
            (base/'receipts'/(run+'.json')).write_text(json.dumps(receipt))
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                frozen_scope={'from':'2025-01-01','to':'2025-01-02','definition':{'datasetId':'btc_spot_1d'},
                              'parameters':{'prepared_sha256':btc._digest(prepared),'source_sha256':btc._digest(raw)}}
                db.execute('UPDATE sync_runs SET frozen_json=? WHERE id=?',(json.dumps(frozen_scope),run))
            changed=rows('spot_1d');changed[0]['close']=100.5
            self.assertFalse(btc.formal_evidence(fake,'spot_1d',pa.Table.from_pylist(changed),fake._snapshot('spot_1d'))['verified'])
            receipt['preparedSha256']='0'*64
            (base/'receipts'/(run+'.json')).write_text(json.dumps(receipt))
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                frozen_scope={'from':'2025-01-01','to':'2025-01-02','definition':{'datasetId':'btc_spot_1d'},
                              'parameters':{'prepared_sha256':btc._digest(prepared),'source_sha256':btc._digest(raw)}}
                db.execute('UPDATE sync_runs SET frozen_json=? WHERE id=?',(json.dumps(frozen_scope),run))
            with self.assertRaisesRegex(ValueError,'回执不一致'):
                btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'))

    def test_daily_freshness_failure_preserves_last_complete_batch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            btc.publish_batch(Fake(),DAY,DAY,root)
            before=(root/'current.json').read_bytes()
            with self.assertRaisesRegex(ValueError,'来源延迟'):
                btc.publish_batch(Fake(),DAY,DAY+timedelta(days=1),root,max_lag_days=1)
            self.assertEqual(before,(root/'current.json').read_bytes())

    def test_pointer_failure_and_member_corruption_preserve_fixed_batch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            result=btc.publish_batch(Fake(),DAY,DAY,root)
            before=(root/'current.json').read_bytes()
            original=btc._atomic_json
            def fail_pointer(path,value):
                if path.name=='current.json':raise OSError('模拟指针切换前失败')
                return original(path,value)
            with patch.object(btc,'_atomic_json',side_effect=fail_pointer):
                with self.assertRaises(OSError):btc.publish_batch(Fake(),DAY,DAY,root)
            self.assertEqual(before,(root/'current.json').read_bytes())
            manifest=json.loads((root/'batches'/result['batchId']/'manifest.json').read_text())
            (root/manifest['members']['spot_1d']['file']).write_bytes(b'broken')
            with self.assertRaisesRegex(ValueError,'成员摘要不符'):btc.read_batch(root,'spot_1d',mode='explore')

    def test_synchronous_prices_never_carry_a_different_hour(self):
        fake=Fake()
        index=rows('index_1h'); del index[7]
        fake.tables['index_1h']=pa.Table.from_pylist(index)
        strict=btc.funding_with_prices(fake,DAY,DAY,mode='synchronous')[0]
        self.assertIsNone(strict['index_close'])
        self.assertFalse(strict['prices_complete'])
        carried=btc.funding_with_prices(fake,DAY,DAY,mode='carry')[0]
        self.assertTrue(carried['index_carried'])
        self.assertEqual(START+timedelta(hours=6),carried['index_open_time'])
        expired=btc.funding_with_prices(fake,DAY,DAY,mode='carry',max_age_seconds=3600)[0]
        self.assertIsNone(expired['index_close'])

    def test_report_failure_keeps_pointer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            btc.publish_batch(Fake(), DAY, DAY, root)
            before = (root/'current.json').read_bytes()
            with patch.object(btc, 'quality_report', side_effect=OSError('模拟报告失败')):
                with self.assertRaises(OSError): btc.publish_batch(Fake(), DAY, DAY, root)
            self.assertEqual(before, (root/'current.json').read_bytes())


if __name__ == '__main__': unittest.main()
