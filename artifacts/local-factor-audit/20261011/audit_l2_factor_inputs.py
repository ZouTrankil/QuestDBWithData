import json,requests
from pathlib import Path
root=Path(__file__).resolve().parent
def query(sql):
    r=requests.get('http://localhost:9000/exec',params={'query':sql,'limit':'0,1001'},timeout=60)
    r.raise_for_status();d=r.json();assert 'error' not in d,d
    assert len(d['dataset'])<1001
    return [dict(zip([c['name'] for c in d['columns']],row)) for row in d['dataset']]
definitions=[d for d in json.loads(Path('data-app/src/main/resources/factor/definitions-v2.json').read_text())['definitions'] if d['enabled'] and d['sourceGroup']=='l2_microstructure']
state=query("select id,table_name,directoryName,table_txn,wal_txn,table_suspended from tables() where table_name='l2_daily_features'")
columns=query("select * from table_columns('l2_daily_features')");names={c['column'] for c in columns}
missing=[dict(factor=d['factorId'],column=d['sourceColumn']) for d in definitions if d['sourceColumn'] not in names]
coverage=query("select feature_version,parser_version,count() rows,count_distinct(symbol) symbols from l2_daily_features where ts>='2026-09-30' and ts<'2026-10-01' group by feature_version,parser_version")
after=query("select id,table_name,directoryName,table_txn,wal_txn,table_suspended from tables() where table_name='l2_daily_features'")
result=dict(enabledFactors=len(definitions),enabledPublicationFactors=sum(d['publishObservations'] for d in definitions),physicalColumns=len(names),missingBindings=missing,
            lineageFields=sorted(n for n in names if any(s in n for s in ['lineage','digest','source','archive','version'])),
            versions=coverage,coverageDate='2026-09-30',sourceState=after,stable=state==after,productionReady=False,
            limitation='Full-history version scan exceeded 60 seconds; this report only checks the requested date and schema.')
(root/'l2-factor-source-audit.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
