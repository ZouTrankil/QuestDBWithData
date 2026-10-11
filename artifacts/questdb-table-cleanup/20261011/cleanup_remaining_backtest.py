import json
from datetime import datetime,timezone
from audit import ROOT,query,objects,ident

backup='java_backtest_daily_backup_2cf197c215184001b14792d3597dfe64'
target='backtest_daily'
baseline={r['table_name']:r for r in objects(json.loads((ROOT/'remaining-before.json').read_text()))}
details=json.loads((ROOT/'backtest-full-detail.json').read_text())
sources=json.loads((ROOT/'backtest-st-source-check.json').read_text())
price=json.loads((ROOT/'backtest-price-source-proof.json').read_text())
assert price['status']=='VERIFIED' and price['formal_matches_current_source']
assert price['difference']['ts_code']=='688291.SH' and price['difference']['trade_date']=='2026-09-08T00:00:00.000000Z'
expected={str(y) for y in range(2010,2018)}|{f'{y}-{m:02}' for y in range(2018,2027) for m in range(1,13) if y<2026 or m<=9}
periods=[str(e.get('period',e['year'])) for e in details]
assert len(periods)==len(expected) and set(periods)==expected,'Incomplete period coverage'
assert sum(e['backup_rows'] for e in details)==baseline[backup]['table_row_count']
assert sum(e['formal_rows'] for e in details)==baseline[target]['table_row_count']
assert all(e['backup_rows']==e['backup_direct_rows'] and e['missing_keys']==0 for e in details)
assert all(v==0 for e in details for k,v in e.items() if k.startswith('lost_'))
assert all(v==0 for e in details for k,v in e.items() if k.startswith('changed_') and k not in ['changed_rows','changed_is_st','changed_open','changed_high','changed_low','changed_close'])
for e in details:
    expected_price=1 if e.get('period')=='2026-09' else 0
    assert all(e['changed_'+c]==expected_price for c in ['open','high','low','close'])
    assert e['changed_rows']==e['changed_is_st']+expected_price
source_map={e['period']:e for e in sources}
for e in details:
    if e['changed_is_st']:
        s=source_map[str(e.get('period',e['year']))]
        assert s['changed_rows']==s['formal_matches_source']==e['changed_is_st'] and s['backup_matches_source']==0

before={r['table_name']:r for r in objects(query('select * from tables()'))}
guards=['id','directoryName','table_txn','table_row_count','wal_txn','wal_pending_row_count','table_suspended','dedup','walEnabled','partitionBy','designatedTimestamp']
for name in [backup,target,'stk_st_daily','stk_factor','stk_suspend']:
    assert all(before[name].get(k)==baseline[name].get(k) for k in guards),(name,'changed during audit')
    assert not before[name]['table_suspended'] and not before[name]['wal_pending_row_count']
for name in [backup,target]:
    assert query('select count() from '+ident(name))['dataset'][0][0]==baseline[name]['table_row_count']
views=objects(query('select * from views()'))+objects(query('select * from materialized_views()'))
assert not any(backup in json.dumps(v) for v in views)
proof={'backup':backup,'target':target,'verified_periods':len(periods),'backup_rows':sum(e['backup_rows'] for e in details),'formal_rows':sum(e['formal_rows'] for e in details),'missing_keys':0,'changed_is_st':sum(e['changed_is_st'] for e in details),'changed_ohlc_rows':1,'price_source_proof':price,'other_fields_changed':0,'formal_st_changes_match_current_source':True,'formal_rows_added':sum(e['formal_rows']-e['backup_rows'] for e in details),'inserted_rows':0}
(ROOT/'backtest-delete-proof.json').write_text(json.dumps(proof,indent=2))
reply=query('DROP TABLE '+ident(backup),45)
assert reply.get('ddl')=='OK'
after={r['table_name']:r for r in objects(query('select * from tables()'))}
assert set(before)-set(after)=={backup}
assert all(before[target].get(k)==after[target].get(k) for k in guards)
assert query('select count() from backtest_daily')['dataset'][0][0]==proof['formal_rows']
for n in set(before)-{backup}:
    assert before[n]['id']==after[n]['id'] and before[n]['directoryName']==after[n]['directoryName']
proof.update(status='DELETED_VERIFIED',formal_identity_and_transactions_unchanged=True)
p=ROOT/'remaining-cleanup-result.json'; result=json.loads(p.read_text(encoding='utf-8'))
result['deleted'].append(proof)
assert len(result['deleted'])==4
removed={e['backup'] for e in result['deleted']}
assert set(baseline)-set(after)==removed
assert not any('_backup_' in n for n in after)
result.update(status='PASSED',finished_at=datetime.now(timezone.utc).isoformat(),before_table_count=len(baseline),after_table_count=len(after),remaining_backup_tables=[],all_backup_business_keys_present_in_formal_tables=True)
p.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
(ROOT/'remaining-tables-after.json').write_text(json.dumps(list(after.values()),indent=2))
print(json.dumps(proof),flush=True)
print('FINAL',result['status'],'tables',len(baseline),'->',len(after),'deleted',len(result['deleted']),flush=True)
