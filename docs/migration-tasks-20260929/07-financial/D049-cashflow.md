# D049 · cashflow

- 状态：planned，尚未派发。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D048`；前项验收后才执行本项。
- 数据对象：`cashflow`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `cashflow` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`ann_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,ann_date,end_date`。
- Python声明键：`ts_code,ann_date,end_date`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`cashflow`；sync_function：`sync_cashflow`。
- 配置同步日期列：`ann_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["cashflow"], "date_column": ["ann_date"], "frequency": ["weekly"], "time": ["sunday 06:00"], "rate_limit": [20], "timeout": [1800]}`。

## 本数据sync模式与注意事项

普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/stock/finance/cashflow_sync.py:40 `@api_rate_limit(api_limit=20, period=60)`

## 单数据交付清单

- [ ] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [ ] D02：本表业务Key、物理去重键、冲突/修订规则。
- [ ] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [ ] D04：本表按键/范围的typed read与分页，接入读取组合。
- [ ] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [ ] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [ ] D07：注册 `cashflow` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
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
| `f_ann_date` | `STRING` | 待逐字段映射与语义核验 |
| `end_date` | `STRING` | 待逐字段映射与语义核验 |
| `comp_type` | `STRING` | 待逐字段映射与语义核验 |
| `report_type` | `STRING` | 待逐字段映射与语义核验 |
| `end_type` | `STRING` | 待逐字段映射与语义核验 |
| `net_profit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `finan_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_fr_sale_sg` | `DOUBLE` | 待逐字段映射与语义核验 |
| `recp_tax_rends` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_depos_incr_fi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_loans_cb` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_inc_borr_oth_fi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `prem_fr_orig_contr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_insured_dep` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_reinsur_prem` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_disp_tfa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ifc_cash_incr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_disp_faas` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_loans_oth_bank` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_cap_incr_repur` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_fr_oth_operate_a` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_inf_fr_operate_a` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_paid_goods_s` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_paid_to_for_empl` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_paid_for_taxes` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_clt_loan_adv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_dep_cbob` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_pay_claims_orig_inco` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pay_handling_chrg` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pay_comm_insur_plcy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_cash_pay_oper_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `st_cash_out_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_cashflow_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_recp_ral_inv_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_disp_withdrwl_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_recp_return_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_recp_disp_fiolta` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_recp_disp_sobu` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stot_inflows_inv_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_pay_acq_const_fiolta` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_paid_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_disp_subs_oth_biz` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_pay_ral_inv_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_pledge_loan` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stot_out_inv_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_cashflow_inv_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_recp_borrow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `proc_issue_bonds` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_cash_recp_ral_fnc_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stot_cash_in_fnc_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `free_cashflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_prepay_amt_borr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_pay_dist_dpcp_int_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `incl_dvd_profit_paid_sc_ms` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_cashpay_ral_fnc_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stot_cashout_fnc_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_cash_flows_fnc_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `eff_fx_flu_cash` | `DOUBLE` | 待逐字段映射与语义核验 |
| `n_incr_cash_cash_equ` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_cash_equ_beg_period` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_cash_equ_end_period` | `DOUBLE` | 待逐字段映射与语义核验 |
| `c_recp_cap_contrib` | `DOUBLE` | 待逐字段映射与语义核验 |
| `incl_cash_rec_saims` | `DOUBLE` | 待逐字段映射与语义核验 |
| `uncon_invest_loss` | `DOUBLE` | 待逐字段映射与语义核验 |
| `prov_depr_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depr_fa_coga_dpba` | `DOUBLE` | 待逐字段映射与语义核验 |
| `amort_intang_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_amort_deferred_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `decr_deferred_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `incr_acc_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `loss_disp_fiolta` | `DOUBLE` | 待逐字段映射与语义核验 |
| `loss_scr_fa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `loss_fv_chg` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invest_loss` | `DOUBLE` | 待逐字段映射与语义核验 |
| `decr_def_inc_tax_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `incr_def_inc_tax_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `decr_inventories` | `DOUBLE` | 待逐字段映射与语义核验 |
| `decr_oper_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `incr_oper_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `others` | `DOUBLE` | 待逐字段映射与语义核验 |
| `im_net_cashflow_oper_act` | `DOUBLE` | 待逐字段映射与语义核验 |
| `conv_debt_into_cap` | `DOUBLE` | 待逐字段映射与语义核验 |
| `conv_copbonds_due_within_1y` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fa_fnc_leases` | `DOUBLE` | 待逐字段映射与语义核验 |
| `im_n_incr_cash_equ` | `DOUBLE` | 待逐字段映射与语义核验 |
| `net_dism_capital_add` | `DOUBLE` | 待逐字段映射与语义核验 |
| `net_cash_rece_sec` | `DOUBLE` | 待逐字段映射与语义核验 |
| `credit_impa_loss` | `DOUBLE` | 待逐字段映射与语义核验 |
| `use_right_asset_dep` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_loss_asset` | `DOUBLE` | 待逐字段映射与语义核验 |
| `end_bal_cash` | `DOUBLE` | 待逐字段映射与语义核验 |
| `beg_bal_cash` | `DOUBLE` | 待逐字段映射与语义核验 |
| `end_bal_cash_equ` | `DOUBLE` | 待逐字段映射与语义核验 |
| `beg_bal_cash_equ` | `DOUBLE` | 待逐字段映射与语义核验 |
| `update_flag` | `STRING` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/finance/cashflow_sync.py:197`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/cashflow.py`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:132`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:6`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:7`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/global_structural_factors.py:170`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/global_structural_factors.py:210`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/global_structural_factors.py:287`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `cashflow` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `cashflow` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D049` 行及 `results/D049.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D049：cashflow。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/07-financial/D049-cashflow.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D048 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D049.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
