"""Final known JVM and actual scoped ledger census; no database publication."""
from datetime import datetime, timezone
from contextlib import closing
import json
import sqlite3
import xml.etree.ElementTree as ET
import prepare_d103_equity_style_increment_isolated as fixture
from record_d103_initial_java_failure_readonly import binding, save

def main():
    directory=fixture.DIRECTORY
    receipt_path=directory/'java-increment-acceptance-20261006.json'
    receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
    original=json.loads((directory/'java-initial-readonly-recovery-20261006.json').read_text(encoding='utf-8'))
    assert receipt['status']=='VERIFIED_ISOLATED_INCREMENTAL' and receipt['key_and_full_field_comparisons']==90 and receipt['exact_double_bit_comparisons']==87
    assert receipt['actual_target']['rowCount']==3 and receipt['actual_target']['sequenceTxn']==5 and receipt['source']['rawRows']==48
    assert receipt['increment_plan']['request']['parameters']['checkpoint_reason']=='VERIFIED_PREFIX_APPEND'
    assert receipt['increment_plan']['request']['from']=='2026-07-01' and receipt['increment_plan']['request']['to']=='2026-08-01'
    junit=directory/'java-increment-result-20261006/TEST-com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest.xml'
    xml=ET.parse(junit).getroot()
    assert xml.attrib['tests']=='1' and all(xml.attrib[key]=='0' for key in ['failures','errors','skipped'])
    observed=datetime.now(timezone.utc).isoformat()
    process={'pid':receipt['jvm_pid'],'birth_utc':receipt['jvm_birth_utc'],'stopped_observed_at':observed}
    actual=fixture.current_java_identity(process)
    native=save('final-increment-java-native-stop-20261006.json',{
        'task_id':'D103','pid':process['pid'],'birth_utc':process['birth_utc'],'observed_at':observed,
        'original_identity_present':False,'matches':actual['current_matches'],'jvm_identity_evidence':receipt['jvm_identity_evidence']})
    ids=original['original_run_ids']+receipt['run_ids']
    ledger=fixture.inspect_ledger(fixture.LEDGER,ids,receipt['actual_target']['targetId'])
    with closing(sqlite3.connect(fixture.LEDGER.resolve().as_uri()+'?mode=ro',uri=True,timeout=5)) as db:
        db.row_factory=sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        for key,table,order in [('events','sync_events','entry_id,revision'),('groups','sync_group_members','parent_run_id,ordinal')]:
            ledger[key]=[dict(row) for row in db.execute(f'SELECT * FROM {table} ORDER BY {order} LIMIT 1001')]
            assert len(ledger[key])<=1000
    assert len(ledger['runs'])==9 and len(ledger['entries'])==21 and not ledger['leases']
    original_snapshot=original['ledger_snapshot']
    original_ids=set(original['original_run_ids'])
    for key in ['runs','entries','events','groups']:
        historic=original_snapshot[key]
        current=ledger[key]
        assert all(row in current for row in historic), 'Original '+key+' evidence changed'
    ledger.update(run_ids=ids,observed_at=observed,original_runs_preserved=True)
    terminal=save('final-java-terminal-ledger-20261006.json',ledger)
    executor=save('final-increment-java-executor-completion-20261006.json',{
        'task_id':'D103','origin':'Root transcription of actual exec/write_stdin tool result',
        'session_id':4159,'chunk_id':'9401bc','exit_code':0,'status':'COMPLETED','observed_at':observed,
        'log':binding(directory/'java-increment-live-20261006.log'),'build_result':'BUILD SUCCESSFUL in 28s'})
    print(json.dumps(save('final-java-process-and-ledger-review-20261006.json',{
        'task_id':'D103','status':'VERIFIED_KNOWN_INCREMENT_JVM_STOP_AND_ALL_SCOPED_LEDGER_TERMINAL',
        'increment_receipt':binding(receipt_path),'initial_readonly_receipt':binding(directory/'java-initial-readonly-recovery-20261006.json'),
        'native':native,'executor_completion':executor,'junit':binding(junit),'ledger_terminal':terminal,
        'run_count':9,'entry_count':21,'event_count':len(ledger['events']),'retained_leases':0,
        'original_initial_jvm_pid_birth_not_recorded':True,'original_initial_junit_status':'FAILED_FINAL_RECEIPT_SERIALIZATION',
        'formal_writes':0,'new_source_submissions':0,'new_output_publications':0,
        'scope':'READ-only terminal census after actual incremental success. Original failed test and source UNKNOWN ACK remain preserved; no native identity is invented.'})))

if __name__=='__main__':main()
