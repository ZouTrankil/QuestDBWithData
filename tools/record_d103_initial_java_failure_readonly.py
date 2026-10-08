"""Record this observed terminal report-serialization failure; no publication."""
import hashlib
import json
from pathlib import Path
import sqlite3
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'artifacts/java-migration/D103/commands'
LEDGER = ROOT / 'var/d103-java-acceptance.sqlite3'

def binding(path):
    path = Path(path).resolve(strict=True)
    return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def save(name, value):
    path = DIRECTORY / name
    with path.open('x',encoding='utf-8',newline='\n') as handle:
        json.dump(value,handle,ensure_ascii=False,indent=2)
        handle.flush()
        import os
        os.fsync(handle.fileno())
    return binding(path)

def main():
    failed_xml=DIRECTORY/'java-initial-failed-result-20261006/TEST-com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest.xml'
    suite=ET.parse(failed_xml).getroot()
    assert suite.attrib['tests']=='1' and suite.attrib['failures']=='1' and suite.attrib['errors']=='0' and suite.attrib['skipped']=='0'
    failure=suite.find('testcase/failure')
    assert failure is not None and 'InvalidDefinitionException' in failure.attrib['type']
    assert 'java.time.YearMonth' in failure.text and 'EquityStyleMonthlyLiveAcceptanceTest.java:177' in failure.text
    stdout=suite.find('system-out').text
    assert 'd103-private - Shutdown completed' in stdout and 'd103-formal - Shutdown completed' in stdout
    source=binding(DIRECTORY/'failed-initial-producer-java-20261006.txt')
    assert source['sha256']=='f103618ef3006e505c9aa26677f497370d62b1ff65ce597747c29a781a08858f'
    with sqlite3.connect(LEDGER.resolve().as_uri()+'?mode=ro',uri=True,timeout=5) as connection:
        connection.row_factory=sqlite3.Row
        connection.execute('PRAGMA query_only=ON')
        snapshot={}
        for key,table,order in [('runs','sync_runs','id'),('entries','sync_entries','id'),('events','sync_events','entry_id,revision'),('groups','sync_group_members','parent_run_id,ordinal'),('leases','sync_interval_locks','id')]:
            snapshot[key]=[dict(row) for row in connection.execute(f'SELECT * FROM {table} ORDER BY {order} LIMIT 1001')]
            assert len(snapshot[key])<=1000
    assert len(snapshot['runs'])==8 and len(snapshot['entries'])==18 and not snapshot['leases']
    assert all(row['state'] in {'VERIFIED','CANCELLED'} for row in snapshot['entries'])
    runs={row['id']:row for row in snapshot['runs']}
    entries={row['id']:row for row in snapshot['entries'] if row['kind']=='RUN'}
    assert set(runs)==set(entries)
    mode=lambda row:json.loads(row['frozen_json']).get('mode')
    standalone=[r for r in runs.values() if r['job_id']=='data.equity_style_monthly' and entries[r['id']]['state']=='VERIFIED' and mode(r)=='MATERIALIZE' and r['parent_run_id'] is None]
    assert len(standalone)==2
    standalone.sort(key=lambda r:entries[r['id']]['updated_at'])
    group=[r for r in runs.values() if r['job_id']=='group.prepared_writes'];assert len(group)==1
    child=[r for r in runs.values() if r['job_id']=='write.equity_style_monthly'];assert len(child)==1 and child[0]['parent_run_id']==group[0]['id']
    cancelled=[r for r in runs.values() if entries[r['id']]['state']=='CANCELLED'];assert len(cancelled)==2
    cancelled_resume=[r for r in cancelled if r['id'].startswith('d103-cancelled-resume-')];assert len(cancelled_resume)==1
    resume=[r for r in runs.values() if r['parent_run_id']==cancelled_resume[0]['id']];assert len(resume)==1
    assert resume[0]['frozen_json']==cancelled_resume[0]['frozen_json']
    reconcile=[r for r in runs.values() if mode(r)=='RECONCILE'];assert len(reconcile)==1
    other_cancel=[r for r in cancelled if r!=cancelled_resume[0]];assert len(other_cancel)==1
    roles=[('first',standalone[0]),('same_range_replay',standalone[1]),('cancelled_for_resume',cancelled_resume[0]),('exact_resume',resume[0]),('readonly_reconcile',reconcile[0]),('configured_write_group',group[0]),('typed_write_child',child[0]),('cancelled_before_source',other_cancel[0])]
    operations=[]
    for role,run in roles:
        entry=entries[run['id']];payload=json.loads(entry['payload_json'])
        rows=0 if entry['state']=='CANCELLED' else payload['verification']['expectedRows']
        assert rows==(0 if 'cancelled' in role else 1 if role in {'configured_write_group','typed_write_child'} else 2)
        operations.append({'role':role,'run_id':run['id'],'state':entry['state'],'verified_rows':rows,'mode':mode(run),'parent_run_id':run['parent_run_id'],'ledger_entry':entry})
    observed=datetime.now(timezone.utc).isoformat()
    snapshot.update({'ledger_path':str(LEDGER.resolve()),'run_ids':[op['run_id'] for op in operations], 'original_operations':operations,'all_entries_terminal':True,'retained_leases':0,'mode':'ro','query_only':True,'observed_at':observed})
    snapshot_proof=save('original-initial-java-terminal-ledger-20261006.json',snapshot)
    completion=save('original-initial-java-executor-completion-20261006.json',{
        'task_id':'D103','origin':'Root transcription of the actual exec/write_stdin tool result',
        'session_id':10320,'exit_code':1,'chunk_id':'f0be13','observed_at':observed,
        'status':'COMPLETED','test_result':'1 test completed, 1 failed; final receipt serialization at line177',
        'original_jvm_pid_birth_not_recorded':True})
    main_java=ROOT/'data-app/src/main/java/com/zoutrankil/data'
    gate={'protocol_version':1,'task_id':'D103','decision':'accepted_for_readonly_receipt_recovery','checked_at':observed,
        'failed_junit':binding(failed_xml),'log':binding(DIRECTORY/'java-initial-live-20261006.log'),
        'executed_test_source':source,'port_source':binding(main_java/'derived/storage/EquityStyleMonthlyWritePort.java'),
        'adapter_source':binding(main_java/'derived/application/EquityStyleMonthlyMaterializeAdapter.java'),
        'runner_source':binding(main_java/'service/SyncJobRunner.java'),
        'typed_adapter_source':binding(main_java/'service/PreparedWriteAdapter.java'),
        'original_executor_completion':completion,'ledger_snapshot':snapshot_proof,
        'create_claim':binding(DIRECTORY/'java-output-create-once-20261006.json'),
        'initial_source_recovery':binding(DIRECTORY/'source-fixture-initial-readonly-reconciliation-20261006.json'),
        'original_jvm_pid_birth_not_recorded':True,'retry_forbidden':True,
        'all_original_operations_finished_before_serialization_failure':True,
        'sender_ownership':{'synchronous_flush':True,'auto_flush_disabled':True,'retry_timeout_zero':True,'close_in_finally':True,'no_business_writer_child':True},
        'scope':'Only this completed synchronous initial test: original JUnit remains FAILED. No original PID/native absence is invented. Fresh Java SELECT-only recovery may revalidate original exact ledger/source/target; no resend, DDL, ledger transition or August admission.'}
    print(json.dumps(save('coordinator-initial-java-receipt-failure-recovery-20261006.json',gate)))

if __name__=='__main__':main()
