import sys,math
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_market
root=Path(__file__).resolve().parent
rows=[]
for case in range(3):
    for i,date in enumerate(pd.date_range('2026-01-01',periods=160)):
        close=10+math.sin(i/7)+i*.01 if case!=2 else 10.
        amount=1000+i*3+50*math.cos(i/9)
        if case==1 and i in [3,4,21,44,89]:close=np.nan
        if case==1 and i in [2,19,23,80,81,82]:amount=np.nan
        if case==2 and i in [40,41]:close=1e-15*(i-39)
        if case==2 and 90<=i<120:amount=0.
        rows.append(dict(timestamp=date,ts_code=f'{510000+case:06}.SH',close=close,amount=amount))
raw=pd.DataFrame(rows);expected=_build_market({'etf_market':raw})
columns=['ret1','ret5','ret20','amount5','amount20','amount_ratio_5d_20d','vol5','vol20','drawdown20','recent_drawdown20']
def encode(value):
    value=float(value)
    return 'NaN' if math.isnan(value) else 'Infinity' if value==math.inf else '-Infinity' if value==-math.inf else repr(value)
lines=['\t'.join(['symbol','close','amount']+columns)]
for row in expected.to_dict('records'):
    lines.append('\t'.join([row['ts_code']]+[encode(row[c]) for c in ['close','amount']+columns]))
(root/'etf-market-reference.tsv').write_text('\n'.join(lines)+'\n')
print(f'Python ETF reference: {len(expected)} rows, {len(columns)} columns')
