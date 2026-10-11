import sys,math
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_share_factors
root=Path(__file__).resolve().parent;market=[];share=[]
for case in range(4):
    for i,date in enumerate(pd.date_range('2026-01-01',periods=60)):
        symbol=f'{510000+case:06}.SH';close=10+math.sin(i/4)
        if case==1 and i in [8,9,18]:close=np.nan
        if case==3 and i in [40,41,42]:close=0.
        market.append(dict(trade_date=date,ts_code=symbol,close=close,name='',category='其他'))
        shares=1000+i*3 if case%2==0 else 1000-i*4
        if case==3 and i in [17,18,19,20,21,22]:shares=0.
        if i%6!=0 and i>2:share.append(dict(timestamp=date,ts_code=symbol,fd_share=shares))
raw=pd.DataFrame(market);reported=pd.DataFrame(share)
expected=_build_share_factors(raw,{'etf_share':reported})
columns=['total_share','share_change_5d','share_delta','net_flow_yi','aum_yi','positive_flow','flow_intensity_5d','flow_persistence_5d']
def encode(value):
    value=float(value)
    return 'NaN' if math.isnan(value) else 'Infinity' if value==math.inf else '-Infinity' if value==-math.inf else repr(value)
lines=['\t'.join(['symbol','close','fd_share']+columns)]
for row in expected.to_dict('records'):
    lines.append('\t'.join([row['ts_code']]+[encode(row[c]) for c in ['close','fd_share']+columns]))
(root/'etf-share-reference.tsv').write_text('\n'.join(lines)+'\n');print(len(expected))
