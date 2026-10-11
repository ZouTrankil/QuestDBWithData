import sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _constituent_membership
root=Path(__file__).resolve().parent
rows=[]
for case,weights in enumerate([[(3,9),(1,9),(np.nan,np.nan)],[(0,0),(-1,-2),(np.nan,np.nan)],[(np.nan,30),(0,20),(2,99)],[(3,9),(1,9),(2,9)]]):
    for i,(ratio,mkv) in enumerate(weights):
        rows.append(dict(ts_code=f'E{case}',symbol=f'S{i if case!=3 or i!=2 else 0}',stk_mkv_ratio=ratio,mkv=mkv,ann_date='20260901',end_date='20260630'))
rows.append(dict(ts_code='EXCLUDED',symbol='S0',stk_mkv_ratio=100,mkv=100,ann_date='20260901',end_date='20260630'))
expected=_constituent_membership({'latest_constituents':pd.DataFrame(rows)},{'E0','E1','E2','E3'})
def enc(v):return 'NaN' if pd.isna(v) else repr(float(v))
lines=['\t'.join(['INPUT',r['ts_code'],r['symbol'],enc(r['stk_mkv_ratio']),enc(r['mkv'])]) for r in rows]
lines+=['\t'.join(['EXPECT',r['etf_code'],r['stock_code'],enc(r['component_weight'])]) for r in expected.to_dict('records')]
(root/'etf-weights-reference.tsv').write_text('\n'.join(lines)+'\n');print(len(expected))
