"""Independent actual read-only closure for the D104 known-partial continuation."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True
import prepare_d104_macro_core_initial_after_known_partial as continuation

base=continuation.base
audit=base.audit
DIRECTORY=base.DIRECTORY

def bound(path):
    return {'path':str(Path(path).resolve()),'sha256':audit.digest(path)}

def save(name,value):
    path=DIRECTORY/name
    audit.save_new(path,value)
    return bound(path)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--receipt-sha256',required=True)
    parser.add_argument('--session-id',type=int)
    parser.add_argument('--chunk-id',required=True)
    args=parser.parse_args()
    receipt=base.load(continuation.OUTPUT,args.receipt_sha256)
    assert receipt['task_id']=='D104' and receipt['status']=='VERIFIED_ISOLATED_SOURCE_INITIAL'
    assert receipt['script_sha256']==audit.digest(continuation.__file__)
    assert receipt['new_attempted_operations']==receipt['new_acknowledged_operations']==10
    assert receipt['new_submitted_source_rows']==receipt['new_acknowledged_source_rows']==21
    assert receipt['total_known_acknowledged_operations']==12
    assert receipt['cpi_resubmissions']==receipt['formal_writes']==receipt['private_output_writes']==0
    assert receipt['automatic_retry'] is False and receipt['formal_mutated'] is False
    assert receipt['reference_project_mutated'] is False and receipt['original_initial_status']=='FAILED_PRESERVED'
    for path,sha in receipt['helper_sha256'].items():assert audit.digest(path)==sha
    gate=base.load(receipt['partial_admission']['path'],receipt['partial_admission']['sha256'])
    fake=argparse.Namespace(failed_initial=Path(receipt['failed_initial']['path']),
        failed_initial_sha256=receipt['failed_initial']['sha256'],partial_admission=Path(receipt['partial_admission']['path']),
        partial_admission_sha256=receipt['partial_admission']['sha256'])
    _,partial,failed,preflight,whole,initial,old_proofs=continuation.admission_inputs(fake)
    assert receipt['original_known_ack_operations']==failed['operations']
    assert [(op['table'],op['kind'],op['ack']) for op in receipt['remaining_operations']]==[
        (table,kind,'ACKNOWLEDGED') for table in continuation.REMAINING for kind in ('DDL','DML')]
    new_proofs=[]
    for op in receipt['remaining_operations']:
        path=continuation.claim_path(op['kind'],op['table'])
        assert Path(op['claim_path']).resolve()==path.resolve()
        claim=base.load(path,op['claim_sha256'])
        assert claim['ack']=='ACKNOWLEDGED' and claim['producer_identity']==receipt['producer_identity']
        assert claim['target']==receipt['private_target_attestation'] and claim['partial_admission']==receipt['partial_admission']
        assert claim['invocation_id']==receipt['invocation_id'] and claim['failed_initial']==receipt['failed_initial']
        model=preflight['original_models'][op['table']]
        sql=base.create_sql(op['table'],model) if op['kind']=='DDL' else base.insert_sql(op['table'],initial[op['table']],model)
        assert claim['sql_sha256']==op['sql_sha256']==audit.transport.sha_bytes(sql.encode('utf-8'))
        assert claim['source_records_sha256']==(None if op['kind']=='DDL' else audit.canonical_sha(initial[op['table']]))
        raw=op['raw_response'];raw_path=Path(raw['path'])
        assert raw_path.resolve()==path.with_suffix('.response.bin').resolve()
        assert claim['raw_response']==raw and audit.digest(raw_path)==raw['sha256']
        body=raw_path.read_bytes();assert len(body)==raw['bytes'] and claim['http_status']==op['http_status']==200
        payload=json.loads(body.decode('utf-8'),object_pairs_hook=base.unique_json)
        base.validate_ack(payload,op['kind'],op['rows'])
        assert claim['response']==op['response']==payload
        new_proofs.extend([bound(path),bound(raw_path)])
    stop_data=continuation.original_absent(receipt['producer_identity'])
    target=base.PrivateTarget(receipt['private_target_attestation']['data_root'],receipt['private_target_attestation']['pid'],
        receipt['startup_evidence']['path'],receipt['startup_evidence']['sha256'])
    assert target.verify()==receipt['private_target_attestation']==gate['target']
    formal=private=None
    try:
        formal=audit.ReadOnlyPG();private=base.PrivateReader(target)
        formal_before=base.formal_complete(formal,preflight,whole)
        private_before={table:continuation.private_state(private,table) for table in (*audit.SOURCES,*base.PROTECTED)}
        assert private_before==receipt['private_after']
        assert all(not private_before[table]['exists'] for table in base.PROTECTED)
        assert not continuation.LEDGER.exists()
        rows={};comparisons={};schemas={}
        for table in audit.SOURCES:
            schemas[table]=private.records('SELECT * FROM table_columns(%s)',(table,),cap=100)
            audit.validate_schema(schemas[table],private_before[table],preflight['original_models'][table])
            rows[table]=base.source_rows(private,table,preflight['original_models'][table])
            comparisons[table]=base.strict_compare(rows[table],initial[table],table,preflight['original_models'][table])
            assert rows[table]==receipt['actual_source_rows'][table]
        private_after={table:continuation.private_state(private,table) for table in private_before}
        formal_after=base.formal_complete(formal,preflight,whole)
        assert private_before==private_after and formal_before==formal_after==receipt['formal_before']==receipt['formal_after']
        assert sum(len(value) for value in rows.values())==23
        assert sum(value['full_field_comparisons'] for value in comparisons.values())==294
        assert target.verify()==gate['target'] and not continuation.LEDGER.exists()
    finally:
        if formal is not None:formal.close()
        if private is not None:private.close()
    stopped=save('remaining-initial-source-producer-native-stop-20261007.json',{'task_id':'D104',**stop_data})
    executor=save('remaining-initial-source-executor-completion-20261007.json',{
        'task_id':'D104','origin':'Root transcription of actual exec/write_stdin terminal output',
        'session_id':args.session_id,'chunk_id':args.chunk_id,'exit_code':0,'status':'COMPLETED',
        'log':bound(DIRECTORY/'source-fixture-initial-after-known-partial-20261007.log')})
    actual_bits=sum(isinstance(value,float) for data in rows.values() for row in data for value in row.values())
    result={'task_id':'D104','protocol_version':1,'status':'VERIFIED_COMPLETE_INITIAL_SOURCE_BY_INDEPENDENT_READONLY_REVIEW',
        'checked_at':datetime.now(timezone.utc).isoformat(),'initial_fixture':bound(continuation.OUTPUT),
        'failed_initial':receipt['failed_initial'],'original_initial_status':'FAILED_PRESERVED',
        'partial_admission':receipt['partial_admission'],'partial_audit':receipt['partial_readonly_audit'],
        'native_stop':stopped,'executor_completion':executor,'preflight':receipt['preflight_evidence'],
        'startup':receipt['startup_evidence'],'target':gate['target'],'private_before':private_before,'private_after':private_after,
        'formal_before':formal_before,'formal_after':formal_after,'actual_source_rows':rows,'comparisons':comparisons,'schemas':schemas,
        'source_rows':23,'full_field_comparisons':294,'nullable_double_slots':270,'non_null_double_bits':actual_bits,
        'original_known_ack_operations':failed['operations'],'remaining_operations':receipt['remaining_operations'],
        'original_claim_and_raw_response_bindings':old_proofs,'new_claim_and_raw_response_bindings':new_proofs,
        'known_ACK_operations':12,'cpi_resubmissions':0,'DDL_DML_in_this_review':0,'formal_writes':0,'output_writes':0,
        'unknown_ack_admitted':False,'java_ledger_absent':True,'next_task_admitted':False}
    print(json.dumps(save('coordinator-complete-initial-source-readonly-review-20261007.json',result)))

if __name__=='__main__':main()
