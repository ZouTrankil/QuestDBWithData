import json
from pathlib import Path
p=Path('artifacts/local-factor-audit/20261011')
definitions=json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions']
columns=set(json.loads((p/'shared-final-values.json').read_text())[0])
groups={'canonical_daily','daily_price_tech','global_structural_daily','fundamental_pit','global_structural_pit'}
if (p/'market-state-live-parity.json').exists():
 assert json.loads((p/'market-state-live-parity.json').read_text())['mismatches']==0
 groups.add('market_state')
 columns.update(d['sourceColumn'] for d in definitions if d['sourceGroup']=='market_state')
if (p/'events-parity.json').exists() and (p/'margin-factors-parity.json').exists():
 assert json.loads((p/'events-parity.json').read_text())['mismatches']==0
 assert json.loads((p/'margin-factors-parity.json').read_text())['mismatches']==0
 groups.add('event_pit')
 columns.update(d['sourceColumn'] for d in definitions if d['sourceGroup']=='event_pit')
selected=[d for d in definitions if d['enabled'] and d['publishObservations'] and d['sourceGroup'] in groups]
missing=[{'factor':d['factorId'],'column':d['sourceColumn'],'group':d['sourceGroup']} for d in selected if d['sourceColumn'] not in columns]
remaining={}
for d in definitions:
 if d['enabled'] and d['publishObservations'] and d['sourceGroup'] not in groups:remaining[d['sourceGroup']]=remaining.get(d['sourceGroup'],0)+1
result={'supportedGroupDefinitions':len(selected),'missingNativeColumns':missing,'remainingGroupCounts':remaining,'note':'Available source columns are not factor-by-factor numeric or publication acceptance.'}
(p/'native-binding-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
