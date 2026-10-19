"""Regenerate the unmatched-market oracle with the original Python implementation.

Run with back-monitor's pinned Python and --source-root back-monitor/src.
The baseline fixture and promoted Parquet files are never overwritten.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source-root', type=Path, required=True)
args = parser.parse_args()
directory = Path(__file__).parent
script = (directory / 'generate_python_golden.py').read_text(encoding='utf-8')
needle = 'l2 = pd.DataFrame(raw)'
assert script.count(needle) == 1
script = script.replace(needle, """for date in signals:
    original = next(row for row in raw if row['trade_date'] == date)
    for symbol in ('001999.SZ', '019001.SZ'):
        added = dict(original)
        added['symbol'] = symbol
        raw.append(added)
l2 = pd.DataFrame(raw)""")
needle = 'chip = pd.DataFrame('
assert script.count(needle) == 1
script = script.replace(needle, """metadata = pd.concat([metadata, pd.DataFrame([
    dict(symbol='001999.SZ', name='Pre-listing sample', industry_l2='I0')
])], ignore_index=True)
chip = pd.DataFrame(""")
with tempfile.TemporaryDirectory(prefix='etf-nullable-python-oracle-') as temporary:
    stage = Path(temporary)
    generator = stage / 'generate_python_golden.py'
    generator.write_text(script, encoding='utf-8')
    subprocess.run([sys.executable, str(generator), '--source-root', str(args.source_root)], check=True)
    output = directory / 'python-promoted-nullable-golden.json'
    generated = stage / 'python-promoted-golden.json'
    # Feature dictionaries can have a different insertion order across Python
    # hash seeds. Keep the checked-in bytes when every oracle value is unchanged.
    if not output.exists() or json.loads(output.read_text(encoding='utf-8')) != json.loads(generated.read_text(encoding='utf-8')):
        shutil.copyfile(generated, output)
    print('nullable_golden=' + str(output))
