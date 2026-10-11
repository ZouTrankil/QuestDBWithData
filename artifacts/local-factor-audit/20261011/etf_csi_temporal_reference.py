import numpy as np
import pandas as pd
from pathlib import Path
root=Path(__file__).resolve().parent
dates=pd.date_range('2025-01-01',periods=340)
market=pd.DataFrame(dict(date=dates,close=10+np.sin(np.arange(340)/9)+np.arange(340)*.01,amount=100+np.arange(340)*2))
market.loc[[2,30,90,150,200],'close']=np.nan
basic=pd.DataFrame(dict(date=dates,turnover=2+np.sin(np.arange(340)/3)))
basic=basic[basic.index%7!=0].copy();basic.loc[basic.index%17==0,'turnover']=np.nan
basic['turnover_20']=basic.turnover.rolling(20,min_periods=10).mean()
market['ret1']=market.close.pct_change(fill_method=None);market['return_252']=market.close.pct_change(252,fill_method=None)
market['volatility_252']=market.ret1.rolling(252,min_periods=120).std();market['amount_20']=market.amount.rolling(20,min_periods=10).mean()
market=market.merge(basic[['date','turnover_20']],on='date',how='left')
market.to_csv(root/'etf-csi-temporal-market.tsv',sep='\t',index=False,na_rep='NaN');basic[['date','turnover']].to_csv(root/'etf-csi-temporal-basic.tsv',sep='\t',index=False,na_rep='NaN')
