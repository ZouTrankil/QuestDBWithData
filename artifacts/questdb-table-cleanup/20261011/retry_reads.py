import json
from collections import Counter
from audit import ROOT, query, ident, objects

cache = {}
for line in (ROOT / 'queries.jsonl').open(encoding='utf-8'):
    q = json.loads(line)
    if q['sql'].startswith('select * from "') and 'error' not in q['result']:
        cache[q['sql']] = q['result']
verification = json.loads((ROOT / 'verification.json').read_text())
for e in verification:
    if e['target'] not in ['stk_suspend', 'moneyflow_hsgt']:
        continue
    try:
        datasets = []
        for table, count in [(e['backup'],e['backup_rows']), (e['target'],e['formal_rows'])]:
            rows = Counter()
            chunk = 10000 if count > 10000 else 20
            for off in range(0,count,chunk):
                sql = f'select * from {ident(table)} limit {off},{min(off+chunk,count)}'
                result = cache.get(sql)
                if result is None:
                    result = query(sql, timeout=45)
                rows.update(tuple(r) for r in result['dataset'])
                if count > 10000:
                    print(table, min(off+chunk,count), '/', count, flush=True)
            assert rows.total() == count
            datasets.append(rows)
        e['backup_rows_not_in_formal'] = (datasets[0]-datasets[1]).total()
        e['formal_rows_not_in_backup'] = (datasets[1]-datasets[0]).total()
        e['status'] = 'COVERED' if e['backup_rows_not_in_formal'] == 0 else 'KEEP_DISTINCT_VALUES'
        e.pop('error',None)
    except Exception as exc:
        e.update(status='KEEP_READ_ERROR',error=str(exc))
    (ROOT / 'verification.json').write_text(json.dumps(verification,indent=2))
    print(json.dumps(e),flush=True)
