# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2,<3", "pyarrow>=18", "PyYAML>=6", "duckdb>=1.2", "pytz>=2024.1"]
# ///
"""BTC 日更编排与离线备份；正式采集仍只调用 Java 原入口。"""
from __future__ import annotations
import argparse
from contextlib import ExitStack, closing
from datetime import date, datetime, timedelta, timezone
import fcntl
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid
import traceback
import btc_research_data as btc

UTC = timezone.utc


def setting(name, default):
    value = int(btc.configured_crypto_value(name, default))
    if value <= 0:
        raise ValueError("运行配置必须大于零：" + name)
    return value


def month_shift(day, delta):
    ordinal = day.year * 12 + day.month - 1 + delta
    return date(ordinal // 12, ordinal % 12 + 1, 1)


def revision_ranges(start, through, state, today, recent=2, max_files=18):
    """近期窗口每天复核；旧月每周轮查，游标只在完整探测成功后推进。"""
    if max_files < recent * len(btc.DATASETS):
        raise ValueError("修订文件预算不足以覆盖近期月份")
    last = month_shift(through.replace(day=1), -1)
    first = max(start.replace(day=1), month_shift(last, 1-recent))
    ranges = []
    if first <= last:
        ranges.append((first, month_shift(last, 1)-timedelta(days=1), "recent"))
    last_scan = date.fromisoformat(state['scannedAt']) if state.get('scannedAt') else None
    if max_files >= (recent+1)*len(btc.DATASETS) and (last_scan is None or today-last_scan >= timedelta(days=7)):
        cursor = date.fromisoformat(state.get('cursor', str(start.replace(day=1))))
        if cursor >= first or cursor < start.replace(day=1): cursor = start.replace(day=1)
        if cursor < first:
            ranges.append((cursor, month_shift(cursor, 1)-timedelta(days=1), "older"))
    return ranges


def queue_summary(base):
    result = {"unresolvedLocks": [], "archiveIssues": [], "fundingIssues": []}
    ledger = base/'sync-ledger.sqlite3'
    if ledger.is_file():
        with closing(sqlite3.connect(ledger.as_uri()+'?mode=ro', uri=True)) as db:
            result['unresolvedLocks'] = [dict(zip(('runId','dataset','inDoubt'), r)) for r in db.execute("SELECT run_id,dataset_id,in_doubt FROM sync_interval_locks")]
    queue = base/'binance-vision/archive-queue.sqlite3'
    if queue.is_file():
        with closing(sqlite3.connect(queue.as_uri()+'?mode=ro', uri=True)) as db:
            result['archiveIssues'] = [dict(zip(('task','dataset','url','phase','runId','reason'),r)) for r in db.execute("SELECT id,dataset,url,phase,run_id,reason FROM archive_tasks WHERE phase NOT IN ('VERIFIED') ORDER BY dataset,url")]
            if db.execute("SELECT name FROM sqlite_master WHERE name='funding_api_tails'").fetchone():
                result['fundingIssues'] = [dict(zip(('from','through','phase','runId','reason'),r)) for r in db.execute("SELECT from_date,through_date,phase,run_id,reason FROM funding_api_tails WHERE phase!='VERIFIED'")]
    return result


def run_command(binary, arguments, log, timeout):
    # 不经 shell，日志只保存现有公开来源输出；私密配置不复制进报告。
    with log.open('ab') as stream:
        process = subprocess.Popen([str(binary), *arguments], cwd=btc.ROOT, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            import signal
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise RuntimeError("Java 作业超时；保留原状态，先核对未决运行")
    if code:
        raise RuntimeError("Java 作业失败，退出码 " + str(code))


def daily(through, binary, *, check_revisions=True, trigger="manual"):
    base = btc.configured_writer_root()
    if base is None: raise ValueError("缺 app.crypto-data.root")
    base.mkdir(parents=True, exist_ok=True)
    if through >= datetime.now(UTC).date(): raise ValueError("只接受已结束 UTC 日期")
    logs = base/'daily-runs'; logs.mkdir(exist_ok=True)
    with (base/'daily.lock').open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
        if (base/'binance-vision/stop.request').exists(): raise ValueError("存在 stop.request，未启动日更")
        if not binary.is_file() or not os.access(binary, os.X_OK): raise ValueError("先构建 BTC installDist")
        run_id = datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex
        report = {"runId":run_id, "through":str(through), "startedAt":datetime.now(UTC).isoformat(), "state":"RUNNING", "dataVerified":False, "observationProtocol":1, "revisionGuardClear":False, "trigger":trigger}
        pointer=base/'research/current.json'
        report['previousBatch']=json.loads(pointer.read_text()).get('batchId') if pointer.is_file() else None
        target = logs/(run_id+'.json'); log = logs/(run_id+'.log')
        btc._atomic_json(target, report)
        try:
            report['stage']='disk_check'
            usage = sum(p.stat().st_size for p in base.rglob('*') if p.is_file())
            if usage > setting('daily-max-root-bytes', 10737418240) or shutil.disk_usage(base).free < setting('daily-min-free-bytes', 1073741824):
                raise RuntimeError("磁盘容量门槛未通过，未启动采集")
            timeout = setting('daily-timeout-seconds', 3600)
            start = date.fromisoformat(str(btc.configured_crypto_value('archive-history-start','2020-01-01')))
            attempts = setting('daily-source-attempts', 2)
            report['stage']='source_sync'
            for attempt in range(attempts):
                run_command(binary, ['sync-binance','--through',str(through)], log, timeout)
            report['sourceAttempts'] = attempts
            cursor_path = base/'binance-vision/revision-schedule.json'
            cursor = json.loads(cursor_path.read_text()) if cursor_path.exists() else {}
            revision_reports = []
            report['stage']='revision_check'
            if check_revisions:
                for first,last,kind in revision_ranges(start, through, cursor, datetime.now(UTC).date(), setting('revision-recent-months',2), setting('revision-max-files',18)):
                    folder = base/'binance-vision/revision-checks'
                    before = set(folder.glob('*.json'))
                    run_command(binary, ['check-binance-revisions','--dataset','all','--from',str(first),'--through',str(last)],log,timeout)
                    added = set(folder.glob('*.json'))-before
                    if len(added)!=1: raise RuntimeError("修订探测未生成唯一报告")
                    path = added.pop(); revision = json.loads(path.read_text())
                    revision_reports.append({"file":str(path),"sha256":btc._digest(path),"requiresReview":sum(r['status']=='REVISION_REQUIRES_REVIEW' for r in revision['results']),"incomplete":sum(r['status'] not in ('UNCHANGED','REVISION_REQUIRES_REVIEW') for r in revision['results'])})
                    # 没计划、来源错误或旧状态不明均不推进旧月轮查游标。
                    if kind=='older' and all(r['status'] in ('UNCHANGED','REVISION_REQUIRES_REVIEW') for r in revision['results']):
                        cursor={'cursor':str(month_shift(first,1)), 'scannedAt':str(datetime.now(UTC).date())}
                        btc._atomic_json(cursor_path,cursor)
            report['revisionReports']=revision_reports
            report['stage']='revision_guard'
            report['pendingRevisions']=btc.pending_revisions(base)
            if report['pendingRevisions']:
                raise RuntimeError("存在跨期待核对修订，保留旧研究批次")
            report['queue']=queue_summary(base)
            if report['queue']['unresolvedLocks'] or report['queue']['fundingIssues']:
                raise RuntimeError("存在未决写入或资金费率未完成项，保留旧研究批次")
            if any(r['incomplete'] for r in revision_reports):
                raise RuntimeError("修订探测未完整结束，保留旧研究批次")
            if any(r['requiresReview'] for r in revision_reports):
                raise RuntimeError("发现历史修订，先显式核对处理，保留旧研究批次")
            endpoint, auth = btc.configured_endpoint()
            report['stage']='research_publish'
            result = btc.publish_batch(btc.BtcReader(endpoint,auth,base), start,through,base/'research',max_lag_days=setting('daily-max-lag-days',1),require_verified=True)
            report['stage']='coverage_check'
            report['revisionGuardClear']=True
            report['batch']=result
            report['dataVerified']=result['dataVerified']
            report['state']='PARTIAL_AVAILABLE' if result['issues'] else 'AVAILABLE'
            if not result['dataVerified']: report['state']='UNVERIFIED'
            manifest=json.loads((base/'research/batches'/result['batchId']/'manifest.json').read_text())
            report['coverage']={d:{'rows':m['rows'],'through':m['through'],'formalVerified':m['formalEvidence']['verified']} for d,m in manifest['members'].items()}
            quality=json.loads((base/'research/batches'/result['batchId']/'quality.json').read_text())
            report['qualityCoverage']=quality['coverage']
            report['staleDatasets']=[d for d,c in quality['coverage'].items() if c['last'] is None or through-date.fromisoformat(c['last'][:10]) >= timedelta(days=setting('daily-max-lag-days',1))]
            if report['staleDatasets']: report['state']='REQUIRES_ATTENTION'

        except Exception as error:
            report['state']='REQUIRES_ATTENTION'
            report['error']=type(error).__name__+': '+str(error)
            report['errorDetails']=getattr(error,'diagnostics',{})
            report['errorFrames']=[{'file':Path(f.filename).name,'function':f.name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)]
            report['queue']=queue_summary(base)
        finally:
            report['finishedAt']=datetime.now(UTC).isoformat()
            report['currentBatch']=json.loads(pointer.read_text()).get('batchId') if pointer.is_file() else None
            btc._atomic_json(target,report)
            btc._atomic_json(logs/'latest.json',report)
            try:
                btc._atomic_json(base/'daily-observations.json',observations(base))
            except Exception as observation_error:
                # 数据批次不因观察报表失败回滚，但旧的绿色观察结论必须失效。
                report['observationError']=type(observation_error).__name__+': '+str(observation_error)
                btc._atomic_json(base/'daily-observations.json',{'ready':False,'error':report['observationError']})
                btc._atomic_json(target,report)
                btc._atomic_json(logs/'latest.json',report)
            retention=setting('daily-log-retention-days',30)
            cutoff=datetime.now(UTC).timestamp()-retention*86400
            for old in logs.glob('*.log'):
                if old.stat().st_mtime<cutoff: old.unlink()
        return report


def backup(destination):
    """拿原进程文件锁后备份；SQLite 用 backup API，不复制活动主文件丢 WAL。"""
    base=btc.configured_writer_root()
    if base is None or not base.is_dir(): raise ValueError('原数据目录不存在')
    base=base.resolve(); destination=destination.resolve()
    if destination.exists() or destination.is_relative_to(base) or base.is_relative_to(destination):
        raise ValueError('备份必须使用原根目录之外的新目录')
    with ExitStack() as stack:
        for name, flock in [('daily.lock',True),('collector.lock',False),('binance-vision/archive-queue.lock',False),('ledger-writer.lock',False),('api-budget.sqlite3.lock',False),('research/.publish.lock',True)]:
            path=base/name; path.parent.mkdir(parents=True,exist_ok=True)
            stream=stack.enter_context(path.open('a+b'))
            (fcntl.flock if flock else fcntl.lockf)(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        pending=destination.with_name(destination.name+'.'+uuid.uuid4().hex+'.pending')
        pending.mkdir(parents=True)
        try:
            files={}
            for source in sorted(base.rglob('*')):
                if source.is_symlink(): raise ValueError('备份不接受符号链接')
                if not source.is_file() or source.name.endswith(('-wal','-shm','.lock')): continue
                relative=source.relative_to(base); target=pending/relative; target.parent.mkdir(parents=True,exist_ok=True)
                if source.suffix in ('.sqlite3','.sqlite'):
                    with closing(sqlite3.connect(source.as_uri()+'?mode=ro',uri=True)) as original, closing(sqlite3.connect(target)) as copied:
                        original.backup(copied)
                        if copied.execute('PRAGMA integrity_check').fetchone()[0]!='ok': raise ValueError('SQLite 备份损坏')
                else: shutil.copyfile(source,target)
                with target.open('rb') as stream:
                    os.fsync(stream.fileno())
                files[str(relative)]=btc._digest(target)
            btc._atomic_json(pending/'backup-manifest.json',{'schemaVersion':1,'originalRoot':str(base),'createdAt':datetime.now(UTC).isoformat(),'files':files,'restoreReadOnly':True})
            # 引用与 SQLite 都在临时副本核验后才发布，不能把坏副本命名为完成。
            verify_backup(pending)
            for directory in sorted((p for p in pending.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
                btc._sync_directory(directory)
            btc._sync_directory(pending)
            pending.replace(destination)
            btc._sync_directory(destination.parent)
        except Exception:
            shutil.rmtree(pending,ignore_errors=True);raise
    return verify_backup(destination)


def verify_backup(destination):
    manifest=json.loads((destination/'backup-manifest.json').read_text())
    if manifest.get('schemaVersion')!=1:raise ValueError('未知备份版本')
    original=Path(manifest['originalRoot'])
    def original_copy(name):
        path=Path(name)
        if not path.is_absolute():
            path=btc.ROOT/path
        path=path.resolve()
        if not path.is_relative_to(original):
            raise ValueError('备份引用越出原根目录')
        mapped=btc._safe_path(destination,str(path.relative_to(original)))
        if not mapped.is_file():raise ValueError('备份缺少原件引用：'+str(path.relative_to(original)))
        return mapped
    for name,digest in manifest['files'].items():
        path=btc._safe_path(destination,name)
        if btc._digest(path)!=digest:raise ValueError('备份文件摘要不符：'+name)
        if path.suffix in ('.sqlite','.sqlite3'):
            with closing(sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)) as db:
                if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('SQLite 完整性失败')
                tables={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if 'review_reports' in tables:
                    for relative,digest in db.execute('SELECT path,sha256 FROM review_reports'):
                        report=btc._safe_path(path.parent,relative)
                        if not report.is_file() or btc._digest(report)!=digest:raise ValueError('备份修订报告缺失或摘要不符')
                if 'funding_api_tails' in tables:
                    for raw,digest in db.execute('SELECT raw,sha FROM funding_api_tails WHERE raw IS NOT NULL'):
                        if btc._digest(original_copy(raw))!=digest:raise ValueError('备份 API 原响应摘要不符')
                if 'archive_revision_runs' in tables:
                    for (bundle,) in db.execute('SELECT bundle FROM archive_revision_runs'):
                        original_copy(bundle)

    def check_references(value):
        if isinstance(value,dict):
            for key,item in value.items():
                if key in ('raw','prepared','checksum','bundle') and isinstance(item,str) and Path(item).is_absolute():
                    reference=Path(item).resolve()
                    if reference.is_relative_to(original):
                        mapped=btc._safe_path(destination,str(reference.relative_to(original)))
                        if not mapped.exists():raise ValueError('备份缺少原件引用：'+str(reference.relative_to(original)))
                check_references(item)
        elif isinstance(value,list):
            for item in value:check_references(item)
    for name in manifest['files']:
        if name.endswith('.json'):
            check_references(json.loads(btc._safe_path(destination,name).read_text()))
    # 原件中的绝对路径继续指向原根，不能把副本直接作为第二个 writer 启动。
    return {'files':len(manifest['files']),'verified':True,'restoreReadOnly':True,'originalReferencesPreserved':True}




def schedule_settings():
    start=int(btc.configured_crypto_value('schedule-start-utc-hour',1))
    interval=setting('schedule-retry-hours',3)
    attempts=setting('schedule-max-attempts',8)
    if not 0 <= start <= 23 or start+interval*(attempts-1)>23:
        raise ValueError('日更排程必须在同一 UTC 日内，且最多二十四个时刻')
    return {'startUtcHour':start,'retryHours':interval,'maxAttempts':attempts,
            'utcHours':[start+interval*i for i in range(attempts)]}


def scheduled(binary, now=None):
    """同一真实 UTC 日有界续跑；重启仍从原报告计数，不复制队列或重置预算。"""
    now=now or datetime.now(UTC)
    if now.tzinfo is None:raise ValueError('排程时刻必须带偏移量')
    now=now.astimezone(UTC)
    policy=schedule_settings()
    base=btc.configured_writer_root()
    if base is None:raise ValueError('缺 app.crypto-data.root')
    base.mkdir(parents=True,exist_ok=True)
    through=now.date()-timedelta(days=1)
    with (base/'schedule.lock').open('a+b') as lock:
        try:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            return {'state':'ALREADY_RUNNING','through':str(through)}
        if now.hour<policy['startUtcHour']:
            return {'state':'NOT_DUE','through':str(through)}
        # 未完成意图也消耗本日一次调度尝试；进程崩溃不能让计数归零。
        records=[]
        for path in (base/'daily-runs').glob(now.strftime('%Y%m%d')+'*.json'):
            record=json.loads(path.read_text())
            if record.get('trigger')=='scheduled':
                if record.get('through')!=str(through):raise ValueError('本日调度记录的冻结截止日不符')
                records.append(record)
        records.sort(key=lambda r:r['startedAt'])
        if records:
            last=records[-1]
            if last.get('state') in ('AVAILABLE','PARTIAL_AVAILABLE') and last.get('dataVerified') is True:
                pointer=base/'research/current.json'
                if pointer.is_file() and json.loads(pointer.read_text())['batchId']==last.get('currentBatch'):
                    btc.require_no_pending_revisions(base)
                    return {'state':'ALREADY_AVAILABLE','through':str(through),'batch':last['batch']}
            if len(records)>=policy['maxAttempts']:
                return {'state':'RETRY_LIMIT','through':str(through),'attempts':len(records)}
            if now-datetime.fromisoformat(last['startedAt'])<timedelta(hours=policy['retryHours']):
                return {'state':'NOT_DUE','through':str(through),'attempts':len(records)}
        try:
            return daily(through,binary,trigger='scheduled')
        except BlockingIOError:
            return {'state':'ALREADY_RUNNING','through':str(through)}


def observations(base, now=None, required_days=7):
    """按真实运行日验收连续周期；同一天重跑和历史补跑都不能凑成七天。"""
    now = now or datetime.now(UTC)
    if now.tzinfo is None:raise ValueError('观察时刻必须带偏移量')
    now = now.astimezone(UTC)
    today = now.date()
    if required_days <= 0:
        raise ValueError("观察周期必须大于零")
    expected = {today - timedelta(days=i) for i in range(1, required_days+1)}
    accepted, rejected, attempts = {}, [], {}
    for path in sorted((base/'daily-runs').glob('*.json')):
        if path.name == 'latest.json':
            continue
        record = json.loads(path.read_text())
        started = datetime.fromisoformat(record['startedAt'])
        day = date.fromisoformat(record['through'])
        if day not in expected:
            continue
        attempts[str(day)] = attempts.get(str(day), 0)+1
        reason = None
        if record.get('observationProtocol') != 1:
            reason = '缺少新版日更验收标记'
        elif started.tzinfo is None or not record.get('finishedAt') or datetime.fromisoformat(record['finishedAt']).tzinfo is None or not (started <= datetime.fromisoformat(record['finishedAt']) <= now):
            reason = '运行未完成或完成时刻不可信'
        elif day != started.astimezone(UTC).date()-timedelta(days=1):
            reason = '历史补跑不计作日更观察'
        elif record.get('state') not in ('AVAILABLE','PARTIAL_AVAILABLE') or record.get('dataVerified') is not True or record.get('revisionGuardClear') is not True:
            reason = '来源、认证或修订检查未通过'
        else:
            try:
                batch = record['batch']['batchId']
                manifest = json.loads((base/'research/batches'/batch/'manifest.json').read_text())
                if set(manifest['members']) != set(btc.DATASETS) or manifest['ruleVersion'] < 3 or manifest['until'] != datetime.combine(day+timedelta(days=1),datetime.min.time(),UTC).isoformat():
                    raise ValueError('批次范围、成员或质量规则不符')
                for dataset in btc.DATASETS:
                    result = btc.read_batch(base/'research',dataset,batch_id=batch,mode='explore')
                    if not result['formalVerified']:
                        raise ValueError('批次缺少正式认证')
                accepted[str(day)] = {'runId':record['runId'],'batchId':batch,'reportSha256':btc._digest(path)}
            except (KeyError, OSError, ValueError) as error:
                reason = '批次原件核验失败：'+str(error)
        if reason:
            rejected.append({'runId':record.get('runId'),'through':str(day),'reason':reason})
    current_batch=None
    current_error=None
    current_valid=False
    try:
        current_batch=json.loads((base/'research/current.json').read_text())['batchId']
        manifest=json.loads((base/'research/batches'/current_batch/'manifest.json').read_text())
        if manifest['until']!=datetime.combine(today,datetime.min.time(),UTC).isoformat() or manifest.get('dataVerified') is not True or manifest['ruleVersion']<3 or set(manifest['members'])!=set(btc.DATASETS):
            raise ValueError('当前批次尚未覆盖最近目标日或缺少新版认证')
        current_valid=all(btc.read_batch(base/'research',d,batch_id=current_batch,mode='explore')['formalVerified'] for d in btc.DATASETS)
        if not current_valid:raise ValueError('当前批次有成员缺少正式认证')
    except (OSError,KeyError,ValueError) as error:
        current_error=str(error)
    missing = sorted(str(day) for day in expected if str(day) not in accepted)
    pending = btc.pending_revisions(base)
    state = queue_summary(base)
    return {'schemaVersion':1,'observedAt':now.isoformat(),'requiredDays':required_days,
            'through':str(today-timedelta(days=1)),'acceptedCycles':accepted,'acceptedDays':len(accepted),
            'missingDates':missing,'attemptsByDate':attempts,'rejectedCycles':rejected,
            'pendingRevisions':pending,'unresolvedLocks':state['unresolvedLocks'],
            'currentBatch':current_batch,'currentBatchValid':current_valid,'currentError':current_error,
            'ready':current_valid and not missing and not pending and not state['unresolvedLocks']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['run','scheduled','backup','verify-backup','observations'])
    parser.add_argument('--through',type=date.fromisoformat)
    parser.add_argument('--binary',type=Path,default=btc.ROOT/'build/modules/markets/btc/data-app/_output/install/btc-data/bin/btc-data')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.command!='run' and args.through is not None:parser.error('--through 只适用于 run；scheduled 固定实际 UTC 前日')
    if args.command=='run':result=daily(args.through or datetime.now(UTC).date()-timedelta(days=1),args.binary.resolve())
    elif args.command=='scheduled':result=scheduled(args.binary.resolve())
    elif args.command=='observations':
        base=btc.configured_writer_root()
        if base is None:raise ValueError('缺 app.crypto-data.root')
        result=observations(base)
        btc._atomic_json(args.output or base/'daily-observations.json',result)
    else:
        if args.output is None:parser.error('备份操作必须指定 --output')
        result=backup(args.output) if args.command=='backup' else verify_backup(args.output.resolve())
    brief={k:result[k] for k in ('runId','state','through','dataVerified','batch','error','files','verified','restoreReadOnly','ready','acceptedDays','missingDates') if k in result}
    print(json.dumps(brief,ensure_ascii=False))
    if result.get('state') in ('REQUIRES_ATTENTION','UNVERIFIED','RETRY_LIMIT') or result.get('observationError') or args.command=='observations' and not result['ready']:raise SystemExit(2)


if __name__=='__main__':main()
