"""Record actual known JVM completion and scoped SQLite state; no DB publications."""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).absolute().parent.parent
DIRECTORY=ROOT/'artifacts/java-migration/D104/commands'
LEDGER=ROOT/'var/d104-java-acceptance.sqlite3'


def binding(path):
    return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}


def save(path,data):
    with path.open('x',encoding='utf-8',newline='\n') as stream:
        stream.write(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    return binding(path)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--stage',choices=('initial','increment'),required=True)
    parser.add_argument('--session-id',type=int)
    parser.add_argument('--chunk-id',required=True)
    parser.add_argument('--exit-code',type=int,required=True)
    args=parser.parse_args()
    assert args.exit_code==0
    receipt_path=DIRECTORY/f'java-{args.stage}-acceptance-20261007.json'
    receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
    assert receipt['status']==('VERIFIED_ISOLATED_INITIAL_REPLAY' if args.stage=='initial' else 'VERIFIED_ISOLATED_INCREMENTAL')
    assert receipt['formal_mutated'] is False and receipt['reference_project_mutated'] is False
    assert receipt['double_tolerance']==0
    assert receipt['actual_target']['rowCount']==(2 if args.stage=='initial' else 3)
    assert receipt['key_and_full_field_comparisons']==(18 if args.stage=='initial' else 27)
    assert receipt['nullable_double_slot_comparisons']==(16 if args.stage=='initial' else 24)
    assert receipt['exact_double_bit_comparisons']==(15 if args.stage=='initial' else 22)
    identity_path=Path(receipt['jvm_identity_evidence']['path'])
    assert binding(identity_path)==receipt['jvm_identity_evidence']
    identity=json.loads(identity_path.read_text(encoding='utf-8'))
    assert identity['jvm_pid']==receipt['jvm_pid'] and identity['jvm_birth_utc']==receipt['jvm_birth_utc']
    junit=DIRECTORY/f'java-{args.stage}-result-20261007/TEST-com.zoutrankil.data.config.MacroCoreMonthlyLiveAcceptanceTest.xml'
    xml=ET.parse(junit).getroot()
    assert xml.attrib['tests']=='1' and all(xml.attrib[key]=='0' for key in ('failures','errors','skipped'))
    observed=datetime.now(timezone.utc).isoformat()
    pid=receipt['jvm_pid']
    assert type(pid) is int and 0<pid<2**31
    script=f'''$ErrorActionPreference='Stop'; $taskMatches=@(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}' | ForEach-Object {{ [pscustomobject]@{{pid=$_.ProcessId;birth_utc=$_.CreationDate.ToUniversalTime().ToString('o');name=$_.Name;command=$_.CommandLine}} }}); @{{matches=$taskMatches}}|ConvertTo-Json -Depth 5 -Compress'''
    executable=Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
    native=subprocess.run([str(executable),'-NoProfile','-NonInteractive','-Command',script],capture_output=True,text=True,timeout=30,check=True)
    assert len(native.stdout)<65536
    matches=json.loads(native.stdout)['matches']
    original=datetime.fromisoformat(receipt['jvm_birth_utc'].replace('Z','+00:00'))
    assert not any(datetime.fromisoformat(row['birth_utc'].replace('Z','+00:00'))==original for row in matches),'Identified acceptance JVM still present'
    native_evidence=save(DIRECTORY/f'{args.stage}-java-native-stop-20261007.json',{
        'task_id':'D104','pid':pid,'birth_utc':receipt['jvm_birth_utc'],'observed_at':observed,
        'original_identity_present':False,'matches':matches,'jvm_identity_evidence':binding(identity_path)})
    orders={'runs':('sync_runs','id'),'entries':('sync_entries','id'),
            'events':('sync_events','entry_id,revision'),'groups':('sync_group_members','parent_run_id,ordinal'),
            'leases':('sync_interval_locks','id')}
    with closing(sqlite3.connect(LEDGER.as_uri()+'?mode=ro',uri=True,timeout=5)) as db:
        db.row_factory=sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        snapshot={}
        for name,(table,order) in orders.items():
            snapshot[name]=[dict(row) for row in db.execute(f'SELECT * FROM {table} ORDER BY {order} LIMIT 1001')]
            assert len(snapshot[name])<=1000
        assert not snapshot['leases']
        assert len(snapshot['runs'])==(8 if args.stage=='initial' else 9)
        assert all(row['state'] in ('VERIFIED','VERIFIED_EMPTY','CANCELLED') for row in snapshot['entries'])
        assert sum(row['job_id']=='group.prepared_writes' and row['target_id']=='group-control' for row in snapshot['runs'])==1
        for row in snapshot['runs']:
            assert row['job_id'] in ('data.macro_core_monthly','write.macro_core_monthly','group.prepared_writes')
            if row['job_id']!='group.prepared_writes':
                assert row['target_id']==receipt['actual_target']['targetId']
        actual_ids={row['id'] for row in snapshot['runs']}
        assert set(receipt['run_ids'])<=actual_ids
        if args.stage=='increment':
            historical=json.loads((DIRECTORY/'initial-java-terminal-ledger-20261007.json').read_text(encoding='utf-8'))
            for name in ('runs','entries','events','groups'):
                assert all(row in snapshot[name] for row in historical[name]),f'Original {name} mutated'
            snapshot['initial_operations_preserved']=True
        db.rollback()
    snapshot.update(task_id='D104',ledger_path=str(LEDGER),run_ids=sorted(actual_ids),observed_at=observed,
        all_entries_terminal=True,retained_leases=0,ddl_dml=0,ledger_mutated=False)
    terminal=save(DIRECTORY/f'{args.stage}-java-terminal-ledger-20261007.json',snapshot)
    executor=save(DIRECTORY/f'{args.stage}-java-executor-completion-20261007.json',{
        'task_id':'D104','origin':'Root transcription of actual exec/write_stdin completion',
        'session_id':args.session_id,'chunk_id':args.chunk_id,'exit_code':args.exit_code,
        'status':'COMPLETED','observed_at':observed,'log':binding(DIRECTORY/f'java-{args.stage}-live-20261007.log')})
    result=save(DIRECTORY/f'{args.stage}-java-process-and-ledger-review-20261007.json',{
        'task_id':'D104','stage':args.stage,'status':'VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER',
        'java_receipt':binding(receipt_path),'native':native_evidence,'executor_completion':executor,
        'junit':binding(junit),'ledger_terminal':terminal,'run_count':len(snapshot['runs']),
        'entry_count':len(snapshot['entries']),'event_count':len(snapshot['events']),
        'retained_leases':0,'source_writes':0,'output_writes':0,'formal_writes':0})
    print(json.dumps(result))


if __name__=='__main__':main()
