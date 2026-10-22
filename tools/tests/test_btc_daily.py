"""日更轮查、失败状态和备份只读边界。"""
import importlib.util
import sys
from pathlib import Path
from datetime import date
import tempfile
import unittest
from unittest.mock import patch
import sqlite3
import json

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import btc_daily as daily


class DailyTest(unittest.TestCase):
    def test_month_boundary_and_bounded_weekly_cursor(self):
        windows=daily.revision_ranges(date(2020,1,1),date(2026,10,2),{},date(2026,10,3))
        self.assertEqual([(date(2026,8,1),date(2026,9,30),'recent'),(date(2020,1,1),date(2020,1,31),'older')],windows)
        self.assertEqual(1,len(daily.revision_ranges(date(2020,1,1),date(2026,10,2),{'scannedAt':'2026-10-02','cursor':'2020-02-01'},date(2026,10,3))))
        with self.assertRaises(ValueError):daily.revision_ranges(date(2020,1,1),date(2026,10,2),{},date(2026,10,3),max_files=6)

    def test_sqlite_backup_and_reference_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)/'source';base.mkdir()
            raw=base/'raw';raw.write_bytes(b'original')
            (base/'prepared.json').write_text(json.dumps({'raw':str(raw)}))
            with sqlite3.connect(base/'queue.sqlite3') as db:
                db.execute('PRAGMA journal_mode=WAL');db.execute('CREATE TABLE t(id INTEGER)');db.execute('INSERT INTO t VALUES(1)');db.commit()
                target=Path(tmp)/'copy'
                with patch.object(daily.btc,'configured_writer_root',return_value=base):
                    result=daily.backup(target)
                self.assertTrue(result['verified'])
                with sqlite3.connect(target/'queue.sqlite3') as copied:self.assertEqual([(1,)],copied.execute('SELECT * FROM t').fetchall())
                (target/'raw').write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError,'摘要不符'):daily.verify_backup(target)

    def test_disk_failure_is_reported_and_no_java_writer_starts(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)
            with patch.object(daily.btc,'configured_writer_root',return_value=base), patch.object(daily,'setting',side_effect=lambda key,default: 10**30 if key=='daily-min-free-bytes' else default), patch.object(daily,'run_command') as run:
                result=daily.daily(date(2026,1,1),Path(sys.executable),check_revisions=False)
            self.assertEqual('REQUIRES_ATTENTION',result['state'])
            run.assert_not_called()
            self.assertTrue((base/'daily-runs/latest.json').is_file())
            self.assertFalse(json.loads((base/'daily-observations.json').read_text())['ready'])

    def test_active_original_queue_lock_prevents_backup(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)/'source';(base/'binance-vision').mkdir(parents=True)
            code="import fcntl,sys; f=open(sys.argv[1],'a+b'); fcntl.lockf(f,fcntl.LOCK_EX); print('ready',flush=True); sys.stdin.read()"
            child=subprocess.Popen([sys.executable,'-c',code,str(base/'binance-vision/archive-queue.lock')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
            try:
                self.assertEqual('ready',child.stdout.readline().strip())
                with patch.object(daily.btc,'configured_writer_root',return_value=base):
                    with self.assertRaises(BlockingIOError):daily.backup(Path(tmp)/'copy')
                self.assertFalse((Path(tmp)/'copy').exists())
            finally:
                child.communicate('')

    def test_second_scheduled_writer_is_refused(self):
        import fcntl
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)
            with (base/'daily.lock').open('a+b') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                with patch.object(daily.btc,'configured_writer_root',return_value=base):
                    with self.assertRaises(BlockingIOError):daily.daily(date(2026,1,1),Path(sys.executable))


if __name__=='__main__':unittest.main()
