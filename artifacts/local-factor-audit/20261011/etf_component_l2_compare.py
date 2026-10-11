import json
from pathlib import Path
import pandas as pd
import numpy as np
root=Path(__file__).resolve().parent
members=pd.DataFrame(json.loads((root/'etf-constituents-live.json').read_text())['members'])
l2=pd.DataFrame(json.loads((root/'etf-component-l2-source.json').read_text())['rows'])
joined=l2.merge(members,left_on='symbol',right_on='stock')
rows=json.loads((root/'etf-component-l2-frozen.json').read_text());actual=pd.DataFrame([dict(etf=r['etf'],**r['columns']) for r in rows]).set_index('etf')
expected=pd.DataFrame(index=actual.index)
for source,target in [('main_net_inflow','component_l2_inflow_breadth'),('obi_top1_mean','basket_obi1'),('mean_obi_top5','basket_obi5'),('ofi_mean','basket_ofi'),('mean_spread','basket_spread'),('depth_top5_mean','basket_depth')]:
    value=joined[source].gt(0) if source=='main_net_inflow' else joined[source]
    weight=joined.weight.where(joined[source].notna(),0)
    numerator=(value*weight).groupby(joined.etf).sum();denominator=weight.groupby(joined.etf).sum()
    expected[target]=numerator/denominator.where(denominator.gt(0))
expected['component_l2_mfi_median']=joined.groupby('etf').mfi_score.median()
bad=0
for c in expected:
 bad+=int((~np.isclose(pd.to_numeric(actual[c],errors='coerce').to_numpy(float),expected[c].to_numpy(float),rtol=1e-10,atol=1e-10,equal_nan=True)).sum())
result=dict(etfs=len(actual),checks=len(actual)*len(expected.columns),mismatches=bad,l2SourceRows=len(l2),scope='frozen constituent L2 aggregation')
(root/'etf-component-l2-parity.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));assert bad==0
