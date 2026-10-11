import sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_maturity import _build_recovery
root=Path(__file__).resolve().parent;lines=[]
fields=['ret5','amount_ratio_5d_20d','vol5','vol20','recent_drawdown20','component_up_breadth_1d','recovery_quality_5d']
def enc(v):return 'NaN' if pd.isna(v) else 'Infinity' if v==np.inf else '-Infinity' if v==-np.inf else repr(float(v))
for case in range(4):
    market=[];component=[]
    for i in range(20):
        row=dict(trade_date=pd.Timestamp('2026-09-30'),ts_code=str(i),name='',category='其他',ret5=(i%8-2)*.01,
                 amount_ratio_5d_20d=i%3,vol5=i*.01,vol20=0. if i==4 else .2,recent_drawdown20=-.05 if i%2==0 else -.04)
        if case==1 and i==3:row['amount_ratio_5d_20d']=np.inf
        if case==2:row['ret5']=.02;row['amount_ratio_5d_20d']=1.
        market.append(row);component.append(dict(trade_date=row['trade_date'],ts_code=str(i),component_up_breadth_1d=np.nan if i==6 else (i%5)/5))
    result=_build_recovery(pd.DataFrame(market),pd.DataFrame(component) if case!=3 else pd.DataFrame())
    for row in result.to_dict('records'):lines.append('\t'.join([str(case)]+[enc(row[f]) for f in fields]))
(root/'etf-recovery-reference.tsv').write_text('\n'.join(lines)+'\n');print(len(lines))
