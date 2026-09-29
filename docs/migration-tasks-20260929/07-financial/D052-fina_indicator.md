# D052 · fina_indicator

- 状态：planned，尚未派发。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D051`；前项验收后才执行本项。
- 数据对象：`fina_indicator`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `fina_indicator` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`ann_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,ann_date,end_date`。
- Python声明键：`ts_code,ann_date,end_date`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`fina_indicator`；sync_function：`sync_fina_indicator`。
- 配置同步日期列：`ann_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["fina_indicator"], "date_column": ["ann_date"], "frequency": ["weekly"], "time": ["sunday 04:00"], "rate_limit": [20], "timeout": [1800]}`。

## 本数据sync模式与注意事项

普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/stock/finance/fina_indicator_sync.py:200 `@api_rate_limit(api_limit=20, period=60)`

## 单数据交付清单

- [ ] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [ ] D02：本表业务Key、物理去重键、冲突/修订规则。
- [ ] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [ ] D04：本表按键/范围的typed read与分页，接入读取组合。
- [ ] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [ ] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [ ] D07：注册 `fina_indicator` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [ ] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [ ] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `ann_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `end_date` | `STRING` | 待逐字段映射与语义核验 |
| `eps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dt_eps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_revenue_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `revenue_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `capital_rese_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `surplus_rese_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `undist_profit_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `extra_item` | `DOUBLE` | 待逐字段映射与语义核验 |
| `profit_dedt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gross_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `current_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `quick_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cash_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invturn_days` | `DOUBLE` | 待逐字段映射与语义核验 |
| `arturn_days` | `DOUBLE` | 待逐字段映射与语义核验 |
| `inv_turn` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ar_turn` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ca_turn` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fa_turn` | `DOUBLE` | 待逐字段映射与语义核验 |
| `assets_turn` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_income` | `DOUBLE` | 待逐字段映射与语义核验 |
| `valuechange_income` | `DOUBLE` | 待逐字段映射与语义核验 |
| `interst_income` | `DOUBLE` | 待逐字段映射与语义核验 |
| `daa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebitda` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fcff` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fcfe` | `DOUBLE` | 待逐字段映射与语义核验 |
| `current_exint` | `DOUBLE` | 待逐字段映射与语义核验 |
| `noncurrent_exint` | `DOUBLE` | 待逐字段映射与语义核验 |
| `interestdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `netdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tangible_asset` | `DOUBLE` | 待逐字段映射与语义核验 |
| `working_capital` | `DOUBLE` | 待逐字段映射与语义核验 |
| `networking_capital` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invest_capital` | `DOUBLE` | 待逐字段映射与语义核验 |
| `retained_earnings` | `DOUBLE` | 待逐字段映射与语义核验 |
| `diluted2_eps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocfps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `retainedps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cfps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebit_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fcff_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fcfe_ps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `netprofit_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `grossprofit_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cogs_of_sales` | `DOUBLE` | 待逐字段映射与语义核验 |
| `expense_of_sales` | `DOUBLE` | 待逐字段映射与语义核验 |
| `profit_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `saleexp_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `adminexp_of_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `finaexp_of_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `impai_ttm` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gc_of_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_of_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebit_of_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe_waa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe_dt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `npta` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roic` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe_yearly` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roa2_yearly` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe_avg` | `DOUBLE` | 待逐字段映射与语义核验 |
| `opincome_of_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `investincome_of_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_op_profit_of_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tax_to_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dtprofit_to_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `salescash_to_or` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_or` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_opincome` | `DOUBLE` | 待逐字段映射与语义核验 |
| `capitalized_to_da` | `DOUBLE` | 待逐字段映射与语义核验 |
| `debt_to_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `assets_to_eqt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dp_assets_to_eqt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ca_to_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `nca_to_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tbassets_to_totalassets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `int_to_talcap` | `DOUBLE` | 待逐字段映射与语义核验 |
| `eqt_to_talcapital` | `DOUBLE` | 待逐字段映射与语义核验 |
| `currentdebt_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `longdeb_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_shortdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `debt_to_eqt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `eqt_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `eqt_to_interestdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tangibleasset_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tangasset_to_intdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tangibleasset_to_netdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_interestdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_netdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebit_to_interest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `longdebt_to_workingcapital` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebitda_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `turn_days` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roa_yearly` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roa_dp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fixed_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `profit_prefin_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `non_op_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_to_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `nop_to_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_to_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cash_to_liqdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cash_to_liqdebt_withinterest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_to_liqdebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_to_debt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roic_yearly` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_fa_trun` | `DOUBLE` | 待逐字段映射与语义核验 |
| `profit_to_op` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_opincome` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_investincome` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_dtprofit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_eps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_netprofit_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_gsprofit_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_exp_to_sales` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_profit_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_saleexp_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_adminexp_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_finaexp_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_impair_to_gr_ttm` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_gc_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_op_to_gr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_roe` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_dt_roe` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_npta` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_opincome_to_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_investincome_to_ebt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_dtprofit_to_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_salescash_to_or` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_ocf_to_sales` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_ocf_to_or` | `DOUBLE` | 待逐字段映射与语义核验 |
| `basic_eps_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dt_eps_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cfps_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `op_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ebt_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `netprofit_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dt_netprofit_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ocf_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roe_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bps_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `assets_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `eqt_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tr_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `or_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_gr_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_gr_qoq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_sales_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_sales_qoq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_op_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_op_qoq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_profit_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_profit_qoq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_netprofit_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q_netprofit_qoq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `equity_yoy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rd_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `update_flag` | `STRING` | 待逐字段映射与语义核验 |
| `yoy_equity` | `DOUBLE` | 待逐字段映射与语义核验 |
| `growth_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_bps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `growth_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_eps` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_roe` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_netprofit_margin` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_netprofit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_tr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `yoy_or` | `DOUBLE` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/finance/fina_indicator_sync.py:250`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/fina_indicator.py`
- `D:/work/fund_2/back-monitor/config/yaml/research/factor_platform_v2.yaml:218`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:148`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/style_factors.py:383`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/style_factors.py:392`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/style_factors.py:397`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/style_factors.py:418`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/style_factors.py:425`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `fina_indicator` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `fina_indicator` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D052` 行及 `results/D052.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D052：fina_indicator。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/07-financial/D052-fina_indicator.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D051 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D052.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
