#!/usr/bin/env python3
"""只读检查 CN 采集表、已注册 owner 和物理字段；不凭最新日期判定采集完整。"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def query(endpoint: str, sql: str) -> list[dict]:
    request = urllib.request.Request(endpoint.rstrip('/') + '/exec?' + urllib.parse.urlencode(
        {'query': sql, 'limit': '0,10000'}))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=20) as response:
        result = json.load(response)
    if 'error' in result:
        raise ValueError(result['error'])
    columns = [column['name'] for column in result['columns']]
    return [dict(zip(columns, row)) for row in result['dataset']]


def load_jobs(path: Path) -> list[dict]:
    source = path.read_text()
    # 同时接受 CLI 的 Gradle 日志和已导出的 JSON，不把启动日志当作任务数据。
    if source.lstrip().startswith('['):
        return json.loads(source)
    match = re.search(r'\[\s*\{\s*"jobId"', source)
    if not match:
        raise ValueError('没有找到 show-sync-job-definitions 的 JSON 输出')
    return json.JSONDecoder().raw_decode(source[match.start():])[0]


def inspect(endpoint: str, name: str, metadata: dict | None, jobs: list[dict]) -> dict:
    result = {'table': name, 'physical': metadata,
              'jobs': [job for job in jobs if job['datasetId'] == name],
              'complete': False, 'last_success': '待查原部署账本', 'runtime_cause': '待查原部署配置和运行记录'}
    contract = ROOT / 'markets/cn/source/src/main/resources/contracts/source' / (name + '.json')
    result['source_contract'] = str(contract.relative_to(ROOT)) if contract.exists() else None
    if name in {'stock_minute_bars', 'margin_trading', 'index'}:
        result['code_finding'] = '文件导入来源，需核对原文件和导入记录'
    elif name == 'l2_live_normalized_events':
        result['code_finding'] = '旧历史表；当前实时结果写 SQLite/Parquet，不恢复旧 QuestDB writer'
    elif name == 'cn_bond_yield_curve':
        result['code_finding'] = '正式 owner 在主策略入口，不在数据入口的这份任务注册中'
    elif result['jobs']:
        result['code_finding'] = '已注册正式 owner；仍须核对运行绑定、原账本和调度'
    elif contract.exists():
        result['code_finding'] = '有来源契约，但数据入口未注册正式 owner；Batch 仍只写隔离表'
    else:
        result['code_finding'] = '数据入口没有正式 owner，也没有通用来源契约；旧库有数据不代表新程序接管'
    if not metadata:
        result['physical_finding'] = '远端对象不存在'
        return result
    try:
        columns = query(endpoint, f"select * from table_columns('{name}')")
        result['columns'] = columns
        result['latest'] = metadata.get('table_max_timestamp')
        designated = metadata.get('designatedTimestamp')
        if designated and not result['latest']:
            if not re.fullmatch(r'[a-z0-9_]+', designated):
                raise ValueError('指定时间列不是受支持的标识符')
            latest = query(endpoint, f'select "{designated}" from "{name}" order by "{designated}" desc limit 1')
            result['latest'] = latest[0][designated] if latest else None
        if contract.exists():
            expected = json.loads(contract.read_text())['columns']
            actual = {column['column']: column['type'] for column in columns}
            result['contract_differences'] = [
                {'column': field['target'], 'contract': field['type'], 'actual': actual.get(field['target'])}
                for field in expected if actual.get(field['target']) != field['type']]
            result['extra_physical_columns'] = sorted(set(actual) - {field['target'] for field in expected})
        freshness_fields = {
            'etf_basic': 'update_time', 'fut_basic': 'update_time', 'stock_detail_info': 'update_time',
            'etf_portfolio': 'ann_date', 'share_float': 'ann_date', 'index': 'import_time',
            'index_member': 'update_time', 'ths_index': 'update_time', 'ths_member': 'update_time',
        }
        field = freshness_fields.get(name, designated)
        result['freshness_field'] = field
        if field and field != designated:
            try:
                fresh = query(endpoint, f'select max("{field}") as latest from "{name}"')
                result['freshness_latest'] = fresh[0]['latest'] if fresh else None
            except Exception as failure:
                result['freshness_error'] = type(failure).__name__
        else:
            result['freshness_latest'] = result['latest']
        result['physical_finding'] = '已查字段；尚未做来源和完整键值验收'
    except Exception as failure:
        result['inspection_error'] = f'{type(failure).__name__}: {failure}'
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', default='http://100.64.153.106:9000')
    parser.add_argument('--jobs', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    # 范围跟维护中的任务清单一致，退役来源不会被重新纳入。
    plan = ROOT / 'docs/plan/cn-code-review/11-逐表检查并修复采集更新.md'
    names = re.findall(r'^\| `([a-z0-9_]+)`', plan.read_text(), re.M)
    if not names or len(names) != len(set(names)):
        raise ValueError('逐表清单为空或重复')
    metadata = {row['table_name']: row for row in query(args.endpoint, 'select * from tables()')}
    jobs = load_jobs(args.jobs)
    with ThreadPoolExecutor(max_workers=4) as workers:
        rows = list(workers.map(lambda name: inspect(args.endpoint, name, metadata.get(name), jobs), names))
    doc = {'observed_at': datetime.now(timezone.utc).isoformat(), 'read_only': True,
           'scope': len(rows), 'data_verified': False, 'rows': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.json').write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n')
    lines = ['# CN 逐表接管检查', '', f"检查时间：{doc['observed_at']}。共 {len(rows)} 张表，全部只读。",
             '', '这里区分代码缺项和运行原因。最新日期只表示库里见过数据，不代表完整，也不代表已恢复同步。',
             '', '| 表 | 用来检查新鲜度的列 | 已见最新值 | 代码检查 | 字段契约差异数 |', '| --- | --- | --- | --- | --- |']
    for row in rows:
        differences = len(row['contract_differences']) if 'contract_differences' in row else '无通用契约'
        lines.append(f"| `{row['table']}` | `{row.get('freshness_field') or '无'}` | {row.get('freshness_latest') or '待查'} | {row['code_finding']} | {differences} |")
    lines += ['', '上次成功、真实报错和实际目标绑定仍需原部署账本；没有拿到的项目保持待查。',
              '字段差异来自通用来源契约与实库的直接对照；正式 owner 可能有单独契约，不能据此自动改表。',
              f"逐字段证据见 [{args.output.with_suffix('.json').name}]({args.output.with_suffix('.json').name})。", '']
    args.output.with_suffix('.md').write_text('\n'.join(lines))
    print(json.dumps({'tables': len(rows), 'inspection_errors': sum('inspection_error' in row for row in rows),
                      'registered_owners': sum(bool(row['jobs']) for row in rows), 'data_verified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
