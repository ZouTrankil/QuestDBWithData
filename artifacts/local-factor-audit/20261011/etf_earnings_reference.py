import sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _latest_earnings_by_stock,_build_earnings_factor
root=Path(__file__).resolve().parent
forecast=pd.DataFrame([['S0','2026-01-01',10,30],['S0','2026-02-01',np.nan,40],['S1','2026-01-02',1000,1200],['S2','2026-01-02',-900,-800]],columns=['ts_code','ann_date','p_change_min','p_change_max'])
express=pd.DataFrame([['S0','2026-01-01',50,1,2],['S1','2026-01-03',np.nan,10,15],['S2','2026-01-03',np.nan,0,15],['S3','2026-01-02',np.nan,-10,15]],columns=['ts_code','ann_date','yoy_dedu_np','yoy_net_profit','n_income'])
members=pd.DataFrame([['E0','S0',.4],['E0','S1',.3],['E0','S4',.3],['E1','S2',.6],['E1','S3',.4],['E2','S4',1]],columns=['etf_code','stock_code','component_weight'])
frames={'stock_forecast':forecast,'stock_express':express};latest=_latest_earnings_by_stock(frames)
result=_build_earnings_factor(frames,members,pd.Timestamp('2026-09-30'))
def enc(v):return 'NaN' if pd.isna(v) else repr(float(v))
lines=[]
for row in forecast.itertuples(index=False,name=None):lines.append('\t'.join(['F',row[0],row[1]]+[enc(v) for v in row[2:]]))
for row in express.itertuples(index=False,name=None):lines.append('\t'.join(['X',row[0],row[1]]+[enc(v) for v in row[2:]]))
for row in members.itertuples(index=False,name=None):lines.append('\t'.join(['M',row[0],row[1],enc(row[2])]))
for row in latest.to_dict('records'):lines.append('\t'.join(['S',row['stock_code'],enc(row['earnings_outlook'])]))
for row in result.to_dict('records'):lines.append('\t'.join(['E',row['ts_code']]+[enc(row[c]) for c in ['weighted_outlook','earnings_coverage','component_earnings_outlook']]))
(root/'etf-earnings-reference.tsv').write_text('\n'.join(lines)+'\n');print('4 stocks, 3 ETFs')
