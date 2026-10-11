import sys
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.research.engines.etf_style_benchmarks import _robust_zscore
cases=[[1,2],[1,1,1,100],[np.nan,np.inf,-np.inf,1,2,3,4,1000],[7]*10,[np.nan]*5,[0,1e-14,2e-14],list(np.linspace(-8,9,100))+[1e6]]
lines=[]
for i,values in enumerate(cases):
 for raw,expected in zip(values,_robust_zscore(pd.Series(values))):
  def enc(x):return 'NaN' if np.isnan(x) else 'Infinity' if x==np.inf else '-Infinity' if x==-np.inf else repr(float(x))
  lines.append(f'{i}\t{enc(raw)}\t{enc(expected)}')
Path(__file__).with_name('etf-csi-normalization-reference.tsv').write_text('\n'.join(lines)+'\n')
