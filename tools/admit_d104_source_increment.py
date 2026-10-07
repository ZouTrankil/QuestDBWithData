"""Root file-only admission for five August source INSERTs after Java terminal proof."""
from datetime import datetime,timezone
import hashlib,json
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'artifacts/java-migration/D104/commands'

def binding(path):return {'path':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
def load(path):return json.loads(path.read_text(encoding='utf-8'))

def main():
    initial_path=D/'source-fixture-initial-after-known-partial-20261007.json'
    java_path=D/'java-initial-acceptance-20261007.json'
    terminal_path=D/'initial-java-process-and-ledger-review-20261007.json'
    initial,java,terminal=map(load,(initial_path,java_path,terminal_path))
    assert binding(initial_path)['sha256']=='050cd029e0ec639bd1e11dcd2b8d4b8e5336ee45554abae22b430c075de8f006'
    assert binding(java_path)['sha256']=='2ef5ded567bf02de76170516e0acd4fedd3a4b3242133a79bafac4986bc559b1'
    assert binding(terminal_path)['sha256']=='738744cd9f782ae0f47faa1a76064cc80b8fa7772aebb3f173fcd0798831f1e2'
    assert initial['status']=='VERIFIED_ISOLATED_SOURCE_INITIAL' and java['status']=='VERIFIED_ISOLATED_INITIAL_REPLAY'
    assert terminal['status']=='VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER'
    assert terminal['retained_leases']==0 and terminal['run_count']==8 and terminal['entry_count']==18
    assert terminal['java_receipt']==binding(java_path)
    for key in ('native','executor_completion','junit','ledger_terminal'):
        assert binding(Path(terminal[key]['path']))==terminal[key]
    assert load(Path(terminal['native']['path']))['original_identity_present'] is False
    assert load(Path(terminal['executor_completion']['path']))['exit_code']==0
    assert java['actual_target']['rowCount']==2 and java['key_and_full_field_comparisons']==18
    assert java['nullable_double_slot_comparisons']==16 and java['exact_double_bit_comparisons']==15
    review_path=D/'coordinator-source-increment-static-review-20261007.json'
    review=load(review_path)
    assert review['task_id']=='D104' and review['status']=='PASS' and not review['blockers']
    for item in review.get('bindings',review.get('code_bindings',[])):
        path=Path(item.get('absolute_path',item['path']))
        if not path.is_absolute():path=ROOT/path
        assert binding(path)['sha256']==item['sha256']
    script=ROOT/'tools/prepare_d104_macro_core_increment_isolated.py'
    guards=ROOT/'tools/test_d104_increment_guards.py'
    log=D/'python-source-increment-guards-frozen-20261007.log'
    log_text=log.read_text(encoding='utf-8-sig')
    assert log_text.rstrip().endswith('OK')
    gate={'task_id':'D104','protocol_version':1,'decision':'accepted_for_bounded_source_increment',
        'checked_at':datetime.now(timezone.utc).isoformat(),'initial_fixture':binding(initial_path),'java_initial':binding(java_path),
        'terminal_review':binding(terminal_path),'preflight':initial['preflight_evidence'],'startup':initial['startup_evidence'],
        'target':initial['private_target_attestation'],'source_month':'202608','source_rows':5,'source_INSERT_attempts':5,
        'new_DDL':0,'private_output_writes':0,'formal_writes':0,'automatic_retry':False,'script':binding(script),
        'guards':binding(guards),'guard_log':binding(log),'independent_static_review':binding(review_path),
        'source_tables':['cn_cpi','cn_ppi','cn_pmi','cn_m','sf_month'],'GDP_INSERT_rows':0,
        'private_output_expected_rows':2,'sqlite_tables_unchanged_required':5,'original_initial_status':'FAILED_PRESERVED',
        'java_incremental_writes_admitted':False,'D105_admitted':False,
        'scope':'Only one actual captured August observation in each five monthly sources; retain initial whole source prefix, two target months, all ledger rows and the original failed CPI-preparation evidence.'}
    out=D/'coordinator-source-increment-admission-20261007.json'
    with out.open('x',encoding='utf-8',newline='\n') as stream:stream.write(json.dumps(gate,ensure_ascii=False,indent=2)+'\n')
    import prepare_d104_macro_core_increment_isolated as increment
    admitted=increment.admission_inputs(SimpleNamespace(increment_admission=out,increment_admission_sha256=binding(out)['sha256']))
    assert admitted[0]==gate
    print(json.dumps({**binding(out),'consumer_file_only_protocol_validation':'PASS'}))

if __name__=='__main__':main()
