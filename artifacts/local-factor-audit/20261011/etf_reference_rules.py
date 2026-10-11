import sys
from pathlib import Path
sys.path.insert(0,'F:/repo/engine/back-monitor/src')
from quant_platform.data.contracts.etf_style import STYLE_RULES,classify_research_style
root=Path(__file__).resolve().parent
names=['','未知ETF']+[f'测试{k.upper()}ETF' for _,keys in STYLE_RULES for k in keys]
names += [a[1][0]+b[1][0] for a in STYLE_RULES for b in STYLE_RULES]
(root/'etf-style-reference.tsv').write_text('\n'.join(name+'\t'+classify_research_style(name) for name in names)+'\n',encoding='utf-8')
