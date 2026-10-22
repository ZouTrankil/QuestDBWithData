"""剩余任务的跨期阻断、缺证拒绝、固定关联和空 Schema 验收。"""
from pathlib import Path
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import btc_research_data as btc
import btc_daily as daily
from test_btc_research_batch import Fake, rows, DAY, START
import pyarrow as pa

RUN = 'market-00000000-0000-0000-0000-000000000001'
NEXT = 'market-00000000-0000-0000-0000-000000000002'
TASK = 'a' * 64


def ledger(base):
    with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
        db.execute('CREATE TABLE IF NOT EXISTS sync_runs(id TEXT,job_id TEXT,target_id TEXT,frozen_json TEXT)')
        db.execute('CREATE TABLE IF NOT EXISTS sync_entries(run_id TEXT,kind TEXT,state TEXT,updated_at TEXT)')
        db.execute('CREATE TABLE IF NOT EXISTS sync_interval_locks(run_id TEXT,dataset_id TEXT,in_doubt INTEGER)')


def queue(base):
    root = base/'binance-vision'; root.mkdir(exist_ok=True)
    with sqlite3.connect(root/'archive-queue.sqlite3') as db:
        db.execute('CREATE TABLE IF NOT EXISTS archive_tasks(id TEXT,dataset TEXT,url TEXT,phase TEXT,run_id TEXT,reason TEXT)')
        db.execute('CREATE TABLE IF NOT EXISTS archive_revision_runs(id TEXT,task TEXT,previous_run TEXT,new_run TEXT,source_sha TEXT,bundle TEXT,phase TEXT)')


def make_run(base, run=RUN, through='2025-01-01'):
    ledger(base)
    target = 'market-' + hashlib.sha256(json.dumps({'id':1,'directoryName':'spot_1d'},separators=(',',':')).encode()).hexdigest()
    for directory in ('prepared','receipts'):(base/directory).mkdir(exist_ok=True)
    raw = base/(run+'.response'); raw.write_bytes(b'original')
    row = {k:v.isoformat() if isinstance(v,datetime) else v for k,v in rows('spot_1d')[0].items()}
    doc = {'definition':{'datasetId':'btc_spot_1d'},'rows':[row],'raw':str(raw),'sourceSha256':btc._digest(raw)}
    prepared = base/'prepared'/(run+'.json'); prepared.write_text(json.dumps(doc))
    card = {'dataset':'btc_spot_1d','targetId':target,'sourceSha256':doc['sourceSha256'],'preparedSha256':btc._digest(prepared),'result':{'state':'VERIFIED','runId':run}}
    receipt = base/'receipts'/(run+'.json'); receipt.write_text(json.dumps(card))
    with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
        db.execute('INSERT INTO sync_runs VALUES(?,?,?,?)',(run,'market.btc_spot_1d',target,json.dumps({'from':'2025-01-01','to':through,'definition':{'datasetId':'btc_spot_1d'},'parameters':{'prepared_sha256':btc._digest(prepared),'source_sha256':doc['sourceSha256']}})))
        db.execute("INSERT INTO sync_entries VALUES(?,'RUN','VERIFIED',?)",(run,'2026-10-21T00:00:00Z' if run==RUN else '2026-10-21T01:00:00Z'))
    return prepared, receipt, doc, card


def revision_report(base):
    queue(base)
    folder=base/'binance-vision/revision-checks';folder.mkdir(exist_ok=True)
    raw=base/'new.zip.response';raw.write_bytes(b'new official sample')
    candidate={'task':TASK,'dataset':'spot_1d','from':'2025-01-01','through':'2025-01-01',
               'originalRun':RUN,'oldSha256':'b'*64,'newSha256':btc._digest(raw),'status':'REVISION_REQUIRES_REVIEW'}
    report=folder/'old-month.json';report.write_text(json.dumps({'results':[candidate]}))
    return report, raw, candidate


class RemainingTest(unittest.TestCase):
    def test_missing_latest_success_evidence_never_falls_back_to_old_run(self):
        for missing in ('prepared','receipt'):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as tmp:
                base=Path(tmp);make_run(base)
                prepared, receipt, _, _=make_run(base,NEXT)
                (prepared if missing=='prepared' else receipt).unlink()
                fake=Fake();fake.writer_root=base
                with self.assertRaisesRegex(ValueError,'缺少 prepared 或回执'):
                    btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'))

    def test_missing_evidence_outside_frozen_scope_does_not_block_unrelated_range(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);make_run(base)
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                db.execute('INSERT INTO sync_runs VALUES(?,?,?,?)',(NEXT,'market.btc_spot_1d',
                    'market-'+hashlib.sha256(json.dumps({'id':1,'directoryName':'spot_1d'},separators=(',',':')).encode()).hexdigest(),
                    json.dumps({'from':'2024-01-01','to':'2024-01-01','definition':{'datasetId':'btc_spot_1d'}})))
                db.execute("INSERT INTO sync_entries VALUES(?,'RUN','VERIFIED','2026-10-21T01:00:00Z')",(NEXT,))
            fake=Fake();fake.writer_root=base
            self.assertTrue(btc.formal_evidence(fake,'spot_1d',fake.tables['spot_1d'],fake._snapshot('spot_1d'))['verified'])

    def test_old_revision_survives_next_scan_and_blocks_manual_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);report,_,candidate=revision_report(base)
            self.assertEqual(1,len(btc.pending_revisions(base)))
            (report.parent/'today.json').write_text(json.dumps({'results':[dict(candidate,status='UNCHANGED')]}))
            self.assertEqual(1,len(btc.pending_revisions(base)))
            with sqlite3.connect(base/'binance-vision/revision-review.sqlite3') as db:
                self.assertEqual(1,db.execute('SELECT count(*) FROM review_findings').fetchone()[0])
            fake=Fake();output=base/'research';btc.publish_batch(fake,DAY,DAY,output)
            pointer=(output/'current.json').read_bytes();fake.writer_root=base
            batch_id=json.loads(pointer)['batchId']
            with self.assertRaisesRegex(ValueError,'当前版本存在待核对修订'):
                btc.read_batch(output,'spot_1d',columns=['close'],require_verified=False)
            fixed=btc.read_batch(output,'spot_1d',batch_id=batch_id,columns=['close'],require_verified=False)
            self.assertEqual(1,len(fixed['pendingRevisions']))
            self.assertEqual('REVISION_REVIEW',fixed['resultState'])
            with self.assertRaisesRegex(ValueError,'跨期待核对修订'):btc.publish_batch(fake,DAY,DAY,output)
            self.assertEqual(pointer,(output/'current.json').read_bytes())
            report.unlink()
            with self.assertRaisesRegex(ValueError,'原报告缺失'):btc.pending_revisions(base)

    def test_old_pending_revision_blocks_daily_even_when_scan_is_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);ledger(base);revision_report(base)
            with patch.object(daily.btc,'configured_writer_root',return_value=base),patch.object(daily,'run_command'),patch.object(daily.btc,'publish_batch') as publish:
                result=daily.daily(date(2026,10,20),Path(sys.executable),check_revisions=False)
            self.assertEqual('REQUIRES_ATTENTION',result['state'])
            self.assertEqual(1,len(result['pendingRevisions']))
            publish.assert_not_called()

    def test_only_matching_verified_java_revision_resolves_pending_and_is_rechecked(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);_,raw,candidate=revision_report(base)
            self.assertEqual(1,len(btc.pending_revisions(base)))
            prepared,receipt,doc,card=make_run(base,NEXT)
            checksum=base/'new.CHECKSUM';checksum.write_text(candidate['newSha256']+' new.zip')
            revision=hashlib.sha256((RUN+'|'+candidate['newSha256']).encode()).hexdigest()
            bundle=base/'bundle.json';bundle.write_text(json.dumps({'format':'binance.archive.bundle.v2','members':[
                {'task':TASK,'raw':str(raw),'sha256':candidate['newSha256'],'checksum':str(checksum),'checksumSha256':btc._digest(checksum)}]}))
            doc.update(raw=str(bundle),sourceSha256=btc._digest(bundle),evidence={'previousRun':RUN,'revision':revision})
            doc['rows'][0]['source_sha256']=candidate['newSha256'];prepared.write_text(json.dumps(doc))
            card.update(sourceSha256=doc['sourceSha256'],preparedSha256=btc._digest(prepared));receipt.write_text(json.dumps(card))
            with sqlite3.connect(base/'sync-ledger.sqlite3') as db:
                frozen=json.loads(db.execute('SELECT frozen_json FROM sync_runs WHERE id=?',(NEXT,)).fetchone()[0])
                frozen['parameters']={'prepared_sha256':btc._digest(prepared),'source_sha256':doc['sourceSha256']}
                db.execute('UPDATE sync_runs SET frozen_json=? WHERE id=?',(json.dumps(frozen),NEXT))
            with sqlite3.connect(base/'binance-vision/archive-queue.sqlite3') as db:
                db.execute('INSERT INTO archive_tasks VALUES(?,?,?,?,?,?)',(TASK,'spot_1d','url','VERIFIED',NEXT,None))
                db.execute('INSERT INTO archive_revision_runs VALUES(?,?,?,?,?,?,?)',(revision,TASK,RUN,NEXT,candidate['newSha256'],str(bundle),'PUBLISHING'))
            self.assertEqual(1,len(btc.pending_revisions(base)))
            with sqlite3.connect(base/'binance-vision/archive-queue.sqlite3') as db:db.execute("UPDATE archive_revision_runs SET phase='VERIFIED'")
            self.assertEqual([],btc.pending_revisions(base))
            prepared.unlink()
            with self.assertRaisesRegex(ValueError,'缺少 prepared'):btc.pending_revisions(base)


    def test_overlap_conflict_also_remains_blocking_when_new_scan_is_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);root=base/'binance-vision';folder=root/'overlap-conflicts';folder.mkdir(parents=True)
            (folder/'conflict.json').write_text(json.dumps({'dataset':'btc_spot_1d','targetId':'original-target',
                'conflicts':[{'key':'original-encoded-key','field':'close','existing':1,'incoming':2}]}))
            self.assertEqual('overlap',btc.pending_revisions(base)[0]['kind'])
            recent=root/'revision-checks';recent.mkdir();(recent/'today.json').write_text(json.dumps({'results':[{'status':'UNCHANGED'}]}))
            with self.assertRaisesRegex(ValueError,'跨期待核对修订'):
                btc.require_no_pending_revisions(base,START,START+timedelta(days=1),['spot_1d'])

    def test_empty_remote_read_and_export_preserve_actual_schema_and_do_not_certify_source(self):
        reader=btc.BtcReader('http://localhost:9000');reader._snapshot=lambda _: (1,'spot_1d',1,1)
        reader._query=lambda _: ([{'name':'open_time','type':'TIMESTAMP'},{'name':'close','type':'DOUBLE'}],[])
        table=reader.read('spot_1d',DAY,DAY)
        self.assertEqual(['open_time','close'],table.column_names);self.assertEqual(0,table.num_rows)
        self.assertEqual('UTC',table.schema.field('open_time').type.tz)
        with tempfile.TemporaryDirectory() as tmp:
            meta=btc.export(reader,'spot_1d',DAY,DAY,Path(tmp))
            self.assertEqual('EMPTY_OBSERVED',meta['resultState']);self.assertFalse(meta['dataVerified'])
            self.assertEqual(table.schema,btc.load_export(Path(tmp),'spot_1d').schema)

    def test_fixed_batch_funding_association_has_own_purpose_and_preserves_rate(self):
        fake=Fake();fake.tables['index_1h']=fake.tables['index_1h'].filter(pa.array([i!=7 for i in range(24)]))
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);result=btc.publish_batch(fake,DAY,DAY,root)
            report=json.loads((root/'batches'/result['batchId']/'quality.json').read_text())
            self.assertEqual(1,report['fundingPriceAssociations']['incompleteEvents'])
            self.assertEqual('MISSING_HOUR',report['fundingPriceAssociations']['events'][0]['prices']['index_1h']['status'])
            self.assertEqual(1,btc.read_batch(root,'funding',columns=['funding_rate'],require_verified=False)['actualRows'])
            with self.assertRaisesRegex(ValueError,'质量规则'):btc.read_batch(root,'funding',columns=['funding_rate'],require_verified=False,purpose='funding_synchronous')
            explored=btc.read_batch(root,'funding',columns=['funding_rate'],mode='explore',purpose='funding_synchronous')
            self.assertEqual(1,len(explored['associations']));self.assertFalse(explored['associations'][0]['complete'])


    def test_funding_association_rejects_corrupted_dependent_price_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);result=btc.publish_batch(Fake(),DAY,DAY,root)
            manifest=json.loads((root/'batches'/result['batchId']/'manifest.json').read_text())
            (root/manifest['members']['index_1h']['file']).write_bytes(b'broken price file')
            with self.assertRaisesRegex(ValueError,'关联价格摘要不符'):
                btc.read_batch(root,'funding',columns=['funding_rate'],purpose='funding_synchronous',require_verified=False)
            # 不依赖价格的原始费率仍然能读取。
            self.assertEqual(1,btc.read_batch(root,'funding',columns=['funding_rate'],require_verified=False)['actualRows'])

    def test_fixed_batch_funding_association_success_and_empty_subrange(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);btc.publish_batch(Fake(),DAY,DAY,root)
            result=btc.read_batch(root,'funding',columns=['funding_rate'],require_verified=False,purpose='funding_synchronous')
            self.assertTrue(result['associations'][0]['complete'])
            empty=btc.read_batch(root,'funding',columns=['funding_rate'],require_verified=False,lower=START,until=START+timedelta(hours=1))
            self.assertEqual('EMPTY_RESULT',empty['resultState']);self.assertEqual(['funding_rate'],empty['table'].column_names)


    def test_backup_rejects_missing_api_raw_before_publishing_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)/'source';root=base/'binance-vision';root.mkdir(parents=True)
            with sqlite3.connect(root/'archive-queue.sqlite3') as db:
                db.execute('CREATE TABLE funding_api_tails(raw TEXT,sha TEXT)')
                db.execute('INSERT INTO funding_api_tails VALUES(?,?)',(str(base/'missing.response'),'a'*64))
            target=Path(tmp)/'copy'
            with patch.object(daily.btc,'configured_writer_root',return_value=base):
                with self.assertRaisesRegex(ValueError,'缺少原件引用'):daily.backup(target)
            self.assertFalse(target.exists())
            self.assertFalse(list(Path(tmp).glob('*.pending')))

    def test_backup_rejects_registry_whose_probe_report_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)/'source';root=base/'binance-vision';root.mkdir(parents=True)
            with sqlite3.connect(root/'revision-review.sqlite3') as db:
                db.execute('CREATE TABLE review_reports(path TEXT,sha256 TEXT)')
                db.execute('INSERT INTO review_reports VALUES(?,?)',('revision-checks/missing.json','a'*64))
            target=Path(tmp)/'copy'
            with patch.object(daily.btc,'configured_writer_root',return_value=base):
                with self.assertRaisesRegex(ValueError,'修订报告缺失'):daily.backup(target)
            self.assertFalse(target.exists())



    def test_schedule_accepts_standard_overrides_and_rejects_cross_day_budget(self):
        import os
        with patch.dict(os.environ,{'APP_CRYPTO_DATA_SCHEDULE_START_UTC_HOUR':'0','APP_CRYPTO_DATA_SCHEDULE_RETRY_HOURS':'4','APP_CRYPTO_DATA_SCHEDULE_MAX_ATTEMPTS':'6'}):
            self.assertEqual([0,4,8,12,16,20],daily.schedule_settings()['utcHours'])
            with self.assertRaisesRegex(ValueError,'必须带偏移量'):
                daily.scheduled(Path(sys.executable),datetime(2026,10,21,1))
            os.environ['APP_CRYPTO_DATA_SCHEDULE_MAX_ATTEMPTS']='8'
            with self.assertRaisesRegex(ValueError,'同一 UTC 日'):daily.schedule_settings()

    def test_scheduled_late_source_retry_is_persistent_and_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);folder=base/'daily-runs';folder.mkdir()
            now=datetime(2026,10,21,10,tzinfo=timezone.utc)
            record={'runId':'prior','trigger':'scheduled','through':'2026-10-20','startedAt':'2026-10-21T07:00:00+00:00','state':'REQUIRES_ATTENTION'}
            (folder/'20261021T070000Z-prior.json').write_text(json.dumps(record))
            with patch.object(daily.btc,'configured_writer_root',return_value=base),patch.object(daily,'daily',return_value={'state':'REQUIRES_ATTENTION'}) as run:
                self.assertEqual('NOT_DUE',daily.scheduled(Path(sys.executable),now-timedelta(minutes=1))['state']);run.assert_not_called()
                daily.scheduled(Path(sys.executable),now)
                run.assert_called_once_with(date(2026,10,20),Path(sys.executable),trigger='scheduled')
                run.reset_mock()
                for i in range(7):
                    (folder/f'20261021-extra-{i}.json').write_text(json.dumps(record))
                self.assertEqual('RETRY_LIMIT',daily.scheduled(Path(sys.executable),now)['state']);run.assert_not_called()

    def test_scheduled_success_stops_same_day_and_next_day_gets_new_frozen_cutoff(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);folder=base/'daily-runs';folder.mkdir();(base/'research').mkdir()
            (base/'research/current.json').write_text(json.dumps({'batchId':'done'}))
            record={'runId':'prior','trigger':'scheduled','through':'2026-10-20','startedAt':'2026-10-21T07:00:00+00:00','state':'PARTIAL_AVAILABLE','dataVerified':True,'currentBatch':'done','batch':{'batchId':'done'}}
            (folder/'20261021T070000Z-prior.json').write_text(json.dumps(record))
            with patch.object(daily.btc,'configured_writer_root',return_value=base),patch.object(daily,'daily',return_value={'state':'AVAILABLE'}) as run:
                self.assertEqual('ALREADY_AVAILABLE',daily.scheduled(Path(sys.executable),datetime(2026,10,21,10,tzinfo=timezone.utc))['state']);run.assert_not_called()
                daily.scheduled(Path(sys.executable),datetime(2026,10,22,1,tzinfo=timezone.utc))
                run.assert_called_once_with(date(2026,10,21),Path(sys.executable),trigger='scheduled')

    def test_scheduled_does_not_reset_running_attempt_or_restart_before_due(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);folder=base/'daily-runs';folder.mkdir()
            record={'runId':'prior','trigger':'scheduled','through':'2026-10-20','startedAt':'2026-10-21T07:00:00+00:00','state':'RUNNING'}
            (folder/'20261021T070000Z-prior.json').write_text(json.dumps(record))
            with patch.object(daily.btc,'configured_writer_root',return_value=base),patch.object(daily,'daily',side_effect=BlockingIOError('原日更锁仍在')) as run:
                self.assertEqual('NOT_DUE',daily.scheduled(Path(sys.executable),datetime(2026,10,21,9,tzinfo=timezone.utc))['state']);run.assert_not_called()
                self.assertEqual('ALREADY_RUNNING',daily.scheduled(Path(sys.executable),datetime(2026,10,21,10,tzinfo=timezone.utc))['state'])

    def test_observations_never_count_same_day_replays_as_seven_cycles(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);folder=base/'daily-runs';folder.mkdir()
            now=datetime(2026,10,22,2,tzinfo=timezone.utc)
            # 七份文件仍然只有同一个真实目标日，且旧报告没有新版验收标记。
            for i in range(7):
                record={'runId':str(i),'through':'2026-10-21','startedAt':'2026-10-22T01:00:00+00:00',
                        'finishedAt':'2026-10-22T01:01:00+00:00','state':'AVAILABLE','dataVerified':True}
                (folder/f'{i}.json').write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError,'必须带偏移量'):
                daily.observations(base,datetime(2026,10,22,2))
            result=daily.observations(base,now)
            self.assertFalse(result['ready']);self.assertEqual(0,result['acceptedDays'])
            self.assertEqual(7,len(result['missingDates']))

    def test_observations_require_seven_distinct_completed_live_date_scopes(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);folder=base/'daily-runs';folder.mkdir()
            now=datetime(2026,10,22,2,tzinfo=timezone.utc)
            for i in range(1,8):
                day=now.date()-timedelta(days=i);started=datetime.combine(day+timedelta(days=1),datetime.min.time(),timezone.utc)+timedelta(hours=1)
                batch=f'{i:064x}';batch_dir=base/'research/batches'/batch;batch_dir.mkdir(parents=True)
                (batch_dir/'manifest.json').write_text(json.dumps({'members':{d:{} for d in btc.DATASETS},'ruleVersion':3,'dataVerified':True,
                    'until':datetime.combine(day+timedelta(days=1),datetime.min.time(),timezone.utc).isoformat()}))
                record={'runId':str(i),'through':str(day),'startedAt':started.isoformat(),'finishedAt':(started+timedelta(minutes=1)).isoformat(),
                        'state':'PARTIAL_AVAILABLE','dataVerified':True,'revisionGuardClear':True,'observationProtocol':1,'batch':{'batchId':batch}}
                (folder/f'{i}.json').write_text(json.dumps(record))
            (base/'research/current.json').write_text(json.dumps({'batchId':f'{1:064x}'}))
            # 文件真实性由 read_batch 的独立摘要/Schema 测试覆盖，这里只隔离观察周期判定。
            with patch.object(daily.btc,'read_batch',return_value={'formalVerified':True}):
                result=daily.observations(base,now)
                self.assertTrue(result['ready']);self.assertEqual(7,result['acceptedDays'])
                p=folder/'7.json';record=json.loads(p.read_text());record['state']='REQUIRES_ATTENTION';p.write_text(json.dumps(record))
                self.assertFalse(daily.observations(base,now)['ready'])


if __name__=='__main__':unittest.main()
