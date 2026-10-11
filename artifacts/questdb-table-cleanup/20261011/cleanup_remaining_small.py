import json,hashlib
from datetime import datetime,timezone
from audit import ROOT,query,objects,ident

def digest(rows):
    return hashlib.sha256(json.dumps(rows,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()

before={r['table_name']:r for r in objects(json.loads((ROOT/'remaining-before.json').read_text()))}
details=json.loads((ROOT/'remaining-small-details.json').read_text(encoding='utf-8'))
patch=json.loads((ROOT/'stock-gap-fill-result.json').read_text(encoding='utf-8'))
assert patch['status']=='VERIFIED' and patch['only_two_cells_changed']
result={'endpoint':'127.0.0.1:9000','started_at':datetime.now(timezone.utc).isoformat(),'deleted':[],'filled_cells':patch,'status':'IN_PROGRESS'}
path=ROOT/'remaining-cleanup-result.json'
for e in details:
    backup,target=e['backup'],e['target']
    catalog={r['table_name']:r for r in objects(query('select * from tables()'))}
    for name in [backup,target]:
        assert catalog[name]['id']==before[name]['id'] and catalog[name]['directoryName']==before[name]['directoryName']
        assert not catalog[name]['table_suspended'] and not catalog[name]['wal_pending_row_count']
    views=objects(query('select * from views()'))+objects(query('select * from materialized_views()'))
    assert not any(backup in json.dumps(v) for v in views)
    a,b=[objects(query('select * from '+ident(n),45)) for n in [backup,target]]
    assert digest(a)==e['backup_digest']
    assert digest(b)==(patch['after_digest'] if target=='stock_detail_info' else e['formal_digest'])
    k=e['key']; clock='update_time' if target=='stock_detail_info' else 'updated_at'
    old,new=[{r[k]:r for r in rows} for rows in [a,b]]
    assert len(old)==len(a) and len(new)==len(b) and old.keys()<=new.keys()
    for key in old:
        if old[key]!=new[key]:
            assert datetime.fromisoformat(new[key][clock])>datetime.fromisoformat(old[key][clock]), key
        for col,value in old[key].items():
            assert value is None or new[key][col] is not None,(key,col)
    response=query('DROP TABLE '+ident(backup),45)
    assert response.get('ddl')=='OK'
    actual=objects(query('select * from '+ident(target),45))
    assert digest(actual)==digest(b)
    record={k:v for k,v in e.items() if k not in ['changes','missing_rows']}
    record.update(status='DELETED_VERIFIED',missing_keys=0,non_null_values_lost=0,formal_digest_after=digest(actual),formal_rows_after=len(actual))
    result['deleted'].append(record)
    path.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print('DELETED_VERIFIED',backup,'formal_rows',len(actual),flush=True)
result['small_tables_status']='PASSED'
path.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
