import json
import re
import urllib.request
import urllib.parse
import subprocess
import threading
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:9000/exec'
LOG_LOCK = threading.Lock()

def query(sql, timeout=12):
    url = BASE + '?' + urllib.parse.urlencode({'query': sql, 'limit': '0,10000'})
    response = subprocess.run(['curl.exe', '--noproxy', '*', '--silent', '--show-error', '--max-time', str(timeout), url], capture_output=True, timeout=timeout+6)
    if response.returncode:
        raise RuntimeError(response.stderr.decode(errors='replace'))
    result = json.loads(response.stdout)
    if 'error' in result:
        raise RuntimeError(result['error'])
    with LOG_LOCK:
        with (ROOT / 'queries.jsonl').open('a', encoding='utf-8') as out:
            out.write(json.dumps({'at': datetime.now(timezone.utc).isoformat(), 'sql': sql, 'result': result}) + '\n')
    return result

def objects(result):
    return [dict(zip([c['name'] for c in result['columns']], row)) for row in result['dataset']]

def ident(name):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', name):
        raise ValueError(name)
    return '"' + name + '"'

if __name__ == '__main__':
    tables = objects(query('select * from tables()'))
    names = {x['table_name']: x for x in tables}
    audit = []
    for item in tables:
        name = item['table_name']
        if '_backup_' not in name:
            continue
        target = re.sub(r'^java_(?:d\d+_)?', '', name.split('_backup_')[0])
        entry = {'backup': name, 'target': target, 'backup_metadata': item, 'target_metadata': names.get(target)}
        audit.append(entry)
        try:
            for kind, table in [('backup', name), ('target', target)]:
                entry[kind + '_columns'] = objects(query("select * from table_columns('" + table + "')"))
                ts = names[table]['designatedTimestamp']
                stats = 'count() as rows' + (', min(' + ident(ts) + ') as min_ts, max(' + ident(ts) + ') as max_ts' if ts else '')
                entry[kind + '_stats'] = objects(query('select ' + stats + ' from ' + ident(table)))[0]
            bcols = [(c['column'], c['type']) for c in entry['backup_columns']]
            tcols = [(c['column'], c['type']) for c in entry['target_columns']]
            entry['schema_equal'] = bcols == tcols
            if entry['schema_equal'] and entry['backup_stats']['rows'] < 1000000:
                entry['backup_rows_not_in_target'] = query('select count() from (select * from ' + ident(name) + ' except all select * from ' + ident(target) + ')')['dataset'][0][0]
                entry['target_rows_not_in_backup'] = query('select count() from (select * from ' + ident(target) + ' except all select * from ' + ident(name) + ')')['dataset'][0][0]
        except Exception as exc:
            entry['error'] = str(exc)
        (ROOT / 'audit.json').write_text(json.dumps(audit, indent=2), encoding='utf-8')
        print(json.dumps({k: v for k, v in entry.items() if k not in ['backup_metadata', 'target_metadata', 'backup_columns', 'target_columns']}), flush=True)
