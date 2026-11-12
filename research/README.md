# 小体积数据与研究 Playbook

先读数据地图，再做一个完整研究。原教材有 9 本 Notebook、20 幅图、6 篇报告；2026-11-10 新增 QQQ 长期研究，现有 **10 本 Notebook、26 幅图、8 篇报告**。原数据目录约 37.6 MB，QQQ 回放输出另约 10.3 MB，仍在 300 MB 预算内。没有下载全套 ML4Trading、Level 2 或机器学习模型。

新增：[QQQ 长期验证方案](reports/07-qqq-risk-and-timing-playbook.md)、[25.8 年实际结果](reports/08-qqq-long-term-results.md)、[已执行 Notebook](notebooks/09_qqq_long_term_timing.ipynb)、[本机 HTML](../var/research-playbook/html/09_qqq_long_term_timing.html)。这是新取得的供应商复权近似回放，与下述原 ETF 快照缺口分开。

原九本教材检查日期：2026-11-07。研究快照不是正式库的新验收，也不是最新投资建议。BTC 现货在本机重新回放；ETF 使用原验收摘要，逐日重算仍缺本机原快照；财报先用四家公司 2022—2024 年度数据。具体缺口见[数据报告](reports/02-data-readiness.md)。

## 直接看成果

已运行的 Notebook 自带图表与输出，不联网。浏览器可直接打开本机 [HTML 阅读目录](../var/research-playbook/html/index.html)。HTML 和真实数据放在忽略目录，复制仓库不会自动带走它们。

| 顺序 | Notebook | 学会什么 |
| --- | --- | --- |
| 00 | [数据地图](notebooks/00_data_map.ipynb) | 来源、容量、缺口、按字段准入 |
| 01 | [ETF 基线](notebooks/01_etf_baselines.ipynb) | 等权、动量、趋势、成本，如何复用原执行器 |
| 02 | [BTC、ETH 与资金费](notebooks/02_crypto_price_and_funding.ipynb) | 信号时序、真实净值、回撤、资金费事件 |
| 03 | [AI 资本开支与现金流](notebooks/03_ai_capex_and_cashflow.ipynb) | 现金口径、FCF 桥接、累计值转季度 |
| 04 | [宏观流动性](notebooks/04_macro_liquidity.ipynb) | 单位、同日对齐、公布时点、重叠窗口 |
| 05 | [稳定币与链上](notebooks/05_stablecoins_and_onchain.ipynb) | 存量、地址、费用与价格的边界 |
| 06 | [验证与不确定性](notebooks/06_validation_and_uncertainty.ipynb) | 分阶段、块抽样、标签泄漏、试验账 |
| 07 | [ML4Trading 真实面板](notebooks/07_ml4t_real_panel_quality.ipynb) | 63.6 万行日线的证券身份、日历及异常 |
| 08 | [证据优先的财报研究](notebooks/08_evidence_first_research.ipynb) | 检索、计算、引用、证据不足时拒答 |
| 09 | [QQQ 长期择时](notebooks/09_qqq_long_term_timing.ipynb) | MACD、风险交换、同风险对照、长期反例 |

报告：[目标项目源码研究](reports/01-ml4t-methods.md)、[数据准备与缺口](reports/02-data-readiness.md)、[ETF/BTC 基线](reports/03-baselines.md)、[AI 现金流](reports/04-ai-cashflow.md)、[宏观与链上](reports/05-macro-onchain.md)、[研究执行手册](reports/06-research-playbook.md)。每本 Notebook 都有研究问题、代码、图表解读、限制和练习。

## 本机怎么运行

从仓库根目录执行。需要 JDK 24、uv 和 Python 3.12。软件环境约 425 MB，与 36.2 MB 数据分开计量；已有环境无需重复安装。没有启用自动更新或后台任务。

```sh
UV_PROJECT_ENVIRONMENT=../var/research-playbook-env uv sync --project research --locked
var/research-playbook-env/bin/python -m tools.research_playbook.prepare
var/research-playbook-env/bin/python -m tools.research_playbook.render
var/research-playbook-env/bin/python -m unittest tools.tests.test_research_playbook tools.tests.test_plan_1029_research
```

`render` 顺序执行所有单元格，任何异常立即失败；只用当前环境的内核，结束后关闭内核。输出写回 Notebook，HTML 和执行摘要写到 `var/research-playbook/html/`。执行摘要记录数据 manifest 和公共代码 SHA-256。交互学习可运行：

```sh
var/research-playbook-env/bin/jupyter lab research/notebooks
```

原九本教材验证：9 本、45 个代码单元格全部执行通过，嵌入 20 幅图，无单元格错误或 stderr；18 项 Python 测试通过。Java 离线验证通过精确容量上限、流式超限、空响应及保留旧 `.part` 的失败路径。图表已抽检，研究文档本地链接无断链。原回放器单元测试仍有 NumPy 时间单位弃用提示，不影响本次结果；没有修改该既有逻辑。下载器测试入口：

```sh
mkdir -p var/research-playbook/java-tests
javac -d var/research-playbook/java-tests tools/ResearchSourceFetch.java tools/tests/ResearchSourceFetchTest.java
java -cp var/research-playbook/java-tests ResearchSourceFetchTest
```

### 数据从哪里来

已下载原件冻结在 `var/research-playbook/data/raw/`。准备器只读原件，不发网络请求；派生表在 `prepared/`，来源、摘要、容量、用途和缺口在 `manifest.json`。

新环境先按需下载小源，再准备。配置唯一入口为 [config/research-playbook.properties](../config/research-playbook.properties)，这是独立研究工具配置，不导入生产 Spring profile。举例只取一个来源：

```sh
java tools/ResearchSourceFetch.java config/research-playbook.properties fred_walcl
```

省略最后一个参数会遍历配置清单。已有同 URL、同摘要文件直接复用，不重新下载。单文件最多 25 MB、数据目录最多 300 MB；流式读取也检查容量，失败不发布成功回执。原件不符时保留现场并报错，不自动覆盖。全量命令退出码 2 表示至少一个来源失败，其余成功来源仍保留。

SEC 自动源需要真实姓名及邮箱的 `SEC_USER_AGENT`，未配置不会发请求；此前接口返回 403。即使以后成功下载，仍需完成季度选择、修订版本和发布时间验证，准备器不会自动把季度数据标成已验收。可选代理为 `RESEARCH_HTTPS_PROXY`，只接受无凭据的 HTTP 代理地址。不把身份或凭据写进配置文件。

BTC 使用配置指定的原有固定批次，严格按 OHLC 和实际资金费事件字段准入；缺原批次时准备器标记 blocked。已有教学派生表可离线阅读，但在另一台机器重建需携带原批次证据和引用的 objects。ETF/ETH 原快照导入步骤在 Notebook 01；`RESEARCH_EQUITY_INPUTS` 只改变本工具读入位置，不能绕过原 SHA-256。

### 结构与复用

- `research/notebooks/`：讲解与分析的正式入口；不另存一份同名脚本作为第二份教材。
- `research/data/`：小型手工财务表、原文定位、目标项目阅读证据；金额单位是百万美元。
- `tools/research_playbook/data.py`：容量保护、摘要校验、来源解析。
- `prepare.py`：离线准备和缺口清单；`analysis.py`：确有复用的口径；`render.py`：执行和导出。
- `tools/ResearchSourceFetch.java`：有限研究原件下载；不读写正式 QuestDB，不推进生产检查点。
- 原 `tools/plan_1029_research.py` 继续承担基线执行，`tools/btc_research_data.py` 继续承担 BTC 严格准入，没有另造回测器。

图中真实样本、历史摘要和合成教学示例明确区分。ML4Trading/AlgoSeek 数据保留其原数据权利；Coin Metrics 社区数据为 CC BY-NC 4.0。原件没有纳入 Git，也没有对外分享。请连同来源与适用许可使用，不能认为代码许可等于数据许可。

## QQQ 长期研究复现

先按[结果报告](reports/08-qqq-long-term-results.md#复现与交付)下载两份小源并运行 `python -m tools.research_playbook.qqq_timing`，再执行 Notebook 09。它复用原回放器的显式目标仓位和现金计息接口，旧参数默认行为保持。2026-11-10 新 Notebook 的 7 个代码单元、6 幅图执行通过，23 项相关 Python 测试通过；原九本未在本轮重新执行。
