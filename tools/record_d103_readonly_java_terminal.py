"""Bind the observed successful READ-only JVM completion and actual terminal ledger."""
from datetime import datetime, timezone
import json
import xml.etree.ElementTree as ET
import prepare_d103_equity_style_increment_after_readonly_recovery as protocol
from record_d103_initial_java_failure_readonly import binding, save

def main():
    directory=protocol.DIRECTORY
    receipt_path=directory/'java-initial-readonly-recovery-20261006.json'
    receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
    assert receipt['status']=='VERIFIED_ISOLATED_INITIAL_BY_READONLY_RECOVERY'
    source_path=directory/'source-fixture-initial-readonly-reconciliation-20261006.json'
    source=json.loads(source_path.read_text(encoding='utf-8'))
    failure_gate_path=directory/'coordinator-initial-java-receipt-failure-recovery-20261006.json'
    junit=directory/'java-initial-readonly-recovery-result-20261006/TEST-com.zoutrankil.data.config.EquityStyleMonthlyInitialReadOnlyRecoveryTest.xml'
    xml=ET.parse(junit).getroot()
    assert xml.attrib['tests']=='1' and all(xml.attrib[key]=='0' for key in ['failures','errors','skipped'])
    observed=datetime.now(timezone.utc).isoformat()
    process={'pid':receipt['jvm_pid'],'birth_utc':receipt['jvm_birth_utc'],'stopped_observed_at':observed}
    actual=protocol.base.current_java_identity(process)
    assert actual['original_identity_present'] is False
    native=save('readonly-recovery-java-native-stop-20261006.json',{
        'task_id':'D103','pid':process['pid'],'birth_utc':process['birth_utc'],'observed_at':observed,
        'original_identity_present':False,'matches':actual['current_matches'],
        'scope':'Actual CIM query of the identified fresh readonly recovery JVM; does not invent original failed JVM identity.'})
    actual_ledger=protocol.inspect_original_ledger(receipt['original_run_ids'],receipt['actual_target']['targetId'])
    protocol.validate_original_operations(receipt,actual_ledger)
    actual_ledger.update({'run_ids':receipt['original_run_ids'],'observed_at':observed})
    terminal=save('readonly-recovery-java-terminal-ledger-20261006.json',actual_ledger)
    executor=save('readonly-recovery-java-executor-completion-20261006.json',{
        'task_id':'D103','origin':'Root transcription of actual exec/write_stdin completion',
        'session_id':20904,'chunk_id':'ba0d52','exit_code':0,'status':'COMPLETED','observed_at':observed,
        'log':binding(directory/'java-initial-readonly-recovery-live-20261006.log'),'build_result':'BUILD SUCCESSFUL in 24s'})
    gate={'protocol_version':2,'task_id':'D103','decision':'accepted_for_bounded_source_increment_after_readonly_recovery',
        'initial_recovery':binding(source_path),'java_readonly_recovery':binding(receipt_path),
        'original_initial_failure':binding(failure_gate_path),'target':source['private_target_attestation'],'bounded_source':source['held_increment_capture'],
        'java_process':{**process,'native_evidence':native},'executor_completion':executor,'junit':binding(junit),
        'ledger':{'path':str(protocol.LEDGER.resolve()),'run_ids':receipt['original_run_ids'],'terminal_evidence':terminal},
        'retry_forbidden':True,'checked_at':observed,
        'scope':'One separately admitted sixteen-row August source append; no output/formal DDL/DML. Original initial JUnit remains failed final serialization, original JVM identity unavailable, original source INSERT ACK UNKNOWN. Fresh READ-only recovery verifies all original actual operations and its known JVM has stopped.'}
    print(json.dumps(save('coordinator-increment-after-readonly-recovery-admission-20261006.json',gate)))

if __name__=='__main__':main()
