"""缺口候选、来源冲突、分钟完整网格和脱敏诊断。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock
from datetime import datetime,timezone
import zipfile
import sqlite3
import hashlib
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import btc_quality_review as review
import btc_research_data as btc


class QualityReviewTest(unittest.TestCase):
    def test_query_timeout_has_context_without_credentials_or_sql(self):
        reader=btc.BtcReader('https://example.test','secret-auth')
        reader.opener=Mock();reader.opener.open.side_effect=TimeoutError('secret-auth https://private-host')
        with self.assertRaises(btc.BtcQueryError) as raised:reader._query('SELECT * FROM "btc_spot_1h" LIMIT 1')
        info=raised.exception.diagnostics
        self.assertEqual(['btc_spot_1h'],info['tables']);self.assertEqual('TimeoutError',info['causeType'])
        self.assertNotIn('secret-auth',str(raised.exception));self.assertNotIn('private-host',str(raised.exception));self.assertNotIn('SELECT',str(raised.exception))

    def test_minute_aggregation_rejects_holes_and_keeps_exact_trade_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'minute.zip';start=1609459200000
            rows=[f'{start+i*60000},10,12,9,11,1,{start+i*60000+59999},10,2,0.5,5,0' for i in range(1440)]
            with zipfile.ZipFile(p,'w') as z:z.writestr('minutes.csv','\n'.join(rows))
            result=review.minute_summary(p,'2021-01-01')
            self.assertTrue(result['complete']);self.assertEqual('2880',result['values']['trade_count'])
            rows[100]=rows[99]
            with zipfile.ZipFile(p,'w') as z:z.writestr('minutes.csv','\n'.join(rows))
            self.assertFalse(review.minute_summary(p,'2021-01-01')['complete'])

    def test_candidate_is_not_written_and_source_conflict_is_not_auto_resolved(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);plan_path=base/'plan.json';report_path=base/'source.json'
            plan={'batchId':'a'*64,'qualitySha256':'q','requests':[{'dataset':'spot_1h','day':'2021-01-01'}]};plan_path.write_text(json.dumps(plan))
            issue={'id':'issue','kind':'missing_bar','dataset':'spot_1h','from':'2021-01-01T00:00:00Z'}
            tables={d:[] for d in btc.DATASETS}
            row={'open_time':'2021-01-01T00:00:00Z','close_time':'2021-01-01T00:59:59.999Z','open':10,'high':12,'low':9,'close':11}
            for f in review.FIELDS[4:]:row[f]=1
            item={'dataset':'spot_1h','day':'2021-01-01','monthly':{'status':'SOURCE_OR_VALIDATION_FAILED'},'daily':{'status':'PARSED','coverage':{'rows':[row]}},'rest':{'status':'PARSED','coverage':{'rows':[dict(row)]}}}
            report={'planSha256':btc._digest(plan_path),'formalWrite':False,'results':[item]}
            with patch.object(review,'batch_material',return_value=('a'*64,tables,{'issues':[issue]},'q')):
                report_path.write_text(json.dumps(report));_,result=review.summarize(base,plan_path,report_path)
                self.assertEqual({'CANDIDATE_REQUIRES_FORMAL_REVIEW':1},result['findingCounts']);self.assertFalse(result['formalWrite'])
                item['rest']['coverage']['rows'][0]['close']=10
                report_path.write_text(json.dumps(report));_,result=review.summarize(base,plan_path,report_path)
                self.assertEqual({'SOURCE_CONFLICT':1},result['findingCounts'])
                report['planSha256']='wrong';report_path.write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError,'原计划'):review.summarize(base,plan_path,report_path)

    def test_repair_plan_only_accepts_full_missing_price_day(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);plan_path=base/'plan.json';report_path=base/'source.json'
            plan={'batchId':'a'*64,'qualitySha256':'q'};plan_path.write_text(json.dumps(plan))
            rows=[{'open_time':f'2022-04-27T{h:02d}:00:00Z'} for h in range(24)]
            report={'results':[{'dataset':'index_1h','day':'2022-04-27','daily':{'sha256':'d'*64,'coverage':{'rows':rows}},'rest':{'coverage':{'rows':rows}}}]};report_path.write_text(json.dumps(report))
            findings=[{'dataset':'index_1h','time':r['open_time'],'issueId':str(h),'kind':'missing_bar','status':'CANDIDATE_REQUIRES_FORMAL_REVIEW','candidateSources':['daily','rest']} for h,r in enumerate(rows)]
            tables={d:[] for d in btc.DATASETS}
            with patch.object(review,'summarize',return_value=(None,{'findings':findings})),patch.object(review,'batch_material',return_value=('a'*64,tables,{},'q')):
                target=review.repair_plan(base,plan_path,report_path)
                self.assertEqual(1,len(json.loads(target.read_text())['requests']))
                self.assertEqual(target,review.repair_plan(base,plan_path,report_path))
                self.assertEqual({'schemaVersion','batchId','qualitySha256','reviewReport','reviewReportSha256','requests'},set(json.loads(target.read_text())))
                tables['index_1h']=[rows[0]]
                with self.assertRaisesRegex(ValueError,'完整缺日'):review.repair_plan(base,plan_path,report_path)

    def test_process_success_does_not_certify_unverified_repair_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);path=base/'binance-vision/archive-queue.sqlite3';path.parent.mkdir()
            request={'dataset':'index_1h','day':'2022-04-27'}
            url='https://data.binance.vision/data/futures/um/daily/indexPriceKlines/BTCUSDT/1h/BTCUSDT-1h-2022-04-27.zip'
            key=hashlib.sha256(f'binance.archive.v1|index_1h|{url}|2022-04-27|2022-04-27'.encode()).hexdigest()
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE archive_tasks(id TEXT,phase TEXT)');db.execute('INSERT INTO archive_tasks VALUES(?,?)',(key,'BLOCKED_TARGET'));db.commit()
                with self.assertRaisesRegex(ValueError,'未全部核验'):review.require_verified_repairs(base,[request])
                db.execute("UPDATE archive_tasks SET phase='VERIFIED'");db.commit()
                review.require_verified_repairs(base,[request])

    def test_old_repair_plan_cannot_replace_a_newer_research_pointer(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);(base/'research').mkdir();(base/'research/current.json').write_text(json.dumps({'batchId':'b'*64}))
            plan=base/'plan.json';plan.write_text(json.dumps({'batchId':'a'*64}))
            with patch.object(review.daily,'queue_summary',return_value={'unresolvedLocks':[]}),patch.object(review,'repair_plan') as prepare:
                with self.assertRaisesRegex(ValueError,'不能回退'):review.repair(base,plan,base/'report.json')
                prepare.assert_not_called()

    def test_tampered_original_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);raw=base/'binance-vision/raw/binance/x.response';raw.parent.mkdir(parents=True);raw.write_bytes(b'original')
            item={'raw':str(raw),'sha256':btc._digest(raw)}
            self.assertEqual(raw.resolve(),review.original(base,item));raw.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'摘要不符'):review.original(base,item)


if __name__=='__main__':unittest.main()
