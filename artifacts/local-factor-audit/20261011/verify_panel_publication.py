import argparse,json,datetime,requests
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--require-complete',action='store_true');args=p.parse_args()
root=Path('artifacts/local-factor-audit/20261011');e=json.loads((root/'panel-observations.json').read_text());expected={tuple(r[:5]):r for r in e['observations']};assert len(expected)==len(e['observations'])
query="select signal_date,entity_type,entity_id,factor_id,definition_version,factor_value,available_at,quality_status,scope,l2_source_lineage_digest from factor_observation where signal_date>='2026-09-30' and signal_date<'2026-10-01' and factor_id in ('reversal_1d','risk_adv20') and definition_version='factor_platform_v2_0_2'"
s=requests.Session()
def fetch(sql):
 r=s.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,20000'},timeout=60);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<20000;return d['dataset']
state="select id,directoryName,table_txn,wal_txn from tables() where table_name='factor_observation'"
before=fetch(state);actual=fetch(query);after=fetch(state);seen=set();bad=[]
for row in actual:
 row[0]=row[0][:10];key=tuple(row[:5]);assert key not in seen,'duplicate key';seen.add(key);ref=expected.get(key)
 if ref is not None:
  row[6]=datetime.datetime.fromisoformat(row[6].removesuffix('Z')).isoformat();ref=ref.copy();ref[6]=datetime.datetime.fromisoformat(ref[6]).isoformat()
 if row!=ref:bad.append({'key':key,'actual':row,'expected':ref})
r={'expectedRows':len(expected),'visibleRows':len(seen),'missingRows':len(expected.keys()-seen),'mismatches':bad,'sourceStable':before==after,'walSettled':len(after)==1 and after[0][2]==after[0][3],'state':after,'observedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(root/'panel-publish-current-audit.json').write_text(json.dumps(r,indent=2));print(json.dumps({k:v for k,v in r.items() if k!='mismatches'}));print('mismatches',len(bad))
assert not bad
if args.require_complete:assert r['missingRows']==0 and r['sourceStable'] and r['walSettled'],'Not complete/stable/settled'
