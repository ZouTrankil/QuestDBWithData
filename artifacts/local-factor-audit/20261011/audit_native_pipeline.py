import hashlib,json,sqlite3,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(__file__).resolve().parent
receipts=[p for p in (root/'native-pipeline').glob('*/pipeline.json') if not json.loads(p.read_text()).get('computationReused')];assert len(receipts)==1
path=receipts[0];receipt=json.loads(path.read_text());physical=json.loads((root/'native-pipeline-physical.json').read_text())
assert receipt['phase']=='PARTIAL' and not receipt['factorReady'] and not receipt['sourceComplete']
assert receipt['publication']['verifiedRows']==receipt['publication']['sourceRows']==5571
assert physical['missingRows']==physical['mismatches']==physical['unexpectedRows']==0
assert physical['sourceStable'] and physical['walSettled']
pool_bytes=(path.parent/'pools.json').read_bytes();sha=hashlib.sha256(pool_bytes).hexdigest()
assert sha==receipt['coverageReceipt']['fileSha256']
assert (root/'native-pipeline'/'coverage'/'objects'/(sha+'.json')).read_bytes()==pool_bytes
connection=sqlite3.connect(Path('var/sync-ledger.sqlite3').resolve().as_uri()+'?mode=ro',uri=True)
pages=connection.execute("select state,count(*) from sync_entries where run_id=? and kind='SLICE' group by state",(receipt['publicationRunId'],)).fetchall()
assert pages==[('VERIFIED',23)]
tests=[]
for name in ['com.zoutrankil.data.cli.FactorPipelineCommandsTest','com.zoutrankil.data.factor.FactorPoolCoverageStoreTest']:
    suite=ET.parse(Path('data-app/build/test-results/test')/('TEST-'+name+'.xml')).getroot()
    assert suite.attrib['failures']==suite.attrib['errors']=='0';tests.append(dict(name=name,tests=int(suite.attrib['tests'])))
result=dict(executionId=receipt['executionId'],phase=receipt['phase'],verifiedRows=5571,verifiedPages=23,
            poolSections=receipt['coverageReceipt']['sections'],poolObjectSha256=sha,physical=physical,tests=tests,
            automaticPostCloseEnabled=False,sourceComplete=False,factorReady=False)
(root/'native-pipeline-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
