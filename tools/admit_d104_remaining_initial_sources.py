"""Root's file-only admission of the five never-submitted D104 sources."""
from datetime import datetime, timezone
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'artifacts/java-migration/D104/commands'

def binding(path):
    return {'path':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def main():
    partial_path=D/'coordinator-known-initial-cpi-readonly-review-20261007.json'
    assert binding(partial_path)['sha256']=='49b089dcf684c0da50aabcb2478ad2143f4eeadda506e4e8cb608f2ca0632604'
    partial=json.loads(partial_path.read_text(encoding='utf-8'))
    assert partial['status']=='VERIFIED_KNOWN_ACKNOWLEDGED_INITIAL_CPI_BY_READONLY_REVIEW'
    review_path=D/'coordinator-known-partial-continuation-static-review-20261007.json'
    review=json.loads(review_path.read_text(encoding='utf-8'))
    assert review['status']=='PASS' and not review['blockers']
    assert binding(review_path)['sha256']=='61d70a68126a94389da7288c91c8d43f6bb6d8c54042105044459beab1dab8a1'
    for item in review['bindings']:
        assert binding(Path(item['absolute_path']))['sha256']==item['sha256']
    for item in review['input_evidence_bindings_rehashed']:
        assert binding(Path(item['path']))==item
    script=ROOT/'tools/prepare_d104_macro_core_initial_after_known_partial.py'
    guards=ROOT/'tools/test_d104_known_partial_guards.py'
    assert binding(script)['sha256']=='d813f0363ad5dc0558001b2b9f8c6a4225187ddf1c8fe4520d6898feb7ffac32'
    assert binding(guards)['sha256']=='1f03292ebb7460e6e41e35522efa070f8e66777d56bfe4d2fe01f19bc241bb3e'
    log=D/'python-known-partial-guards-final-20261007.log'
    text=log.read_text(encoding='utf-8-sig')
    assert 'Ran 31 tests' in text and text.rstrip().endswith('OK')
    for key in ('failed_initial','preflight','startup','native_stop','executor_completion'):
        item=partial[key];assert binding(Path(item['path']))==item
    gate={'task_id':'D104','protocol_version':1,'decision':'accepted_for_remaining_five_source_initial_only',
        'checked_at':datetime.now(timezone.utc).isoformat(),'partial_audit':binding(partial_path),
        **{key:partial[key] for key in ('failed_initial','preflight','startup','native_stop','executor_completion','target')},
        'script':binding(script),'guards':binding(guards),'guards_log':binding(log),'guards_PASS':31,
        'guards_executor':{'chunk_id':'75e9b6','exit_code':0,'status':'COMPLETED'},'independent_static_review':binding(review_path),
        'new_DDL':5,'new_DML':5,'new_source_rows':21,'cpi_resubmissions':0,'formal_writes':0,'private_output_writes':0,
        'automatic_retry':False,'original_failed_evidence_preserved':True,'java_writes_admitted':False,'D105_admitted':False,
        'scope':'Only five never-submitted missing source tables in the exact attested D104 private instance. Two original CPI ACKs remain untouched. Original initial receipt stays FAILED.'}
    out=D/'coordinator-remaining-initial-source-admission-20261007.json'
    with out.open('x',encoding='utf-8',newline='\n') as stream:stream.write(json.dumps(gate,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(binding(out)))

if __name__=='__main__':main()
