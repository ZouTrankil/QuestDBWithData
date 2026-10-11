import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from audit import ROOT, query, objects

backup='java_backtest_daily_backup_2cf197c215184001b14792d3597dfe64'
fields=['open','high','low','close','vol','amount','adj_factor','up_limit','down_limit','is_suspended','is_st']
def different(c):
    return f'(b.{c} != t.{c} OR (b.{c} IS NULL AND t.{c} IS NOT NULL) OR (b.{c} IS NOT NULL AND t.{c} IS NULL))'
path=ROOT/'backtest-full-detail.json'
out=json.loads(path.read_text()) if path.exists() else []
periods=[]
for y in range(2010,2027):
    if y<2018:
        periods.append((str(y),f'{y}-01-01',f'{y+1}-01-01',y))
    else:
        for m in range(1,13):
            if y==2026 and m>9: break
            periods.append((f'{y}-{m:02}',f'{y}-{m:02}-01',f'{y+1}-01-01' if m==12 else f'{y}-{m+1:02}-01',y))
def inspect_period(task):
    period,lo,hi,y=task
    interval=f"trade_date >= '{lo}' AND trade_date < '{hi}'"
    base=f'(SELECT * FROM {backup} WHERE {interval}) b LEFT JOIN (SELECT * FROM backtest_daily WHERE {interval}) t ON b.trade_date=t.trade_date AND b.ts_code=t.ts_code'
    selections=['count() AS backup_rows','sum(CASE WHEN t.ts_code IS NULL THEN 1 ELSE 0 END) AS missing_keys','sum(CASE WHEN t.ts_code IS NOT NULL AND ('+' OR '.join(different(c) for c in fields)+') THEN 1 ELSE 0 END) AS changed_rows']
    for c in fields:
        selections += [f'sum(CASE WHEN t.ts_code IS NOT NULL AND {different(c)} THEN 1 ELSE 0 END) AS changed_{c}', f'sum(CASE WHEN t.ts_code IS NOT NULL AND b.{c} IS NOT NULL AND t.{c} IS NULL THEN 1 ELSE 0 END) AS lost_{c}']
    result=objects(query('SELECT '+','.join(selections)+' FROM '+base,55))[0]
    result['year']=y
    result['period']=period
    result['formal_rows']=query('select count() from backtest_daily where '+interval,45)['dataset'][0][0]
    result['backup_direct_rows']=query('select count() from '+backup+' where '+interval,45)['dataset'][0][0]
    assert result['backup_rows']==result['backup_direct_rows'],'nonunique joined keys'
    if result['missing_keys']:
        result['missing_samples']=objects(query('SELECT b.* FROM '+base+' WHERE t.ts_code IS NULL LIMIT 20',45))
    if result['changed_rows'] and not any(e.get('samples') for e in out):
        cols='b.trade_date,b.ts_code,'+','.join(f'b.{c} AS old_{c},t.{c} AS new_{c}' for c in fields)
        result['samples']=objects(query('SELECT '+cols+' FROM '+base+' WHERE t.ts_code IS NOT NULL AND ('+' OR '.join(different(c) for c in fields)+') LIMIT 10',45))
    return result

pending=[p for p in periods if not any(str(e.get('period',e.get('year')))==p[0] for e in out)]
with ThreadPoolExecutor(max_workers=3) as executor:
    tasks={executor.submit(inspect_period,p):p for p in pending}
    for future in as_completed(tasks):
        result=future.result()
        out.append(result)
        out.sort(key=lambda r:str(r.get('period',r['year'])))
        (ROOT/'backtest-full-detail.json').write_text(json.dumps(out,indent=2))
        print(json.dumps({k:v for k,v in result.items() if k in ['period','backup_rows','formal_rows','missing_keys','changed_rows'] or (k.startswith(('changed_','lost_')) and v)}),flush=True)
