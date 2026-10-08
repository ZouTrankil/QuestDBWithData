"""Read-only static inventory; never imports or executes the reference application."""
import ast
import hashlib
import json
import platform
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent / 'back-monitor'
PHYSICAL_SCHEMA_UNVERIFIED = {'fut_holding', 'etf_basic', 'disclosure_date', 'ths_index', 'etf_share'}
IMPLEMENTED_NATIVE = {'daily', 'daily_basic', 'etf_daily', 'stk_limit', 'etf_adj', 'moneyflow', 'etf_factor', 'margin_detail', 'moneyflow_hsgt', 'stk_suspend', 'etf_portfolio', 'stk_factor', 'stk_st_daily', 'cn_bond_yield_curve', 'cyq_perf', 'index_daily_market', 'index_daily_basic', 'exchange_calendar', 'fina_mainbz', 'fina_audit', 'dividend', 'share_float', 'shibor', 'shibor_lpr', 'hibor', 'cn_cpi', 'cn_ppi', 'cn_pmi', 'cn_m', 'cn_gdp', 'fut_daily', 'fut_settle', 'fut_mapping', 'ft_limit', 'fut_holding', 'fut_basic', 'etf_basic', 'disclosure_date', 'ths_index', 'etf_share'}
EVIDENCE = {'cn_bond_yield_curve': 'evidence/chinabond-yield-live.json', 'cyq_perf': 'evidence/cyq-perf-live.json',
            'fina_audit': 'evidence/fina-audit-live.json', 'share_float': 'evidence/share-float-live.json',
            'fut_daily': 'evidence/fut-daily-live.json', 'fut_settle': 'evidence/fut-settle-live.json',
            'fut_mapping': 'evidence/fut-mapping-live.json', 'ft_limit': 'evidence/ft-limit-live.json', 'fut_holding': 'evidence/fut-holding-implementation.json',
            'fut_basic': 'evidence/fut-basic-live.json', 'etf_basic': 'evidence/etf-basic-implementation.json',
            'disclosure_date': 'evidence/disclosure-date-implementation.json', 'ths_index': 'evidence/ths-index-implementation.json', 'etf_share': 'evidence/etf-share-implementation.json'}
CORE = set('daily daily_basic stk_factor stk_limit stk_suspend stk_st_daily moneyflow moneyflow_hsgt margin_detail cn_bond_yield_curve etf_daily etf_adj etf_factor etf_portfolio'.split())
VERIFIED_LEGACY_JAVA = {'stock_detail_info'}
LEGACY_IMPLEMENTATION = {'stock_detail_info': 'StockDetailInfoJobService / StaticStockDetailWriteAdapter'}
LEGACY_EVIDENCE = {'stock_detail_info': 'docs/migration-tasks-20260929/results/D002.json'}

def disposition(name):
    if name in VERIFIED_LEGACY_JAVA: return 'existing-java-producer-batch-integration-pending'
    if name in IMPLEMENTED_NATIVE: return 'implemented-awaiting-full-acceptance'
    return 'pending-implementation'

def implementation(name):
    if name in LEGACY_IMPLEMENTATION: return LEGACY_IMPLEMENTATION[name]
    if name in IMPLEMENTED_NATIVE: return 'SourceProductRunner / source_'+name
    return None

def evidence(name):
    if name in LEGACY_EVIDENCE: return LEGACY_EVIDENCE[name]
    if name in IMPLEMENTED_NATIVE: return EVIDENCE.get(name, 'evidence/native-producers.json')
    return None

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def value(node):
    if isinstance(node, ast.Constant): return node.value
    if isinstance(node, ast.Name): return {'symbol': node.id}
    if isinstance(node, (ast.List, ast.Tuple)): return [value(n) for n in node.elts]
    if isinstance(node, ast.Dict): return {str(value(k)): value(v) for k, v in zip(node.keys, node.values)}
    return {'expression': ast.unparse(node)}

sources = []
source_definitions = sorted((REFERENCE / 'src/quant_platform/data/adapters/config/table_definitions').glob('*.py'))
for path in source_definitions:
    tree = ast.parse(path.read_text())
    for node in ast.walk(tree):
        if not isinstance(node, ast.Dict): continue
        record = value(node)
        if isinstance(record.get('name'), str) and 'sync_function' in record:
            name = record['name']
            sources.append(dict(asset=name, kind='source', owner='JDB-05' if name in CORE else 'JDB-06',
                disposition=disposition(name),
                implementation=implementation(name),
                evidence=evidence(name), reference=str(path.relative_to(REFERENCE)),
                line=node.lineno, reference_sha256=sha(path), contract=record,
                acceptance='frozen source + exact key/field comparison + isolated QuestDB + revision/restart',
                physical_schema_verified=name in VERIFIED_LEGACY_JAVA or name in IMPLEMENTED_NATIVE and name not in PHYSICAL_SCHEMA_UNVERIFIED, deployed_enabled='unknown'))
# Public non-Tushare sources are registered in Java even when absent from the legacy provider catalogue.
for name in ({'cn_bond_yield_curve'} - {source['asset'] for source in sources}) if source_definitions else set():
    contract_path = ROOT / 'batch-app/src/main/resources/contracts/source' / f'{name}.json'
    sources.append(dict(asset=name, kind='source', owner='JDB-05', disposition=disposition(name),
        implementation=implementation(name), evidence=evidence(name), reference=str(contract_path.relative_to(ROOT)),
        line=None, reference_sha256=sha(contract_path), contract=json.loads(contract_path.read_text()),
        acceptance='finite live-source + exact key/field comparison + isolated QuestDB; full-market and Python parity remain open',
        physical_schema_verified=True, deployed_enabled='unknown'))
old = json.loads((ROOT / 'docs/migration-tasks-20260929/manifest.json').read_text())
if not source_definitions:
    # The separate read-only checkout can be pruned between turns. Rebuild the
    # 77-source inventory from the Java repository's D001-D084 cards, retaining
    # the explicit source providers plus the index_daily_basic source card.
    # Do not fabricate the deleted Python table-definition payload.
    source_cards = [task for task in old['tasks']
        if re.fullmatch(r'D0(?:0[1-9]|[1-7][0-9]|8[0-4])', task.get('id', ''))
        and task.get('dataset')
        and (task.get('provider') in {'TUSHARE_TO_VERIFY', 'EXTERNAL_OR_MIXED'} or task.get('id') == 'D020')]
    for task in source_cards:
        name = task['dataset']
        card = ROOT / 'docs/migration-tasks-20260929' / task['spec_path']
        text = card.read_text()
        physical_columns = []
        in_columns = False
        for line in text.splitlines():
            if line.startswith('## 物理字段清单') or line.startswith('## 物理列清单'):
                in_columns = True
                continue
            if in_columns and line.startswith('## '): break
            if in_columns:
                match = re.match(r'\|\s*`([^`]+)`\s*\|\s*`?([A-Z][A-Z0-9_]*(?:\([^)]*\))?)`?\s*\|', line)
                if match: physical_columns.append({'name': match.group(1), 'type': match.group(2)})
        sources.append(dict(asset=name, kind='source', owner='JDB-05' if name in CORE else 'JDB-06',
            disposition=disposition(name),
            implementation=implementation(name),
            evidence=evidence(name),
            source_card_status='verified-isolated-acceptance' if name in VERIFIED_LEGACY_JAVA else None,
            jdb_batch_status='existing Java producer requires explicit Batch integration/reuse decision' if name in VERIFIED_LEGACY_JAVA else None,
            reference='docs/migration-tasks-20260929/'+task['spec_path'], line=None,
            reference_sha256=sha(card), contract={'name':name,'provider':task.get('provider'),'priorTask':task['id'],
                'specPath':'docs/migration-tasks-20260929/'+task['spec_path'],'physicalColumns':physical_columns,
                'sourceConfigSnapshot':('D004 card and read-only HEAD connector: monthly static ths_index() full snapshot, no params, max 5000, connector rate limit 200/min' if name=='ths_index' else 'D016 card and read-only HEAD connector: daily fund_share(trade_date) full-market snapshot, unpaged max 2000, source rate-limit decorator 200/min' if name=='etf_share' else 'unavailable; original read-only checkout was pruned')},
            acceptance='source card + exact key/field comparison + isolated QuestDB + revision/restart',
            physical_schema_verified=bool(physical_columns) and (name in VERIFIED_LEGACY_JAVA or name in IMPLEMENTED_NATIVE and name not in PHYSICAL_SCHEMA_UNVERIFIED), deployed_enabled='unknown'))
    if len(source_cards) != 77:
        raise SystemExit(f'Expected 77 manifest source cards from the frozen D001-D084 inventory, found {len(source_cards)}')
objects = []
for task in old['tasks']:
    if not task.get('dataset'): continue
    name = task['dataset']
    research = name.startswith(('factor_', 'strategy_', 'backtest_', 'prediction_', 'orders', 'trade_signals', 'trade_records'))
    objects.append(dict(asset=name, prior_task=task['id'], provider=task.get('provider'),
        disposition='research-excluded-pending-owner-review' if research else disposition(name),
        owner='external-research' if research else ('JDB-07' if 'l2' in name.lower() or 'tick' in name else ('JDB-05' if name in CORE else 'JDB-06/08-owner-review')),
        reference='docs/migration-tasks-20260929/'+task['spec_path'],
        note=('D002 isolated Java producer verified; determine reuse/integration into the independent Batch runtime' if name in VERIFIED_LEGACY_JAVA else 'Projection/model presence is not a producer or acceptance certificate')))
entries = []
patterns = ['src/quant_platform/common/runtime/*catalog.py', 'src/quant_platform/common/config/task_scheduler.py',
            'scripts/pipeline/*.ps1', 'src/quant_platform/data/**/actor*.py',
            'config/yaml/quant_platform/capabilities/**/*.yaml', 'src/quant_platform/data/**/cli*.py',
            'config/yaml/data/data_governance_contracts.yaml', 'config/yaml/data/data_quality_rules.yaml']
for pattern in patterns:
    for path in sorted(REFERENCE.glob(pattern)):
        entries.append(dict(path=str(path.relative_to(REFERENCE)), sha256=sha(path),
            disposition='read-only-reference-needs-semantic-owner-review', deployed_state='unknown'))
report = dict(schema_version=1, host=platform.node(), platform=platform.platform(), workspace=str(ROOT),
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
    reference_head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REFERENCE,text=True).strip(),
    scope='Java only; no production takeover; no reference application execution',
    orca_task_id=None, orca_dispatch_id=None, sources=sources, objects=objects, entrypoints=entries,
    missing_evidence=['original read-only checkout table_definitions files absent; source rows reconstructed from D-task cards without Python sync/config payload',
                      'Windows scheduler and deployed environment overrides', 'full per-source isolated integration and full-market coverage (31 finite acceptance samples across 38 native contracts; not full-market)',
                      'representative load and predeclared capacity SLA', 'real vendor DFCF daily archive and live L2 access unavailable',
                      'per-object semantic owner review; static heuristics are not final disposition'])
out = ROOT / 'docs/java-batch-20260929/inventory.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(dict(sources=len(sources), objects=len(objects), entrypoints=len(entries), output=str(out))))
