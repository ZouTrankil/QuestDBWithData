"""Compare ordered normalized CSV rows from both production parsers; no feature computation."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import sys
import gc
import numpy as np
import pandas as pd

REPO = Path(r'D:\work\fund_2\back-monitor')
sys.path.insert(0, str(REPO / 'src'))
from quant_platform.data.adapters.connectors.level2.sources.dfcf_csv_source import DfcfCsvLevel2Source
from quant_platform.data.adapters.connectors.level2.kuake_parser import KuakeL2Parser

DATES = ('20260928', '20260929', '20260930')
RAW_SYMBOLS = ('000001.SZ', '600000.SH', '300750.SZ', '688981.SH', '510300.SZ', '159915.SZ', '588000.SZ')
FIELD_COUNTS = {'deal': 7, 'order': 5, 'snapshot': 51}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--date', choices=DATES)
    ap.add_argument('--root', type=Path, default=Path(__file__).resolve().parent)
    args = ap.parse_args()
    root = args.root.resolve()
    raw_root = root.parent / 'l2-performance-validation/raw'
    source = DfcfCsvLevel2Source(raw_root)
    parser = KuakeL2Parser(source=source)
    report = {'generatedAt': datetime.now(timezone.utc).isoformat(),
              'reference': 'KuakeL2Parser(source=DfcfCsvLevel2Source).load_stock_data(..., preserve_deal_id=True)',
              'relativeTolerance': 1e-12, 'absoluteTolerance': 1e-12, 'symbols': {},
              'rowsCompared': 0, 'cellsCompared': 0, 'mismatchedCells': 0, 'failedTables': 0}
    output = root / ('parser-comparison-' + (args.date or 'all') + '.json')
    for day in (args.date,) if args.date else DATES:
        for raw_symbol in RAW_SYMBOLS:
            date = datetime.strptime(day, '%Y%m%d')
            expected = parser.load_stock_data(raw_symbol, date, preserve_deal_id=True, load_snapshot=True)
            tables = {}
            for table, field_count in FIELD_COUNTS.items():
                path = root / 'parser-java' / day / raw_symbol / (table + '.csv.gz')
                actual = pd.read_csv(path, dtype={'BuyID':'str', 'SellID':'str', 'DealID':'str', 'OrderID':'str'})
                reference = expected[table].reset_index(drop=True)
                failures = {}
                compared_rows = min(len(actual), len(reference))
                if len(actual) != len(reference):
                    failures['rows'] = {'java': len(actual), 'python': len(reference)}
                if len(actual.columns) != field_count:
                    failures['fields'] = {'expected': field_count, 'actual': len(actual.columns)}
                if len(actual) == len(reference):
                    for column in actual.columns:
                        if column not in reference:
                            failures[column] = {'missingInPython': True}
                            continue
                        if column.endswith('ID'):
                            mismatch = actual[column].fillna('').ne(reference[column].astype(str))
                        else:
                            mismatch = ~np.isclose(pd.to_numeric(actual[column]), pd.to_numeric(reference[column]),
                                                   rtol=1e-12, atol=1e-12, equal_nan=True)
                        indices = np.flatnonzero(mismatch)
                        report['cellsCompared'] += len(actual)
                        report['mismatchedCells'] += len(indices)
                        if len(indices):
                            first = int(indices[0])
                            failures[column] = {'mismatches': len(indices), 'firstIndex': first,
                                                'java': str(actual.iloc[first][column]), 'python': str(reference.iloc[first][column])}
                report['rowsCompared'] += compared_rows
                report['failedTables'] += bool(failures)
                tables[table] = {'rows': len(actual), 'fields': len(actual.columns), 'mismatches': failures,
                                 'javaExportSha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                del actual, reference
            report['symbols'][day + '/' + raw_symbol] = tables
            report['status'] = 'failed' if report['failedTables'] else 'in_progress'
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(day, raw_symbol, json.dumps(tables, ensure_ascii=False), flush=True)
            source._sz_deal_csv_cache.clear()
            del expected
            gc.collect()
    report['status'] = 'passed' if report['failedTables'] == 0 else 'failed'
    report['symbolDays'] = len(report['symbols'])
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k != 'symbols'}, ensure_ascii=False), flush=True)
    return 0 if report['status'] == 'passed' else 2

if __name__ == '__main__':
    raise SystemExit(main())
