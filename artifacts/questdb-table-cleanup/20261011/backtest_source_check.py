import json
from audit import ROOT,query,objects
backup='java_backtest_daily_backup_2cf197c215184001b14792d3597dfe64'
entries=json.loads((ROOT/'backtest-full-detail.json').read_text())
dest=ROOT/'backtest-st-source-check.json'
out=json.loads(dest.read_text()) if dest.exists() else []
for e in entries:
    period=str(e.get('period',e['year']))
    if not e['changed_is_st'] or any(x['period']==period for x in out):continue
    y=e['year']
    if len(period)==4:lo=f'{y}-01-01';hi=f'{y+1}-01-01'
    else:
        m=int(period[-2:]);lo=period+'-01';hi=f'{y+1}-01-01' if m==12 else f'{y}-{m+1:02}-01'
    p=f"trade_date >= '{lo}' AND trade_date < '{hi}'"
    differences=f'(SELECT t.trade_date,t.ts_code,b.is_st AS old_st,t.is_st AS new_st FROM (SELECT * FROM {backup} WHERE {p}) b JOIN (SELECT * FROM backtest_daily WHERE {p}) t ON b.trade_date=t.trade_date AND b.ts_code=t.ts_code WHERE b.is_st!=t.is_st) d'
    source=f"(SELECT * FROM stk_st_daily WHERE timestamp >= '{lo}' AND timestamp < '{hi}') s"
    sql=f'SELECT count() AS changed_rows,sum(CASE WHEN d.new_st=coalesce(s.is_st,0) THEN 1 ELSE 0 END) AS formal_matches_source,sum(CASE WHEN d.old_st=coalesce(s.is_st,0) THEN 1 ELSE 0 END) AS backup_matches_source FROM {differences} LEFT JOIN {source} ON d.trade_date=s.timestamp AND d.ts_code=s.ts_code'
    r=objects(query(sql,55))[0];r['period']=period
    assert r['changed_rows']==e['changed_is_st']
    assert r['formal_matches_source']==r['changed_rows'],r
    out.append(r);dest.write_text(json.dumps(out,indent=2))
    print(json.dumps(r),flush=True)
