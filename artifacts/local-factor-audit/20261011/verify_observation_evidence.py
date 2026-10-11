import argparse,json,datetime,re,requests
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);p.add_argument('--output',required=True);p.add_argument('--require-complete',action='store_true');a=p.parse_args()
rows=json.loads(Path(a.evidence).read_text())['observations'];expected={tuple(r[:5]):r for r in rows};assert len(rows)==len(expected)<=100000
sections=sorted({(r[0],r[1],r[3],r[4]) for r in rows});predicates=[]
for date,kind,factor,version in sections:
 for value in (kind,factor,version):assert re.fullmatch(r'[A-Za-z0-9_.-]+',value)
 following=(datetime.date.fromisoformat(date)+datetime.timedelta(days=1)).isoformat()
 predicates.append(f"(signal_date>='{date}' and signal_date<'{following}' and entity_type='{kind}' and factor_id='{factor}' and definition_version='{version}')")
start=min(r[0] for r in rows);end=(datetime.date.fromisoformat(max(r[0] for r in rows))+datetime.timedelta(days=1)).isoformat()
query="select signal_date,entity_type,entity_id,factor_id,definition_version,factor_value,available_at,quality_status,scope,l2_source_lineage_digest from factor_observation where signal_date>='"+start+"' and signal_date<'"+end+"' and ("+' or '.join(predicates)+')'
s=requests.Session()
def fetch(sql):
 r=s.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,100001'},timeout=120);r.raise_for_status();d=r.json();assert 'error' not in d,d;assert len(d['dataset'])<100001;return d['dataset']
state="select id,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended from tables() where table_name='factor_observation'"
before=fetch(state);actual=fetch(query);after=fetch(state);seen=set();bad=[];unexpected=0
for row in actual:
 row[0]=row[0][:10];key=tuple(row[:5]);assert key not in seen,'Duplicate physical key';seen.add(key);ref=expected.get(key)
 if ref is None:unexpected+=1;continue
 row[6]=datetime.datetime.fromisoformat(row[6].removesuffix('Z')).isoformat();ref=ref.copy();ref[6]=datetime.datetime.fromisoformat(ref[6]).isoformat()
 if row!=ref:bad.append(key)
result={'expectedRows':len(expected),'visibleExpectedRows':len(expected.keys()&seen),'missingRows':len(expected.keys()-seen),'mismatches':len(bad),'unexpectedRows':unexpected,'sourceStable':before==after,'walSettled':len(after)==1 and after[0][2]==after[0][3] and after[0][4]==0 and after[0][5] is False,'state':after,'observedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
Path(a.output).write_text(json.dumps(result,indent=2));print(json.dumps(result));assert not bad
if a.require_complete:assert result['missingRows']==0 and result['sourceStable'] and result['walSettled'],'Publication not complete and settled'
