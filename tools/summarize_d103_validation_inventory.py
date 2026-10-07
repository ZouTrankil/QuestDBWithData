"""Count unique passing methods from preserved D103 stage reports, not reruns."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET
from record_d103_initial_java_failure_readonly import binding, save

def main():
    directory=Path('artifacts/java-migration/D103/commands').resolve()
    stages=['java-unit-stored-units-result-20261006','java-empty-target-shared-result-20261006','java-year-month-json-result-20261006','java-initial-readonly-recovery-result-20261006','java-increment-result-20261006']
    methods={};reports=[]
    for stage in stages:
        for path in sorted((directory/stage).glob('*.xml')):
            root=ET.parse(path).getroot()
            assert root.attrib['failures']==root.attrib['errors']==root.attrib['skipped']=='0'
            proof=binding(path);reports.append(proof)
            for case in root.findall('testcase'):
                assert all(case.find(tag) is None for tag in ['failure','error','skipped'])
                key=(case.attrib['classname'],case.attrib['name'])
                entry=methods.setdefault(key,{'class':key[0],'method':key[1],'status':'PASS','passing_report_bindings':[]})
                entry['passing_report_bindings'].append(proof)
    live_classes={'com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest','com.zoutrankil.data.config.EquityStyleMonthlyInitialReadOnlyRecoveryTest'}
    pure=[entry for entry in methods.values() if entry['class'] not in live_classes]
    live=[entry for entry in methods.values() if entry['class'] in live_classes]
    assert len(methods)==125 and len(pure)==123 and len(live)==2
    python_log=directory/'python-all-d103-guards-20261006.log'
    log=python_log.read_text(encoding='utf-8-sig')
    assert 'Ran 122 tests' in log and '\nOK' in log and 'FAILED' not in log
    result={'task_id':'D103','status':'PASS_CURRENT_CODE_BOUNDED_VALIDATION_INVENTORY','unique_java_passing_methods':125,
        'unique_java_pure_methods':123,'d103_specific_java_pure_methods':96,'shared_java_pure_methods':27,'unique_java_live_methods':2,
        'python_unique_pure_guards':122,'java_reports':reports,'java_methods':list(methods.values()),'python_pure_report':binding(python_log),
        'preserved_original_failed_live':binding(directory/'java-initial-failed-result-20261006/TEST-com.zoutrankil.data.config.EquityStyleMonthlyLiveAcceptanceTest.xml'),
        'original_initial_stage_junit_status':'FAILED_FINAL_RECEIPT_SERIALIZATION; preserved, not reclassified',
        'live_stage_results':{'original_initial':'actual operations completed, final YearMonth receipt serialization failed','fresh_readonly_recovery':'PASS; no DDL/DML/ledger transitions','incremental':'PASS; actual July overlap plus August append'},
        'counts_do_not_sum_repeated_runs':True,'unbounded_or_full_acceptance':False,'formal_write_acceptance':False}
    print(json.dumps(save('validation-inventory-20261006.json',result)))

if __name__=='__main__':main()
