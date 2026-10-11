import json,re
from pathlib import Path
root=Path(__file__).resolve().parent
def parse(name):
    text=(root/name).read_text(encoding='utf-8-sig')
    records=[]
    for start in re.finditer(r'^\{',text,re.M):
        try:value,_=json.JSONDecoder().raw_decode(text[start.start():])
        except json.JSONDecodeError:continue
        if 'coverage' in value:records.append(value)
    assert len(records)==1,(name,len(records))
    return records[0]
record=parse('pool-coverage-record.log')
inspect=parse('pool-coverage-inspect.log')
assert record['coverage']==inspect['coverage']
assert len(record['coverage'])==20
assert all(c['physicalComplete'] and not c['sourceComplete'] for c in record['coverage'])
assert len(inspect['retryPlan']['sections'])==20
assert all(s['reason']=='partial_source' for s in inspect['retryPlan']['sections'])
result=dict(recordedSections=20,reopenedPhysicalSections=20,retrySections=20,retryReason='partial_source',
            fileSha256=record['receipt']['fileSha256'],automaticComputePlannerIntegrated=False)
(root/'pool-coverage-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
