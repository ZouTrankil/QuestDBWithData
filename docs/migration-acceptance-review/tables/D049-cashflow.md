# D049 · cashflow 表验收

优先级：P2。本文件仅验收 `cashflow`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D049-cashflow.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/cashflow.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/cashflow.py)（模型入口；不自动视为生产者）
- [src/quant_platform/research/application/fundamental_scoring.py:6](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/application/fundamental_scoring.py:6)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/global_structural_factors.py:170](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/global_structural_factors.py:170)（既有审计调用引用，实施时复核）

**已登记来源约束：** 普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D049-01 | 同公司不同 report_type，现金净增加与期初期末余额 | n_incr_cash_cash_equ/c_cash_equ_beg_period/c_cash_equ_end_period 按来源逐列；报表版本不得碰撞 |
| D049-02 | 补报旧期现金流并修订 update_flag | ann_date/f_ann_date/end_date 区分，已完成期间元数据须失效再验证 |
| D049-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `cashflow`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D049-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D049-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `cashflow` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D049-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `cashflow.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `cashflow.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `f_ann_date` | `STRING` | 逐行比较源映射结果与 `cashflow.f_ann_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_date` | `STRING` | 逐行比较源映射结果与 `cashflow.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `comp_type` | `STRING` | 逐行比较源映射结果与 `cashflow.comp_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `report_type` | `STRING` | 逐行比较源映射结果与 `cashflow.report_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_type` | `STRING` | 逐行比较源映射结果与 `cashflow.end_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `net_profit` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.net_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `finan_exp` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.finan_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_fr_sale_sg` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_fr_sale_sg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `recp_tax_rends` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.recp_tax_rends`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_depos_incr_fi` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_depos_incr_fi`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_loans_cb` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_loans_cb`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_inc_borr_oth_fi` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_inc_borr_oth_fi`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prem_fr_orig_contr` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.prem_fr_orig_contr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_insured_dep` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_insured_dep`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_reinsur_prem` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_reinsur_prem`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_disp_tfa` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_disp_tfa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ifc_cash_incr` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.ifc_cash_incr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_disp_faas` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_disp_faas`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_loans_oth_bank` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_loans_oth_bank`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_cap_incr_repur` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_cap_incr_repur`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_fr_oth_operate_a` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_fr_oth_operate_a`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_inf_fr_operate_a` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_inf_fr_operate_a`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_paid_goods_s` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_paid_goods_s`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_paid_to_for_empl` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_paid_to_for_empl`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_paid_for_taxes` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_paid_for_taxes`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_clt_loan_adv` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_clt_loan_adv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_dep_cbob` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_dep_cbob`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_pay_claims_orig_inco` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_pay_claims_orig_inco`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pay_handling_chrg` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.pay_handling_chrg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pay_comm_insur_plcy` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.pay_comm_insur_plcy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_cash_pay_oper_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_cash_pay_oper_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `st_cash_out_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.st_cash_out_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_cashflow_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_cashflow_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_recp_ral_inv_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_recp_ral_inv_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_disp_withdrwl_invest` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_disp_withdrwl_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_recp_return_invest` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_recp_return_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_recp_disp_fiolta` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_recp_disp_fiolta`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_recp_disp_sobu` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_recp_disp_sobu`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `stot_inflows_inv_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.stot_inflows_inv_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_pay_acq_const_fiolta` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_pay_acq_const_fiolta`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_paid_invest` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_paid_invest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_disp_subs_oth_biz` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_disp_subs_oth_biz`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_pay_ral_inv_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_pay_ral_inv_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_pledge_loan` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_pledge_loan`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `stot_out_inv_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.stot_out_inv_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_cashflow_inv_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_cashflow_inv_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_recp_borrow` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_recp_borrow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `proc_issue_bonds` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.proc_issue_bonds`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_cash_recp_ral_fnc_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_cash_recp_ral_fnc_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `stot_cash_in_fnc_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.stot_cash_in_fnc_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `free_cashflow` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.free_cashflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_prepay_amt_borr` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_prepay_amt_borr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_pay_dist_dpcp_int_exp` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_pay_dist_dpcp_int_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `incl_dvd_profit_paid_sc_ms` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.incl_dvd_profit_paid_sc_ms`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_cashpay_ral_fnc_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_cashpay_ral_fnc_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `stot_cashout_fnc_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.stot_cashout_fnc_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_cash_flows_fnc_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_cash_flows_fnc_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eff_fx_flu_cash` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.eff_fx_flu_cash`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_incr_cash_cash_equ` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.n_incr_cash_cash_equ`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_cash_equ_beg_period` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_cash_equ_beg_period`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_cash_equ_end_period` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_cash_equ_end_period`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `c_recp_cap_contrib` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.c_recp_cap_contrib`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `incl_cash_rec_saims` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.incl_cash_rec_saims`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `uncon_invest_loss` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.uncon_invest_loss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prov_depr_assets` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.prov_depr_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depr_fa_coga_dpba` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.depr_fa_coga_dpba`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amort_intang_assets` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.amort_intang_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lt_amort_deferred_exp` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.lt_amort_deferred_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decr_deferred_exp` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.decr_deferred_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `incr_acc_exp` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.incr_acc_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `loss_disp_fiolta` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.loss_disp_fiolta`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `loss_scr_fa` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.loss_scr_fa`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `loss_fv_chg` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.loss_fv_chg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_loss` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.invest_loss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decr_def_inc_tax_assets` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.decr_def_inc_tax_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `incr_def_inc_tax_liab` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.incr_def_inc_tax_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decr_inventories` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.decr_inventories`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decr_oper_payable` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.decr_oper_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `incr_oper_payable` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.incr_oper_payable`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `others` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.others`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `im_net_cashflow_oper_act` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.im_net_cashflow_oper_act`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `conv_debt_into_cap` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.conv_debt_into_cap`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `conv_copbonds_due_within_1y` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.conv_copbonds_due_within_1y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fa_fnc_leases` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.fa_fnc_leases`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `im_n_incr_cash_equ` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.im_n_incr_cash_equ`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `net_dism_capital_add` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.net_dism_capital_add`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `net_cash_rece_sec` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.net_cash_rece_sec`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_impa_loss` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.credit_impa_loss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `use_right_asset_dep` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.use_right_asset_dep`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_loss_asset` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.oth_loss_asset`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `end_bal_cash` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.end_bal_cash`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `beg_bal_cash` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.beg_bal_cash`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `end_bal_cash_equ` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.end_bal_cash_equ`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `beg_bal_cash_equ` | `DOUBLE` | 逐行比较源映射结果与 `cashflow.beg_bal_cash_equ`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `update_flag` | `STRING` | 逐行比较源映射结果与 `cashflow.update_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "f_ann_date", "end_date", "comp_type", "report_type", "end_type", "net_profit", "finan_exp", "c_fr_sale_sg", "recp_tax_rends", "n_depos_incr_fi", "n_incr_loans_cb", "n_inc_borr_oth_fi", "prem_fr_orig_contr", "n_incr_insured_dep", "n_reinsur_prem", "n_incr_disp_tfa", "ifc_cash_incr", "n_incr_disp_faas", "n_incr_loans_oth_bank", "n_cap_incr_repur", "c_fr_oth_operate_a", "c_inf_fr_operate_a", "c_paid_goods_s", "c_paid_to_for_empl", "c_paid_for_taxes", "n_incr_clt_loan_adv", "n_incr_dep_cbob", "c_pay_claims_orig_inco", "pay_handling_chrg", "pay_comm_insur_plcy", "oth_cash_pay_oper_act", "st_cash_out_act", "n_cashflow_act", "oth_recp_ral_inv_act", "c_disp_withdrwl_invest", "c_recp_return_invest", "n_recp_disp_fiolta", "n_recp_disp_sobu", "stot_inflows_inv_act", "c_pay_acq_const_fiolta", "c_paid_invest", "n_disp_subs_oth_biz", "oth_pay_ral_inv_act", "n_incr_pledge_loan", "stot_out_inv_act", "n_cashflow_inv_act", "c_recp_borrow", "proc_issue_bonds", "oth_cash_recp_ral_fnc_act", "stot_cash_in_fnc_act", "free_cashflow", "c_prepay_amt_borr", "c_pay_dist_dpcp_int_exp", "incl_dvd_profit_paid_sc_ms", "oth_cashpay_ral_fnc_act", "stot_cashout_fnc_act", "n_cash_flows_fnc_act", "eff_fx_flu_cash", "n_incr_cash_cash_equ", "c_cash_equ_beg_period", "c_cash_equ_end_period", "c_recp_cap_contrib", "incl_cash_rec_saims", "uncon_invest_loss", "prov_depr_assets", "depr_fa_coga_dpba", "amort_intang_assets", "lt_amort_deferred_exp", "decr_deferred_exp", "incr_acc_exp", "loss_disp_fiolta", "loss_scr_fa", "loss_fv_chg", "invest_loss", "decr_def_inc_tax_assets", "incr_def_inc_tax_liab", "decr_inventories", "decr_oper_payable", "incr_oper_payable", "others", "im_net_cashflow_oper_act", "conv_debt_into_cap", "conv_copbonds_due_within_1y", "fa_fnc_leases", "im_n_incr_cash_equ", "net_dism_capital_add", "net_cash_rece_sec", "credit_impa_loss", "use_right_asset_dep", "oth_loss_asset", "end_bal_cash", "beg_bal_cash", "end_bal_cash_equ", "beg_bal_cash_equ", "update_flag"
FROM "cashflow"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D049-source-normalized.jsonl`、`D049-actual.jsonl`、`D049-key-diff.json`、`D049-field-diff.json`、`D049-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
