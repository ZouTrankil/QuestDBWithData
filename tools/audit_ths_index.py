"""Read-only D004 schema and bounded complete THS directory baseline; no application imports."""
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import base64
import json
import yaml

root = Path(__file__).resolve().parents[1]
config = yaml.safe_load((root / 'src/main/resources/application.yml').read_text(encoding='utf-8'))['app']['questdb']
auth = base64.b64encode(f"{config['username']}:{config['password']}".encode()).decode()
endpoint = f"http://{config['host']}:{config['qwp-port']}/exec"
queries = {
    'table': "SELECT * FROM tables() WHERE table_name='ths_index'",
    'columns': "SELECT * FROM table_columns('ths_index')",
    'counts': 'SELECT count(*) rows,count_distinct(ts_code) codes,min(update_time) first_observed,max(update_time) last_observed FROM ths_index',
    'duplicate_codes': 'SELECT * FROM (SELECT ts_code,count(*) rows FROM ths_index GROUP BY ts_code) WHERE rows>1 LIMIT 20',
    'scope_counts': 'SELECT exchange,type,count(*) rows FROM ths_index GROUP BY exchange,type ORDER BY exchange,type',
    'catalog': 'SELECT ts_code,name,count,exchange,list_date,type,update_time FROM ths_index ORDER BY ts_code,update_time LIMIT 5001',
    'version': 'SELECT build()',
}
report = {'task_id': 'D004', 'observed_at': datetime.now(timezone.utc).isoformat(), 'access': 'read_only_http_select'}
for name, sql in queries.items():
    request = Request(endpoint + '?' + urlencode({'query': sql}), headers={'Authorization': 'Basic ' + auth})
    try:
        with urlopen(request, timeout=20) as response:
            content = response.read(8*1024*1024+1)
        if len(content)>8*1024*1024:
            raise ValueError('Response exceeds bound')
        data = json.loads(content)
    except Exception as failure:
        raise RuntimeError(f'Read-only THS query failed: {name}, {type(failure).__name__}') from None
    if 'error' in data:
        raise RuntimeError(f'QuestDB rejected THS query: {name}')
    columns = [column['name'] for column in data['columns']]
    report[name] = {'query': sql, 'rows': [dict(zip(columns, row)) for row in data['dataset']]}
    if name == 'catalog' and len(report[name]['rows'])>5000:
        raise RuntimeError('THS baseline exceeds bounded 5000-row audit')
folder = root / 'artifacts/java-migration/D004'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'physical-baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'counts': report['counts']['rows'], 'scopes': report['scope_counts']['rows'],
                  'duplicates': len(report['duplicate_codes']['rows']), 'columns': len(report['columns']['rows'])}, ensure_ascii=False))
