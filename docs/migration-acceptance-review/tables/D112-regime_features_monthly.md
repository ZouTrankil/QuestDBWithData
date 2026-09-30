# D112 · regime_features_monthly 表验收

优先级：P4。本文件仅验收 `regime_features_monthly`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/11-derived/D112-regime_features_monthly.md)

## 1. 已知结构与来源

- 旧快照：主时间 `month`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`month`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/regime/features_monthly.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/regime/features_monthly.py)（模型入口；不自动视为生产者）
- [src/api/services/market_ui.py:117](/Users/Apple/zq/fun/back-monitor/src/api/services/market_ui.py:117)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/factor_regime/factor_regime_monthly.py:113](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/factor_regime/factor_regime_monthly.py:113)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/derived/workflows.py:78](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/derived/workflows.py:78)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D112-01 | 四组月度产品某月缺一个上游 | 明确join基准与缺值传播，不能drop整月后假装覆盖完整 |
| D112-02 | usdcnh_*与rmb_index_*两组列 | 对应来源不同不得互相复制；宽表每列有源表.字段或公式，冲突同名列显式映射 |
| D112-03 | 将真实非空批次规范化后保存；先确认 `month` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `regime_features_monthly`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D112-04 | 对键 `month` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D112-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `regime_features_monthly` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D112-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `month` | `TIMESTAMP` | 逐行比较源映射结果与 `regime_features_monthly.month`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `hs300_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.hs300_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `zz500_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.zz500_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `all_a_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.all_a_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cs1000_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.cs1000_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `small_large_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.small_large_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid_large_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.mid_large_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.growth_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `value_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.value_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_value_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.growth_value_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `energy_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.energy_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `materials_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.materials_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `industrials_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.industrials_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `consumer_discretionary_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.consumer_discretionary_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `consumer_staples_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.consumer_staples_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `healthcare_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.healthcare_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `financials_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.financials_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `it_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.it_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `telecom_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.telecom_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `utilities_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.utilities_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `energy_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.energy_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `materials_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.materials_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `industrials_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.industrials_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `consumer_discretionary_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.consumer_discretionary_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `consumer_staples_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.consumer_staples_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `healthcare_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.healthcare_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `financials_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.financials_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `it_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.it_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `telecom_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.telecom_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `utilities_vs_all_a_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.utilities_vs_all_a_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_up_count` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_up_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_down_count` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_down_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_flat_count` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_flat_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_up_down_ratio` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_up_down_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_limit_up_count` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_limit_up_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_limit_down_count` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_limit_down_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `month_total_amount` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.month_total_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_turnover_rate` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_turnover_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_turnover_rate` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.median_turnover_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_pct_positive_ratio` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_pct_positive_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_pct_negative_ratio` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.avg_pct_negative_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tradable_stock_count_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.tradable_stock_count_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `usdcnh_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.usdcnh_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `usdcnh_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.usdcnh_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_on_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_on_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_1w_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_1w_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_3m_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_3m_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_1y_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_1y_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lpr_1y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.lpr_1y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `lpr_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.lpr_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_slope_1w_3m_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_slope_1w_3m_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `shibor_slope_3m_1y_month_avg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.shibor_slope_3m_1y_month_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.gov_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.gov_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.gov_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gov_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.gov_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_mtn_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_mtn_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_mtn_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_mtn_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_mtn_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_3y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_bank_3y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_5y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_bank_5y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_7y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_bank_7y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aaa_bank_10y_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.aaa_bank_10y_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_3y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.credit_spread_aaa_gov_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_5y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.credit_spread_aaa_gov_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_7y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.credit_spread_aaa_gov_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `credit_spread_aaa_gov_10y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.credit_spread_aaa_gov_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_3y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_aaa_bank_gov_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_5y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_aaa_bank_gov_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_7y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_aaa_bank_gov_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_aaa_bank_gov_10y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_aaa_bank_gov_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_3y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_bank_mtn_3y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_5y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_bank_mtn_5y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_7y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_bank_mtn_7y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bank_mtn_10y` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.spread_bank_mtn_10y`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cpi_yoy` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.cpi_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ppi_yoy` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.ppi_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pmi_mfg` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.pmi_mfg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gdp_yoy` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.gdp_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `m2_yoy` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.m2_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `social_financing_stock` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.social_financing_stock`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `new_rmb_loan` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.new_rmb_loan`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rmb_index_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.rmb_index_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rmb_index_ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.rmb_index_ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `social_financing_yoy` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.social_financing_yoy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `term_spread` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.term_spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `northbound_net_buy_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.northbound_net_buy_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `margin_balance_month_end` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.margin_balance_month_end`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `margin_balance_change_1m` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.margin_balance_change_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `all_a_pe_ttm_median` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.all_a_pe_ttm_median`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `all_a_pb_median` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.all_a_pb_median`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `all_a_pe_ttm_percentile` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.all_a_pe_ttm_percentile`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `all_a_pb_percentile` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.all_a_pb_percentile`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `erp` | `DOUBLE` | 逐行比较源映射结果与 `regime_features_monthly.erp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `month` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `month` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "month", "hs300_ret_1m", "zz500_ret_1m", "all_a_ret_1m", "cs1000_ret_1m", "small_large_ret_1m", "mid_large_ret_1m", "growth_ret_1m", "value_ret_1m", "growth_value_ret_1m", "energy_ret_1m", "materials_ret_1m", "industrials_ret_1m", "consumer_discretionary_ret_1m", "consumer_staples_ret_1m", "healthcare_ret_1m", "financials_ret_1m", "it_ret_1m", "telecom_ret_1m", "utilities_ret_1m", "energy_vs_all_a_1m", "materials_vs_all_a_1m", "industrials_vs_all_a_1m", "consumer_discretionary_vs_all_a_1m", "consumer_staples_vs_all_a_1m", "healthcare_vs_all_a_1m", "financials_vs_all_a_1m", "it_vs_all_a_1m", "telecom_vs_all_a_1m", "utilities_vs_all_a_1m", "avg_up_count", "avg_down_count", "avg_flat_count", "avg_up_down_ratio", "avg_limit_up_count", "avg_limit_down_count", "month_total_amount", "avg_turnover_rate", "median_turnover_rate", "avg_pct_positive_ratio", "avg_pct_negative_ratio", "tradable_stock_count_month_end", "usdcnh_month_end", "usdcnh_ret_1m", "shibor_on_month_avg", "shibor_1w_month_avg", "shibor_3m_month_avg", "shibor_1y_month_avg", "lpr_1y_month_end", "lpr_5y_month_end", "shibor_slope_1w_3m_month_avg", "shibor_slope_3m_1y_month_avg", "gov_3y_month_end", "gov_5y_month_end", "gov_7y_month_end", "gov_10y_month_end", "aaa_mtn_3y_month_end", "aaa_mtn_5y_month_end", "aaa_mtn_7y_month_end", "aaa_mtn_10y_month_end", "aaa_bank_3y_month_end", "aaa_bank_5y_month_end", "aaa_bank_7y_month_end", "aaa_bank_10y_month_end", "credit_spread_aaa_gov_3y", "credit_spread_aaa_gov_5y", "credit_spread_aaa_gov_7y", "credit_spread_aaa_gov_10y", "spread_aaa_bank_gov_3y", "spread_aaa_bank_gov_5y", "spread_aaa_bank_gov_7y", "spread_aaa_bank_gov_10y", "spread_bank_mtn_3y", "spread_bank_mtn_5y", "spread_bank_mtn_7y", "spread_bank_mtn_10y", "cpi_yoy", "ppi_yoy", "pmi_mfg", "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "rmb_index_month_end", "rmb_index_ret_1m", "social_financing_yoy", "term_spread", "northbound_net_buy_1m", "margin_balance_month_end", "margin_balance_change_1m", "all_a_pe_ttm_median", "all_a_pb_median", "all_a_pe_ttm_percentile", "all_a_pb_percentile", "erp"
FROM "regime_features_monthly"
WHERE "month" >= :window_start
  AND "month" < :window_end
ORDER BY "month"
LIMIT :page_size;
```

审计输出：`D112-source-normalized.jsonl`、`D112-actual.jsonl`、`D112-key-diff.json`、`D112-field-diff.json`、`D112-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
