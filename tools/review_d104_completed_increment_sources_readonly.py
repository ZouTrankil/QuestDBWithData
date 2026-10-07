"""Root actual SELECT-only closure after five known D104 August source ACKs."""
import argparse,json,sys
from datetime import datetime,timezone
from pathlib import Path
sys.dont_write_bytecode=True
import prepare_d104_macro_core_increment_isolated as increment
base,audit=increment.base,increment.audit
continued=increment.continued
D=base.DIRECTORY

def binding(path):return {'path':str(Path(path).resolve()),'sha256':audit.digest(path)}
def save(name,value):
    path=D/name;audit.save_new(path,value);return binding(path)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--receipt-sha256',required=True)
    parser.add_argument('--session-id',type=int)
    parser.add_argument('--chunk-id',required=True)
    args=parser.parse_args()
    receipt=base.load(increment.OUTPUT,args.receipt_sha256)
    assert receipt['task_id']=='D104' and receipt['status']=='VERIFIED_ISOLATED_SOURCE_INCREMENT'
    assert receipt['script_sha256']==audit.digest(increment.__file__)
    for path,sha in receipt['helper_sha256'].items():assert audit.digest(path)==sha
    assert receipt['attempted_operations']==receipt['acknowledged_operations']==receipt['submitted_source_rows']==receipt['acknowledged_source_rows']==5
    assert receipt['formal_writes']==receipt['private_output_writes']==0 and receipt['automatic_retry'] is False
    assert receipt['formal_mutated'] is False and receipt['reference_project_mutated'] is False
    gate_item=receipt['increment_admission']
    parameters=argparse.Namespace(increment_admission=Path(gate_item['path']),increment_admission_sha256=gate_item['sha256'])
    gate,initial,java,terminal,historical,preflight,whole,initial_rows=increment.admission_inputs(parameters)
    assert [(op['table'],op['kind'],op['ack'],op['rows']) for op in receipt['operations']]==[
        (table,'DML','ACKNOWLEDGED',1) for table in increment.AUGUST_TABLES]
    response_proofs=[]
    for op in receipt['operations']:
        table=op['table'];path=increment.claim_path(table)
        assert Path(op['claim_path']).resolve()==path.resolve()
        claim=base.load(path,op['claim_sha256'])
        assert claim['ack']=='ACKNOWLEDGED' and claim['invocation_id']==receipt['invocation_id']
        assert claim['producer_identity']==receipt['producer_identity'] and claim['target']==receipt['private_target_attestation']
        assert claim['increment_admission']==gate_item and claim['initial_fixture']==gate['initial_fixture']
        assert claim['java_initial']==gate['java_initial'] and claim['terminal_review']==gate['terminal_review']
        model=preflight['original_models'][table]
        rows=[row for row in whole[table] if audit.month_key(row[model['timestamp_col']],True)=='202608']
        assert len(rows)==1
        sql=base.insert_sql(table,rows,model)
        assert claim['sql_sha256']==op['sql_sha256']==audit.transport.sha_bytes(sql.encode('utf-8'))
        assert claim['source_records_sha256']==audit.canonical_sha(rows)
        raw=op['raw_response'];raw_path=Path(raw['path'])
        assert raw_path.resolve()==path.with_suffix('.response.bin').resolve()
        assert raw==claim['raw_response'] and audit.digest(raw_path)==raw['sha256']
        body=raw_path.read_bytes();assert len(body)==raw['bytes'] and claim['http_status']==op['http_status']==200
        payload=json.loads(body.decode('utf-8'),object_pairs_hook=base.unique_json)
        base.validate_ack(payload,'DML',1)
        assert payload==claim['response']==op['response']
        response_proofs.extend([binding(path),binding(raw_path)])
    stop=continued.original_absent(receipt['producer_identity'])
    increment.current_java_absent(java)
    target=base.PrivateTarget(gate['target']['data_root'],gate['target']['pid'],gate['startup']['path'],gate['startup']['sha256'])
    assert target.verify()==receipt['private_target_attestation']==gate['target']
    ledger_before=increment.inspect_ledger();increment.validate_ledger(ledger_before,historical,java)
    assert ledger_before==receipt['java_ledger_after']==receipt['java_ledger_before']
    formal=private=None
    try:
        formal=audit.ReadOnlyPG();private=base.PrivateReader(target)
        formal_before=base.formal_complete(formal,preflight,whole)
        private_before={table:continued.private_state(private,table) for table in (*audit.SOURCES,*base.PROTECTED)}
        assert private_before==receipt['private_after']
        output_before=increment.output_frontier(private,java,preflight)
        assert output_before==receipt['outputs_before']==receipt['outputs_after']
        source_rows={};comparisons={};schemas={}
        for table in audit.SOURCES:
            schemas[table]=private.records('SELECT * FROM table_columns(%s)',(table,),cap=100)
            audit.validate_schema(schemas[table],private_before[table],preflight['original_models'][table])
            source_rows[table]=base.source_rows(private,table,preflight['original_models'][table])
            comparisons[table]=base.strict_compare(source_rows[table],whole[table],table,preflight['original_models'][table])
            assert source_rows[table]==receipt['actual_source_rows'][table]
        assert private_before['cn_gdp']==receipt['source_before']['cn_gdp']
        output_after=increment.output_frontier(private,java,preflight)
        formal_after=base.formal_complete(formal,preflight,whole)
        ledger_after=increment.inspect_ledger()
        private_after={table:continued.private_state(private,table) for table in private_before}
        assert private_before==private_after and output_before==output_after
        assert formal_before==formal_after==receipt['formal_before']==receipt['formal_after']
        assert ledger_before==ledger_after and target.verify()==gate['target']
        increment.current_java_absent(java)
        assert sum(len(rows) for rows in source_rows.values())==28
        assert sum(value['full_field_comparisons'] for value in comparisons.values())==412
    finally:
        if formal is not None:formal.close()
        if private is not None:private.close()
    native=save('increment-source-producer-native-stop-20261007.json',{'task_id':'D104',**stop})
    executor=save('increment-source-executor-completion-20261007.json',{
        'task_id':'D104','origin':'Root transcription of actual exec/write_stdin terminal output','session_id':args.session_id,
        'chunk_id':args.chunk_id,'exit_code':0,'status':'COMPLETED','log':binding(D/'source-fixture-increment-20261007.log')})
    nonnull=sum(isinstance(value,float) for rows in source_rows.values() for row in rows for value in row.values())
    review={'task_id':'D104','protocol_version':1,'status':'VERIFIED_COMPLETE_INCREMENT_SOURCE_BY_INDEPENDENT_READONLY_REVIEW',
        'checked_at':datetime.now(timezone.utc).isoformat(),'increment_fixture':binding(increment.OUTPUT),'initial_fixture':gate['initial_fixture'],
        'java_initial':gate['java_initial'],'initial_java_terminal':gate['terminal_review'],'source_increment_admission':gate_item,
        'preflight':gate['preflight'],'startup':gate['startup'],'target':gate['target'],'native_stop':native,'executor_completion':executor,
        'private_before':private_before,'private_after':private_after,'formal_before':formal_before,'formal_after':formal_after,
        'actual_source_rows':source_rows,'comparisons':comparisons,'schemas':schemas,'source_rows':28,'full_field_comparisons':412,
        'nullable_double_slots':383,'non_null_double_bits':nonnull,'source_INSERT_ACKs':5,'source_new_rows':5,'GDP_INSERT_rows':0,
        'output_before':output_before,'output_after':output_after,'java_ledger_before':ledger_before,'java_ledger_after':ledger_after,
        'original_ledger_unchanged':True,'retained_leases':0,'known_operations':receipt['operations'],'claim_raw_response_bindings':response_proofs,
        'DDL_DML_in_this_review':0,'formal_writes':0,'output_writes':0,'unknown_ack_admitted':False,'next_task_admitted':False}
    print(json.dumps(save('coordinator-complete-increment-source-readonly-review-20261007.json',review)))

if __name__=='__main__':main()
