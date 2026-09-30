# D089 · l2_t0_training_labels 表验收

优先级：P4。本文件仅验收 `l2_t0_training_labels`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/10-l2/D089-l2_t0_training_labels.md)

## 1. 已知结构与来源

- 旧快照：主时间 `minute`；分区 `DAY`；WAL `True`；去重 `True`。
- 旧物理键：`symbol`, `minute`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:78](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:78)（既有审计调用引用，实施时复核）
- [src/quant_platform/workflows/data/level2_batch_processor.py:99](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/level2_batch_processor.py:99)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/level2/l2_chip_phase_research.py:461](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/level2/l2_chip_phase_research.py:461)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/level2/l2_chip_phase_action_research.py:744](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/level2/l2_chip_phase_action_research.py:744)（既有审计调用引用，实施时复核）

**已登记来源约束：** 标签依赖未来窗口，确认minute锚点和maturity；不能将未成熟标签计入完成；与特征分别验收。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D089-01 | 同一分钟买先/卖先方向、不同成本参数 | gross/net alpha和机会标签按明确费率/滑点复算，方向不互换 |
| D089-02 | 不可执行/无未来价格场景 | executability_reason/policy_reason可解释，标签不伪造0；训练不得将future字段当输入 |
| D089-03 | 将真实非空批次规范化后保存；先确认 `symbol, minute` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `l2_t0_training_labels`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D089-04 | 对键 `symbol, minute` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D089-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `l2_t0_training_labels` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D089-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `trade_date` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.trade_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `symbol` | `SYMBOL` | 逐行比较源映射结果与 `l2_t0_training_labels.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `market` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.market`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `board` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.board`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `minute` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_t0_training_labels.minute`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `executability_label` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.executability_label`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `executability_reason` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.executability_reason`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `policy_label` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.policy_label`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `policy_reason` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.policy_reason`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `primary_t0_side` | `STRING` | 逐行比较源映射结果与 `l2_t0_training_labels.primary_t0_side`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `roundtrip_cost_rate` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.roundtrip_cost_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `stamp_tax_rate` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.stamp_tax_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `commission_rate` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.commission_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `slippage_bps` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.slippage_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_vwap_return_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_1m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_1m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_1m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_1m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `future_vwap_return_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_3m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_3m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_3m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_3m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `future_vwap_return_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_5m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_5m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_5m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_5m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `future_vwap_return_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_10m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_10m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_10m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_10m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `future_vwap_return_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_15m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_15m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_15m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_15m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `future_vwap_return_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_vwap_return_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `future_mid_return_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.future_mid_return_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_gross_alpha_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_gross_alpha_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_net_alpha_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_net_alpha_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_first_opportunity_label_30m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.sell_first_opportunity_label_30m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `buy_first_aux_gross_alpha_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_gross_alpha_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_net_alpha_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_net_alpha_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_first_aux_opportunity_label_30m` | `LONG` | 逐行比较源映射结果与 `l2_t0_training_labels.buy_first_aux_opportunity_label_30m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `minute` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `minute` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "trade_date", "symbol", "market", "board", "minute", "executability_label", "executability_reason", "policy_label", "policy_reason", "primary_t0_side", "roundtrip_cost_rate", "stamp_tax_rate", "commission_rate", "slippage_bps", "future_vwap_return_1m", "future_mid_return_1m", "sell_first_gross_alpha_1m", "sell_first_net_alpha_1m", "sell_first_opportunity_label_1m", "buy_first_aux_gross_alpha_1m", "buy_first_aux_net_alpha_1m", "buy_first_aux_opportunity_label_1m", "future_vwap_return_3m", "future_mid_return_3m", "sell_first_gross_alpha_3m", "sell_first_net_alpha_3m", "sell_first_opportunity_label_3m", "buy_first_aux_gross_alpha_3m", "buy_first_aux_net_alpha_3m", "buy_first_aux_opportunity_label_3m", "future_vwap_return_5m", "future_mid_return_5m", "sell_first_gross_alpha_5m", "sell_first_net_alpha_5m", "sell_first_opportunity_label_5m", "buy_first_aux_gross_alpha_5m", "buy_first_aux_net_alpha_5m", "buy_first_aux_opportunity_label_5m", "future_vwap_return_10m", "future_mid_return_10m", "sell_first_gross_alpha_10m", "sell_first_net_alpha_10m", "sell_first_opportunity_label_10m", "buy_first_aux_gross_alpha_10m", "buy_first_aux_net_alpha_10m", "buy_first_aux_opportunity_label_10m", "future_vwap_return_15m", "future_mid_return_15m", "sell_first_gross_alpha_15m", "sell_first_net_alpha_15m", "sell_first_opportunity_label_15m", "buy_first_aux_gross_alpha_15m", "buy_first_aux_net_alpha_15m", "buy_first_aux_opportunity_label_15m", "future_vwap_return_30m", "future_mid_return_30m", "sell_first_gross_alpha_30m", "sell_first_net_alpha_30m", "sell_first_opportunity_label_30m", "buy_first_aux_gross_alpha_30m", "buy_first_aux_net_alpha_30m", "buy_first_aux_opportunity_label_30m"
FROM "l2_t0_training_labels"
WHERE "minute" >= :window_start
  AND "minute" < :window_end
ORDER BY "symbol", "minute"
LIMIT :page_size;
```

审计输出：`D089-source-normalized.jsonl`、`D089-actual.jsonl`、`D089-key-diff.json`、`D089-field-diff.json`、`D089-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
