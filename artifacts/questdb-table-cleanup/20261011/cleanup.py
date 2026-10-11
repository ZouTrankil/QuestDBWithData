"""Drop only audited, fully covered backup tables, with catalog identity guards."""
import json
from datetime import datetime, timezone
from audit import ROOT, query, ident, objects

ALLOWLIST = {
    'java_d012_stk_st_daily_backup_1e4ae59286ca4bb2851c251fa98445ce',
    'java_d121_market_sentiment_daily_backup_498c008c1bd2418aab5a9d75dfd05d69',
    'java_regime_features_monitor_daily_backup_77bf160fea8b4817b0aaa25067388360',
    'java_stk_suspend_backup_a3bb5f0e38494dbe8b0ed669776e87fd',
    'java_d027_moneyflow_hsgt_backup_1e56f04bbca6404090e1ed2622a25624',
}
baseline = {r['table_name']: r for r in objects(json.loads((ROOT/'tables-before.json').read_text(encoding='utf-8-sig')))}
verified = json.loads((ROOT/'verification.json').read_text())
plan = [e for e in verified if e['backup'] in ALLOWLIST and e['status'] in ['EXACT_DUPLICATE','COVERED'] and e['backup_rows_not_in_formal'] == 0]
guard_fields = ['id','directoryName','table_txn','table_row_count','table_min_timestamp','table_max_timestamp','wal_txn','wal_pending_row_count','table_suspended']

def catalog():
    return {r['table_name']: r for r in objects(query('select * from tables()'))}

def check_unchanged(name, current):
    if name not in current:
        raise RuntimeError('Table disappeared: '+name)
    for field in guard_fields:
        actual, expected = current[name].get(field), baseline[name].get(field)
        if field in ['table_min_timestamp','table_max_timestamp'] and actual and expected:
            actual = datetime.fromisoformat(actual)
            expected = datetime.fromisoformat(expected)
        if actual != expected:
            raise RuntimeError('Table changed since verification: '+name+' '+field)
    if current[name]['table_suspended'] or current[name]['wal_pending_row_count']:
        raise RuntimeError('Unsettled table: '+name)

current = catalog()
views = objects(query('select * from views()')) + objects(query('select * from materialized_views()'))
for e in plan:
    for name in [e['backup'], e['target']]:
        check_unchanged(name,current)
    if any(e['backup'] in json.dumps(v) for v in views):
        raise RuntimeError('View still refers to backup: '+e['backup'])
(ROOT/'delete-plan.json').write_text(json.dumps(plan,indent=2))
print('APPROVED_SCOPE_PLAN',json.dumps([e['backup'] for e in plan]),flush=True)

import sys
if '--apply' not in sys.argv:
    raise SystemExit(0)
result = {'endpoint':'127.0.0.1:9000','started_at':datetime.now(timezone.utc).isoformat(),'deleted':[],'retained':[e for e in verified if e not in plan]}
for e in plan:
    current = catalog()
    for name in [e['backup'],e['target']]:
        check_unchanged(name,current)
    response = query('DROP TABLE '+ident(e['backup']))
    if response.get('ddl') != 'OK':
        raise RuntimeError('Unexpected drop response: '+json.dumps(response))
    result['deleted'].append(e)
    (ROOT/'cleanup-result.json').write_text(json.dumps(result,indent=2))
    print('DROPPED',e['backup'],flush=True)
after = catalog()
(ROOT/'tables-after.json').write_text(json.dumps(list(after.values()),indent=2))
deleted = {e['backup'] for e in result['deleted']}
assert not deleted.intersection(after), 'Deleted tables remain in catalog'
assert set(baseline)-deleted <= set(after), 'Unexpected table loss'
for name in set(baseline)-deleted:
    check_unchanged(name,after)
for e in result['deleted']:
    actual = query('select count() from '+ident(e['target']))['dataset'][0][0]
    assert actual == e['formal_rows'], (e['target'],actual,e['formal_rows'])
result.update(status='PASSED',before_table_count=len(baseline),after_table_count=len(after),all_other_table_identities_and_catalog_counts_unchanged=True)
(ROOT/'cleanup-result.json').write_text(json.dumps(result,indent=2))
print('RESULT',json.dumps({k:v for k,v in result.items() if k not in ['deleted','retained']}),flush=True)
