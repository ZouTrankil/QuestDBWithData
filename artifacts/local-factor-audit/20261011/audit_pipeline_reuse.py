import json,hashlib,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(__file__).resolve().parent
receipts={p.parent.name:(p,json.loads(p.read_text())) for p in (root/'native-pipeline').glob('*/pipeline.json')}
reused=[value for value in receipts.values() if value[1].get('computationReused')];assert len(reused)==1
path,current=reused[0];previous_path,previous=receipts[current['reusedExecutionId']]
assert current['phase']=='PARTIAL' and not current['sourceComplete'] and not current['factorReady']
assert current['publication']['verifiedRows']==5571
for filename in ['observations.json','pools.json','compute.json']:
    assert (path.parent/filename).read_bytes()==(previous_path.parent/filename).read_bytes(),filename
assert hashlib.sha256((path.parent/'observations.json').read_bytes()).hexdigest()==current['observationEvidenceSha256']
physical=json.loads((root/'native-pipeline-reuse-physical.json').read_text())
previous_physical=json.loads((root/'native-pipeline-physical.json').read_text())
assert physical['state']==previous_physical['state']
assert physical['missingRows']==physical['mismatches']==physical['unexpectedRows']==0
assert physical['sourceStable'] and physical['walSettled']
tests=0
for name in ['com.zoutrankil.data.factor.FactorPipelineReuseTest','com.zoutrankil.data.cli.FactorPipelineCommandsTest','com.zoutrankil.data.factor.FactorPoolCoverageStoreTest']:
    suite=ET.parse(Path('data-app/build/test-results/test')/('TEST-'+name+'.xml')).getroot()
    assert suite.attrib['failures']==suite.attrib['errors']=='0';tests+=int(suite.attrib['tests'])
result=dict(executionId=current['executionId'],reusedExecutionId=current['reusedExecutionId'],
            computationReused=True,byteIdenticalFiles=3,physicalRowsVerified=5571,targetTransactionUnchanged=True,
            sourceComplete=False,factorReady=False,phase='PARTIAL',targetedTests=tests,
            scope='Exact-input reuse for the same date, factor selection and pools; historical dirty-window propagation remains pending.')
(root/'pipeline-reuse-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
