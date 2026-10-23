# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2,<3", "pyarrow>=18", "PyYAML>=6", "pytz>=2024.1", "duckdb>=1.2"]
# ///
"""从固定 BTC 研究批次生成有限来源核对；Python 不下载来源，也不写 QuestDB。"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import fcntl
import hashlib
import json
import math
import sqlite3
from contextlib import closing
from pathlib import Path
import uuid
import zipfile
import btc_research_data as btc
import btc_daily as daily

UTC = timezone.utc
KINDS = {'missing_bar', 'shortened_bar', 'daily_hourly_conflict'}
FIELDS = ['open','high','low','close','volume','quote_volume','trade_count','taker_buy_volume','taker_buy_quote_volume']


def batch_material(base, batch_id=None):
    output = base/'research'
    batch_id = batch_id or json.loads((output/'current.json').read_text())['batchId']
    tables = {d:btc.read_batch(output,d,batch_id=batch_id,mode='explore')['table'].to_pylist() for d in btc.DATASETS}
    folder = output/'batches'/batch_id
    quality = json.loads((folder/'quality.json').read_text())
    return batch_id, tables, quality, btc._digest(folder/'quality.json')


def prepare(base):
    batch_id,_,quality,sha = batch_material(base)
    requests = {}
    for issue in quality['issues']:
        if issue['kind'] in KINDS:
            key = (issue['dataset'],issue['from'][:10])
            requests.setdefault(key,{'dataset':key[0],'day':key[1],'issueIds':[],'minuteCheck':False})['issueIds'].append(issue['id'])
            if issue['kind']=='daily_hourly_conflict' and key[0]=='spot_1h':requests[key]['minuteCheck']=True
    if not requests or len(requests)>64:raise ValueError('本轮日期预算须为 1—64，扩大范围前分批核对')
    plan = {'schemaVersion':1,'batchId':batch_id,'qualitySha256':sha,'createdAt':datetime.now(UTC).isoformat(),'requests':[requests[k] for k in sorted(requests)]}
    folder = base/'binance-vision/quality-review-plans';folder.mkdir(parents=True,exist_ok=True)
    target = folder/(uuid.uuid4().hex+'.json');btc._atomic_json(target,plan)
    return target


def original(base,item):
    raw=Path(item['raw']).resolve()
    if not raw.is_relative_to((base/'binance-vision/raw/binance').resolve()) or btc._digest(raw)!=item['sha256']:
        raise ValueError('核对原响应越界或摘要不符')
    if 'checksum' in item:
        checksum=Path(item['checksum']).resolve()
        if not checksum.is_relative_to((base/'binance-vision/raw/binance').resolve()) or btc._digest(checksum)!=item['checksumSha256']:
            raise ValueError('核对 CHECKSUM 越界或摘要不符')
        fields=checksum.read_text().split()
        if len(fields)!=2 or fields[0].lower()!=item['sha256'] or fields[1].lstrip('*')!=item['url'].rsplit('/',1)[1]:
            raise ValueError('核对 CHECKSUM 未绑定该原文')
    return raw


def instant(value):
    result = datetime.fromisoformat(str(value)) if not isinstance(value,datetime) else value
    if result.tzinfo is None:raise ValueError('核对时间缺 UTC 偏移量')
    return result.astimezone(UTC)


def complete(row,dataset,rest=False):
    start,end=instant(row['open_time']),instant(row['close_time'])
    step=timedelta(days=1) if dataset=='spot_1d' else timedelta(hours=1)
    precision=timedelta(microseconds=1 if dataset.startswith('spot') and start.year>=2025 and not rest else 1000)
    return (end==start+step-precision and all(math.isfinite(float(row[k])) and float(row[k])>0 for k in ['open','high','low','close'])
            and float(row['low'])<=min(float(row['open']),float(row['close']))<=max(float(row['open']),float(row['close']))<=float(row['high']))


def close_enough(a,b,field):
    return int(a)==int(b) if field=='trade_count' else math.isclose(float(a),float(b),rel_tol=1e-12,abs_tol=1e-8)


def minute_summary(raw,day):
    """只汇总完整 UTC 分钟网格；不跨缺口凑出一条正式日线。"""
    start=datetime.combine(date.fromisoformat(day),datetime.min.time(),UTC)
    micros=start.year>=2025
    unit=1_000_000 if micros else 1000
    with zipfile.ZipFile(raw) as archive:
        names=archive.infolist()
        if len(names)!=1 or names[0].is_dir() or names[0].file_size>16*1024*1024:raise ValueError('分钟 ZIP 超预算或成员数量不符')
        lines=archive.read(names[0]).decode().splitlines()
    if lines and lines[0].startswith('open_time,'):lines=lines[1:]
    if len(lines)!=1440:return {'complete':False,'reason':'分钟根数不足或超限','rows':len(lines)}
    rows=[line.split(',') for line in lines]
    for i,r in enumerate(rows):
        expected=int(start.timestamp())*unit+i*60*unit
        if len(r)!=12 or int(r[0])!=expected or int(r[6])!=expected+60*unit-1:
            return {'complete':False,'reason':'分钟时间网格或结束边界不完整','row':i}
        values=[Decimal(r[j]) for j in [1,2,3,4,5,7,8,9,10]]
        if any(not v.is_finite() or v<0 for v in values) or any(Decimal(r[j])<=0 for j in [1,2,3,4]) or not Decimal(r[3])<=min(Decimal(r[1]),Decimal(r[4]))<=max(Decimal(r[1]),Decimal(r[4]))<=Decimal(r[2]) or Decimal(r[8])!=Decimal(r[8]).to_integral_value():
            raise ValueError('分钟价格、成交字段或笔数无效')
    values={'open':rows[0][1],'high':str(max(Decimal(r[2]) for r in rows)),'low':str(min(Decimal(r[3]) for r in rows)),'close':rows[-1][4]}
    for name,index in [('volume',5),('quote_volume',7),('trade_count',8),('taker_buy_volume',9),('taker_buy_quote_volume',10)]:values[name]=str(sum(Decimal(r[index]) for r in rows))
    return {'complete':True,'rows':1440,'values':values}


def summarize(base,plan_path,report_path):
    plan=json.loads(plan_path.read_text());report=json.loads(report_path.read_text())
    if report.get('planSha256')!=btc._digest(plan_path) or report.get('formalWrite') is not False:raise ValueError('来源核对报告未绑定原计划')
    batch_id,tables,quality,sha=batch_material(base,plan['batchId'])
    if sha!=plan['qualitySha256']:raise ValueError('原固定质量报告已变化')
    requests={(r['dataset'],r['day']):r for r in plan['requests']}
    results={(r['dataset'],r['day']):r for r in report['results']}
    if len(results)!=len(report['results']) or results.keys()!=requests.keys():raise ValueError('来源报告日期不完整或重复')
    baseline={d:{instant(r[btc.DATASETS[d][1]]):r for r in rows} for d,rows in tables.items()}
    findings=[];conflicts=[];source_counts=Counter()
    for (dataset,day),item in results.items():
        candidates={}
        for source in ['monthly','daily','rest']:
            material=item[source];source_counts[source+'/'+material['status']]+=1
            if 'raw' in material:original(base,material)
            if material['status']=='PARSED':
                rows=material['coverage']['rows']
                candidates[source]={instant(r['open_time']):r for r in rows if instant(r['open_time']).date().isoformat()==day}
        for issue in quality['issues']:
            if issue['dataset']!=dataset or issue['from'][:10]!=day or issue['kind'] not in {'missing_bar','shortened_bar'}:continue
            time=instant(issue['from']);choices={s:rows[time] for s,rows in candidates.items() if time in rows and complete(rows[time],dataset,s=='rest')}
            names=list(choices);agree=all(all(close_enough(choices[names[0]][f],choices[s][f],f) for f in FIELDS if f in choices[s]) for s in names[1:]) if names else False
            existing=baseline[dataset].get(time)
            changes={s:[f for f in FIELDS if f in row and existing and not close_enough(existing[f],row[f],f)] for s,row in choices.items()}
            findings.append({'issueId':issue['id'],'dataset':dataset,'time':time.isoformat(),'kind':issue['kind'],'status':'CANDIDATE_REQUIRES_FORMAL_REVIEW' if choices and agree else 'SOURCE_CONFLICT' if choices else 'NO_COMPLETE_SOURCE','candidateSources':names,'changedFields':changes,'automaticWrite':False})
        if item.get('minutes',{}).get('status')=='FROZEN_NOT_YET_VALIDATED':
            minute=minute_summary(original(base,item['minutes']),day)
            conflict={'day':day,'minutes':minute,'sourceSha256':item['minutes']['sha256']}
            if minute['complete']:
                native=next(r for t,r in baseline['spot_1d'].items() if t.date().isoformat()==day)
                hourly=[r for t,r in baseline['spot_1h'].items() if t.date().isoformat()==day]
                conflict['nativeMatches']={f:close_enough(minute['values'][f],native[f],f) for f in FIELDS}
                conflict['hourlyTradeMatches']={f:close_enough(minute['values'][f],sum(Decimal(str(r[f])) for r in hourly),f) for f in FIELDS[4:]}
            conflicts.append(conflict)
        elif requests[(dataset,day)].get('minuteCheck',False):conflicts.append({'day':day,'minutes':{'complete':False,'reason':item.get('minutes',{}).get('reason','分钟原文未冻结')}})
    closures=btc.configured_crypto_value('venue-closures',[])
    closure_notes=[]
    for finding in findings:
        if finding['dataset']!='spot_1h':continue
        start=instant(finding['time'])
        for closure in closures:
            if closure.get('exchange')=='BINANCE' and closure.get('instrument')=='SPOT' and start<instant(closure['until']) and start+timedelta(hours=1)>instant(closure['from']):
                closure_notes.append({'issueId':finding['issueId'],'time':finding['time'],'closureFrom':closure['from'],'closureUntil':closure['until'],'evidenceUrl':closure['evidence-url'],'note':'落在已有官方停市说明范围，仍保留缺口或提前结束，不补造价格'})
    result={'schemaVersion':1,'knownClosureEvidence':closure_notes,'batchId':batch_id,'planSha256':btc._digest(plan_path),'sourceReport':str(report_path.resolve()),'sourceReportSha256':btc._digest(report_path),'checkedAt':datetime.now(UTC).isoformat(),'formalWrite':False,'sourceStatuses':dict(source_counts),'findings':findings,'findingCounts':dict(Counter(f['status'] for f in findings)),'dailyHourlyConflicts':conflicts,'remainingFundingIssues':dict(Counter(i['kind'] for i in quality['issues'] if i['kind'] in {'unknown_interval','funding_price_missing'}))}
    target=report_path.with_suffix('.summary.json');btc._atomic_json(target,result)
    return target,result


def repair_plan(base, plan_path, report_path):
    """只选整日全缺、日包与 REST 逐字段一致的标记价/指数价；现货改值不自动处理。"""
    _, summary = summarize(base, plan_path, report_path)
    plan = json.loads(plan_path.read_text())
    batch_id, tables, _, _ = batch_material(base, plan['batchId'])
    requests=[]
    for item in json.loads(report_path.read_text())['results']:
        dataset,day=item['dataset'],item['day']
        if dataset not in {'mark_1h','index_1h'}:continue
        findings=[f for f in summary['findings'] if f['dataset']==dataset and f['time'][:10]==day]
        if len(findings)!=24 or any(f['kind']!='missing_bar' or f['status']!='CANDIDATE_REQUIRES_FORMAL_REVIEW' or not {'daily','rest'}<=set(f['candidateSources']) for f in findings):continue
        if any(instant(r['open_time']).date().isoformat()==day for r in tables[dataset]):raise ValueError('原批次不是完整缺日，停止自动补齐')
        expected={datetime.combine(date.fromisoformat(day),datetime.min.time(),UTC)+timedelta(hours=h) for h in range(24)}
        daily_rows=item['daily']['coverage']['rows'];rest_rows=item['rest']['coverage']['rows']
        if {instant(r['open_time']) for r in daily_rows}!=expected or len(daily_rows)!=24 or {instant(r['open_time']) for r in rest_rows}!=expected or len(rest_rows)!=24:raise ValueError('日包或 REST 的小时键不完整')
        requests.append({'dataset':dataset,'day':day,'dailySha256':item['daily']['sha256'],'issueIds':[f['issueId'] for f in findings]})
    if not requests:raise ValueError('没有满足完整缺日补齐条件的候选；原未决通过原恢复入口处理')
    result={'schemaVersion':1,'batchId':batch_id,'qualitySha256':plan['qualitySha256'],'reviewReport':str(report_path.resolve()),'reviewReportSha256':btc._digest(report_path),'requests':requests}
    target=plan_path.with_suffix('.repair.json')
    if target.exists():
        if json.loads(target.read_text())!=result:raise ValueError('原补齐计划已存在且内容不同')
    else:btc._atomic_json(target,result)
    return target


def require_verified_repairs(base, requests):
    with closing(sqlite3.connect((base/'binance-vision/archive-queue.sqlite3').resolve().as_uri()+'?mode=ro',uri=True)) as db:
        for request in requests:
            kind='markPriceKlines' if request['dataset']=='mark_1h' else 'indexPriceKlines'
            url=f"https://data.binance.vision/data/futures/um/daily/{kind}/BTCUSDT/1h/BTCUSDT-1h-{request['day']}.zip"
            key=hashlib.sha256(f"binance.archive.v1|{request['dataset']}|{url}|{request['day']}|{request['day']}".encode()).hexdigest()
            row=db.execute('SELECT phase FROM archive_tasks WHERE id=?',(key,)).fetchone()
            if row!=('VERIFIED',):raise ValueError('补齐原任务未全部核验，不发布研究批次：'+key)


def repair(base, plan_path, report_path):
    with (base/'daily.lock').open('a+b') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if daily.queue_summary(base)['unresolvedLocks']:raise ValueError('存在原未决写入，先走原运行恢复')
        if json.loads((base/'research/current.json').read_text())['batchId']!=json.loads(plan_path.read_text())['batchId']:
            raise ValueError('当前研究版本已变化，先基于当前批次重新核对，不能回退新鲜版本')
        target=repair_plan(base,plan_path,report_path)
        attempt={'startedAt':datetime.now(UTC).isoformat(),'state':'RUNNING','plan':str(target.resolve()),'planSha256':btc._digest(target)}
        attempt_path=target.parent/'repair-attempts'/(uuid.uuid4().hex+'.json')
        btc._atomic_json(attempt_path,attempt)
        try:
            binary=btc.ROOT/'build/modules/markets/btc/data-app/_output/install/btc-data/bin/btc-data'
            log=target.with_suffix('.log')
            daily.run_command(binary,['repair-binance-quality','--plan',str(target.resolve())],log,daily.setting('daily-timeout-seconds',3600))
            if daily.queue_summary(base)['unresolvedLocks']:raise ValueError('补齐仍有原未决写入，不发布研究批次')
            plan=json.loads(target.read_text())
            # Java 正常退出不等于全部日期成功；每个原任务必须已完成来源和正式回读。
            require_verified_repairs(base,plan['requests'])
            folder=base/'research/batches'/plan['batchId']
            manifest=json.loads((folder/'manifest.json').read_text())
            # 修复沿用原批次截止日；历史修复不会计作新的真实日更周期。
            endpoint,auth=btc.configured_endpoint()
            result=btc.publish_batch(btc.BtcReader(endpoint,auth,base),date.fromisoformat(manifest['from'][:10]),date.fromisoformat(manifest['until'][:10])-timedelta(days=1),base/'research',require_verified=True)
            btc._atomic_json(base/'daily-observations.json',daily.observations(base))
            receipt={'plan':str(target),'planSha256':btc._digest(target),'finishedAt':datetime.now(UTC).isoformat(),'researchBatch':result}
            btc._atomic_json(target.with_suffix('.receipt.json'),receipt)
            attempt['state']='VERIFIED'
            attempt['researchBatch']=result
            return receipt
        except Exception as error:
            attempt['state']='REQUIRES_ATTENTION'
            attempt['error']=type(error).__name__+': '+str(error)
            attempt['errorDetails']=getattr(error,'diagnostics',{})
            raise
        finally:
            attempt['finishedAt']=datetime.now(UTC).isoformat()
            attempt['unresolvedLocks']=daily.queue_summary(base)['unresolvedLocks']
            attempt['currentBatch']=json.loads((base/'research/current.json').read_text())['batchId']
            btc._atomic_json(attempt_path,attempt)



def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','run','summarize','repair'])
    parser.add_argument('--plan',type=Path)
    parser.add_argument('--report',type=Path)
    args=parser.parse_args();base=btc.configured_writer_root()
    if args.command=='prepare':print(prepare(base));return
    if args.command=='repair':
        if not args.plan or not args.report:parser.error('repair 必须提供 --plan 和 --report')
        print(json.dumps(repair(base,args.plan,args.report),ensure_ascii=False));return
    if args.command=='run':
        with (base/'daily.lock').open('a+b') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            plan=args.plan or prepare(base)
            log=plan.with_suffix('.log')
            binary=btc.ROOT/'build/modules/markets/btc/data-app/_output/install/btc-data/bin/btc-data'
            daily.run_command(binary,['audit-binance-quality','--plan',str(plan.resolve())],log,daily.setting('daily-timeout-seconds',3600))
            report=Path(log.read_text().splitlines()[-1])
            target,result=summarize(base,plan,report)
    else:
        if not args.plan or not args.report:parser.error('summarize 必须提供 --plan 和 --report')
        target,result=summarize(base,args.plan,args.report)
    print(json.dumps({'summary':str(target),'findingCounts':result['findingCounts'],'sourceStatuses':result['sourceStatuses']},ensure_ascii=False))


if __name__=='__main__':main()
