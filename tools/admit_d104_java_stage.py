"""Create root's reviewable, file-only D104 Java acceptance admission."""
import argparse
from datetime import datetime,timezone
import hashlib,json,re
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'artifacts/java-migration/D104/commands'

def binding(path):
    return {'path':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def load(path):return json.loads(path.read_text(encoding='utf-8'))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--stage',choices=('initial','increment'),required=True)
    parser.add_argument('--implementation-review',type=Path,required=True)
    parser.add_argument('--acceptance-review',type=Path,required=True)
    args=parser.parse_args();initial=args.stage=='initial'
    fixture=D/('source-fixture-initial-after-known-partial-20261007.json' if initial else 'source-fixture-increment-20261007.json')
    review_path=D/('coordinator-complete-initial-source-readonly-review-20261007.json' if initial else 'coordinator-complete-increment-source-readonly-review-20261007.json')
    review=load(review_path)
    assert review['task_id']=='D104' and review['protocol_version']==1
    assert review['status']==('VERIFIED_COMPLETE_INITIAL_SOURCE_BY_INDEPENDENT_READONLY_REVIEW' if initial else 'VERIFIED_COMPLETE_INCREMENT_SOURCE_BY_INDEPENDENT_READONLY_REVIEW')
    assert review['initial_fixture' if initial else 'increment_fixture']==binding(fixture)
    data=load(fixture)
    assert data['status']==('VERIFIED_ISOLATED_SOURCE_INITIAL' if initial else 'VERIFIED_ISOLATED_SOURCE_INCREMENT')
    assert data['formal_mutated'] is False and data['automatic_retry'] is False
    assert data['formal_writes']==data['private_output_writes']==0
    for key in ('native_stop','executor_completion','preflight','startup'):
        assert binding(Path(review[key]['path']))==review[key]
    assert load(Path(review['native_stop']['path']))['original_identity_present'] is False
    assert load(Path(review['executor_completion']['path']))['exit_code']==0
    reviews=[]
    for path in (args.implementation_review,args.acceptance_review):
        evidence=load(path)
        assert evidence['task_id']=='D104' and evidence['status'] in ('PASS','VERIFIED_INDEPENDENT_STATIC_REVIEW')
        assert not evidence.get('blockers',[])
        for item in evidence.get('code_bindings',evidence.get('bindings',[])):
            target=Path(item.get('absolute_path',item['path']))
            if not target.is_absolute():target=ROOT/target
            assert binding(target)['sha256']==item['sha256'],target
        reviews.append(binding(path))
    archive=D/'java-pure-tests-final-20261007'
    xmls=sorted(archive.glob('TEST-*.xml'))
    assert len(xmls)==9
    xmls.append(D/'java-pure-tests-initial-failed-20261007/TEST-com.zoutrankil.data.service.MacroCoreMonthlyCatalogStartupTest.xml')
    names=set();cases=[]
    for path in xmls:
        suite=ET.parse(path).getroot()
        assert suite.attrib['failures']==suite.attrib['errors']==suite.attrib['skipped']=='0'
        for case in suite.findall('testcase'):
            name=(case.attrib['classname'],case.attrib['name']);assert name not in names
            names.add(name);cases.append({'class':name[0],'method':name[1],'junit':binding(path)})
    assert len(cases)==122
    catalog_path=D/'java-catalog-startup-final-20261007.json'
    catalog=load(catalog_path)
    assert catalog['dataset_count']==53 and catalog['job_count']==42
    assert catalog['database_connections']==0 and catalog['ledger_created'] is False
    harness=ROOT/'data-app/src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyLiveAcceptanceTest.java'
    literal=harness.read_text(encoding='utf-8').split('CODE_PATHS=List.of(',1)[1].split(');',1)[0]
    paths=re.findall(r'"((?:data-core/|data-app/|batch-app/)?src/(?:main|test)/java/[^"\r\n]+\.java)"',literal)
    assert len(paths)==len(set(paths))==28
    gate={'task_id':'D104','protocol_version':1,'stage':args.stage,
        'decision':'accepted_for_bounded_java_initial_materialization' if initial else 'accepted_for_bounded_java_incremental_materialization',
        'checked_at':datetime.now(timezone.utc).isoformat(),'source_fixture':binding(fixture),'source_review':binding(review_path),
        'preflight':review['preflight'],'startup':review['startup'],'target':review['target'],
        'target_table':'java_d104_macro_core_monthly_acceptance','bounded_java_write_rows':2,
        'expected_http_flushes':4 if initial else 1,'maximum_total_submitted_rows':7 if initial else 2,'missing_only_output_DDL':1 if initial else 0,
        'source_writes':0,'formal_writes':0,'automatic_retry':False,'reference_writes':0,
        'code_bindings':[binding(ROOT/path) for path in paths],'independent_reviews':reviews,
        'java_pure_unique_PASS':122,'pure_junit_bindings':[binding(path) for path in xmls],'catalog':binding(catalog_path),
        'pure_executor':{'session_id':25382,'chunk_id':'773946','exit_code':0},
        'catalog_executor':{'session_id':49615,'chunk_id':'be578d','exit_code':1,'scope':'Catalog one-test XML passed; separate source-test fixture failure preserved and corrected in the final 121-test run.'},
        'original_partial_initial_status':'FAILED_PRESERVED','formal_replacement_admitted':False,'FULL_admitted':False,'D105_admitted':False}
    if not initial:
        terminal=D/'initial-java-process-and-ledger-review-20261007.json'
        state=load(terminal);assert state['status']=='VERIFIED_KNOWN_JVM_STOP_AND_TERMINAL_LEDGER' and state['retained_leases']==0
        gate['initial_java_terminal']=binding(terminal)
    out=D/f'coordinator-java-{args.stage}-admission-20261007.json'
    with out.open('x',encoding='utf-8',newline='\n') as stream:stream.write(json.dumps(gate,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(binding(out)))

if __name__=='__main__':main()
