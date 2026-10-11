import sys
from pathlib import Path
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_etf_l2_self
root=Path(__file__).resolve().parent
market=pd.DataFrame([['2026-09-29','E0','old','A'],['2026-09-29','E0','new','B'],['2026-09-30','E1','one','C']],columns=['trade_date','ts_code','name','category'])
market.trade_date=pd.to_datetime(market.trade_date)
l2=pd.DataFrame([['2026-09-29','E0',1,2,3,4],['2026-09-29','E0',5,6,7,8],['2026-09-30','E0',9,10,11,12],['2026-09-30','X',13,14,15,16]],columns=['ts','symbol','obi_top1_mean','ofi_mean','mean_spread','depth_top5_mean'])
lines=[]
for case,dates in [('all',None),('empty',set()),('selected',{pd.Timestamp('2026-09-30')})]:
    result=_build_etf_l2_self({'stock_l2':l2},market,output_dates=dates)
    for row in result.to_dict('records'):
        lines.append('\t'.join([case,str(row['trade_date'].date()),row['ts_code'],'' if pd.isna(row['name']) else row['name'],'' if pd.isna(row['category']) else row['category']]+[str(float(row[c])) for c in ['etf_l2_obi1','etf_l2_ofi','etf_l2_spread','etf_l2_depth']]))
(root/'etf-self-l2-reference.tsv').write_text('\n'.join(lines)+'\n')
