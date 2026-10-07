import collections
import json
import shutil
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
ARCHIVE_ROOT = pathlib.Path(r'D:\BaiduNetdiskDownload\202609')
SEVEN = pathlib.Path(r'C:\Program Files\7-Zip\7z.exe')
DATES = ['20260928', '20260929', '20260930']
BASE = ['000001.SZ', '600000.SH', '300750.SZ', '688981.SH', '510300.SZ', '159915.SZ', '588000.SZ']
TABLES = ['行情.csv', '逐笔委托.csv', '逐笔成交.csv']


def parse(date):
    listing = (ROOT / 'archive-metadata' / f'{date}.txt').read_text(encoding='utf-8-sig')
    header, members_text = listing.split('----------', 1)
    archive = {}
    for line in header.splitlines():
        if ' = ' in line:
            k, v = line.split(' = ', 1)
            archive[k] = v
    members = []
    for block in members_text.strip().split('\n\n'):
        member = {}
        for line in block.splitlines():
            if ' = ' in line:
                k, v = line.split(' = ', 1)
                member[k] = v
        if 'Path' in member:
            member['Size'] = int(member.get('Size') or 0)
            member['Packed Size'] = int(member.get('Packed Size') or 0)
            members.append(member)
    files = [m for m in members if 'D' not in m.get('Attributes', '')]
    symbols = collections.defaultdict(dict)
    blocks = collections.defaultdict(list)
    for member in files:
        parts = member['Path'].split('\\')
        if len(parts) == 3:
            symbols[parts[1]][parts[2]] = member
        if member.get('Block'):
            blocks[int(member['Block'])].append(member)
    rows = []
    for symbol, tables in symbols.items():
        rows.append({'raw_symbol': symbol, 'csv_bytes': sum(tables.get(t, {}).get('Size', 0) for t in TABLES),
                     'tables': {t: tables.get(t, {}).get('Size') for t in TABLES},
                     'missing_tables': [t for t in TABLES if t not in tables]})
    rows.sort(key=lambda row: (-row['csv_bytes'], row['raw_symbol']))
    (ROOT / 'archive-metadata' / f'{date}-symbols.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    selection = [s for s in BASE if s in symbols]
    extra = [row['raw_symbol'] for row in rows if row['raw_symbol'] not in selection][:3]
    selection.extend(extra)
    selected_paths = [symbols[s][t]['Path'] for s in selection for t in TABLES if t in symbols[s]]
    selected = {p for p in selected_paths}
    touched_blocks = {int(m['Block']) for m in files if m['Path'] in selected and m.get('Block')}
    selected_bytes = sum(m['Size'] for m in files if m['Path'] in selected)
    touched_bytes = sum(m['Size'] for b in touched_blocks for m in blocks[b])
    touched_packed = sum(m['Packed Size'] for b in touched_blocks for m in blocks[b])
    block_sizes = sorted(sum(m['Size'] for m in ms) for ms in blocks.values())
    archive_path = ARCHIVE_ROOT / f'{date}.7z'
    result = {'date': date, 'archive': str(archive_path), 'compressed_bytes': archive_path.stat().st_size,
              'expanded_bytes': sum(m['Size'] for m in files), 'symbol_count': len(symbols),
              'member_count': len(members), 'file_count': len(files), 'method': archive.get('Method'),
              'solid': archive.get('Solid'), 'block_count': int(archive.get('Blocks', 0)),
              'block_uncompressed_bytes_min': block_sizes[0], 'block_uncompressed_bytes_median': block_sizes[len(block_sizes)//2],
              'block_uncompressed_bytes_max': block_sizes[-1],
              'blocks_spanning_multiple_symbols': sum(len({m['Path'].split('\\')[1] for m in ms}) > 1 for ms in blocks.values()),
              'missing_table_count': sum(bool(row['missing_tables']) for row in rows),
              'symbols_manifest': str(ROOT / 'archive-metadata' / f'{date}-symbols.json'),
              'missing_tables': [row for row in rows if row['missing_tables']],
              'largest_symbols': rows[:20], 'selected_symbols': selection,
              'selected_csv_bytes': selected_bytes, 'selected_block_count': len(touched_blocks),
              'selected_touched_blocks_expanded_bytes_upper_bound': touched_bytes,
              'selected_touched_blocks_packed_bytes': touched_packed,
              'selected_file_count': len(selected_paths)}
    (ROOT / 'archive-metadata' / f'{date}-selection.txt').write_text('\n'.join(selected_paths) + '\n', encoding='utf-8')
    return result


def main():
    census = [parse(d) for d in DATES]
    expanded = sum(row['expanded_bytes'] for row in census)
    disk = {d: {'total_bytes': shutil.disk_usage(d + ':\\').total, 'free_bytes': shutil.disk_usage(d + ':\\').free} for d in ['C', 'D']}
    output = {'archives': census, 'total_expanded_bytes': expanded, 'disk': disk,
              'full_extract_headroom_bytes_with_20_percent': int(expanded * 1.2),
              'note': 'Solid archive: selected files share compressed blocks with other members; decoder may read/decode preceding members. Selected touched-block size is a conservative decode upper bound, not additional output disk use.'}
    (ROOT / 'census.json').write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(output, ensure_ascii=False), flush=True)
    summaries = []
    for row in census:
        date = row['date']
        source_list = ROOT / 'archive-metadata' / f'{date}-selection.txt'
        command = [str(SEVEN), 'x', row['archive'], f'-o{ROOT / "raw"}', f'@{source_list}', '-scsUTF-8', '-sccUTF-8', '-y', '-bb0', '-bsp0']
        start = time.perf_counter()
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        elapsed = time.perf_counter() - start
        (ROOT / 'archive-metadata' / f'{date}-extraction.log').write_text(completed.stdout, encoding='utf-8')
        actual = list((ROOT / 'raw' / date).glob('*/*.csv')) if (ROOT / 'raw' / date).exists() else []
        reported_files = re.search(r'^Files:\s+(\d+)', completed.stdout, re.MULTILINE)
        reported_size = re.search(r'^Size:\s+(\d+)', completed.stdout, re.MULTILINE)
        summary = {'date': date, 'elapsed_seconds': elapsed, 'exit_code': completed.returncode,
                   'selected_symbols': row['selected_symbols'], 'expected_csv_count': row['selected_file_count'],
                   'actual_csv_count': len(actual), 'actual_csv_bytes': sum(p.stat().st_size for p in actual),
                   'source_root': str(ROOT / 'raw'), 'output_date_dir': str(ROOT / 'raw' / date),
                   'conservative_decode_bytes': row['selected_touched_blocks_expanded_bytes_upper_bound'],
                   'compressed_blocks_bytes': row['selected_touched_blocks_packed_bytes']}
        summary['sevenzip_reported_files'] = int(reported_files.group(1)) if reported_files else None
        summary['sevenzip_reported_size_bytes'] = int(reported_size.group(1)) if reported_size else None
        summary['sevenzip_reported_size_note'] = '7-Zip solid-block accounting includes members processed during decode; actual CSV count/bytes independently measured from selected output directories.'
        summaries.append(summary)
        (ROOT / 'extraction-summary.json').write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding='utf-8')
        print('EXTRACTION_COMPLETE ' + json.dumps(summary, ensure_ascii=False), flush=True)
        if completed.returncode or len(actual) != row['selected_file_count']:
            raise RuntimeError(f'Extraction failed for {date}; see archive log')


def append_quantiles():
    census_path = ROOT / 'census.json'
    census = json.loads(census_path.read_text(encoding='utf-8'))
    summaries_path = ROOT / 'extraction-summary.json'
    summaries = json.loads(summaries_path.read_text(encoding='utf-8'))
    configs = []
    for original, summary in zip(census['archives'], summaries):
        date = original['date']
        parsed = parse(date)
        rows = json.loads((ROOT / 'archive-metadata' / f'{date}-symbols.json').read_text(encoding='utf-8'))
        byte_values = sorted(row['csv_bytes'] for row in rows)
        selected = list(original['selected_symbols'])
        candidates = [row for row in rows if row['raw_symbol'].startswith(('00', '30', '60', '68', '43', '83', '87', '88', '92', '5', '15'))]
        extra = []
        for quantile in [0.05, 0.25, 0.5, 0.75, 0.9]:
            x = quantile * (len(byte_values) - 1)
            lo = int(x)
            hi = min(lo + 1, len(byte_values) - 1)
            target = byte_values[lo] + (byte_values[hi] - byte_values[lo]) * (x - lo)
            choice = min((row for row in candidates if row['raw_symbol'] not in selected), key=lambda row: (abs(row['csv_bytes'] - target), row['raw_symbol']))
            selected.append(choice['raw_symbol'])
            extra.append({'quantile': quantile, 'target_bytes_all_instruments': target,
                          'raw_symbol': choice['raw_symbol'], 'actual_csv_bytes': choice['csv_bytes'],
                          'deviation_bytes': choice['csv_bytes'] - target,
                          'selection_note': 'Nearest unused candidate from stock/ETF-like code prefixes; table-byte quantiles use all archive instruments.'})
        original['symbols_manifest'] = parsed['symbols_manifest']
        original['quantile_samples'] = extra
        original['selected_symbols'] = selected
        original['selected_file_count'] = 3 * len(selected)
        lookup = {row['raw_symbol']: row for row in rows}
        original['selected_csv_bytes'] = sum(lookup[s]['csv_bytes'] for s in selected)
        listing = (ROOT / 'archive-metadata' / f'{date}.txt').read_text(encoding='utf-8-sig').split('----------', 1)[1]
        members = []
        for section in listing.strip().split('\n\n'):
            member = dict(line.split(' = ', 1) for line in section.splitlines() if ' = ' in line)
            if member.get('Block'):
                members.append(member)
        selected_set = set(selected)
        touched = {m['Block'] for m in members if m['Path'].split('\\')[1] in selected_set}
        original['initial_selected_block_count'] = original['selected_block_count']
        original['initial_selected_touched_blocks_expanded_bytes_upper_bound'] = original['selected_touched_blocks_expanded_bytes_upper_bound']
        original['selected_block_count'] = len(touched)
        original['selected_touched_blocks_expanded_bytes_upper_bound'] = sum(int(m['Size'] or 0) for m in members if m['Block'] in touched)
        original['selected_touched_blocks_packed_bytes'] = sum(int(m.get('Packed Size') or 0) for m in members if m['Block'] in touched)
        paths = [f'{date}\\{row["raw_symbol"]}\\{table}' for row in extra for table in TABLES]
        selection_file = ROOT / 'archive-metadata' / f'{date}-quantile-selection.txt'
        selection_file.write_text('\n'.join(paths) + '\n', encoding='utf-8')
        start = time.perf_counter()
        completed = subprocess.run([str(SEVEN), 'x', original['archive'], f'-o{ROOT / "raw"}', f'@{selection_file}', '-scsUTF-8', '-sccUTF-8', '-y', '-bb0', '-bsp0'],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        elapsed = time.perf_counter() - start
        (ROOT / 'archive-metadata' / f'{date}-quantile-extraction.log').write_text(completed.stdout, encoding='utf-8')
        actual = list((ROOT / 'raw' / date).glob('*/*.csv'))
        summary['initial_elapsed_seconds'] = summary['elapsed_seconds']
        summary['additional_quantile_elapsed_seconds'] = elapsed
        summary['elapsed_seconds'] += elapsed
        summary['selected_symbols'] = selected
        summary['expected_csv_count'] = original['selected_file_count']
        summary['actual_csv_count'] = len(actual)
        summary['actual_csv_bytes'] = sum(p.stat().st_size for p in actual)
        summary['additional_quantile_exit_code'] = completed.returncode
        for log_name, prefix in [(f'{date}-extraction.log', 'initial'), (f'{date}-quantile-extraction.log', 'additional_quantile')]:
            log = (ROOT / 'archive-metadata' / log_name).read_text(encoding='utf-8')
            reported_files = re.search(r'^Files:\s+(\d+)', log, re.MULTILINE)
            reported_size = re.search(r'^Size:\s+(\d+)', log, re.MULTILINE)
            summary[f'{prefix}_sevenzip_reported_files'] = int(reported_files.group(1)) if reported_files else None
            summary[f'{prefix}_sevenzip_reported_size_bytes'] = int(reported_size.group(1)) if reported_size else None
        summary['conservative_decode_bytes'] = original['selected_touched_blocks_expanded_bytes_upper_bound']
        summary['compressed_blocks_bytes'] = original['selected_touched_blocks_packed_bytes']
        summary['sevenzip_reported_size_note'] = '7-Zip Files/Size summary differs from actual selected output; preserve both measurements and use independently measured CSV bytes for written-output throughput.'
        configs.append({'date': date, 'source_root': str(ROOT / 'raw'), 'symbols': selected,
                        'initial_symbols': selected[:10], 'quantile_samples': extra,
                        'selected_csv_bytes': original['selected_csv_bytes']})
        (ROOT / 'benchmark-symbols.json').write_text(json.dumps(configs, ensure_ascii=False, indent=2), encoding='utf-8')
        census_path.write_text(json.dumps(census, ensure_ascii=False, indent=2), encoding='utf-8')
        summaries_path.write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding='utf-8')
        print('QUANTILE_EXTRACTION_COMPLETE ' + json.dumps({'date': date, 'elapsed_seconds': elapsed, 'symbols': selected,
              'quantile_samples': extra, 'actual_csv_count': len(actual), 'actual_csv_bytes': summary['actual_csv_bytes'], 'exit_code': completed.returncode}, ensure_ascii=False), flush=True)
        if completed.returncode or len(actual) != original['selected_file_count'] or summary['actual_csv_bytes'] != original['selected_csv_bytes']:
            raise RuntimeError(f'Quantile extraction failed for {date}; see archive log')


if __name__ == '__main__':
    if '--append-quantiles' in sys.argv:
        append_quantiles()
    else:
        main()
