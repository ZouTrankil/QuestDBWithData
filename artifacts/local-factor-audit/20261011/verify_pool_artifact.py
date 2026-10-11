import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parent
encoded=(root/'live-pools-v2-values.json').read_bytes()
value=json.loads(encoded)
report=json.loads((root/'live-pools-v2-report.json').read_text())
old=json.loads((root/'live-pools-values.json').read_text())
membership=json.loads((root/'live-pool-membership-parity.json').read_text())
assert value['schema']=='java.factor_pool_values.v1'
assert value['sourceComplete'] is False
assert hashlib.sha256(encoded).hexdigest()==report['poolArtifactSha256']
identity=json.dumps(value['inputIdentity'],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
assert hashlib.sha256(identity).hexdigest()==value['inputFingerprint']==report['poolInputFingerprint']
assert value['sections']==old['sections'],'Numerical outputs changed'
for section in value['coverage']:
    expected=next(p for p in membership['pools'] if p['pool']==section['pool'])
    assert section['available']==expected['available']
    assert section['membershipHash']==expected.get('membershipHash',''),section['pool']
    previous=next(p for p in old['coverage'] if p['pool']==section['pool'])
    assert section['members']==previous['members']
result=dict(schema=value['schema'],fileSha256=report['poolArtifactSha256'],inputFingerprint=value['inputFingerprint'],
            membershipHashesCompared=len(value['coverage']),sectionComparisons=len(value['sections']),
            outputRows=sum(len(s['symbols']) for s in value['sections']),mismatches=0,sourceComplete=False)
(root/'pool-artifact-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
