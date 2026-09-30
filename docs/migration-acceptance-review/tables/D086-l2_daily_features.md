# D086 · l2_daily_features 表验收

优先级：P4。本文件仅验收 `l2_daily_features`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/10-l2/D086-l2_daily_features.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ts`；分区 `DAY`；WAL `True`；去重 `True`。
- 旧物理键：`ts`, `symbol`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/l2_features.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/l2_features.py)（模型入口；不自动视为生产者）
- [src/quant_platform/workflows/data/validate_dfcf_l2_regression.py:39](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/validate_dfcf_l2_regression.py:39)（既有审计调用引用，实施时复核）
- [src/quant_platform/workflows/data/process_dfcf_l2.py:3](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/process_dfcf_l2.py:3)（既有审计调用引用，实施时复核）
- [src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:3](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:3)（既有审计调用引用，实施时复核）

**已登记来源约束：** 对照level2_pipeline.py和各feature builder复刻日特征，固定源样例逐字段对齐；ts映射trade_date，保留symbol/版本及lineage；不借改名重放未核验WAL。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D086-01 | 同一天同股票有deal/order而无snapshot | has_*与records一致，依赖盘口的指标缺失有质量说明 |
| D086-02 | 同输入更换feature_version/parser_version | 复算指标全列一致；旧键不含版本，先冻结覆盖或留版本策略防混读 |
| D086-03 | 将真实非空批次规范化后保存；先确认 `ts, symbol` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `l2_daily_features`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D086-04 | 对键 `ts, symbol` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D086-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `l2_daily_features` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D086-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_daily_features.ts`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `symbol` | `SYMBOL` | 逐行比较源映射结果与 `l2_daily_features.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `has_deal` | `BOOLEAN` | 逐行比较源映射结果与 `l2_daily_features.has_deal`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `has_order` | `BOOLEAN` | 逐行比较源映射结果与 `l2_daily_features.has_order`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `has_snapshot` | `BOOLEAN` | 逐行比较源映射结果与 `l2_daily_features.has_snapshot`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `deal_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.deal_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `order_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.order_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `snapshot_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.snapshot_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `continuous_auction_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.continuous_auction_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `crossed_quote_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.crossed_quote_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `locked_quote_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.locked_quote_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `missing_top5_quote_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.missing_top5_quote_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `feature_version` | `STRING` | 逐行比较源映射结果与 `l2_daily_features.feature_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `parser_version` | `STRING` | 逐行比较源映射结果与 `l2_daily_features.parser_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `order_link_coverage` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.order_link_coverage`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `deal_order_match_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.deal_order_match_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `nonnegative_depth_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.nonnegative_depth_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `valid_spread_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.valid_spread_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `orphan_execution_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.orphan_execution_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cancel_without_fill_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.cancel_without_fill_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `partial_fill_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.partial_fill_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_cancel_time_ms` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.median_cancel_time_ms`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_first_fill_time_ms` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.median_first_fill_time_ms`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `passive_fill_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.passive_fill_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `large_order_fill_rate` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.large_order_fill_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `partial_cancel_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.partial_cancel_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `replace_like_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.replace_like_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_30m_spread_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.open_30m_spread_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close_30m_obi_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.close_30m_obi_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `midday_liquidity_drop` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.midday_liquidity_drop`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `afternoon_ofi_reversal` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.afternoon_ofi_reversal`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_close_vol_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.open_close_vol_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close_30m_impact_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.close_30m_impact_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `aggressor_label_match_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.aggressor_label_match_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid_cross_match_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mid_cross_match_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tick_rule_fallback_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.tick_rule_fallback_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `unclassified_trade_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.unclassified_trade_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_recovery_5s` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_recovery_5s`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `best_quote_depletion_rate` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.best_quote_depletion_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `add_cancel_execute_ratio_top1` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.add_cancel_execute_ratio_top1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_turnover_top5` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_turnover_top5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `book_pressure_decay` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.book_pressure_decay`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `queue_depletion_speed` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.queue_depletion_speed`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_rel_aggro` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_rel_aggro`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_inter_arrival_ms` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.median_inter_arrival_ms`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `algo_windows` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.algo_windows`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `total_windows` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.total_windows`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `mean_algo_entropy` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_algo_entropy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_retail_entropy` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_retail_entropy`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `algo_total_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.algo_total_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `retail_total_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.retail_total_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `wash_trade_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.wash_trade_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `wash_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.wash_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `clean_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.clean_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spoof_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.spoof_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `fake_pressure_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.fake_pressure_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `fake_support_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.fake_support_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `mean_sell_otr` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_sell_otr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_buy_otr` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_buy_otr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_cancel_distance_ticks` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_cancel_distance_ticks`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_net_flow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.total_net_flow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q2_accumulation` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.q2_accumulation`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_vwap_skew` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_vwap_skew`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `q1_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.q1_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `q2_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.q2_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `q3_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.q3_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `q4_count` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.q4_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `main_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.main_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `retail_funds_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.retail_funds_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `main_funds_buy_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.main_funds_buy_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `main_funds_sell_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.main_funds_sell_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid_tier_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mid_tier_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trade_size_gini` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.trade_size_gini`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_auction_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.open_auction_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close_auction_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.close_auction_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_spread` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mean_obi_top5` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mean_obi_top5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amihud_illiquidity` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.amihud_illiquidity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top1_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.obi_top1_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top5_std` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.obi_top5_std`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top1_std` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.obi_top1_std`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_top1_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_top1_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_top5_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_top5_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_slope` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_slope`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_convexity` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.depth_convexity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `microprice_minus_mid_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.microprice_minus_mid_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `microprice_minus_mid_std` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.microprice_minus_mid_std`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `effective_spread_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.effective_spread_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `realized_spread_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.realized_spread_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `impact_30s` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.impact_30s`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `impact_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.impact_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `orderbook_replenish_speed` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.orderbook_replenish_speed`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_mean` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_mean`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_std` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_std`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_persistence` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_persistence`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_positive_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_positive_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_negative_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_negative_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `near_touch_cancel_add_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.near_touch_cancel_add_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gmm_main_force_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.gmm_main_force_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gmm_hft_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.gmm_hft_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gmm_retail_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.gmm_retail_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `gmm_main_force_net_inflow` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.gmm_main_force_net_inflow`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mfi_pulse` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mfi_pulse`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mfi_escort` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mfi_escort`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mfi_precip` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mfi_precip`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mfi_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.mfi_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_slope` | `DOUBLE` | 逐行比较源映射结果与 `l2_daily_features.ofi_slope`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.total_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `clean_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.clean_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `large_order_records` | `LONG` | 逐行比较源映射结果与 `l2_daily_features.large_order_records`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ts` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ts` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts", "symbol", "has_deal", "has_order", "has_snapshot", "deal_records", "order_records", "snapshot_records", "continuous_auction_ratio", "crossed_quote_ratio", "locked_quote_ratio", "missing_top5_quote_ratio", "feature_version", "parser_version", "order_link_coverage", "deal_order_match_ratio", "nonnegative_depth_ratio", "valid_spread_ratio", "orphan_execution_ratio", "cancel_without_fill_ratio", "partial_fill_ratio", "median_cancel_time_ms", "median_first_fill_time_ms", "passive_fill_ratio", "large_order_fill_rate", "partial_cancel_ratio", "replace_like_ratio", "open_30m_spread_mean", "close_30m_obi_mean", "midday_liquidity_drop", "afternoon_ofi_reversal", "open_close_vol_ratio", "close_30m_impact_mean", "aggressor_label_match_ratio", "mid_cross_match_ratio", "tick_rule_fallback_ratio", "unclassified_trade_ratio", "depth_recovery_5s", "best_quote_depletion_rate", "add_cancel_execute_ratio_top1", "depth_turnover_top5", "book_pressure_decay", "queue_depletion_speed", "mean_rel_aggro", "median_inter_arrival_ms", "algo_windows", "total_windows", "mean_algo_entropy", "mean_retail_entropy", "algo_total_amount", "retail_total_amount", "wash_trade_ratio", "wash_amount", "clean_amount", "spoof_count", "fake_pressure_count", "fake_support_count", "mean_sell_otr", "mean_buy_otr", "mean_cancel_distance_ticks", "total_net_flow", "q2_accumulation", "mean_vwap_skew", "q1_count", "q2_count", "q3_count", "q4_count", "main_net_inflow", "retail_funds_net_inflow", "main_funds_buy_amount", "main_funds_sell_amount", "mid_tier_net_inflow", "trade_size_gini", "open_auction_net_inflow", "close_auction_net_inflow", "mean_spread", "mean_obi_top5", "amihud_illiquidity", "obi_top1_mean", "obi_top5_std", "obi_top1_std", "depth_top1_mean", "depth_top5_mean", "depth_slope", "depth_convexity", "microprice_minus_mid_mean", "microprice_minus_mid_std", "effective_spread_mean", "realized_spread_1m", "impact_30s", "impact_5m", "orderbook_replenish_speed", "ofi_mean", "ofi_std", "ofi_persistence", "ofi_positive_ratio", "ofi_negative_ratio", "near_touch_cancel_add_ratio", "gmm_main_force_ratio", "gmm_hft_ratio", "gmm_retail_ratio", "gmm_main_force_net_inflow", "mfi_pulse", "mfi_escort", "mfi_precip", "mfi_score", "ofi_slope", "total_records", "clean_records", "large_order_records"
FROM "l2_daily_features"
WHERE "ts" >= :window_start
  AND "ts" < :window_end
ORDER BY "ts", "symbol"
LIMIT :page_size;
```

审计输出：`D086-source-normalized.jsonl`、`D086-actual.jsonl`、`D086-key-diff.json`、`D086-field-diff.json`、`D086-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
