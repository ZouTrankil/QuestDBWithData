import json
from collections import Counter
from audit import ROOT, query, objects, ident

raw = json.loads((ROOT / 'tables-before.json').read_text(encoding='utf-8-sig'))
tables = {t['table_name']: t for t in objects(raw)}
previous = {x['backup']: x for x in json.loads((ROOT / 'audit.json').read_text())}
results = []
for name, meta in tables.items():
    if '_backup_' not in name:
        continue
    import re
    target = re.sub(r'^java_(?:d\d+_)?', '', name.split('_backup_')[0])
    e = {'backup': name, 'target': target, 'backup_id': meta['id'], 'target_id': tables.get(target, {}).get('id')}
    try:
        if target not in tables:
            e['status'] = 'KEEP_MISSING_FORMAL_REQUIRES_PROMOTION_VALIDATION'
        elif previous.get(name, {}).get('backup_rows_not_in_target') == 0:
            e.update(status='EXACT_DUPLICATE', backup_rows=previous[name]['backup_stats']['rows'], formal_rows=previous[name]['target_stats']['rows'], backup_rows_not_in_formal=0, formal_rows_not_in_backup=0)
        else:
            counts = []
            for table in [name, target]:
                counts.append(query('select count() from ' + ident(table))['dataset'][0][0])
            e.update(backup_rows=counts[0], formal_rows=counts[1])
            schemas = [objects(query("select * from table_columns('" + t + "')")) for t in [name, target]]
            e['schema_equal'] = [(x['column'], x['type']) for x in schemas[0]] == [(x['column'], x['type']) for x in schemas[1]]
            if not e['schema_equal']:
                e['status'] = 'KEEP_SCHEMA_DIFFERENCE'
            elif max(counts) > 1000000:
                e['status'] = 'KEEP_LARGE_TABLE_NOT_YET_COMPARED'
            else:
                data = []
                for table, count in zip([name,target], counts):
                    rows = Counter()
                    for offset in range(0, count, 10000):
                        part = query('select * from ' + ident(table) + ' limit ' + str(offset) + ',' + str(min(offset+10000, count)))
                        rows.update(tuple(x) for x in part['dataset'])
                    assert rows.total() == count, (table, rows.total(), count)
                    data.append(rows)
                e['backup_rows_not_in_formal'] = (data[0] - data[1]).total()
                e['formal_rows_not_in_backup'] = (data[1] - data[0]).total()
                e['status'] = 'COVERED' if e['backup_rows_not_in_formal'] == 0 else 'KEEP_DISTINCT_VALUES'
    except Exception as exc:
        e['status'] = 'KEEP_READ_ERROR'
        e['error'] = str(exc)
    results.append(e)
    (ROOT / 'verification.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    print(json.dumps(e), flush=True)
