import sys,math
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _residual_cross_section
root=Path(__file__).resolve().parent;lines=[]
for case in range(5):
    n=11 if case==4 else 30
    frame=pd.DataFrame(dict(ret20=[math.sin(i/3)*.2+i*.001 for i in range(n)],
                            beta60=[1. if case==1 else i*.07+math.cos(i/5) for i in range(n)],
                            category=[None if i%4==0 else ['成长','价值','债券'][i%3] for i in range(n)]))
    if case==2:frame.loc[[1,4,7],'ret20']=np.nan;frame.loc[[2,5],'beta60']=np.nan
    if case==3:frame['category']='唯一风格'
    expected=_residual_cross_section(frame)
    for i,row in frame.iterrows():
        def encode(v):return 'NaN' if pd.isna(v) else repr(float(v))
        lines.append('\t'.join([str(case),encode(row.ret20),encode(row.beta60),row.category or '<NULL>',encode(expected[i])]))
(root/'etf-residual-reference.tsv').write_text('\n'.join(lines)+'\n',encoding='utf-8');print(len(lines))
