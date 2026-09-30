# D047 · income 表验收

优先级：P2。本文件仅验收 `income`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D047-income.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/income.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/income.py)（模型入口；不自动视为生产者）
- [src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_platform/factor_platform_v2_inputs.py:41)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/global_structural_factors.py:168](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/global_structural_factors.py:168)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/factor_platform/factor_platform_value_compute.py:183](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/factor_platform/factor_platform_value_compute.py:183)（既有审计调用引用，实施时复核）

**已登记来源约束：** 普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D047-01 | 同证券同 ann_date/end_date 不同 report_type/comp_type | 先证明旧键是否覆盖不同报表；不允许随机丢一类 |
| D047-02 | 收入、营业利润、归母净利及 update_flag 修订 | revenue/total_revenue/operate_profit/n_income_attr_p 独立对源；f_ann_date 决定可见性规则需冻结 |
| D047-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `income`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D047-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D047-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `income` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D047-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `income.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `income.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `f_ann_date` | `STRING` | 逐行比较源映射结果与 `income.f_ann_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_date` | `STRING` | 逐行比较源映射结果与 `income.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `report_type` | `STRING` | 逐行比较源映射结果与 `income.report_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `comp_type` | `STRING` | 逐行比较源映射结果与 `income.comp_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `end_type` | `STRING` | 逐行比较源映射结果与 `income.end_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `basic_eps` | `DOUBLE` | 逐行比较源映射结果与 `income.basic_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `diluted_eps` | `DOUBLE` | 逐行比较源映射结果与 `income.diluted_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_revenue` | `DOUBLE` | 逐行比较源映射结果与 `income.total_revenue`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `revenue` | `DOUBLE` | 逐行比较源映射结果与 `income.revenue`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `int_income` | `DOUBLE` | 逐行比较源映射结果与 `income.int_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prem_earned` | `DOUBLE` | 逐行比较源映射结果与 `income.prem_earned`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `comm_income` | `DOUBLE` | 逐行比较源映射结果与 `income.comm_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_commis_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_commis_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_oth_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_oth_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_oth_b_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_oth_b_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prem_income` | `DOUBLE` | 逐行比较源映射结果与 `income.prem_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `out_prem` | `DOUBLE` | 逐行比较源映射结果与 `income.out_prem`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `une_prem_reser` | `DOUBLE` | 逐行比较源映射结果与 `income.une_prem_reser`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reins_income` | `DOUBLE` | 逐行比较源映射结果与 `income.reins_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_sec_tb_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_sec_tb_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_sec_uw_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_sec_uw_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_asset_mg_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_asset_mg_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_b_income` | `DOUBLE` | 逐行比较源映射结果与 `income.oth_b_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fv_value_chg_gain` | `DOUBLE` | 逐行比较源映射结果与 `income.fv_value_chg_gain`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `invest_income` | `DOUBLE` | 逐行比较源映射结果与 `income.invest_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ass_invest_income` | `DOUBLE` | 逐行比较源映射结果与 `income.ass_invest_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `forex_gain` | `DOUBLE` | 逐行比较源映射结果与 `income.forex_gain`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_cogs` | `DOUBLE` | 逐行比较源映射结果与 `income.total_cogs`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oper_cost` | `DOUBLE` | 逐行比较源映射结果与 `income.oper_cost`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `int_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.int_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `comm_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.comm_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `biz_tax_surchg` | `DOUBLE` | 逐行比较源映射结果与 `income.biz_tax_surchg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.sell_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `admin_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.admin_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fin_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.fin_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `assets_impair_loss` | `DOUBLE` | 逐行比较源映射结果与 `income.assets_impair_loss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prem_refund` | `DOUBLE` | 逐行比较源映射结果与 `income.prem_refund`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `compens_payout` | `DOUBLE` | 逐行比较源映射结果与 `income.compens_payout`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reser_insur_liab` | `DOUBLE` | 逐行比较源映射结果与 `income.reser_insur_liab`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `div_payt` | `DOUBLE` | 逐行比较源映射结果与 `income.div_payt`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reins_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.reins_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oper_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.oper_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `compens_payout_refu` | `DOUBLE` | 逐行比较源映射结果与 `income.compens_payout_refu`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `insur_reser_refu` | `DOUBLE` | 逐行比较源映射结果与 `income.insur_reser_refu`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reins_cost_refund` | `DOUBLE` | 逐行比较源映射结果与 `income.reins_cost_refund`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `other_bus_cost` | `DOUBLE` | 逐行比较源映射结果与 `income.other_bus_cost`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `operate_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.operate_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `non_oper_income` | `DOUBLE` | 逐行比较源映射结果与 `income.non_oper_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `non_oper_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.non_oper_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `nca_disploss` | `DOUBLE` | 逐行比较源映射结果与 `income.nca_disploss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.total_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `income_tax` | `DOUBLE` | 逐行比较源映射结果与 `income.income_tax`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_income` | `DOUBLE` | 逐行比较源映射结果与 `income.n_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_income_attr_p` | `DOUBLE` | 逐行比较源映射结果与 `income.n_income_attr_p`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `minority_gain` | `DOUBLE` | 逐行比较源映射结果与 `income.minority_gain`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_compr_income` | `DOUBLE` | 逐行比较源映射结果与 `income.oth_compr_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `t_compr_income` | `DOUBLE` | 逐行比较源映射结果与 `income.t_compr_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `compr_inc_attr_p` | `DOUBLE` | 逐行比较源映射结果与 `income.compr_inc_attr_p`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `compr_inc_attr_m_s` | `DOUBLE` | 逐行比较源映射结果与 `income.compr_inc_attr_m_s`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebit` | `DOUBLE` | 逐行比较源映射结果与 `income.ebit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ebitda` | `DOUBLE` | 逐行比较源映射结果与 `income.ebitda`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `insurance_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.insurance_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `undist_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.undist_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `distable_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.distable_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rd_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.rd_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fin_exp_int_exp` | `DOUBLE` | 逐行比较源映射结果与 `income.fin_exp_int_exp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fin_exp_int_inc` | `DOUBLE` | 逐行比较源映射结果与 `income.fin_exp_int_inc`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `transfer_surplus_rese` | `DOUBLE` | 逐行比较源映射结果与 `income.transfer_surplus_rese`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `transfer_housing_imprest` | `DOUBLE` | 逐行比较源映射结果与 `income.transfer_housing_imprest`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `transfer_oth` | `DOUBLE` | 逐行比较源映射结果与 `income.transfer_oth`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `adj_lossgain` | `DOUBLE` | 逐行比较源映射结果与 `income.adj_lossgain`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `withdra_legal_surplus` | `DOUBLE` | 逐行比较源映射结果与 `income.withdra_legal_surplus`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `withdra_legal_pubfund` | `DOUBLE` | 逐行比较源映射结果与 `income.withdra_legal_pubfund`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `withdra_biz_devfund` | `DOUBLE` | 逐行比较源映射结果与 `income.withdra_biz_devfund`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `withdra_rese_fund` | `DOUBLE` | 逐行比较源映射结果与 `income.withdra_rese_fund`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `withdra_oth_ersu` | `DOUBLE` | 逐行比较源映射结果与 `income.withdra_oth_ersu`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `workers_welfare` | `DOUBLE` | 逐行比较源映射结果与 `income.workers_welfare`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `distr_profit_shrhder` | `DOUBLE` | 逐行比较源映射结果与 `income.distr_profit_shrhder`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `prfshare_payable_dvd` | `DOUBLE` | 逐行比较源映射结果与 `income.prfshare_payable_dvd`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `comshare_payable_dvd` | `DOUBLE` | 逐行比较源映射结果与 `income.comshare_payable_dvd`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `capit_comstock_div` | `DOUBLE` | 逐行比较源映射结果与 `income.capit_comstock_div`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `net_after_nr_lp_correct` | `DOUBLE` | 逐行比较源映射结果与 `income.net_after_nr_lp_correct`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_impa_loss` | `DOUBLE` | 逐行比较源映射结果与 `income.credit_impa_loss`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `net_expo_hedging_benefits` | `DOUBLE` | 逐行比较源映射结果与 `income.net_expo_hedging_benefits`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_impair_loss_assets` | `DOUBLE` | 逐行比较源映射结果与 `income.oth_impair_loss_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_opcost` | `DOUBLE` | 逐行比较源映射结果与 `income.total_opcost`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amodcost_fin_assets` | `DOUBLE` | 逐行比较源映射结果与 `income.amodcost_fin_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oth_income` | `DOUBLE` | 逐行比较源映射结果与 `income.oth_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `asset_disp_income` | `DOUBLE` | 逐行比较源映射结果与 `income.asset_disp_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `continued_net_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.continued_net_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `end_net_profit` | `DOUBLE` | 逐行比较源映射结果与 `income.end_net_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `update_flag` | `STRING` | 逐行比较源映射结果与 `income.update_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "f_ann_date", "end_date", "report_type", "comp_type", "end_type", "basic_eps", "diluted_eps", "total_revenue", "revenue", "int_income", "prem_earned", "comm_income", "n_commis_income", "n_oth_income", "n_oth_b_income", "prem_income", "out_prem", "une_prem_reser", "reins_income", "n_sec_tb_income", "n_sec_uw_income", "n_asset_mg_income", "oth_b_income", "fv_value_chg_gain", "invest_income", "ass_invest_income", "forex_gain", "total_cogs", "oper_cost", "int_exp", "comm_exp", "biz_tax_surchg", "sell_exp", "admin_exp", "fin_exp", "assets_impair_loss", "prem_refund", "compens_payout", "reser_insur_liab", "div_payt", "reins_exp", "oper_exp", "compens_payout_refu", "insur_reser_refu", "reins_cost_refund", "other_bus_cost", "operate_profit", "non_oper_income", "non_oper_exp", "nca_disploss", "total_profit", "income_tax", "n_income", "n_income_attr_p", "minority_gain", "oth_compr_income", "t_compr_income", "compr_inc_attr_p", "compr_inc_attr_m_s", "ebit", "ebitda", "insurance_exp", "undist_profit", "distable_profit", "rd_exp", "fin_exp_int_exp", "fin_exp_int_inc", "transfer_surplus_rese", "transfer_housing_imprest", "transfer_oth", "adj_lossgain", "withdra_legal_surplus", "withdra_legal_pubfund", "withdra_biz_devfund", "withdra_rese_fund", "withdra_oth_ersu", "workers_welfare", "distr_profit_shrhder", "prfshare_payable_dvd", "comshare_payable_dvd", "capit_comstock_div", "net_after_nr_lp_correct", "credit_impa_loss", "net_expo_hedging_benefits", "oth_impair_loss_assets", "total_opcost", "amodcost_fin_assets", "oth_income", "asset_disp_income", "continued_net_profit", "end_net_profit", "update_flag"
FROM "income"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D047-source-normalized.jsonl`、`D047-actual.jsonl`、`D047-key-diff.json`、`D047-field-diff.json`、`D047-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
