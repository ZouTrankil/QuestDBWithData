"""File-only unique test inventory with honest preserved historical failures."""
import ast,hashlib,json,re
from datetime import datetime,timezone
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'artifacts/java-migration/D104/commands'

def binding(path):return {'path':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def main():
    pure=sorted((D/'java-pure-tests-final-20261007').glob('TEST-*.xml'))
    assert len(pure)==9
    pure.append(D/'java-pure-tests-initial-failed-20261007/TEST-com.zoutrankil.data.service.MacroCoreMonthlyCatalogStartupTest.xml')
    live=[D/f'java-{stage}-result-20261007/TEST-com.zoutrankil.data.config.MacroCoreMonthlyLiveAcceptanceTest.xml' for stage in ('initial','increment')]
    unique=set();cases=[];pure_count=0;invocations=0
    for phase,paths in (('pure',pure),('live',live)):
        for path in paths:
            suite=ET.parse(path).getroot()
            assert all(suite.attrib[key]=='0' for key in ('failures','errors','skipped'))
            for case in suite.findall('testcase'):
                key=(case.attrib['classname'],case.attrib['name']);unique.add(key);invocations+=1
                if phase=='pure':pure_count+=1
                cases.append({'phase':phase,'class':key[0],'method':key[1],'report':binding(path)})
    assert pure_count==122 and len(unique)==123 and invocations==124
    python=[];python_total=0
    groups=[('test_d104_preflight_guards.py','python-preflight-guards-20261007.log',44),
            ('test_d104_fixture_guards.py','python-initial-fixture-guards-20261007.log',35),
            ('test_d104_known_partial_guards.py','python-known-partial-guards-final-20261007.log',31),
            ('test_d104_increment_guards.py','python-source-increment-guards-frozen-20261007.log',49)]
    for name,log_name,expected in groups:
        path=ROOT/'tools'/name;log=D/log_name
        tree=ast.parse(path.read_text(encoding='utf-8'))
        names=[node.name+'.'+child.name for node in tree.body if isinstance(node,ast.ClassDef)
               for child in node.body if isinstance(child,(ast.FunctionDef,ast.AsyncFunctionDef)) and child.name.startswith('test_')]
        text=log.read_text(encoding='utf-8-sig');matches=re.findall(r'Ran (\d+) tests',text)
        assert len(matches)==1 and int(matches[0])==len(names) and text.rstrip().endswith('OK')
        if expected is not None:assert len(names)==expected
        assert len(set(names))==len(names)
        python_total+=len(names)
        python.append({'guard_source':binding(path),'actual_log':binding(log),'unique_PASS':len(names),'methods':names})
    failed_xml=D/'java-pure-tests-initial-failed-20261007/TEST-com.zoutrankil.data.service.MacroCoreMonthlySourceTest.xml'
    failure=ET.parse(failed_xml).getroot();assert failure.attrib['failures']=='1'
    failed_fixture=D/'source-fixture-initial-20261007.json'
    retained=json.loads(failed_fixture.read_text(encoding='utf-8'))
    assert retained['status']=='FAILED' and retained['attempted_operations']==retained['acknowledged_operations']==2
    assert binding(failed_fixture)['sha256']=='516195048104e45c61ed0d7ff5796f32eca6d72dabc493c16b2a3f1e3667c534'
    result={'task_id':'D104','protocol_version':1,'status':'VERIFIED_CURRENT_UNIQUE_TEST_INVENTORY',
        'checked_at':datetime.now(timezone.utc).isoformat(),'java_unique_passing_methods':123,'java_pure_unique_methods':122,
        'java_live_unique_methods':1,'java_live_actual_stage_invocations':2,'java_PASS_invocations':124,
        'current_inventory_failures':0,'current_inventory_errors':0,'current_inventory_skips':0,
        'python_unique_pure_guards':python_total,'java_reports':[binding(path) for path in pure+live],'java_cases':cases,'python':python,
        'historical_failures_preserved':[
            {'kind':'INITIAL_PURE_TEST_FIXTURE_CAST_BEFORE_PRODUCTION_GUARD','evidence':binding(failed_xml),
             'log':binding(D/'java-pure-tests-initial-20261007.log'),
             'resolution':'Only the test fixture was corrected to call the production derive type guard; current full 121-method pure run passed. Catalog one-pass artifact from the initial run remains separate.'},
            {'kind':'KNOWN_CPI_ACKS_THEN_METADATA_COUNT_VISIBILITY_RACE','evidence':binding(failed_fixture),
             'resolution':'Original failed receipt stays FAILED, two known CPI ACKs independently read back and never resubmitted; separate reviewed continuation submitted five missing sources only.'}],
        'test_execution_owner':'root; XML and logs copied from actual exec/write_stdin completions',
        'independent_review_is_not_execution':True,'formal_writes':0,'FULL':0,'next_task_admitted':False}
    out=D/'validation-inventory-20261007.json'
    with out.open('x',encoding='utf-8',newline='\n') as stream:stream.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(binding(out)))

if __name__=='__main__':main()
