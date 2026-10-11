import sys,math
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_factor_calculations import _build_component_factors
root=Path(__file__).resolve().parent;lines=[];date=pd.Timestamp('2026-01-08')
def enc(value):return 'NaN' if pd.isna(value) else repr(float(value))
fields=['main_net_inflow','mfi_score','obi_top1_mean','mean_obi_top5','ofi_mean','mean_spread','depth_top5_mean']
for case in range(4):
    stock=[];l2=[];members=[]
    for i in range(14):
        symbol=f'{600000+i:06}.SH';weight=(i+1)/105 if case!=3 else 1e-15
        members.append(dict(stock_code=symbol,etf_code='510000.SH',component_weight=weight))
        for day in range(8):
            close=10+i+math.sin(day*.4+i)
            if case==1 and i%4==0 and day in [2,6,7]:close=np.nan
            stock.append(dict(trade_date=date-pd.Timedelta(days=7-day),ts_code=symbol,close=close))
        values=[(-1 if i%2 else 1)*(i+1),i/15,i*.03,i*.04,i*.05,.001+i*.0001,100+i]
        if case==1:values[i%7]=np.nan
        l2.append(dict(ts=date,symbol=symbol,**dict(zip(fields,values))))
    stock=pd.DataFrame(stock);members=pd.DataFrame(members);l2=pd.DataFrame(l2)
    if case==2:l2['symbol']='999999.SH'
    result=_build_component_factors({'stock_market':stock,'stock_l2':l2},members,output_dates={date}).iloc[0]
    temp=stock.sort_values(['ts_code','trade_date']).copy()
    temp['r1']=temp.groupby('ts_code').close.pct_change(fill_method=None);temp['r5']=temp.groupby('ts_code').close.pct_change(5,fill_method=None)
    lines.append(f'CASE\t{case}')
    for row in members.to_dict('records'):lines.append('WEIGHT\t'+enc(row['component_weight']))
    for row in temp.loc[temp.trade_date.eq(date)].merge(members,left_on='ts_code',right_on='stock_code').to_dict('records'):
        lines.append('\t'.join(['STOCK',row['ts_code'],enc(row['component_weight']),enc(row['r1']),enc(row['r5'])]))
    for row in l2.merge(members,left_on='symbol',right_on='stock_code').to_dict('records'):
        lines.append('\t'.join(['L2',enc(row['component_weight'])]+[enc(row[f]) for f in fields]))
    for key,value in result.items():
        if key not in ['trade_date','ts_code']:lines.append('\t'.join(['EXPECT',key,enc(value)]))
    lines.append('END')
(root/'etf-component-reference.tsv').write_text('\n'.join(lines)+'\n');print('4 component cases')
