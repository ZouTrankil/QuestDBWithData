import sys,inspect,textwrap
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
import quant_platform.research.engines.etf_style_benchmarks as reference
names=['pb','pe_ttm','dv_ttm','n_cashflow_act','total_mv','q_gr_yoy','q_profit_yoy','roe','grossprofit_margin','ocf_to_or','debt_to_assets','volatility_252','return_252','amount_20','turnover_20']
stock=pd.DataFrame({name:np.linspace(.1+j,20+j,80) for j,name in enumerate(names)})
stock['volatility_252']/=1000;stock['return_252']/=100
for j,name in enumerate(names):stock.loc[np.arange(j%5,80,11),name]=np.nan
stock.loc[1,['pb','pe_ttm','total_mv','volatility_252']]=0
stock.loc[2,['pb','pe_ttm','total_mv','amount_20','turnover_20']]=-1
stock.loc[3,'pb']=np.inf;stock.loc[4,'n_cashflow_act']=1e15
stock['trade_date']=pd.Timestamp('2026-09-30');original=stock[names].copy()
stock['weekly_volatility_52']=stock['volatility_252']*np.sqrt(5)
# Execute the actual Python transformation/composite block, excluding already-covered temporal/PIT joins.
source=inspect.getsource(reference._stock_style_panel)
body=source[source.index('    stock["book_to_price"]'):source.index('    return stock[')]
namespace=dict(vars(reference));namespace['stock']=stock;exec(textwrap.dedent(body),namespace)
expected=namespace['stock'];result=pd.concat([original,expected[list(reference.CSI_REFERENCE_FACTOR_IDS)]],axis=1)
result.to_csv(Path(__file__).with_name('etf-csi-styles-reference.tsv'),sep='\t',index=False,na_rep='NaN')
