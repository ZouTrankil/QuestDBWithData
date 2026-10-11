import json, hashlib
from collections import Counter
from audit import ROOT, query, objects, ident

def digest(rows):
    return hashlib.sha256(json.dumps(rows,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()

entries = [e for e in json.loads((ROOT/'verification.json').read_text()) if e['status']=='KEEP_DISTINCT_VALUES' and e['target']!='backtest_daily']
out=[]
for e in entries:
    rows=[]
    for name in [e['backup'],e['target']]:
        r=query('select * from '+ident(name),45)
        data=objects(r)
        count=query('select count() from '+ident(name))['dataset'][0][0]
        assert len(data)==count
        rows.append(data)
    key='ts_code' if e['target']=='stock_detail_info' else 'trade_date'
    clock='update_time' if e['target']=='stock_detail_info' else 'updated_at'
    old,new=[{r[key]:r for r in data} for data in rows]
    assert len(old)==len(rows[0]) and len(new)==len(rows[1])
    changed=[]; fields=Counter(); lost_fields=Counter(); clocks=Counter()
    for k in old.keys() & new.keys():
        diff={c:{'old':old[k][c],'formal':new[k][c]} for c in old[k] if old[k][c]!=new[k][c]}
        if not diff: continue
        business={c:d for c,d in diff.items() if c!=clock}
        for c in business: fields[c]+=1
        for c,d in business.items():
            if d['old'] is not None and d['formal'] is None: lost_fields[c]+=1
        a,b=old[k][clock],new[k][clock]
        ordering='unknown' if a is None or b is None else ('formal_newer' if b>a else ('same' if b==a else 'formal_older'))
        clocks[ordering]+=1
        changed.append({'key':k,'old_updated':a,'formal_updated':b,'ordering':ordering,'fields':diff})
    result={'target':e['target'],'backup':e['backup'],'key':key,'backup_rows':len(old),'formal_rows':len(new),'backup_digest':digest(rows[0]),'formal_digest':digest(rows[1]),'missing_rows':[old[k] for k in sorted(old.keys()-new.keys())],'new_keys':sorted(new.keys()-old.keys()),'changed_rows':len(changed),'business_changed_rows':sum(any(c!=clock for c in r['fields']) for r in changed),'changed_fields':dict(fields),'non_null_to_null':dict(lost_fields),'update_ordering':dict(clocks),'changes':changed}
    out.append(result)
    (ROOT/'remaining-small-details.json').write_text(json.dumps(out,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['changes','missing_rows','new_keys']},ensure_ascii=False),flush=True)
