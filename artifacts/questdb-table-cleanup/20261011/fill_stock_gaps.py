import json,hashlib,copy
from audit import ROOT, query, objects,ident

def digest(rows):
    return hashlib.sha256(json.dumps(rows,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
def literal(s):
    return "'"+s.replace("'","''")+"'"

detail=next(e for e in json.loads((ROOT/'remaining-small-details.json').read_text(encoding='utf-8')) if e['target']=='stock_detail_info')
before=objects(query('select * from stock_detail_info',45))
backup=objects(query('select * from '+ident(detail['backup']),45))
assert digest(before)==detail['formal_digest'] and digest(backup)==detail['backup_digest']
old=next(r for r in backup if r['ts_code']=='301139.SZ')
row=next(r for r in before if r['ts_code']=='301139.SZ')
assert row['area'] is None and row['industry'] is None and old['area'] and old['industry']
expected=copy.deepcopy(before)
edited=next(r for r in expected if r['ts_code']=='301139.SZ')
edited.update(area=old['area'],industry=old['industry'])
intent={'table':'stock_detail_info','key':'301139.SZ','before':row,'expected_after':edited,'fields':['area','industry'],'before_digest':digest(before),'expected_after_digest':digest(expected)}
(ROOT/'stock-gap-fill-intent.json').write_text(json.dumps(intent,indent=2,ensure_ascii=False),encoding='utf-8')
sql='UPDATE stock_detail_info SET area='+literal(old['area'])+', industry='+literal(old['industry'])+" WHERE ts_code='301139.SZ' AND area IS NULL AND industry IS NULL AND update_time="+literal(row['update_time'])
reply=query(sql,45)
after=objects(query('select * from stock_detail_info',45))
assert digest(after)==digest(expected),'Full-table verification differs from the exact two-cell update'
intent.update(status='VERIFIED',response=reply,after_digest=digest(after),formal_rows=len(after),only_two_cells_changed=True)
(ROOT/'stock-gap-fill-result.json').write_text(json.dumps(intent,indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps(intent,ensure_ascii=True),flush=True)
