"""Read-only D005 schema, identity aggregates and 100-row sample; no application imports."""
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
 'stock_codes': 'SELECT DISTINCT ts_code FROM index_member ORDER BY ts_code LIMIT 10001',
 'table': "SELECT * FROM tables() WHERE table_name='index_member'",
 'columns': "SELECT * FROM table_columns('index_member')",
 'counts': 'SELECT count(*) rows,count_distinct(index_code) industries,min(update_time) first_observed,max(update_time) last_observed FROM index_member',
 'scopes': 'SELECT level,is_new,count(*) rows FROM index_member GROUP BY level,is_new',
 'pair_duplicates': 'SELECT * FROM (SELECT index_code,ts_code,count(*) rows FROM index_member GROUP BY index_code,ts_code) WHERE rows>1 LIMIT 20',
 'period_duplicates': 'SELECT * FROM (SELECT index_code,ts_code,in_date,count(*) rows FROM index_member GROUP BY index_code,ts_code,in_date) WHERE rows>1 LIMIT 20',
 'sample': 'SELECT * FROM index_member ORDER BY index_code,ts_code,in_date LIMIT 100',
 'version': 'SELECT build()',
}
report = {'task_id': 'D005', 'observed_at': datetime.now(timezone.utc).isoformat(), 'access': 'read_only_http_select'}
for name, sql in queries.items():
    request = Request(endpoint + '?' + urlencode({'query': sql}), headers={'Authorization': 'Basic ' + auth})
    try:
        with urlopen(request, timeout=20) as response:
            content = response.read(8*1024*1024+1)
        if len(content)>8*1024*1024:
            raise ValueError('Response exceeds bound')
        data = json.loads(content)
    except Exception as failure:
        raise RuntimeError(f'Read-only index membership query failed: {name}, {type(failure).__name__}') from None
    if 'error' in data:
        raise RuntimeError(f'QuestDB rejected index membership query: {name}')
    columns = [column['name'] for column in data['columns']]
    report[name] = {'query': sql, 'rows': [dict(zip(columns, row)) for row in data['dataset']]}
folder = root / 'artifacts/java-migration/D005'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'physical-baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'counts': report['counts']['rows'], 'scopes': report['scopes']['rows'], 'period_duplicate_samples': len(report['period_duplicates']['rows']), 'columns': len(report['columns']['rows'])},ensure_ascii=False))
