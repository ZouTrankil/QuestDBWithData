# D050 · balance

- 状态：planned，尚未派发。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D049`；前项验收后才执行本项。
- 数据对象：`balance`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `balance` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`ann_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,ann_date,end_date`。
- Python声明键：`ts_code,ann_date,end_date`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`balancesheet`；sync_function：`sync_balance`。
- 配置同步日期列：`ann_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["balancesheet"], "date_column": ["ann_date"], "frequency": ["weekly"], "time": ["sunday 05:30"], "rate_limit": [20], "timeout": [1800]}`。

## 本数据sync模式与注意事项

普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/stock/finance/balance_sync.py:133 `@api_rate_limit(api_limit=20, period=60)`

## 单数据交付清单

- [ ] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [ ] D02：本表业务Key、物理去重键、冲突/修订规则。
- [ ] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [ ] D04：本表按键/范围的typed read与分页，接入读取组合。
- [ ] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [ ] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [ ] D07：注册 `balance` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
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
| `report_type` | `STRING` | 待逐字段映射与语义核验 |
| `comp_type` | `STRING` | 待逐字段映射与语义核验 |
| `end_type` | `STRING` | 待逐字段映射与语义核验 |
| `total_share` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cap_rese` | `DOUBLE` | 待逐字段映射与语义核验 |
| `undistr_porfit` | `DOUBLE` | 待逐字段映射与语义核验 |
| `surplus_rese` | `DOUBLE` | 待逐字段映射与语义核验 |
| `special_rese` | `DOUBLE` | 待逐字段映射与语义核验 |
| `money_cap` | `DOUBLE` | 待逐字段映射与语义核验 |
| `trad_asset` | `DOUBLE` | 待逐字段映射与语义核验 |
| `notes_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `accounts_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `prepayment` | `DOUBLE` | 待逐字段映射与语义核验 |
| `div_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `int_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `inventories` | `DOUBLE` | 待逐字段映射与语义核验 |
| `amor_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `nca_within_1y` | `DOUBLE` | 待逐字段映射与语义核验 |
| `sett_rsrv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `loanto_oth_bank_fi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `premium_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reinsur_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reinsur_res_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pur_resale_fa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_cur_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_cur_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fa_avail_for_sale` | `DOUBLE` | 待逐字段映射与语义核验 |
| `htm_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_eqt_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invest_real_estate` | `DOUBLE` | 待逐字段映射与语义核验 |
| `time_deposits` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_rec` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fix_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cip` | `DOUBLE` | 待逐字段映射与语义核验 |
| `const_materials` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fixed_assets_disp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `produc_bio_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oil_and_gas_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `intan_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `r_and_d` | `DOUBLE` | 待逐字段映射与语义核验 |
| `goodwill` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_amor_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `defer_tax_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `decr_in_disbur` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_nca` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_nca` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cash_reser_cb` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depos_in_oth_bfi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `prec_metals` | `DOUBLE` | 待逐字段映射与语义核验 |
| `deriv_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rr_reins_une_prem` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rr_reins_outstd_cla` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rr_reins_lins_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rr_reins_lthins_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `refund_depos` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ph_pledge_loans` | `DOUBLE` | 待逐字段映射与语义核验 |
| `refund_cap_depos` | `DOUBLE` | 待逐字段映射与语义核验 |
| `indep_acct_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `client_depos` | `DOUBLE` | 待逐字段映射与语义核验 |
| `client_prov` | `DOUBLE` | 待逐字段映射与语义核验 |
| `transac_seat_fee` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invest_as_receiv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_borr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `st_borr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cb_borr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depos_ib_deposits` | `DOUBLE` | 待逐字段映射与语义核验 |
| `loan_oth_bank` | `DOUBLE` | 待逐字段映射与语义核验 |
| `trading_fl` | `DOUBLE` | 待逐字段映射与语义核验 |
| `notes_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `acct_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `adv_receipts` | `DOUBLE` | 待逐字段映射与语义核验 |
| `sold_for_repur_fa` | `DOUBLE` | 待逐字段映射与语义核验 |
| `comm_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `payroll_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `taxes_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `int_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `div_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `acc_exp` | `DOUBLE` | 待逐字段映射与语义核验 |
| `deferred_inc` | `DOUBLE` | 待逐字段映射与语义核验 |
| `st_bonds_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `payable_to_reinsurer` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rsrv_insur_cont` | `DOUBLE` | 待逐字段映射与语义核验 |
| `acting_trading_sec` | `DOUBLE` | 待逐字段映射与语义核验 |
| `acting_uw_sec` | `DOUBLE` | 待逐字段映射与语义核验 |
| `non_cur_liab_due_1y` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_cur_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_cur_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bond_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `specific_payables` | `DOUBLE` | 待逐字段映射与语义核验 |
| `estimated_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `defer_tax_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `defer_inc_non_cur_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_ncl` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_ncl` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depos_oth_bfi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `deriv_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depos` | `DOUBLE` | 待逐字段映射与语义核验 |
| `agency_bus_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `prem_receiv_adva` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depos_received` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ph_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reser_une_prem` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reser_outstd_claims` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reser_lins_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `reser_lthins_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `indept_acc_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pledge_borr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `indem_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `policy_div_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `treasury_share` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ordin_risk_reser` | `DOUBLE` | 待逐字段映射与语义核验 |
| `forex_differ` | `DOUBLE` | 待逐字段映射与语义核验 |
| `invest_loss_unconf` | `DOUBLE` | 待逐字段映射与语义核验 |
| `minority_int` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_hldr_eqy_exc_min_int` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_hldr_eqy_inc_min_int` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_liab_hldr_eqy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lt_payroll_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_comp_income` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_eqt_tools` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_eqt_tools_p_shr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lending_funds` | `DOUBLE` | 待逐字段映射与语义核验 |
| `acc_receivable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `st_fin_payable` | `DOUBLE` | 待逐字段映射与语义核验 |
| `payables` | `DOUBLE` | 待逐字段映射与语义核验 |
| `hfs_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `hfs_sales` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cost_fin_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fair_value_fin_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cip_total` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_pay_total` | `DOUBLE` | 待逐字段映射与语义核验 |
| `long_pay_total` | `DOUBLE` | 待逐字段映射与语义核验 |
| `debt_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_debt_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_eq_invest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_illiq_fin_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_eq_ppbond` | `DOUBLE` | 待逐字段映射与语义核验 |
| `receiv_financing` | `DOUBLE` | 待逐字段映射与语义核验 |
| `use_right_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lease_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `contract_assets` | `DOUBLE` | 待逐字段映射与语义核验 |
| `contract_liab` | `DOUBLE` | 待逐字段映射与语义核验 |
| `accounts_receiv_bill` | `DOUBLE` | 待逐字段映射与语义核验 |
| `accounts_pay` | `DOUBLE` | 待逐字段映射与语义核验 |
| `oth_rcv_total` | `DOUBLE` | 待逐字段映射与语义核验 |
| `fix_assets_total` | `DOUBLE` | 待逐字段映射与语义核验 |
| `update_flag` | `STRING` | 待逐字段映射与语义核验 |
| `update_time` | `TIMESTAMP` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/finance/balance_sync.py:164`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/balance.py`
- `D:/work/fund_2/back-monitor/config/yaml/research/eco_event_categories.yaml:87`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:109`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:6`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:12`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/semiconductor_equipment_rotation.py:266`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/semiconductor_equipment_rotation.py:275`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `balance` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `balance` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D050` 行及 `results/D050.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D050：balance。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/07-financial/D050-balance.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D049 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D050.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
