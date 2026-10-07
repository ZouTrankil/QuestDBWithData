"""Read-only admission audit for the known acknowledged partial initial preparation."""
from datetime import datetime, timezone
import json
from pathlib import Path
import prepare_d104_macro_core_isolated as fixture

DIRECTORY=fixture.DIRECTORY
FAILED=DIRECTORY/'source-fixture-initial-20261007.json'
FAILED_SHA='516195048104e45c61ed0d7ff5796f32eca6d72dabc493c16b2a3f1e3667c534'
SCRIPT_SHA='23389881d8c6b8401dfb8b482378f6cdbe911f79e0b72269e46dcc6573388922'


def binding(path):
    return {'path':str(path),'sha256':fixture.audit.digest(path)}


def save(name,data):
    path=DIRECTORY/name
    fixture.audit.save_new(path,data)
    return binding(path)


def main():
    assert fixture.audit.digest(fixture.__file__)==SCRIPT_SHA
    failed=fixture.load(FAILED,FAILED_SHA)
    assert failed['status']=='FAILED'
    assert failed['error']=={'type':'RuntimeError','message':'Null private txn requires actual empty proof'}
    assert failed['attempted_operations']==failed['acknowledged_operations']==2
    assert failed['submitted_source_rows']==failed['acknowledged_source_rows']==2
    assert failed['automatic_retry'] is False
    assert [(operation['table'],operation['kind'],operation['ack']) for operation in failed['operations']]==[
        ('cn_cpi','DDL','ACKNOWLEDGED'),('cn_cpi','DML','ACKNOWLEDGED')]
    proofs=[]
    for operation in failed['operations']:
        claim_path=Path(operation['claim_path'])
        claim=fixture.load(claim_path,operation['claim_sha256'])
        assert claim['ack']=='ACKNOWLEDGED' and claim['producer_identity']==failed['producer_identity']
        raw=operation['raw_response']; assert fixture.audit.digest(raw['path'])==raw['sha256']
        body=Path(raw['path']).read_bytes()
        assert len(body)==raw['bytes']
        payload=json.loads(body,object_pairs_hook=fixture.unique_json)
        fixture.validate_ack(payload,operation['kind'],operation['rows'])
        assert payload==claim['response']==operation['response']
        proofs.extend([binding(claim_path),raw])
    observed=datetime.now(timezone.utc).isoformat()
    pid=failed['producer_identity']['pid']
    assert type(pid) is int and pid>0
    native=fixture.native_query(f"$ErrorActionPreference='Stop'; $taskMatches=@(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}' | ForEach-Object {{ [pscustomobject]@{{pid=$_.ProcessId;birth_utc=$_.CreationDate.ToUniversalTime().ToString('o');name=$_.Name}} }}); @{{matches=$taskMatches}}|ConvertTo-Json -Depth 4 -Compress")
    original=fixture.native.normalize_birth(failed['producer_identity']['birth_utc'])
    assert not any(fixture.native.normalize_birth(row['birth_utc'])==original for row in native['matches'])
    stop=save('known-initial-source-producer-native-stop-20261007.json',{
        'task_id':'D104','producer_identity':failed['producer_identity'],'original_identity_present':False,
        'matches':native['matches'],'observed_at':observed})
    executor=save('known-initial-source-executor-completion-20261007.json',{
        'task_id':'D104','origin':'Root transcription of actual exec/write_stdin terminal output',
        'session_id':41796,'chunk_id':'0c35a3','exit_code':1,'status':'COMPLETED',
        'log':binding(DIRECTORY/'source-fixture-initial-20261007.log'),'observed_at':observed})
    preflight=fixture.load(failed['preflight_evidence']['path'],failed['preflight_evidence']['sha256'])
    fixture.validate_preflight(preflight)
    whole=fixture.captured_sources(preflight)
    initial=fixture.initial_sources(whole,preflight['original_models'])
    startup=failed['startup_evidence']
    target=fixture.PrivateTarget(failed['private_target_attestation']['data_root'],
        failed['private_target_attestation']['pid'],startup['path'],startup['sha256'])
    target_before=target.verify()
    formal=private=None
    try:
        formal=fixture.audit.ReadOnlyPG()
        private=fixture.PrivateReader(target)
        formal_before=fixture.formal_complete(formal,preflight,whole)
        private_before={table:fixture.private_state(private,table) for table in (*fixture.audit.SOURCES,*fixture.PROTECTED)}
        assert private_before['cn_cpi']['exists'] and private_before['cn_cpi']['settled']
        assert private_before['cn_cpi']['actual_select_count']==2
        assert all(not private_before[table]['exists'] for table in (*fixture.audit.SOURCES[1:],*fixture.PROTECTED))
        schema=private.records("SELECT * FROM table_columns('cn_cpi')",cap=100)
        fixture.audit.validate_schema(schema,private_before['cn_cpi'],preflight['original_models']['cn_cpi'])
        rows=fixture.source_rows(private,'cn_cpi',preflight['original_models']['cn_cpi'])
        comparison=fixture.strict_compare(rows,initial['cn_cpi'],'cn_cpi',preflight['original_models']['cn_cpi'])
        assert comparison['full_field_comparisons']==26
        private_after={table:fixture.private_state(private,table) for table in private_before}
        formal_after=fixture.formal_complete(formal,preflight,whole)
        assert private_before==private_after and formal_before==formal_after==failed['formal_before']
        assert target_before==target.verify()==failed['private_target_attestation']
        assert not (fixture.REPO/'var/d104-java-acceptance.sqlite3').exists()
        for table in fixture.audit.SOURCES[1:]:
            for kind in ('DDL','DML'):assert not fixture.claim_path(kind,table).exists()
    finally:
        if formal is not None:formal.close()
        if private is not None:private.close()
    result={
        'task_id':'D104','protocol_version':1,'status':'VERIFIED_KNOWN_ACKNOWLEDGED_INITIAL_CPI_BY_READONLY_REVIEW',
        'decision':'accepted_for_remaining_five_missing_source_preparation',
        'checked_at':observed,'failed_initial':binding(FAILED),'original_initial_status':'FAILED_PRESERVED',
        'original_known_ack_operations':failed['operations'],'original_producer':failed['producer_identity'],
        'native_stop':stop,'executor_completion':executor,'preflight':failed['preflight_evidence'],
        'startup':startup,'target':target_before,'private_before':private_before,'private_after':private_after,
        'formal_before':formal_before,'formal_after':formal_after,'existing_cpi_rows':rows,
        'existing_cpi_comparison':comparison,'original_claim_and_raw_response_bindings':proofs,
        'remaining_tables':list(fixture.audit.SOURCES[1:]),'new_allowed_DDL':5,'new_allowed_DML':5,
        'new_allowed_source_rows':21,'cpi_resubmissions':0,'output_writes':0,'formal_writes':0,
        'retry_forbidden':True,'readonly_audit_DDL_DML':0,'unknown_ack_admitted':False,
        'scope':'The original two synchronous operations had durable known ACKs and exact actual CPI readback. Original FAILED receipt remains immutable. Only never-submitted missing five sources may be newly prepared after review of a separate tool.'}
    print(json.dumps(save('coordinator-known-initial-cpi-readonly-review-20261007.json',result)))


if __name__=='__main__':main()
