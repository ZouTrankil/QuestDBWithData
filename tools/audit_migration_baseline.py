"""Read-only F001 audit. Run with the Python reference project's uv environment.

Never imports the reference application, runs migrations or writes to QuestDB.
Credentials are read from the Java configuration and never put in the report.
"""
from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import yaml


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--python-project', type=Path, required=True)
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    config = yaml.safe_load((workspace / 'src/main/resources/application.yml').read_text(encoding='utf-8'))
    qdb = config['app']['questdb']
    if any('${' in str(qdb.get(k, '')) for k in ('host', 'username', 'password')):
        raise RuntimeError('Resolve QuestDB configuration placeholders before running this audit')
    auth = base64.b64encode(f"{qdb['username']}:{qdb['password']}".encode()).decode()
    endpoint = f"http://{qdb['host']}:{qdb['qwp-port']}/exec"

    def query(sql):
        if not sql.lstrip().upper().startswith('SELECT ') or ';' in sql:
            raise ValueError('Read-only single SELECT required')
        req = Request(endpoint + '?' + urlencode({'query': sql}),
                      headers={'Authorization': 'Basic ' + auth})
        try:
            with urlopen(req, timeout=20) as response:
                data = json.load(response)
        except HTTPError as exc:
            raise RuntimeError(f'QuestDB read-only query HTTP {exc.code}') from None
        except URLError:
            raise RuntimeError('QuestDB read-only connection failed') from None
        except TimeoutError:
            raise RuntimeError('QuestDB read-only query timed out after 20 seconds') from None
        if 'error' in data:
            raise RuntimeError(str(data['error']))
        columns = [c['name'] for c in data['columns']]
        return [dict(zip(columns, row)) for row in data['dataset']]

    audit = args.python_project / 'artifacts/storage-audit-20260929'
    rows = list(csv.DictReader((audit / 'objects.csv').open(encoding='utf-8-sig')))
    rows = [r for r in rows if r['lifecycle'] in
            {'data_model', 'other_domain_or_unclassified', 'view', 'materialized_view'}]
    original = json.loads((audit / 'metadata.json').read_text(encoding='utf-8'))
    current = query('SELECT * FROM tables()')
    names = {r['table_name'] for r in current}
    view_rows = query('SELECT * FROM views()')
    names.update(r['view_name'] for r in view_rows)
    mv_rows = query('SELECT * FROM materialized_views()')
    names.update(r['view_name'] for r in mv_rows)
    report = {'task_id': 'F001', 'observed_at': datetime.now(timezone.utc).isoformat(),
              'access': 'read_only_http_select', 'target': 'configured app.questdb',
              'version': query('SELECT build()'), 'live_table_metadata': current,
              'live_views': view_rows, 'live_materialized_views': mv_rows,
              'source_inventory_sha256': hashlib.sha256((audit / 'objects.csv').read_bytes()).hexdigest(),
              'objects': [], 'missing_models': [], 'errors': []}
    public = {'daily', 'daily_basic', 'etf_daily', 'exchange_calendar', 'stock_detail_info', 'l2_daily_features'}
    out = workspace / 'artifacts/java-migration/F001'
    out.mkdir(parents=True, exist_ok=True)
    for row in rows:
        name = row['table']
        if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*', name):
            raise ValueError('Unexpected identifier in audited inventory')
        refs = [s for s in row['source_refs'].split(';') if s]
        disposition = ('retirement_candidate' if name in {'backtest_daily','market_breadth_daily_cache','retail_sentiment_daily_cache'}
                       else 'compatibility_interface' if row['lifecycle'] == 'view'
                       else 'implement_from_registered_contract' if row['model_source'] or row['sync_function']
                       else 'owner_confirmation_required')
        item = {'name': name, 'present': name in names, 'disposition': disposition,
                'python_model': row['model_source'], 'sync': row['sync_function'],
                'caller_evidence': refs, 'owner_confirmed': False, 'columns': [],
                'projection': None, 'schema_changes': [], 'sample': None}
        for path in (workspace / 'src/main/java/com/zoutrankil/questdbwithdata/domain').rglob('*.java'):
            if f'object `{name}`' in path.read_text(encoding='utf-8'):
                item['projection'] = path.relative_to(workspace).as_posix()
                break
        if item['present']:
            try:
                columns = query(f"SELECT * FROM table_columns('{name}')")
                item['columns'] = columns
                old = dict(c.split(':', 1) for c in row['columns'].split(';') if ':' in c)
                new = {c['column']: c['type'] for c in columns}
                item['schema_changes'] = [{'column': key, 'old': old.get(key), 'new': new.get(key)}
                                          for key in sorted(old.keys() | new.keys()) if old.get(key) != new.get(key)]
                # Actual bounded data read; metadata row counts are not a substitute.
                safe_columns = [c['column'] for c in columns]
                quoted = ', '.join('"' + c.replace('"', '""') + '"' for c in safe_columns)
                sql = f'SELECT {quoted} FROM "{name}" LIMIT 1'
                sample = query(sql)
                item['sample'] = {'query': sql, 'actual_rows': len(sample), 'sha256': digest(sample),
                                  'values': sample if name in public else None,
                                  'privacy': 'public market sample' if name in public else 'values omitted; digest retained'}
            except RuntimeError as exc:
                item['error'] = str(exc)
                report['errors'].append({'object': name, 'error': str(exc)})
        report['objects'].append(item)
        (out / 'baseline.partial.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f"{len(report['objects'])}/{len(rows)} {name}: "
              f"{item.get('error', 'read' if item['sample'] is not None else 'absent')}", flush=True)
    for model in original['models']:
        name = model['schema']['table_name']
        if name not in {r['table'] for r in rows}:
            report['missing_models'].append({'name': name, 'now_present': name in names,
                                            'status': 'conditional_owner_review', 'source': model['source']})
    report['summary'] = {'audited_objects': len(rows), 'present': sum(x['present'] for x in report['objects']),
                         'projections': sum(x['projection'] is not None for x in report['objects']),
                         'actual_reads': sum(x['sample'] is not None for x in report['objects']),
                         'nonempty_reads': sum(bool(x['sample'] and x['sample']['actual_rows']) for x in report['objects']),
                         'schema_drift_objects': sum(bool(x['schema_changes']) for x in report['objects']),
                         'missing_models': len(report['missing_models']), 'query_errors': len(report['errors'])}
    out = workspace / 'artifacts/java-migration/F001'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# F001逐对象处置与读取证据', '', '本清单是迁移基线，不是数据完整性认证。未确认owner的对象继续保留待核实。', '',
             '| 对象 | 当前存在 | Java投影 | 处置 | 实际样本行 | schema差异 |', '| --- | --- | --- | --- | --- | --- |']
    for item in report['objects']:
        lines.append(f"| {item['name']} | {item['present']} | {bool(item['projection'])} | {item['disposition']} | "
                     f"{item['sample']['actual_rows'] if item['sample'] else '未读取'} | {len(item['schema_changes'])} |")
    lines.extend(['', '## 原缺失模型', ''])
    lines.extend(f"- {x['name']}：当前存在={x['now_present']}；{x['status']}" for x in report['missing_models'])
    (out / 'disposition.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps(report['summary']))
    if report['errors']:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
