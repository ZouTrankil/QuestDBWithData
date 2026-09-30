# D050 · balance 表验收

优先级：P2。本文件仅验收 `balance`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D050-balance.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/balance.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/balance.py)（模型入口；不自动视为生产者）
- [src/quant_platform/research/application/fundamental_scoring.py:6](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:6)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/semiconductor_equipment_rotation.py:266](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/semiconductor_equipment_rotation.py:266)（既有审计调用引用，实施时复核）

**已登记来源约束：** 普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D050-01 | 母表/合并表或不同 report_type 同期同时存在 | 键必须足以保留获准的报表种类 |
| D050-02 | total_assets/total_liab/total_hldr_eqy_inc_min_int 与 update_time | 逐列比对；会计平衡差异按原始披露解释，不能通过强填缺失项使其相等 |
| D050-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `balance`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D050-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D050-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `balance` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D050-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `balance.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `balance.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `f_ann_date` | `STRING` | 逐行比较源映射结果与 `balance.f_ann_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_date` | `STRING` | 逐行比较源映射结果与 `balance.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `report_type` | `STRING` | 逐行比较源映射结果与 `balance.report_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `comp_type` | `STRING` | 逐行比较源映射结果与 `balance.comp_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_type` | `STRING` | 逐行比较源映射结果与 `balance.end_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `total_share` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_share`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cap_rese` | `DOUBLE` | 逐行比较源映射结果与 `balance.cap_rese`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `undistr_porfit` | `DOUBLE` | 逐行比较源映射结果与 `balance.undistr_porfit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `surplus_rese` | `DOUBLE` | 逐行比较源映射结果与 `balance.surplus_rese`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `special_rese` | `DOUBLE` | 逐行比较源映射结果与 `balance.special_rese`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `money_cap` | `DOUBLE` | 逐行比较源映射结果与 `balance.money_cap`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trad_asset` | `DOUBLE` | 逐行比较源映射结果与 `balance.trad_asset`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `notes_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.notes_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `accounts_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.accounts_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prepayment` | `DOUBLE` | 逐行比较源映射结果与 `balance.prepayment`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `div_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.div_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `int_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.int_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `inventories` | `DOUBLE` | 逐行比较源映射结果与 `balance.inventories`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amor_exp` | `DOUBLE` | 逐行比较源映射结果与 `balance.amor_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `nca_within_1y` | `DOUBLE` | 逐行比较源映射结果与 `balance.nca_within_1y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sett_rsrv` | `DOUBLE` | 逐行比较源映射结果与 `balance.sett_rsrv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `loanto_oth_bank_fi` | `DOUBLE` | 逐行比较源映射结果与 `balance.loanto_oth_bank_fi`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `premium_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.premium_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reinsur_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.reinsur_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reinsur_res_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.reinsur_res_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pur_resale_fa` | `DOUBLE` | 逐行比较源映射结果与 `balance.pur_resale_fa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_cur_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_cur_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_cur_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_cur_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fa_avail_for_sale` | `DOUBLE` | 逐行比较源映射结果与 `balance.fa_avail_for_sale`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `htm_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.htm_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_eqt_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_eqt_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_real_estate` | `DOUBLE` | 逐行比较源映射结果与 `balance.invest_real_estate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `time_deposits` | `DOUBLE` | 逐行比较源映射结果与 `balance.time_deposits`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_rec` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_rec`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fix_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.fix_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cip` | `DOUBLE` | 逐行比较源映射结果与 `balance.cip`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `const_materials` | `DOUBLE` | 逐行比较源映射结果与 `balance.const_materials`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fixed_assets_disp` | `DOUBLE` | 逐行比较源映射结果与 `balance.fixed_assets_disp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `produc_bio_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.produc_bio_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oil_and_gas_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.oil_and_gas_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `intan_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.intan_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `r_and_d` | `DOUBLE` | 逐行比较源映射结果与 `balance.r_and_d`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `goodwill` | `DOUBLE` | 逐行比较源映射结果与 `balance.goodwill`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_amor_exp` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_amor_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `defer_tax_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.defer_tax_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decr_in_disbur` | `DOUBLE` | 逐行比较源映射结果与 `balance.decr_in_disbur`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_nca` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_nca`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_nca` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_nca`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cash_reser_cb` | `DOUBLE` | 逐行比较源映射结果与 `balance.cash_reser_cb`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depos_in_oth_bfi` | `DOUBLE` | 逐行比较源映射结果与 `balance.depos_in_oth_bfi`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prec_metals` | `DOUBLE` | 逐行比较源映射结果与 `balance.prec_metals`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `deriv_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.deriv_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rr_reins_une_prem` | `DOUBLE` | 逐行比较源映射结果与 `balance.rr_reins_une_prem`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rr_reins_outstd_cla` | `DOUBLE` | 逐行比较源映射结果与 `balance.rr_reins_outstd_cla`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rr_reins_lins_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.rr_reins_lins_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rr_reins_lthins_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.rr_reins_lthins_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `refund_depos` | `DOUBLE` | 逐行比较源映射结果与 `balance.refund_depos`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ph_pledge_loans` | `DOUBLE` | 逐行比较源映射结果与 `balance.ph_pledge_loans`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `refund_cap_depos` | `DOUBLE` | 逐行比较源映射结果与 `balance.refund_cap_depos`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `indep_acct_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.indep_acct_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `client_depos` | `DOUBLE` | 逐行比较源映射结果与 `balance.client_depos`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `client_prov` | `DOUBLE` | 逐行比较源映射结果与 `balance.client_prov`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `transac_seat_fee` | `DOUBLE` | 逐行比较源映射结果与 `balance.transac_seat_fee`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_as_receiv` | `DOUBLE` | 逐行比较源映射结果与 `balance.invest_as_receiv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_borr` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_borr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `st_borr` | `DOUBLE` | 逐行比较源映射结果与 `balance.st_borr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cb_borr` | `DOUBLE` | 逐行比较源映射结果与 `balance.cb_borr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depos_ib_deposits` | `DOUBLE` | 逐行比较源映射结果与 `balance.depos_ib_deposits`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `loan_oth_bank` | `DOUBLE` | 逐行比较源映射结果与 `balance.loan_oth_bank`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trading_fl` | `DOUBLE` | 逐行比较源映射结果与 `balance.trading_fl`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `notes_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.notes_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `acct_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.acct_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `adv_receipts` | `DOUBLE` | 逐行比较源映射结果与 `balance.adv_receipts`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sold_for_repur_fa` | `DOUBLE` | 逐行比较源映射结果与 `balance.sold_for_repur_fa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `comm_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.comm_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `payroll_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.payroll_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `taxes_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.taxes_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `int_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.int_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `div_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.div_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `acc_exp` | `DOUBLE` | 逐行比较源映射结果与 `balance.acc_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `deferred_inc` | `DOUBLE` | 逐行比较源映射结果与 `balance.deferred_inc`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `st_bonds_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.st_bonds_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `payable_to_reinsurer` | `DOUBLE` | 逐行比较源映射结果与 `balance.payable_to_reinsurer`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rsrv_insur_cont` | `DOUBLE` | 逐行比较源映射结果与 `balance.rsrv_insur_cont`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `acting_trading_sec` | `DOUBLE` | 逐行比较源映射结果与 `balance.acting_trading_sec`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `acting_uw_sec` | `DOUBLE` | 逐行比较源映射结果与 `balance.acting_uw_sec`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `non_cur_liab_due_1y` | `DOUBLE` | 逐行比较源映射结果与 `balance.non_cur_liab_due_1y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_cur_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_cur_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_cur_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_cur_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bond_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.bond_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `specific_payables` | `DOUBLE` | 逐行比较源映射结果与 `balance.specific_payables`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `estimated_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.estimated_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `defer_tax_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.defer_tax_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `defer_inc_non_cur_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.defer_inc_non_cur_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_ncl` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_ncl`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_ncl` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_ncl`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depos_oth_bfi` | `DOUBLE` | 逐行比较源映射结果与 `balance.depos_oth_bfi`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `deriv_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.deriv_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depos` | `DOUBLE` | 逐行比较源映射结果与 `balance.depos`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `agency_bus_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.agency_bus_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prem_receiv_adva` | `DOUBLE` | 逐行比较源映射结果与 `balance.prem_receiv_adva`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depos_received` | `DOUBLE` | 逐行比较源映射结果与 `balance.depos_received`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ph_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.ph_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reser_une_prem` | `DOUBLE` | 逐行比较源映射结果与 `balance.reser_une_prem`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reser_outstd_claims` | `DOUBLE` | 逐行比较源映射结果与 `balance.reser_outstd_claims`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reser_lins_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.reser_lins_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reser_lthins_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.reser_lthins_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `indept_acc_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.indept_acc_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pledge_borr` | `DOUBLE` | 逐行比较源映射结果与 `balance.pledge_borr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `indem_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.indem_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `policy_div_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.policy_div_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `treasury_share` | `DOUBLE` | 逐行比较源映射结果与 `balance.treasury_share`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ordin_risk_reser` | `DOUBLE` | 逐行比较源映射结果与 `balance.ordin_risk_reser`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `forex_differ` | `DOUBLE` | 逐行比较源映射结果与 `balance.forex_differ`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_loss_unconf` | `DOUBLE` | 逐行比较源映射结果与 `balance.invest_loss_unconf`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `minority_int` | `DOUBLE` | 逐行比较源映射结果与 `balance.minority_int`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_hldr_eqy_exc_min_int` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_hldr_eqy_exc_min_int`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_hldr_eqy_inc_min_int` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_hldr_eqy_inc_min_int`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_liab_hldr_eqy` | `DOUBLE` | 逐行比较源映射结果与 `balance.total_liab_hldr_eqy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_payroll_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.lt_payroll_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_comp_income` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_comp_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_eqt_tools` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_eqt_tools`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_eqt_tools_p_shr` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_eqt_tools_p_shr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lending_funds` | `DOUBLE` | 逐行比较源映射结果与 `balance.lending_funds`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `acc_receivable` | `DOUBLE` | 逐行比较源映射结果与 `balance.acc_receivable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `st_fin_payable` | `DOUBLE` | 逐行比较源映射结果与 `balance.st_fin_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `payables` | `DOUBLE` | 逐行比较源映射结果与 `balance.payables`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `hfs_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.hfs_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `hfs_sales` | `DOUBLE` | 逐行比较源映射结果与 `balance.hfs_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cost_fin_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.cost_fin_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fair_value_fin_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.fair_value_fin_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cip_total` | `DOUBLE` | 逐行比较源映射结果与 `balance.cip_total`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_pay_total` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_pay_total`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `long_pay_total` | `DOUBLE` | 逐行比较源映射结果与 `balance.long_pay_total`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `debt_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.debt_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_debt_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_debt_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_eq_invest` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_eq_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_illiq_fin_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_illiq_fin_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_eq_ppbond` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_eq_ppbond`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `receiv_financing` | `DOUBLE` | 逐行比较源映射结果与 `balance.receiv_financing`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `use_right_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.use_right_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lease_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.lease_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `contract_assets` | `DOUBLE` | 逐行比较源映射结果与 `balance.contract_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `contract_liab` | `DOUBLE` | 逐行比较源映射结果与 `balance.contract_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `accounts_receiv_bill` | `DOUBLE` | 逐行比较源映射结果与 `balance.accounts_receiv_bill`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `accounts_pay` | `DOUBLE` | 逐行比较源映射结果与 `balance.accounts_pay`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_rcv_total` | `DOUBLE` | 逐行比较源映射结果与 `balance.oth_rcv_total`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fix_assets_total` | `DOUBLE` | 逐行比较源映射结果与 `balance.fix_assets_total`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `update_flag` | `STRING` | 逐行比较源映射结果与 `balance.update_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `update_time` | `TIMESTAMP` | 逐行比较源映射结果与 `balance.update_time`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type", "total_share", "cap_rese", "undistr_porfit", "surplus_rese", "special_rese", "money_cap", "trad_asset", "notes_receiv", "accounts_receiv", "oth_receiv", "prepayment", "div_receiv", "int_receiv", "inventories", "amor_exp", "nca_within_1y", "sett_rsrv", "loanto_oth_bank_fi", "premium_receiv", "reinsur_receiv", "reinsur_res_receiv", "pur_resale_fa", "oth_cur_assets", "total_cur_assets", "fa_avail_for_sale", "htm_invest", "lt_eqt_invest", "invest_real_estate", "time_deposits", "oth_assets", "lt_rec", "fix_assets", "cip", "const_materials", "fixed_assets_disp", "produc_bio_assets", "oil_and_gas_assets", "intan_assets", "r_and_d", "goodwill", "lt_amor_exp", "defer_tax_assets", "decr_in_disbur", "oth_nca", "total_nca", "cash_reser_cb", "depos_in_oth_bfi", "prec_metals", "deriv_assets", "rr_reins_une_prem", "rr_reins_outstd_cla", "rr_reins_lins_liab", "rr_reins_lthins_liab", "refund_depos", "ph_pledge_loans", "refund_cap_depos", "indep_acct_assets", "client_depos", "client_prov", "transac_seat_fee", "invest_as_receiv", "total_assets", "lt_borr", "st_borr", "cb_borr", "depos_ib_deposits", "loan_oth_bank", "trading_fl", "notes_payable", "acct_payable", "adv_receipts", "sold_for_repur_fa", "comm_payable", "payroll_payable", "taxes_payable", "int_payable", "div_payable", "oth_payable", "acc_exp", "deferred_inc", "st_bonds_payable", "payable_to_reinsurer", "rsrv_insur_cont", "acting_trading_sec", "acting_uw_sec", "non_cur_liab_due_1y", "oth_cur_liab", "total_cur_liab", "bond_payable", "lt_payable", "specific_payables", "estimated_liab", "defer_tax_liab", "defer_inc_non_cur_liab", "oth_ncl", "total_ncl", "depos_oth_bfi", "deriv_liab", "depos", "agency_bus_liab", "oth_liab", "prem_receiv_adva", "depos_received", "ph_invest", "reser_une_prem", "reser_outstd_claims", "reser_lins_liab", "reser_lthins_liab", "indept_acc_liab", "pledge_borr", "indem_payable", "policy_div_payable", "total_liab", "treasury_share", "ordin_risk_reser", "forex_differ", "invest_loss_unconf", "minority_int", "total_hldr_eqy_exc_min_int", "total_hldr_eqy_inc_min_int", "total_liab_hldr_eqy", "lt_payroll_payable", "oth_comp_income", "oth_eqt_tools", "oth_eqt_tools_p_shr", "lending_funds", "acc_receivable", "st_fin_payable", "payables", "hfs_assets", "hfs_sales", "cost_fin_assets", "fair_value_fin_assets", "cip_total", "oth_pay_total", "long_pay_total", "debt_invest", "oth_debt_invest", "oth_eq_invest", "oth_illiq_fin_assets", "oth_eq_ppbond", "receiv_financing", "use_right_assets", "lease_liab", "contract_assets", "contract_liab", "accounts_receiv_bill", "accounts_pay", "oth_rcv_total", "fix_assets_total", "update_flag", "update_time"
FROM "balance"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D050-source-normalized.jsonl`、`D050-actual.jsonl`、`D050-key-diff.json`、`D050-field-diff.json`、`D050-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
