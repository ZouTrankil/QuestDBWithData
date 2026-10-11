import sys,math
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_market
root=Path(__file__).resolve().parent;rows=[]
for case in range(18):
    for i,date in enumerate(pd.date_range('2026-01-01',periods=160)):
        if case%5==0 and i%11==0:continue
        close=10+math.sin(i/7+case*.4)+i*.01+case
        amount=1000+i*3+50*math.cos(i/9+case)
        if case%3==0 and i in [3,4,21,44,89]:close=np.nan
        rows.append(dict(timestamp=date,ts_code=f'{510000+case:06}.SH',close=close,amount=amount))
expected=_build_market({'etf_market':pd.DataFrame(rows)})
columns=['ret1','ret5','ret20','amount5','amount20','amount_ratio_5d_20d','vol5','vol20','drawdown20','recent_drawdown20',
         'market_ret1','ret_market_product','market_ret_sq','beta60','residual_momentum_20d']
def encode(value):
    value=float(value)
    return 'NaN' if math.isnan(value) else 'Infinity' if value==math.inf else '-Infinity' if value==-math.inf else repr(value)
lines=['\t'.join(['symbol','date','close','amount']+columns)]
for row in expected.to_dict('records'):
    lines.append('\t'.join([row['ts_code'],row['trade_date'].strftime('%Y-%m-%d')]+[encode(row[c]) for c in ['close','amount']+columns]))
(root/'etf-panel-reference.tsv').write_text('\n'.join(lines)+'\n');print(f'Python ETF panel reference: {len(expected)} rows')
