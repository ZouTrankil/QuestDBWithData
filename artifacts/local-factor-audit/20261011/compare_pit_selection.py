import json
from pathlib import Path
p=Path('artifacts/local-factor-audit/20261011');report=json.loads((p/'pit-selected-report.json').read_text());old=json.loads((p/'shared-final-report.json').read_text())
assert report['sourceState']==old['sourceState'],'Source versions differ'
expected={}
for name in ['financial-panel-observations.json','structural-observations.json']:
 for row in json.loads((p/name).read_text())['observations']:expected[tuple(row[:5])]=row
rows=json.loads((p/'pit-selected-observations.json').read_text())['observations'];actual={tuple(row[:5]):row for row in rows}
assert len(actual)==len(rows)==len(expected)
bad=[k for k in expected if actual.get(k)!=expected[k]]
result={'rows':len(rows),'mismatches':len(bad),'sourceVersionsEqual':True,'sourceMarketRowsBefore':old['sourceRows'],'sourceMarketRowsAfter':report['sourceRows'],'dailyFeaturesComputed':report['dailyFeaturesComputed'],'matchingPhysicalRows':report['matchingObservationRows'],'pendingPhysicalRows':report['pendingObservationRows']}
(p/'pit-selected-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert not bad and not report['dailyFeaturesComputed'] and report['pendingObservationRows']==0
